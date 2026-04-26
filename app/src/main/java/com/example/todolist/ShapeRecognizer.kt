package com.example.todolist

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

data class Point(val x: Float, val y: Float)

sealed class RecognizedShape {
    data class Checkmark(val confidence: Float) : RecognizedShape()
    data class XMark(val confidence: Float) : RecognizedShape()
    data class UpArrow(val confidence: Float) : RecognizedShape()
    object Unknown : RecognizedShape()
}

// ---------------------------------------------------------------------------
// Internal $P point-cloud types
// ---------------------------------------------------------------------------

private data class PDPoint(val x: Float, val y: Float, val strokeIdx: Int)
private data class Template(val name: String, val points: List<PDPoint>)

// ---------------------------------------------------------------------------
// Core $P math
// ---------------------------------------------------------------------------

private fun dist(a: PDPoint, b: PDPoint): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

/**
 * Resample by arc-length — the correct $P implementation.
 * Points are placed at equal intervals along the path, not at equal index steps.
 */
private fun resample(pts: List<PDPoint>, n: Int): List<PDPoint> {
    if (pts.size < 2) return List(n) { pts.firstOrNull() ?: PDPoint(0f, 0f, 0) }

    // Compute total arc length
    var totalLen = 0f
    for (i in 1 until pts.size) totalLen += dist(pts[i - 1], pts[i])

    val interval = totalLen / (n - 1)
    val result = mutableListOf(pts[0])
    var accumulated = 0f
    var i = 1

    while (i < pts.size && result.size < n) {
        val segLen = dist(pts[i - 1], pts[i])
        if (accumulated + segLen >= interval) {
            val t = (interval - accumulated) / segLen
            val x = pts[i - 1].x + t * (pts[i].x - pts[i - 1].x)
            val y = pts[i - 1].y + t * (pts[i].y - pts[i - 1].y)
            val newPt = PDPoint(x, y, pts[i - 1].strokeIdx)
            result.add(newPt)
            // Insert new point back so remaining distance is computed correctly
            // (shift i-1 to this new point)
            accumulated = 0f
            // Don't advance i — re-measure from the new point
            // We do this by mutating a local reference
            // Actually: subtract interval, keep i, set prev to newPt
            accumulated = 0f
            // Simulate: pts[i-1] = newPt (use accumulation reset)
            // We track this by decrementing and pretending we're at newPt:
            // Simple approach: restart segment distance from newPt to pts[i]
            val remaining = dist(newPt, pts[i])
            accumulated = remaining
            if (result.size < n) {
                // keep consuming remaining in this segment
                while (accumulated >= interval && result.size < n) {
                    // walk along remaining
                    val tt = interval / accumulated
                    // But we need direction — just advance i if no more room
                    break
                }
            }
            i++
        } else {
            accumulated += segLen
            i++
        }
    }

    // Fill any remaining slots with the last point
    while (result.size < n) result.add(pts.last())
    return result
}

/**
 * Proper arc-length resample. Clean implementation.
 */
private fun uniformSample(pts: List<PDPoint>, n: Int): List<PDPoint> {
    if (pts.size == 1) return List(n) { pts[0] }
    if (pts.size < 2) return List(n) { pts.firstOrNull() ?: PDPoint(0f, 0f, 0) }

    // Build cumulative arc-length table
    val cumLen = FloatArray(pts.size)
    cumLen[0] = 0f
    for (i in 1 until pts.size) {
        cumLen[i] = cumLen[i - 1] + dist(pts[i - 1], pts[i])
    }
    val totalLen = cumLen.last()
    if (totalLen == 0f) return List(n) { pts[0] }

    val result = mutableListOf<PDPoint>()
    for (k in 0 until n) {
        val target = k.toFloat() / (n - 1) * totalLen
        // Binary search for segment
        var lo = 0
        var hi = pts.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (cumLen[mid] <= target) lo = mid else hi = mid
        }
        val t = if (cumLen[hi] == cumLen[lo]) 0f
        else (target - cumLen[lo]) / (cumLen[hi] - cumLen[lo])
        val x = pts[lo].x + t * (pts[hi].x - pts[lo].x)
        val y = pts[lo].y + t * (pts[hi].y - pts[lo].y)
        result.add(PDPoint(x, y, pts[lo].strokeIdx))
    }
    return result
}

private fun scaleToSquare(pts: List<PDPoint>, size: Float = 250f): List<PDPoint> {
    val minX = pts.minOf { it.x }
    val maxX = pts.maxOf { it.x }
    val minY = pts.minOf { it.y }
    val maxY = pts.maxOf { it.y }
    val w = maxX - minX
    val h = maxY - minY
    val scale = maxOf(w, h).let { if (it == 0f) 1f else it }
    return pts.map { PDPoint((it.x - minX) / scale * size, (it.y - minY) / scale * size, it.strokeIdx) }
}

