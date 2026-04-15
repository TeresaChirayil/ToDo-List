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

private const val NUM_POINTS = 32
private const val SCORE_THRESHOLD = 0.68f
private const val SCORE_THRESHOLD_CHECKMARK = 0.70f
private const val SCORE_THRESHOLD_EXCLAMATION = 0.76f
private const val SCORE_THRESHOLD_ARROW = 0.72f

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

private val TEMPLATE_CHECKMARK = Template(
    "checkmark",
    normalize(pts(0, 0f,40f, 10f,58f, 20f,72f, 32f,88f, 42f,100f, 56f,78f, 70f,55f, 84f,30f, 100f,0f))
)

private val TEMPLATE_CHECKMARK_WIDE = Template(
    "checkmark",
    normalize(pts(0, 0f,20f, 15f,45f, 28f,65f, 38f,85f, 46f,100f, 60f,72f, 74f,45f, 88f,20f, 100f,0f))
)

private val TEMPLATE_XMARK = Template(
    "xmark",
    normalize(
        pts(0, 0f,0f, 25f,25f, 50f,50f, 75f,75f, 100f,100f) +
                pts(1, 100f,0f, 75f,25f, 50f,50f, 25f,75f, 0f,100f)
    )
)

private val TEMPLATE_XMARK_SINGLE = Template(
    "xmark",
    normalize(pts(0, 0f,0f, 25f,25f, 50f,50f, 75f,75f, 100f,100f,
        75f,25f, 50f,50f, 25f,75f, 0f,100f))
)

private val TEMPLATE_XMARK_LOOP = Template(
    "xmark",
    normalize(pts(0,
        20f,70f, 15f,50f, 20f,25f, 35f,8f, 55f,5f, 75f,15f,
        85f,35f, 80f,55f, 60f,65f, 45f,60f, 35f,50f,
        50f,65f, 65f,80f, 80f,95f, 95f,100f
    ))
)

private val TEMPLATE_XMARK_LOOP_MIRROR = Template(
    "xmark",
    normalize(pts(0,
        80f,70f, 85f,50f, 80f,25f, 65f,8f, 45f,5f, 25f,15f,
        15f,35f, 20f,55f, 40f,65f, 55f,60f, 65f,50f,
        50f,65f, 35f,80f, 20f,95f, 5f,100f
    ))
)

private val TEMPLATE_EXCLAMATION = Template(
    "exclamation",
    normalize(
        pts(0, 50f,0f, 50f,18f, 50f,36f, 50f,54f, 50f,72f) +
                pts(1, 50f,88f, 51f,100f)
    )
)

private val TEMPLATE_EXCLAMATION_GAP = Template(
    "exclamation",
    normalize(
        pts(0, 50f,0f, 50f,15f, 50f,30f, 50f,45f, 50f,60f) +
                pts(1, 50f,82f, 50f,100f)
    )
)

private val TEMPLATE_EXCLAMATION_TICK = Template(
    "exclamation",
    normalize(
        pts(0, 50f,0f, 50f,20f, 50f,40f, 50f,60f, 50f,75f) +
                pts(1, 42f,92f, 50f,96f, 58f,100f)
    )
)

private val TEMPLATE_ARROW_UP = Template(
    "arrow",
    normalize(
        pts(0, 50f,0f, 25f,40f, 0f,80f) +
                pts(1, 50f,0f, 75f,40f, 100f,80f)
    )
)

private val TEMPLATE_ARROW_UP_TALL = Template(
    "arrow",
    normalize(
        pts(0, 50f,0f, 30f,50f, 10f,100f) +
                pts(1, 50f,0f, 70f,50f, 90f,100f)
    )
)

