package com.example.todolist

import android.util.Log
import kotlin.math.sqrt

data class Point(val x: Float, val y: Float)

sealed class RecognizedShape {
    data class Checkmark(val confidence: Float) : RecognizedShape()
    data class XMark(val confidence: Float) : RecognizedShape()
    data class UpArrow(val confidence: Float) : RecognizedShape()
    object Unknown : RecognizedShape()
}

// ---------------------------------------------------------------------------
// $P Point-Cloud Recognizer
// ---------------------------------------------------------------------------

private const val NUM_POINTS = 32
private const val SCORE_THRESHOLD = 0.68f          // slightly lenient for X
private const val SCORE_THRESHOLD_EXCLAMATION = 0.82f

private data class PDPoint(val x: Float, val y: Float, val strokeIdx: Int)
private data class Template(val name: String, val points: List<PDPoint>)

private fun dist(a: PDPoint, b: PDPoint) =
    sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

private fun uniformSample(pts: List<PDPoint>, n: Int): List<PDPoint> {
    if (pts.size < 2) return List(n) { pts.firstOrNull() ?: PDPoint(0f, 0f, 0) }
    return (0 until n).map { i ->
        val idx = (i.toFloat() / (n - 1) * (pts.size - 1)).toInt().coerceIn(0, pts.lastIndex)
        pts[idx]
    }
}

private fun scaleToSquare(pts: List<PDPoint>): List<PDPoint> {
    val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
    val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
    val scale = maxOf(maxX - minX, maxY - minY).let { if (it == 0f) 1f else it }
    return pts.map { PDPoint((it.x - minX) / scale, (it.y - minY) / scale, it.strokeIdx) }
}

private fun translateToOrigin(pts: List<PDPoint>): List<PDPoint> {
    val cx = pts.map { it.x }.average().toFloat()
    val cy = pts.map { it.y }.average().toFloat()
    return pts.map { PDPoint(it.x - cx, it.y - cy, it.strokeIdx) }
}

private fun normalize(pts: List<PDPoint>): List<PDPoint> =
    translateToOrigin(scaleToSquare(uniformSample(pts, NUM_POINTS)))

private fun cloudDistance(candidate: List<PDPoint>, tmpl: List<PDPoint>): Float {
    val matched = BooleanArray(candidate.size)
    var sum = 0f
    for (t in tmpl) {
        var best = Float.MAX_VALUE
        var bestIdx = -1
        for (i in candidate.indices) {
            if (!matched[i]) {
                val d = dist(candidate[i], t)
                if (d < best) { best = d; bestIdx = i }
            }
        }
        if (bestIdx >= 0) { matched[bestIdx] = true; sum += best }
    }
    return sum
}

private fun distanceToScore(d: Float): Float = 1f / (1f + d / NUM_POINTS)

private fun pts(strokeIdx: Int, vararg xy: Float): List<PDPoint> {
    val list = mutableListOf<PDPoint>()
    var i = 0
    while (i < xy.size - 1) { list.add(PDPoint(xy[i], xy[i + 1], strokeIdx)); i += 2 }
    return list
}

// ---------------------------------------------------------------------------
// Templates
// ---------------------------------------------------------------------------

private val TEMPLATE_CHECKMARK = Template(
    "checkmark",
    normalize(pts(0, 0f,40f, 10f,58f, 20f,72f, 32f,88f, 42f,100f, 56f,78f, 70f,55f, 84f,30f, 100f,0f))
)

// Two-stroke X: \ then /
private val TEMPLATE_XMARK = Template(
    "xmark",
    normalize(
        pts(0, 0f,0f, 25f,25f, 50f,50f, 75f,75f, 100f,100f) +
                pts(1, 100f,0f, 75f,25f, 50f,50f, 25f,75f, 0f,100f)
    )
)

// One-stroke X: continuous crossing path
private val TEMPLATE_XMARK_SINGLE = Template(
    "xmark",
    normalize(pts(0, 0f,0f, 25f,25f, 50f,50f, 75f,75f, 100f,100f,
        75f,25f, 50f,50f, 25f,75f, 0f,100f))
)

// One-stroke X drawn the other way: / then back \
private val TEMPLATE_XMARK_SINGLE_ALT = Template(
    "xmark",
    normalize(pts(0, 100f,0f, 75f,25f, 50f,50f, 25f,75f, 0f,100f,
        25f,25f, 50f,50f, 75f,75f, 100f,100f))
)

private val TEMPLATE_EXCLAMATION = Template(
    "exclamation",
    normalize(
        pts(0, 50f,0f, 50f,18f, 50f,36f, 50f,54f, 50f,72f) +
                pts(1, 50f,88f, 51f,100f)
    )
)