private fun translateToOrigin(pts: List<PDPoint>): List<PDPoint> {
    val cx = pts.map { it.x }.average().toFloat()
    val cy = pts.map { it.y }.average().toFloat()
    return pts.map { PDPoint(it.x - cx, it.y - cy, it.strokeIdx) }
}

private val NUM_POINTS = 32
private val SCALE = 250f
// $P paper normalization constant: 0.5 * diagonal of the bounding square
// After scaleToSquare(size=250), the space is 250x250 → diagonal = 353.55
// normalizer = 0.5 * diagonal * NUM_POINTS
private val HALF_DIAGONAL = 0.5f * sqrt(SCALE * SCALE + SCALE * SCALE)

private fun normalize(pts: List<PDPoint>): List<PDPoint> =
    translateToOrigin(scaleToSquare(uniformSample(pts, NUM_POINTS)))

/**
 * $P CloudDistance — paper-exact implementation.
 *
 * Matches each point pts[i] (cycling from `start`) to the nearest unmatched
 * template point, applying an ordering weight that rewards drawing in the
 * same sequence as the template:
 *   weight = 1 - ((i - start + n) % n) / n
 * Early points (close to start) get weight ≈ 1.0; later points get lower weight.
 *
 * From: Vatavu, Anthony, Wobbrock (2012), "$P Point-Cloud Recognizer."
 */
private fun cloudDistance(pts: List<PDPoint>, tmpl: List<PDPoint>, start: Int): Float {
    val n = pts.size
    val unmatched = ArrayDeque(tmpl.indices.toList())
    var i = start
    var sum = 0f
    for (step in 0 until n) {
        var best = Float.MAX_VALUE
        var bestPos = -1
        for (pos in unmatched.indices) {
            val d = dist(pts[i], unmatched[pos].let { tmpl[it] })
            if (d < best) { best = d; bestPos = pos }
        }
        if (bestPos >= 0) unmatched.removeAt(bestPos)
        val weight = 1f - ((i - start + n) % n).toFloat() / n
        sum += weight * best
        i = (i + 1) % n
    }
    return sum
}

/**
 * $P GreedyCloudMatch — paper-exact implementation.
 *
 * Tries matching starting at every `step`-th point (step = sqrt(n)) to find
 * the best rotational alignment of the point clouds. Runs in both directions
 * (pts→tmpl and tmpl→pts) and returns the minimum distance found.
 *
 * This is what makes $P robust to where in the stroke the user started drawing.
 */
private fun greedyCloudMatch(pts: List<PDPoint>, tmpl: List<PDPoint>): Float {
    val n = pts.size
    val step = maxOf(1, Math.floor(Math.pow(n.toDouble(), 0.5)).toInt())
    var best = Float.MAX_VALUE
    var i = 0
    while (i < n) {
        val d1 = cloudDistance(pts, tmpl, i)
        val d2 = cloudDistance(tmpl, pts, i)
        if (d1 < best) best = d1
        if (d2 < best) best = d2
        i += step
    }
    return best
}

// $P scoring formula from paper: score = 1 - d / (0.5 * diagonal * n)
private fun distanceToScore(d: Float): Float = 1f - d / (HALF_DIAGONAL * NUM_POINTS)

// ---------------------------------------------------------------------------
// Helper to build raw point lists from coordinate pairs
// ---------------------------------------------------------------------------

private fun pts(strokeIdx: Int, vararg xy: Float): List<PDPoint> {
    val list = mutableListOf<PDPoint>()
    var i = 0
    while (i + 1 < xy.size) {
        list.add(PDPoint(xy[i], xy[i + 1], strokeIdx))
        i += 2
    }
    return list
}

// ---------------------------------------------------------------------------
// Templates
// ---------------------------------------------------------------------------

// CHECKMARK — v-shape going down-right then up-right (or the classic tick)
// Multiple variants to cover natural variation in angle and size

private val TEMPLATE_CHECKMARK_CLASSIC = Template("checkmark", normalize(
    // Classic Nike tick: start top-left, dip to bottom-middle, finish top-right
    pts(0,
        0f,40f,   8f,55f,  16f,68f,  24f,80f,  32f,90f,  40f,100f,  // down-left leg
        52f,80f,  64f,58f,  76f,36f,  88f,16f,  100f,0f               // up-right leg
    )
))

private val TEMPLATE_CHECKMARK_SHALLOW = Template("checkmark", normalize(
    // Shallower tick — down then gently up
    pts(0,
        0f,20f,  12f,40f,  22f,58f,  32f,72f,  40f,82f,  48f,90f,  54f,100f,
        66f,78f,  78f,55f,  88f,30f,  100f,5f
    )
))