private val TEMPLATE_ARROW_UP_SINGLE = Template(
    "arrow",
    normalize(pts(0,
        0f,80f, 25f,40f, 50f,0f, 75f,40f, 100f,80f
    ))
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
    TEMPLATE_EXCLAMATION_TICK,
    TEMPLATE_ARROW_UP,
    TEMPLATE_ARROW_UP_TALL,
    TEMPLATE_ARROW_UP_SINGLE
)

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
        var secondScore = -1f
        var secondName = ""

        for (tmpl in ALL_TEMPLATES) {
            val d = cloudDistance(candidate, tmpl.points)
            val s = distanceToScore(d)
            Log.d("PDollar", "template=${tmpl.name}  score=${"%.3f".format(s)}")
            if (s > bestScore) {
                secondScore = bestScore; secondName = bestName
                bestScore = s; bestName = tmpl.name
            } else if (s > secondScore && tmpl.name != bestName) {
                secondScore = s; secondName = tmpl.name
            }
        }

        Log.d("PDollar", "BEST → $bestName  score=${"%.3f".format(bestScore)}  strokes=$strokeCount")

        if (bestScore < SCORE_THRESHOLD) return RecognizedShape.Unknown
        if (bestName == "checkmark"   && bestScore < SCORE_THRESHOLD_CHECKMARK)   return RecognizedShape.Unknown
        if (bestName == "exclamation" && bestScore < SCORE_THRESHOLD_EXCLAMATION) return RecognizedShape.Unknown

        if (bestName == "exclamation") {
            if (strokeCount < 2) {
                Log.d("PDollar", "Suppressing exclamation — only 1 stroke, checking arrow fallback")
                if (isTwoStrokeArrow(rawPoints) || isArrowUp(rawPoints)) {
                    Log.d("PDollar", "Falling back to arrow (single stroke V)")
                    bestName = "arrow"
                } else if (secondName == "checkmark" && secondScore >= SCORE_THRESHOLD_CHECKMARK) {
                    Log.d("PDollar", "Falling back to checkmark score=${"%.3f".format(secondScore)}")
                    bestName = "checkmark"; bestScore = secondScore
                } else {
                    return RecognizedShape.Unknown
                }
            } else if (!dotIsBelowLine(rawPoints)) {
                Log.d("PDollar", "Suppressing exclamation — dot not below line, checking arrow fallback")
                if (isTwoStrokeArrow(rawPoints)) {
                    Log.d("PDollar", "Falling back to arrow (2-stroke V)")
                    bestName = "arrow"
                } else {
                    return RecognizedShape.Unknown
                }
            }
        }

        if (bestName == "xmark") {
            if (strokeCount < 2) {
                val lin = linearity(rawPoints)
                if (lin < 0.15f) {
                    Log.d("PDollar", "Suppressing xmark — closed loop (lin=${"%.2f".format(lin)})")
                    return RecognizedShape.Unknown
                }
                if (!isCrossingStroke(rawPoints)) {
                    Log.d("PDollar", "Suppressing xmark — single stroke, no crossing")
                    return RecognizedShape.Unknown
                }
            } else {
                val stroke0 = rawPoints.filter { it.strokeIdx == 0 }
                val stroke1 = rawPoints.filter { it.strokeIdx == 1 }
                val lin0 = linearity(stroke0)
                val lin1 = linearity(stroke1)
                Log.d("PDollar", "xmark 2-stroke lin0=${"%.2f".format(lin0)} lin1=${"%.2f".format(lin1)}")
                if (lin0 < 0.7f || lin1 < 0.7f) {
                    Log.d("PDollar", "Suppressing xmark — 2-stroke but not both straight")
                    return RecognizedShape.Unknown
                }
            }
        }

        if (bestName == "checkmark" && strokeCount == 1 && linearity(rawPoints) > 0.95f) {
            Log.d("PDollar", "Suppressing checkmark — straight diagonal")
            return RecognizedShape.Unknown
        }

        if (bestName == "arrow" && bestScore < SCORE_THRESHOLD_ARROW) return RecognizedShape.Unknown

        if (bestName == "arrow") {
            if (!isArrowUp(rawPoints)) {
                Log.d("PDollar", "Suppressing arrow — does not point upward")
                return RecognizedShape.Unknown
            }
        }

        return when (bestName) {
            "checkmark"   -> RecognizedShape.Checkmark(bestScore)
            "xmark"       -> RecognizedShape.XMark(bestScore)
            "exclamation" -> RecognizedShape.UpArrow(bestScore)
            "arrow"       -> RecognizedShape.UpArrow(bestScore)
            else          -> RecognizedShape.Unknown
        }
    }

    private fun isTwoStrokeArrow(pts: List<PDPoint>): Boolean {
        val stroke0 = pts.filter { it.strokeIdx == 0 }
        val stroke1 = pts.filter { it.strokeIdx == 1 }
        if (stroke0.size < 2 || stroke1.size < 2) return false

        // each stroke should be roughly linear
        val lin0 = linearity(stroke0)
        val lin1 = linearity(stroke1)
        if (lin0 < 0.4f || lin1 < 0.4f) return false

        // both strokes should start near the same top point (the arrow tip)
        val top0 = stroke0.minByOrNull { it.y } ?: return false
        val top1 = stroke1.minByOrNull { it.y } ?: return false
        val totalWidth = pts.maxOf { it.x } - pts.minOf { it.x }
        val tipDist = kotlin.math.abs(top0.x - top1.x) + kotlin.math.abs(top0.y - top1.y)
        Log.d("PDollar", "isTwoStrokeArrow: lin0=${"%.2f".format(lin0)} lin1=${"%.2f".format(lin1)} tipDist=${"%.1f".format(tipDist)} totalWidth=${"%.1f".format(totalWidth)}")

        // tips should be close to each other relative to the overall width
        if (tipDist > totalWidth * 0.85f) return false

        // strokes should diverge downward (ends farther apart than starts)
        val end0 = stroke0.maxByOrNull { it.y } ?: return false
        val end1 = stroke1.maxByOrNull { it.y } ?: return false
        val endDist = kotlin.math.abs(end0.x - end1.x)
        val startDist = kotlin.math.abs(top0.x - top1.x)
        return endDist > startDist
    }

    private fun isArrowUp(pts: List<PDPoint>): Boolean {
        if (pts.isEmpty()) return false
        val maxY = pts.maxOf { it.y }
        val topPoint = pts.minByOrNull { it.y } ?: return false
        val topX = topPoint.x
        val midX = (pts.maxOf { it.x } + pts.minOf { it.x }) / 2f
        val width = pts.maxOf { it.x } - pts.minOf { it.x }
        val height = maxY - pts.minOf { it.y }
        if (height < width * 0.3f) return false
        val centerDist = kotlin.math.abs(topX - midX)
        Log.d("PDollar", "isArrowUp: topX=${"%.1f".format(topX)} midX=${"%.1f".format(midX)} centerDist=${"%.1f".format(centerDist)} width=${"%.1f".format(width)}")
        if (centerDist >= width * 0.55f) return false
        // a checkmark starts top-left and ends bottom-right, topmost point is near the start
        // an arrow has its topmost point near center horizontally
        // extra check: top point should NOT be at the very beginning or end of the stroke
        val topIdx = pts.indexOfFirst { it.y == pts.minOf { p -> p.y } }
        val relPos = topIdx.toFloat() / pts.size
        // if topmost point is in the first 20% or last 20% it's likely a checkmark start/end
        return relPos in 0.15f..0.85f
    }

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
        // too many flips = cursive handwriting, not a real X
        if (xFlips > 6 || yFlips > 6) return false
        return xFlips >= 1 && yFlips >= 1
    }

    private fun linearity(pts: List<PDPoint>): Float {
        if (pts.size < 2) return 0f
        val direct = dist(pts.first(), pts.last())
        val path = (1 until pts.size).sumOf { dist(pts[it - 1], pts[it]).toDouble() }.toFloat()
        return if (path > 0f) direct / path else 0f
    }

    private fun dotIsBelowLine(rawPoints: List<PDPoint>): Boolean {
        val stroke0 = rawPoints.filter { it.strokeIdx == 0 }
        val stroke1 = rawPoints.filter { it.strokeIdx == 1 }
        if (stroke0.isEmpty() || stroke1.isEmpty()) return false
        val lineAvgY = stroke0.map { it.y }.average()
        val dotAvgY  = stroke1.map { it.y }.average()
        if (dotAvgY <= lineAvgY) return false
        val lineHeight = stroke0.maxOf { it.y } - stroke0.minOf { it.y }
        val dotWidth   = stroke1.maxOf { it.x } - stroke1.minOf { it.x }
        val ratio = if (lineHeight > 0f) dotWidth / lineHeight else 1f
        Log.d("PDollar", "dotIsBelowLine: lineAvgY=${"%.1f".format(lineAvgY)} dotAvgY=${"%.1f".format(dotAvgY)} ratio=${"%.2f".format(ratio)}")
        return ratio < 0.5f
    }

    fun looksLikeCheckmarkFast(stroke: Stroke): Boolean {
        val pts = stroke.points.map { PDPoint(it.x, it.y, 0) }
        if (pts.size < 4) return false
        val candidate = normalize(pts)
        val best = listOf(TEMPLATE_CHECKMARK, TEMPLATE_CHECKMARK_WIDE)
            .maxOf { distanceToScore(cloudDistance(candidate, it.points)) }
        val start = pts.first(); val end = pts.last()
        val direct = dist(PDPoint(start.x, start.y, 0), PDPoint(end.x, end.y, 0))
        val path = (1 until pts.size).sumOf { dist(pts[it - 1], pts[it]).toDouble() }.toFloat()
        val lin = if (path > 0f) direct / path else 0f
        Log.d("PDollar", "looksLikeCheckmarkFast score=${"%.3f".format(best)} lin=${"%.2f".format(lin)}")
        return best > 0.60f && lin < 0.80f
    }
}