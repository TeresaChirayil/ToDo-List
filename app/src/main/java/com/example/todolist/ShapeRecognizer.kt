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
private const val SCORE_THRESHOLD         = 0.68f
private const val SCORE_THRESHOLD_CHECKMARK   = 0.70f
private const val SCORE_THRESHOLD_EXCLAMATION = 0.76f

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

// Checkmark: short down-left leg then long up-right leg
private val TEMPLATE_CHECKMARK = Template(
    "checkmark",
    normalize(pts(0, 0f,40f, 10f,58f, 20f,72f, 32f,88f, 42f,100f, 56f,78f, 70f,55f, 84f,30f, 100f,0f))
)

// Wider/shallower checkmark
private val TEMPLATE_CHECKMARK_WIDE = Template(
    "checkmark",
    normalize(pts(0, 0f,20f, 15f,45f, 28f,65f, 38f,85f, 46f,100f, 60f,72f, 74f,45f, 88f,20f, 100f,0f))
)

// Two-stroke X: \ then /
private val TEMPLATE_XMARK = Template(
    "xmark",
    normalize(
        pts(0, 0f,0f, 25f,25f, 50f,50f, 75f,75f, 100f,100f) +
                pts(1, 100f,0f, 75f,25f, 50f,50f, 25f,75f, 0f,100f)
    )
)

// One-stroke X: top-left → bottom-right then back up-left → bottom-right
private val TEMPLATE_XMARK_SINGLE = Template(
    "xmark",
    normalize(pts(0, 0f,0f, 25f,25f, 50f,50f, 75f,75f, 100f,100f,
        75f,25f, 50f,50f, 25f,75f, 0f,100f))
)

// Looping X: loop/teardrop then diagonal crossing through it
private val TEMPLATE_XMARK_LOOP = Template(
    "xmark",
    normalize(pts(0,
        20f,70f, 15f,50f, 20f,25f, 35f,8f, 55f,5f, 75f,15f,
        85f,35f, 80f,55f, 60f,65f, 45f,60f, 35f,50f,
        50f,65f, 65f,80f, 80f,95f, 95f,100f
    ))
)

// Mirrored looping X
private val TEMPLATE_XMARK_LOOP_MIRROR = Template(
    "xmark",
    normalize(pts(0,
        80f,70f, 85f,50f, 80f,25f, 65f,8f, 45f,5f, 25f,15f,
        15f,35f, 20f,55f, 40f,65f, 55f,60f, 65f,50f,
        50f,65f, 35f,80f, 20f,95f, 5f,100f
    ))
)

// Exclamation: vertical line (stroke 0) + dot (stroke 1)
private val TEMPLATE_EXCLAMATION = Template(
    "exclamation",
    normalize(
        pts(0, 50f,0f, 50f,18f, 50f,36f, 50f,54f, 50f,72f) +
                pts(1, 50f,88f, 51f,100f)
    )
)

// Exclamation with wider gap before dot
private val TEMPLATE_EXCLAMATION_GAP = Template(
    "exclamation",
    normalize(
        pts(0, 50f,0f, 50f,15f, 50f,30f, 50f,45f, 50f,60f) +
                pts(1, 50f,82f, 50f,100f)
    )
)

// Exclamation dot drawn as a small tick
private val TEMPLATE_EXCLAMATION_TICK = Template(
    "exclamation",
    normalize(
        pts(0, 50f,0f, 50f,20f, 50f,40f, 50f,60f, 50f,75f) +
                pts(1, 42f,92f, 50f,96f, 58f,100f)
    )
)

private val ALL_TEMPLATES = listOf(
    TEMPLATE_CHECKMARK,
    TEMPLATE_CHECKMARK_WIDE,
    TEMPLATE_XMARK,
    TEMPLATE_XMARK_SINGLE,
    TEMPLATE_XMARK_LOOP,
    TEMPLATE_XMARK_LOOP_MIRROR,
    TEMPLATE_EXCLAMATION,
    TEMPLATE_EXCLAMATION_GAP,
    TEMPLATE_EXCLAMATION_TICK
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
            val s = distanceToScore(d)
            Log.d("PDollar", "template=${tmpl.name}  score=${"%.3f".format(s)}")
            if (s > bestScore) { bestScore = s; bestName = tmpl.name }
        }

        Log.d("PDollar", "BEST → $bestName  score=${"%.3f".format(bestScore)}  strokes=$strokeCount")

        // Base threshold
        if (bestScore < SCORE_THRESHOLD) return RecognizedShape.Unknown

        // Per-gesture thresholds
        if (bestName == "checkmark"   && bestScore < SCORE_THRESHOLD_CHECKMARK)   return RecognizedShape.Unknown
        if (bestName == "exclamation" && bestScore < SCORE_THRESHOLD_EXCLAMATION) return RecognizedShape.Unknown

        // Single-stroke X must actually cross itself
        if (bestName == "xmark" && strokeCount < 2 && !isCrossingStroke(rawPoints)) {
            Log.d("PDollar", "Suppressing xmark — single stroke, no crossing")
            return RecognizedShape.Unknown
        }

        // Straight diagonal must not fire as checkmark (first leg of an X)
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
     * Returns true if a single stroke crosses itself (signature of a one-stroke X or loop-X).
     * Requires direction reversal in both X and Y axes — a real crossing changes both.
     * Using both axes prevents a simple curved line from triggering.
     */
    private fun isCrossingStroke(pts: List<PDPoint>): Boolean {
        if (pts.size < 6) return false
        var xFlips = 0; var yFlips = 0
        var prevDx = 0f; var prevDy = 0f
        for (i in 1 until pts.size) {
            val dx = pts[i].x - pts[i - 1].x
            val dy = pts[i].y - pts[i - 1].y
            if (prevDx != 0f && dx * prevDx < 0) xFlips++
            if (prevDy != 0f && dy * prevDy < 0) yFlips++
            if (dx != 0f) prevDx = dx
            if (dy != 0f) prevDy = dy
        }
        Log.d("PDollar", "isCrossingStroke xFlips=$xFlips yFlips=$yFlips")
        // Require at least 1 flip in each axis (true crossing), OR many flips (looping X)
        return (xFlips >= 1 && yFlips >= 1) || xFlips >= 3 || yFlips >= 3
    }

    /** True if stroke is mostly a straight line (linearity > 80%) */
    private fun isStraightDiagonal(pts: List<PDPoint>): Boolean {
        if (pts.size < 4) return false
        val start = pts.first(); val end = pts.last()
        val direct = dist(start, end)
        val path = (1 until pts.size).sumOf { dist(pts[it - 1], pts[it]).toDouble() }.toFloat()
        val linearity = if (path > 0f) direct / path else 0f
        Log.d("PDollar", "isStraightDiagonal linearity=${"%.3f".format(linearity)}")
        return linearity > 0.80f
    }
}