private val TEMPLATE_CHECKMARK_BIG_V = Template("checkmark", normalize(
    // Wide V with left leg longer
    pts(0,
        0f,10f,  14f,38f,  26f,62f,  36f,82f,  44f,98f,  54f,100f,
        64f,80f,  76f,54f,  88f,28f,  100f,0f
    )
))

private val TEMPLATE_CHECKMARK_STEEP = Template("checkmark", normalize(
    // Almost vertical then steep-up
    pts(0,
        0f,50f,  8f,68f,  16f,84f,  24f,96f,  30f,100f,
        42f,74f,  56f,46f,  72f,20f,  88f,0f,  100f,0f
    )
))

// X-MARK — two crossing diagonal lines

private val TEMPLATE_XMARK_2STROKE = Template("xmark", normalize(
    // Two-stroke: top-left to bottom-right, then top-right to bottom-left
    pts(0,  0f,0f, 25f,25f, 50f,50f, 75f,75f, 100f,100f) +
            pts(1,  100f,0f, 75f,25f, 50f,50f, 25f,75f, 0f,100f)
))

private val TEMPLATE_XMARK_2STROKE_ALT = Template("xmark", normalize(
    // Two-stroke: top-right first, then top-left to bottom-right
    pts(0,  100f,0f, 75f,25f, 50f,50f, 25f,75f, 0f,100f) +
            pts(1,  0f,0f, 25f,25f, 50f,50f, 75f,75f, 100f,100f)
))

private val TEMPLATE_XMARK_SINGLE = Template("xmark", normalize(
    // Single-stroke X: one diagonal then reverse to make the cross
    pts(0,
        0f,0f, 30f,30f, 50f,50f, 70f,70f, 100f,100f,
        70f,30f, 50f,50f, 30f,70f, 0f,100f
    )
))

private val TEMPLATE_XMARK_SINGLE_ALT = Template("xmark", normalize(
    // Single-stroke starting from top-right
    pts(0,
        100f,0f, 70f,30f, 50f,50f, 30f,70f, 0f,100f,
        30f,30f, 50f,50f, 70f,70f, 100f,100f
    )
))

// ARROW UP — V-shape pointing upward (inverted V / caret)

private val TEMPLATE_ARROW_UP_V = Template("arrow", normalize(
    // Two-stroke: left leg down-left from apex, right leg down-right from apex
    pts(0,  50f,0f, 28f,38f, 8f,74f, 0f,100f) +
            pts(1,  50f,0f, 72f,38f, 92f,74f, 100f,100f)
))

private val TEMPLATE_ARROW_UP_V_WIDE = Template("arrow", normalize(
    // Wide caret, shallower legs
    pts(0,  50f,0f, 32f,32f, 14f,64f, 0f,90f) +
            pts(1,  50f,0f, 68f,32f, 86f,64f, 100f,90f)
))

private val TEMPLATE_ARROW_UP_SINGLE = Template("arrow", normalize(
    // Single-stroke V: left leg first, then right leg
    pts(0,
        0f,100f,  18f,72f,  34f,46f,  44f,24f,  50f,0f,
        56f,24f,  66f,46f,  82f,72f,  100f,100f
    )
))

private val TEMPLATE_ARROW_UP_SINGLE_ALT = Template("arrow", normalize(
    // Single-stroke: right leg first
    pts(0,
        100f,100f,  82f,72f,  66f,46f,  56f,24f,  50f,0f,
        44f,24f,   34f,46f,  18f,72f,  0f,100f
    )
))

private val TEMPLATE_ARROW_UP_TALL = Template("arrow", normalize(
    // Tall narrow arrow
    pts(0,  50f,0f, 34f,40f, 18f,70f, 0f,100f) +
            pts(1,  50f,0f, 66f,40f, 82f,70f, 100f,100f)
))

// Arrow WITH a vertical stem — the most natural way to draw an up-arrow
// stroke 0 = left leg, stroke 1 = right leg, stroke 2 = stem
private val TEMPLATE_ARROW_STEM_3S = Template("arrow", normalize(
    pts(0,  50f,15f, 30f,45f, 12f,80f) +
            pts(1,  50f,15f, 70f,45f, 88f,80f) +
            pts(2,  50f,15f, 50f,100f)
))

// Arrow with stem: caret first (single stroke), then stem
private val TEMPLATE_ARROW_STEM_CARET_STEM = Template("arrow", normalize(
    pts(0,  12f,80f, 30f,45f, 50f,15f, 70f,45f, 88f,80f) +
            pts(1,  50f,15f, 50f,100f)
))