private val ALL_TEMPLATES = listOf(
    TEMPLATE_CHECKMARK,
    TEMPLATE_XMARK,
    TEMPLATE_XMARK_SINGLE,
    TEMPLATE_XMARK_SINGLE_ALT,
    TEMPLATE_EXCLAMATION
)

// ---------------------------------------------------------------------------
// ShapeRecognizer
// ---------------------------------------------------------------------------

class ShapeRecognizer {

    fun recognizeAll(strokes: List<Stroke>): RecognizedShape {
        if (strokes.isEmpty()) return RecognizedShape.Unknown
        if (strokes.size > 2) return RecognizedShape.Unknown
        val allPoints = strokes.flatMapIndexed { si, stroke ->
            stroke.points.map { PDPoint(it.x, it.y, si) }
        }
        return matchCloud(allPoints)
    }

    fun recognize(stroke: Stroke): RecognizedShape {
        val pts = stroke.points.map { PDPoint(it.x, it.y, 0) }
        return matchCloud(pts)
    }

    private fun matchCloud(rawPoints: List<PDPoint>): RecognizedShape {
        if (rawPoints.size < 4) return RecognizedShape.Unknown

        val strokeCount = rawPoints.map { it.strokeIdx }.distinct().size
        val candidate = normalize(rawPoints)

        var bestScore = -1f
        var bestName = ""

        for (tmpl in ALL_TEMPLATES) {
            val d = cloudDistance(candidate, tmpl.points)
            val score = distanceToScore(d)
            Log.d("PDollar", "template=${tmpl.name}  dist=${"%.3f".format(d)}  score=${"%.3f".format(score)}")
            if (score > bestScore) { bestScore = score; bestName = tmpl.name }
        }

        Log.d("PDollar", "BEST → $bestName  score=${"%.3f".format(bestScore)}  strokes=$strokeCount")

        if (bestScore < SCORE_THRESHOLD) return RecognizedShape.Unknown
        if (bestName == "exclamation" && bestScore < SCORE_THRESHOLD_EXCLAMATION) return RecognizedShape.Unknown

        // Single-stroke X: allow if stroke crosses itself (direction reverses in both axes)
        if (bestName == "xmark" && strokeCount < 2 && !isCrossingStroke(rawPoints)) {
            Log.d("PDollar", "Suppressing xmark — single stroke, no crossing detected")
            return RecognizedShape.Unknown
        }

        // Single straight diagonal must not become a checkmark (could be first stroke of X)
        if (bestName == "checkmark" && strokeCount == 1 && isStraightDiagonal(rawPoints)) {
            Log.d("PDollar", "Suppressing checkmark — straight diagonal")
            return RecognizedShape.Unknown
        }

        return when (bestName) {
            "checkmark"   -> RecognizedShape.Checkmark(bestScore)
            "xmark"       -> RecognizedShape.XMark(bestScore)
            "exclamation" -> RecognizedShape.UpArrow(bestScore)
            else          -> RecognizedShape.Unknown
        }
    }

    /**
     * True if a single stroke crosses itself — signature of a one-stroke X.
     * Lenient: only requires 1 x-flip OR 1 y-flip (not both), since
     * sloppy X strokes may not fully reverse in both axes.
     */
    private fun isCrossingStroke(pts: List<PDPoint>): Boolean {
        if (pts.size < 6) return false
        var xFlips = 0; var yFlips = 0
        var prevDx = 0f; var prevDy = 0f
        for (i in 1 until pts.size) {
            val dx = pts[i].x - pts[i-1].x
            val dy = pts[i].y - pts[i-1].y
            if (prevDx != 0f && dx * prevDx < 0) xFlips++
            if (prevDy != 0f && dy * prevDy < 0) yFlips++
            if (dx != 0f) prevDx = dx
            if (dy != 0f) prevDy = dy
        }
        Log.d("PDollar", "isCrossingStroke xFlips=$xFlips yFlips=$yFlips")
        // Lenient: 1 flip in either axis is enough
        return xFlips >= 1 || yFlips >= 2
    }

    /** True if the stroke is basically a straight line (82%+ linearity) */
    private fun isStraightDiagonal(pts: List<PDPoint>): Boolean {
        if (pts.size < 4) return false
        val start = pts.first(); val end = pts.last()
        val directDist = dist(start, end)
        val pathLen = (1 until pts.size).sumOf { dist(pts[it - 1], pts[it]).toDouble() }.toFloat()
        val linearity = if (pathLen > 0f) directDist / pathLen else 0f
        Log.d("PDollar", "isStraightDiagonal linearity=${"%.3f".format(linearity)}")
        return linearity > 0.82f
    }
}