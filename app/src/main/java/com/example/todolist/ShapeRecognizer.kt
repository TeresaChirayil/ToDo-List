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
// $P Point-Cloud Recognizer (Vatavu, Anthony, Wobbrock 2012)
// ---------------------------------------------------------------------------

private const val NUM_POINTS = 32          // resample every gesture to this many pts
private const val SCORE_THRESHOLD = 0.70f  // default minimum score (0..1) to accept a match
private const val SCORE_THRESHOLD_EXCLAMATION = 0.82f  // stricter threshold for !

private data class PDPoint(val x: Float, val y: Float, val strokeIdx: Int)

private data class Template(val name: String, val points: List<PDPoint>)

private fun dist(a: PDPoint, b: PDPoint) =
    sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

/** Uniform index-based resampling to exactly n points */
private fun uniformSample(pts: List<PDPoint>, n: Int): List<PDPoint> {
    if (pts.size < 2) return List(n) { pts.firstOrNull() ?: PDPoint(0f, 0f, 0) }
    return (0 until n).map { i ->
        val idx = (i.toFloat() / (n - 1) * (pts.size - 1)).toInt().coerceIn(0, pts.lastIndex)
        pts[idx]
    }
}

/** Scale all points so the bounding box fits in a unit square */
private fun scaleToSquare(pts: List<PDPoint>): List<PDPoint> {
    val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
    val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
    val scale = maxOf(maxX - minX, maxY - minY).let { if (it == 0f) 1f else it }
    return pts.map { PDPoint((it.x - minX) / scale, (it.y - minY) / scale, it.strokeIdx) }
}

/** Translate centroid to origin */
private fun translateToOrigin(pts: List<PDPoint>): List<PDPoint> {
    val cx = pts.map { it.x }.average().toFloat()
    val cy = pts.map { it.y }.average().toFloat()
    return pts.map { PDPoint(it.x - cx, it.y - cy, it.strokeIdx) }
}

/** Full $P normalization: resample → scale → center */
private fun normalize(pts: List<PDPoint>): List<PDPoint> =
    translateToOrigin(scaleToSquare(uniformSample(pts, NUM_POINTS)))

/** $P greedy nearest-neighbor cloud distance */
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

/** Convert distance to a 0..1 confidence score */
private fun distanceToScore(d: Float): Float = 1f / (1f + d / NUM_POINTS)

// ---------------------------------------------------------------------------
// Template definitions  (arbitrary ~100×100 coordinate space, normalized)
// ---------------------------------------------------------------------------

private fun pts(strokeIdx: Int, vararg xy: Float): List<PDPoint> {
    val list = mutableListOf<PDPoint>()
    var i = 0
    while (i < xy.size - 1) { list.add(PDPoint(xy[i], xy[i + 1], strokeIdx)); i += 2 }
    return list
}

private val TEMPLATE_CHECKMARK = Template(
    "checkmark",
    normalize(
        // One stroke: down-left then up-right (V shape)
        pts(
            0,
            0f, 40f,
            10f, 58f,
            20f, 72f,
            32f, 88f,
            42f, 100f,   // bottom of the V
            56f, 78f,
            70f, 55f,
            84f, 30f,
            100f, 0f
        )
    )
)

private val TEMPLATE_XMARK = Template(
    "xmark",
    normalize(
        // Two strokes: \ (stroke 0) and / (stroke 1)
        pts(0, 0f, 0f, 25f, 25f, 50f, 50f, 75f, 75f, 100f, 100f) +
                pts(1, 100f, 0f, 75f, 25f, 50f, 50f, 25f, 75f, 0f, 100f)
    )
)

private val TEMPLATE_EXCLAMATION = Template(
    "exclamation",
    normalize(
        // Two strokes: tall vertical line (stroke 0) + short dot below (stroke 1)
        pts(0, 50f, 0f, 50f, 18f, 50f, 36f, 50f, 54f, 50f, 72f) +
                pts(1, 50f, 88f, 51f, 100f)
    )
)

private val ALL_TEMPLATES = listOf(TEMPLATE_CHECKMARK, TEMPLATE_XMARK, TEMPLATE_EXCLAMATION)

// ---------------------------------------------------------------------------
// ShapeRecognizer — public API is unchanged
// ---------------------------------------------------------------------------

class ShapeRecognizer {

    /**
     * Recognise across all accumulated strokes using $P.
     * All strokes are concatenated into one point cloud so single- and
     * multi-stroke gestures are handled uniformly — no separate heuristics.
     */
    fun recognizeAll(strokes: List<Stroke>): RecognizedShape {
        if (strokes.isEmpty()) return RecognizedShape.Unknown
        val allPoints = strokes.flatMapIndexed { si, stroke ->
            stroke.points.map { PDPoint(it.x, it.y, si) }
        }
        return matchCloud(allPoints)
    }

    /** Single-stroke convenience method (kept for compatibility with InkOverlay). */
    fun recognize(stroke: Stroke): RecognizedShape {
        val pts = stroke.points.map { PDPoint(it.x, it.y, 0) }
        return matchCloud(pts)
    }

    // -----------------------------------------------------------------------

    private fun matchCloud(rawPoints: List<PDPoint>): RecognizedShape {
        if (rawPoints.size < 4) return RecognizedShape.Unknown

        val candidate = normalize(rawPoints)

        var bestScore = -1f
        var bestName = ""

        for (tmpl in ALL_TEMPLATES) {
            val d = cloudDistance(candidate, tmpl.points)
            val score = distanceToScore(d)
            Log.d("PDollar", "template=${tmpl.name}  dist=${"%.3f".format(d)}  score=${"%.3f".format(score)}")
            if (score > bestScore) { bestScore = score; bestName = tmpl.name }
        }

        Log.d("PDollar", "BEST → $bestName  score=${"%.3f".format(bestScore)}  threshold=$SCORE_THRESHOLD")

        if (bestScore < SCORE_THRESHOLD) return RecognizedShape.Unknown
        if (bestName == "exclamation" && bestScore < SCORE_THRESHOLD_EXCLAMATION) return RecognizedShape.Unknown

        return when (bestName) {
            "checkmark"   -> RecognizedShape.Checkmark(bestScore)
            "xmark"       -> RecognizedShape.XMark(bestScore)
            "exclamation" -> RecognizedShape.UpArrow(bestScore)
            else          -> RecognizedShape.Unknown
        }
    }
}