// Arrow with stem: left leg + continues into stem, then right leg
private val TEMPLATE_ARROW_STEM_LEG_STEM = Template("arrow", normalize(
    pts(0,  12f,80f, 30f,45f, 50f,15f, 50f,100f) +
            pts(1,  50f,15f, 70f,45f, 88f,80f)
))

// Arrow with stem: stem drawn first, then caret
private val TEMPLATE_ARROW_STEM_STEM_CARET = Template("arrow", normalize(
    pts(0,  50f,15f, 50f,100f) +
            pts(1,  12f,80f, 30f,45f, 50f,15f, 70f,45f, 88f,80f)
))

// ---------------------------------------------------------------------------
// All templates in priority order (more specific first within each class)
// ---------------------------------------------------------------------------

private val ALL_TEMPLATES = listOf(
    TEMPLATE_CHECKMARK_CLASSIC,
    TEMPLATE_CHECKMARK_SHALLOW,
    TEMPLATE_CHECKMARK_BIG_V,
    TEMPLATE_CHECKMARK_STEEP,
    TEMPLATE_XMARK_2STROKE,
    TEMPLATE_XMARK_2STROKE_ALT,
    TEMPLATE_XMARK_SINGLE,
    TEMPLATE_XMARK_SINGLE_ALT,
    TEMPLATE_ARROW_UP_V,
    TEMPLATE_ARROW_UP_V_WIDE,
    TEMPLATE_ARROW_UP_SINGLE,
    TEMPLATE_ARROW_UP_SINGLE_ALT,
    TEMPLATE_ARROW_UP_TALL,
    TEMPLATE_ARROW_STEM_3S,
    TEMPLATE_ARROW_STEM_CARET_STEM,
    TEMPLATE_ARROW_STEM_LEG_STEM,
    TEMPLATE_ARROW_STEM_STEM_CARET
)

// ---------------------------------------------------------------------------
// Thresholds
// ---------------------------------------------------------------------------

// Thresholds are tuned for the correct $P scoring formula (0.0-1.0 range).
// Self-match = 1.0, good gesture match ≈ 0.85-0.95, wrong gesture ≈ 0.65-0.80.
private const val MIN_SCORE = 0.80f                  // global floor
private const val CHECKMARK_SCORE = 0.82f            // checkmarks: require solid match
private const val XMARK_SCORE = 0.80f
private const val ARROW_SCORE = 0.82f
private const val MARGIN_ARROW_VS_CHECKMARK = 0.06f  // arrow must beat best checkmark score by this

// ---------------------------------------------------------------------------
// Main recognizer class
// ---------------------------------------------------------------------------

class ShapeRecognizer {

    /**
     * Recognise across one or two strokes (the normal call path).
     */
    fun recognizeAll(strokes: List<Stroke>): RecognizedShape {
        if (strokes.isEmpty()) return RecognizedShape.Unknown
        // More than 3 strokes is definitely handwriting — bail early
        // We allow up to 3 for stemmed arrows (left-leg + right-leg + stem)
        if (strokes.size > 3) return RecognizedShape.Unknown

        val allPoints = strokes.flatMapIndexed { si, stroke ->
            stroke.points.map { PDPoint(it.x, it.y, si) }
        }
        return matchCloud(allPoints, strokes.size)
    }

    /**
     * Single-stroke recognition (legacy path).
     */
    fun recognize(stroke: Stroke): RecognizedShape {
        val pts = stroke.points.map { PDPoint(it.x, it.y, 0) }
        return matchCloud(pts, 1)
    }

    // -----------------------------------------------------------------------
    // Core matching
    // -----------------------------------------------------------------------

    private fun matchCloud(rawPoints: List<PDPoint>, strokeCount: Int): RecognizedShape {
        if (rawPoints.size < 5) return RecognizedShape.Unknown

        val candidate = normalize(rawPoints)

        data class Match(val name: String, val score: Float)
        var best = Match("", -1f)
        var second = Match("", -1f)

        for (tmpl in ALL_TEMPLATES) {
            val d = greedyCloudMatch(candidate, tmpl.points)
            val s = distanceToScore(d)
            Log.d("PDollar", "template=${tmpl.name}  score=${"%.3f".format(s)}")
            if (s > best.score) {
                if (best.name != tmpl.name) second = best
                best = Match(tmpl.name, s)
            } else if (s > second.score && tmpl.name != best.name) {
                second = Match(tmpl.name, s)
            }
        }

        Log.d("PDollar", "BEST → ${best.name}  score=${"%.3f".format(best.score)}  strokes=$strokeCount")

        // Global floor
        if (best.score < MIN_SCORE) return RecognizedShape.Unknown

        // Try best first, then fall through to second if best is suppressed.
        // This handles cases where xmark scores highest but fails geometric checks,
        // and arrow (the true intent) is the runner-up.
        val ranked = listOf(best, second).filter { it.score >= MIN_SCORE && it.name.isNotEmpty() }
        for (candidate in ranked) {
            val result = when (candidate.name) {
                "checkmark" -> recognizeCheckmark(candidate.score, rawPoints, strokeCount)
                "xmark"     -> recognizeXmark(candidate.score, rawPoints, strokeCount)
                "arrow"     -> recognizeArrow(candidate.score, rawPoints, strokeCount)
                else        -> RecognizedShape.Unknown
            }
            if (result !is RecognizedShape.Unknown) return result
        }
        return RecognizedShape.Unknown
    }

    // -----------------------------------------------------------------------
    // Per-gesture validation
    // -----------------------------------------------------------------------

    private fun recognizeCheckmark(
        score: Float, raw: List<PDPoint>, strokeCount: Int
    ): RecognizedShape {
        if (score < CHECKMARK_SCORE) return RecognizedShape.Unknown

        // A straight line is not a checkmark
        if (strokeCount == 1 && linearity(raw) > 0.94f) {
            Log.d("PDollar", "Suppressing checkmark — too straight (${linearity(raw)})")
            return RecognizedShape.Unknown
        }

        // A checkmark has a clear directional change — the lowest point should NOT be at the ends
        if (strokeCount == 1) {
            val bottomIdx = raw.indexOfFirst { p -> p.y == raw.maxOf { it.y } }
            val relPos = bottomIdx.toFloat() / raw.size
            // Bottom point should be somewhere in the middle (not the very start/end)
            if (relPos < 0.10f || relPos > 0.90f) {
                Log.d("PDollar", "Suppressing checkmark — bottom at edge (relPos=${"%.2f".format(relPos)})")
                return RecognizedShape.Unknown
            }
        }

        return RecognizedShape.Checkmark(score)
    }

    private fun recognizeXmark(
        score: Float, raw: List<PDPoint>, strokeCount: Int
    ): RecognizedShape {
        if (score < XMARK_SCORE) return RecognizedShape.Unknown

        if (strokeCount >= 2) {
            // Two-stroke X: each stroke should be roughly linear (straight diagonal)
            val s0 = raw.filter { it.strokeIdx == 0 }
            val s1 = raw.filter { it.strokeIdx == 1 }
            val lin0 = linearity(s0)
            val lin1 = linearity(s1)
            Log.d("PDollar", "xmark 2-stroke lin0=${"%.2f".format(lin0)} lin1=${"%.2f".format(lin1)}")
            if (lin0 < 0.65f || lin1 < 0.65f) {
                Log.d("PDollar", "Suppressing xmark — 2-stroke but not both linear")
                return RecognizedShape.Unknown
            }
            // The two strokes should cross (bounding boxes must overlap centrally)
            if (!strokesBoundingBoxesOverlap(s0, s1)) {
                Log.d("PDollar", "Suppressing xmark — strokes don't spatially overlap")
                return RecognizedShape.Unknown
            }
        } else {
            // Single-stroke X: must change direction in both axes (makes a cross shape)
            if (!isCrossingStroke(raw)) {
                Log.d("PDollar", "Suppressing xmark — single stroke, no crossing")
                return RecognizedShape.Unknown
            }
            // Also require it isn't a closed loop (circles/ovals match xmark templates badly)
            if (linearity(raw) < 0.12f) {
                Log.d("PDollar", "Suppressing xmark — looks like closed loop")
                return RecognizedShape.Unknown
            }
        }

        return RecognizedShape.XMark(score)
    }

    private fun recognizeArrow(
        score: Float, raw: List<PDPoint>, strokeCount: Int
    ): RecognizedShape {
        if (score < ARROW_SCORE) return RecognizedShape.Unknown

        // Arrow must beat the best checkmark score by a clear margin
        val checkmarkTemplates = ALL_TEMPLATES.filter { it.name == "checkmark" }
        val candidate = normalize(raw)
        val bestCheckmarkScore = checkmarkTemplates.maxOf {
            distanceToScore(greedyCloudMatch(candidate, it.points))
        }
        if (bestCheckmarkScore >= score - MARGIN_ARROW_VS_CHECKMARK) {
            Log.d("PDollar", "Suppressing arrow — too close to checkmark (${"%.3f".format(bestCheckmarkScore)})")
            return RecognizedShape.Unknown
        }

        // Geometric check: verify the shape is actually an upward caret/arrow
        if (!isArrowUp(raw, strokeCount)) {
            Log.d("PDollar", "Suppressing arrow — failed isArrowUp geometric check")
            return RecognizedShape.Unknown
        }

        return RecognizedShape.UpArrow(score)
    }

    // -----------------------------------------------------------------------
    // Geometry helpers
    // -----------------------------------------------------------------------

    /**
     * Arrow-up geometric check.
     * Dispatches to stroke-count specific checks.
     */
    private fun isArrowUp(pts: List<PDPoint>, strokeCount: Int): Boolean {
        return when {
            strokeCount >= 3 -> isThreeStrokeArrow(pts)
            strokeCount == 2 -> isTwoStrokeArrow(pts)
            else             -> isSingleStrokeArrow(pts)
        }
    }

    /**
     * Three-stroke arrow (left-leg + right-leg + stem):
     * One stroke must be nearly vertical (the stem), and the other two must
     * form a caret that meets at the top of the stem.
     */
    private fun isThreeStrokeArrow(pts: List<PDPoint>): Boolean {
        val strokes = (0..2).map { si -> pts.filter { it.strokeIdx == si } }
        if (strokes.any { it.size < 2 }) return false

        // Find the stem: the stroke with the highest dy/dx ratio
        fun aspect(s: List<PDPoint>): Float {
            val dx = abs(s.maxOf { it.x } - s.minOf { it.x })
            val dy = abs(s.maxOf { it.y } - s.minOf { it.y })
            return if (dx > 0f) dy / dx else 999f
        }
        val stemIdx = strokes.indices.maxByOrNull { aspect(strokes[it]) } ?: return false
        val stemStroke = strokes[stemIdx]
        val stemAspect = aspect(stemStroke)
        Log.d("PDollar", "isThreeStrokeArrow: stemIdx=$stemIdx aspect=${"%.2f".format(stemAspect)}")

        // Stem must be sufficiently vertical
        if (stemAspect < 2.0f) return false

        // Stem's topmost point must be in the top 40% of the combined shape
        val allY = pts.map { it.y }
        val totalHeight = allY.max() - allY.min()
        if (totalHeight == 0f) return false
        val stemTopY = stemStroke.minOf { it.y }
        val relStemTop = (stemTopY - allY.min()) / totalHeight
        Log.d("PDollar", "isThreeStrokeArrow: relStemTop=${"%.2f".format(relStemTop)}")
        return relStemTop < 0.40f
    }

    /**
     * Two-stroke arrow: covers left-leg+right-leg AND caret+stem.
     *
     * For left-leg+right-leg: strokes share an endpoint (the apex).
     * For caret+stem: the caret's apex is in the MIDDLE of stroke 0,
     *   so no endpoints are shared — instead, both strokes have their
     *   topmost points (min Y) close together near the top of the shape.
     *
     * Both cases are unified: check that both strokes' topmost regions
     * are close to each other AND near the top of the combined shape
     * AND near the horizontal center.
     */
    private fun isTwoStrokeArrow(pts: List<PDPoint>): Boolean {
        val s0 = pts.filter { it.strokeIdx == 0 }
        val s1 = pts.filter { it.strokeIdx == 1 }
        if (s0.size < 2 || s1.size < 2) return false

        val allY = pts.map { it.y }
        val allX = pts.map { it.x }
        val totalHeight = allY.max() - allY.min()
        val totalWidth = allX.max() - allX.min()
        val midX = (allX.min() + allX.max()) / 2f
        if (totalHeight == 0f) return false

        // Find each stroke's topmost Y and the X position there
        val s0MinY = s0.minOf { it.y }
        val s1MinY = s1.minOf { it.y }
        val s0ApexX = s0.first { it.y == s0MinY }.x
        val s1ApexX = s1.first { it.y == s1MinY }.x

        // CONDITION 1: Both strokes' tops are close together vertically
        // (within 25% of total height — handles caret+stem where apex is mid-stroke)
        val apexProximity = abs(s0MinY - s1MinY) / totalHeight
        // OR: they share an endpoint (left-leg + right-leg case)
        val s0ends = listOf(s0.first(), s0.last())
        val s1ends = listOf(s1.first(), s1.last())
        val minEndpointDist = s0ends.flatMap { e0 -> s1ends.map { e1 -> dist(e0, e1) } }.min()
        val topsConverge = apexProximity < 0.25f || minEndpointDist < totalHeight * 0.35f

        // CONDITION 2: The apex region is in the top 40% of the overall shape
        val apexY = minOf(s0MinY, s1MinY)
        val relApexY = (apexY - allY.min()) / totalHeight
        val nearTop = relApexY < 0.40f

        // CONDITION 3: The apex is near horizontal center (rejects diagonal pairs)
        val avgApexX = (s0ApexX + s1ApexX) / 2f
        val centerDist = abs(avgApexX - midX)
        val apexCentered = totalWidth == 0f || centerDist < totalWidth * 0.45f

        Log.d("PDollar", "isTwoStrokeArrow: apexProx=${"%.2f".format(apexProximity)} endDist=${"%.1f".format(minEndpointDist)} topsConverge=$topsConverge relApexY=${"%.2f".format(relApexY)} centerDist=${"%.1f".format(centerDist)} apexCentered=$apexCentered")
        return topsConverge && nearTop && apexCentered
    }

    /**
     * Single-stroke arrow (^ caret): apex is the topmost point and should be
     * near the horizontal center of the bounding box.
     * The apex must NOT be at the very start or end of the stroke (that's a checkmark/diagonal).
     */
    private fun isSingleStrokeArrow(pts: List<PDPoint>): Boolean {
        if (pts.isEmpty()) return false
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val width = maxX - minX
        val height = maxY - minY

        if (height < width * 0.20f) return false

        val topPoint = pts.minByOrNull { it.y } ?: return false
        val midX = (minX + maxX) / 2f
        val centerDist = abs(topPoint.x - midX)
        Log.d("PDollar", "isSingleStrokeArrow: centerDist=${"%.1f".format(centerDist)} width=${"%.1f".format(width)}")

        // Apex must be within 45% of center horizontally
        if (centerDist >= width * 0.45f) return false

        // Apex must not be at the very tip of the stroke (that's a diagonal line or checkmark)
        val topIdx = pts.indexOfFirst { it.y == minY }
        val relPos = topIdx.toFloat() / pts.size
        if (relPos < 0.08f || relPos > 0.92f) {
            Log.d("PDollar", "isSingleStrokeArrow: apex at edge (${"%.2f".format(relPos)}) — likely diagonal")
            return false
        }

        return true
    }

    /**
     * For single-stroke X: the stroke must reverse direction in both X and Y axes.
     */
    private fun isCrossingStroke(pts: List<PDPoint>): Boolean {
        if (pts.size < 6) return false
        var xFlips = 0
        var yFlips = 0
        var prevDx = 0f
        var prevDy = 0f
        for (i in 1 until pts.size) {
            val dx = pts[i].x - pts[i - 1].x
            val dy = pts[i].y - pts[i - 1].y
            if (prevDx != 0f && dx * prevDx < 0) xFlips++
            if (prevDy != 0f && dy * prevDy < 0) yFlips++
            if (abs(dx) > 0.5f) prevDx = dx
            if (abs(dy) > 0.5f) prevDy = dy
        }
        Log.d("PDollar", "isCrossingStroke xFlips=$xFlips yFlips=$yFlips")
        // At least one reversal in each direction, but not too many (not handwriting)
        return xFlips in 1..8 && yFlips in 1..8
    }

    /**
     * For two-stroke X: the bounding boxes must have meaningful overlap in both axes.
     */
    private fun strokesBoundingBoxesOverlap(s0: List<PDPoint>, s1: List<PDPoint>): Boolean {
        if (s0.isEmpty() || s1.isEmpty()) return false
        val s0minX = s0.minOf { it.x }; val s0maxX = s0.maxOf { it.x }
        val s0minY = s0.minOf { it.y }; val s0maxY = s0.maxOf { it.y }
        val s1minX = s1.minOf { it.x }; val s1maxX = s1.maxOf { it.x }
        val s1minY = s1.minOf { it.y }; val s1maxY = s1.maxOf { it.y }

        val overlapX = minOf(s0maxX, s1maxX) - maxOf(s0minX, s1minX)
        val overlapY = minOf(s0maxY, s1maxY) - maxOf(s0minY, s1minY)
        val width = maxOf(s0maxX, s1maxX) - minOf(s0minX, s1minX)
        val height = maxOf(s0maxY, s1maxY) - minOf(s0minY, s1minY)

        // Strokes should overlap by at least 20% of total extent in each axis
        val xOk = width == 0f || overlapX / width > 0.20f
        val yOk = height == 0f || overlapY / height > 0.20f
        Log.d("PDollar", "strokesOverlap: xOk=$xOk yOk=$yOk overlapX=${"%.1f".format(overlapX)} overlapY=${"%.1f".format(overlapY)}")
        return xOk && yOk
    }

    /**
     * Ratio of straight-line distance to arc-length. 1.0 = perfectly straight.
     */
    private fun linearity(pts: List<PDPoint>): Float {
        if (pts.size < 2) return 0f
        val direct = dist(pts.first(), pts.last())
        val path = (1 until pts.size).sumOf { dist(pts[it - 1], pts[it]).toDouble() }.toFloat()
        return if (path > 0f) direct / path else 0f
    }

    // -----------------------------------------------------------------------
    // Fast pre-check used by InkOverlay for debounce timing
    // -----------------------------------------------------------------------

    /**
     * Quick heuristic: does this single stroke look like a checkmark?
     * Used by InkOverlay to decide whether to fire immediately or wait for a second stroke.
     */
    fun looksLikeCheckmarkFast(stroke: Stroke): Boolean {
        val pts = stroke.points.map { PDPoint(it.x, it.y, 0) }
        if (pts.size < 4) return false
        val candidate = normalize(pts)
        val bestScore = listOf(
            TEMPLATE_CHECKMARK_CLASSIC,
            TEMPLATE_CHECKMARK_SHALLOW,
            TEMPLATE_CHECKMARK_BIG_V,
            TEMPLATE_CHECKMARK_STEEP
        ).maxOf { distanceToScore(greedyCloudMatch(candidate, it.points)) }

        val lin = linearity(pts)

        // Bottom point should be in the middle portion — not at the very start or end
        val bottomIdx = pts.indexOfFirst { p -> p.y == pts.maxOf { it.y } }
        val relPos = bottomIdx.toFloat() / pts.size
        val bottomInMiddle = relPos in 0.10f..0.90f

        Log.d("PDollar", "looksLikeCheckmarkFast score=${"%.3f".format(bestScore)} lin=${"%.2f".format(lin)} bottomRelPos=${"%.2f".format(relPos)}")
        return bestScore > 0.78f && lin < 0.85f && bottomInMiddle
    }

    /**
     * Quick heuristic: does this single stroke look like a ^ caret shape?
     * A caret has its apex (topmost point) near the horizontal center and
     * NOT at either end of the stroke — both endpoints are lower than the apex.
     * Used by InkOverlay to extend the debounce window so the user has time
     * to draw a stem below and complete a stemmed arrow.
     */
    fun looksLikeCaretFast(stroke: Stroke): Boolean {
        val pts = stroke.points
        if (pts.size < 4) return false

        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val height = maxY - minY
        val width = maxX - minX

        // Must have meaningful height (not a nearly horizontal line)
        if (height < width * 0.15f) return false

        // Apex (topmost = min Y in screen coords) must be in the middle of the stroke
        val topIdx = pts.indexOfFirst { it.y == minY }
        val relPos = topIdx.toFloat() / pts.size
        if (relPos < 0.10f || relPos > 0.90f) return false

        // Apex must be near horizontal center
        val apexX = pts[topIdx].x
        val midX = (minX + maxX) / 2f
        if (width > 0f && abs(apexX - midX) >= width * 0.45f) return false

        // Both endpoints must be clearly lower than the apex (V / ^ shape)
        val endYAvg = (pts.first().y + pts.last().y) / 2f
        if (endYAvg < minY + height * 0.30f) return false

        return true
    }

    /**
     * Quick heuristic: does this single stroke look like one leg of an arrow (^)?
     * A single straight-ish diagonal that starts OR ends near the top-center
     * of its bounding box could be the first stroke of a 2-stroke arrow.
     * Used by InkOverlay to extend the debounce window so the user has time
     * to draw the second leg.
     */
    fun looksLikeArrowLeg(stroke: Stroke): Boolean {
        val pts = stroke.points
        if (pts.size < 3) return false

        // Must be mostly straight (a leg is a diagonal line, not a curve)
        val start = pts.first(); val end = pts.last()
        val direct = sqrt((start.x - end.x).let { it * it } + (start.y - end.y).let { it * it })
        val path = (1 until pts.size).sumOf {
            val dx = pts[it].x - pts[it-1].x; val dy = pts[it].y - pts[it-1].y
            sqrt(dx * dx + dy * dy).toDouble()
        }.toFloat()
        val lin = if (path > 0f) direct / path else 0f
        if (lin < 0.80f) return false  // too curved to be an arrow leg

        // Must have a significant diagonal component (not purely horizontal)
        val dx = abs(end.x - start.x)
        val dy = abs(end.y - start.y)
        if (dy < dx * 0.25f) return false  // nearly horizontal = not an arrow leg

        // One endpoint should be near the top-center of the bounding box
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val midX = (minX + maxX) / 2f
        val width = maxX - minX
        val firstNearTop = pts.first().y <= minY + (pts.maxOf { it.y } - minY) * 0.25f
        val lastNearTop  = pts.last().y  <= minY + (pts.maxOf { it.y } - minY) * 0.25f
        val firstCentered = width == 0f || abs(pts.first().x - midX) < width * 0.55f
        val lastCentered  = width == 0f || abs(pts.last().x  - midX) < width * 0.55f

        val isLeg = (firstNearTop && firstCentered) || (lastNearTop && lastCentered)
        Log.d("PDollar", "looksLikeArrowLeg: lin=${"%.2f".format(lin)} dy/dx=${"%.2f".format(if(dx>0) dy/dx else 99f)} isLeg=$isLeg")
        return isLeg
    }
}