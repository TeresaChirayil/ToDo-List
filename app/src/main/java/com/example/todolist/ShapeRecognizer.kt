package com.example.todolist

import android.util.Log
import kotlin.math.sqrt
import kotlin.math.atan2
import kotlin.math.abs

data class Point(val x: Float, val y: Float)

sealed class RecognizedShape {
    data class Checkmark(val confidence: Float) : RecognizedShape()
    data class XMark(val confidence: Float) : RecognizedShape()
    data class UpArrow(val confidence: Float) : RecognizedShape()
    object Unknown : RecognizedShape()
}

class ShapeRecognizer {

    fun recognizeAll(strokes: List<Stroke>): RecognizedShape {
        if (strokes.isEmpty()) return RecognizedShape.Unknown

        if (strokes.size >= 2) {
            val last2 = strokes.takeLast(2)
            val s1 = last2[0]
            val s2 = last2[1]

            if (isTwoStrokeX(s1, s2)) {
                Log.d("ShapeRecognition", "2-stroke X detected")
                return RecognizedShape.XMark(0.9f)
            }

            if (isTwoStrokeExclamation(s1, s2)) {
                Log.d("ShapeRecognition", "Exclamation mark detected")
                return RecognizedShape.UpArrow(0.9f)
            }
        }

        return recognize(strokes.last())
    }

    fun recognize(stroke: Stroke): RecognizedShape {
        if (stroke.points.size < 5) return RecognizedShape.Unknown

        try {
            val points = stroke.points.map { Point(it.x, it.y) }

            val linearity = calculateLinearity(points)
            val directionChanges = countDirectionChanges(points)
            val closedRatio = calculateClosedLoopRatio(points)
            val aspectRatio = calculateAspectRatio(points)

            Log.d("Geometry", "Linearity: $linearity, DirChanges: $directionChanges, Closed: $closedRatio, Aspect: $aspectRatio")

            val checkScore = scoreCheckmark(linearity, directionChanges, aspectRatio, closedRatio)

            Log.d("ShapeScores", "Check: $checkScore")

            return if (checkScore >= 0.55f) RecognizedShape.Checkmark(checkScore)
            else RecognizedShape.Unknown

        } catch (e: Exception) {
            Log.e("Geometry", "Recognition error: ${e.message}")
            return RecognizedShape.Unknown
        }
    }

    // ✗ X: two crossing diagonal strokes (\ and /)
    private fun isTwoStrokeX(s1: Stroke, s2: Stroke): Boolean {
        val p1 = s1.points.map { Point(it.x, it.y) }
        val p2 = s2.points.map { Point(it.x, it.y) }
        if (p1.size < 3 || p2.size < 3) return false

        val lin1 = calculateLinearity(p1)
        val lin2 = calculateLinearity(p2)
        Log.d("XCheck", "lin1=$lin1, lin2=$lin2")
        if (lin1 < 0.7f || lin2 < 0.7f) return false

        val minX1 = p1.minOf { it.x }; val maxX1 = p1.maxOf { it.x }
        val minY1 = p1.minOf { it.y }; val maxY1 = p1.maxOf { it.y }
        val minX2 = p2.minOf { it.x }; val maxX2 = p2.maxOf { it.x }
        val minY2 = p2.minOf { it.y }; val maxY2 = p2.maxOf { it.y }

        val overlapX = minOf(maxX1, maxX2) - maxOf(minX1, minX2)
        val overlapY = minOf(maxY1, maxY2) - maxOf(minY1, minY2)
        val minDim = minOf(maxX1 - minX1, maxY1 - minY1, maxX2 - minX2, maxY2 - minY2)

        Log.d("XCheck", "overlapX=$overlapX, overlapY=$overlapY, minDim=$minDim")
        if (overlapX < minDim * 0.3f || overlapY < minDim * 0.3f) return false

        val dir1 = diagonalDirection(p1)
        val dir2 = diagonalDirection(p2)
        Log.d("XCheck", "dir1=$dir1, dir2=$dir2")

        return dir1 != 0 && dir2 != 0 && dir1 != dir2
    }

    // Returns +1 for \ diagonal, -1 for / diagonal, 0 if not diagonal enough
    private fun diagonalDirection(points: List<Point>): Int {
        val start = points.first()
        val end = points.last()
        val dx = end.x - start.x
        val dy = end.y - start.y
        val pathLen = calculatePathLength(points)
        if (abs(dx) < pathLen * 0.2f || abs(dy) < pathLen * 0.2f) return 0
        return if ((dx > 0 && dy > 0) || (dx < 0 && dy < 0)) 1 else -1
    }

    // ! Exclamation: tall vertical line stroke + small dot/tap below it (two strokes)
    private fun isTwoStrokeExclamation(s1: Stroke, s2: Stroke): Boolean {
        val p1 = s1.points.map { Point(it.x, it.y) }
        val p2 = s2.points.map { Point(it.x, it.y) }
        if (p1.size < 2 || p2.size < 2) return false

        val isLine1 = isVerticalLine(p1)
        val isLine2 = isVerticalLine(p2)

        Log.d("ExclCheck", "isLine1=$isLine1, isLine2=$isLine2")

        // Both must be vertical-ish, but one must be significantly shorter (the dot)
        if (!isLine1 || !isLine2) return false

        val height1 = p1.maxOf { it.y } - p1.minOf { it.y }
        val height2 = p2.maxOf { it.y } - p2.minOf { it.y }

        // The shorter one is the dot — it must be at least 3x shorter than the line
        val (linePoints, dotPoints) = if (height1 > height2) Pair(p1, p2) else Pair(p2, p1)
        val lineHeight = linePoints.maxOf { it.y } - linePoints.minOf { it.y }
        val dotHeight  = dotPoints.maxOf  { it.y } - dotPoints.minOf  { it.y }

        val sizeRatioOk = lineHeight > dotHeight * 3f
        Log.d("ExclCheck", "lineHeight=$lineHeight, dotHeight=$dotHeight, sizeRatioOk=$sizeRatioOk")
        if (!sizeRatioOk) return false

        // Dot must be below the line
        val lineMaxY = linePoints.maxOf { it.y }
        val dotMidY  = dotPoints.map { it.y }.average().toFloat()
        val dotBelowLine = dotMidY > lineMaxY - lineHeight * 0.2f

        // Dot must be horizontally near the line
        val lineMidX = (linePoints.minOf { it.x } + linePoints.maxOf { it.x }) / 2f
        val dotMidX  = dotPoints.map { it.x }.average().toFloat()
        val dotAligned = abs(dotMidX - lineMidX) < lineHeight * 0.6f

        Log.d("ExclCheck", "dotBelowLine=$dotBelowLine, dotAligned=$dotAligned")

        return dotBelowLine && dotAligned
    }

    // A tall vertical stroke: high aspect ratio (taller than wide), linear
    private fun isVerticalLine(points: List<Point>): Boolean {
        val lin = calculateLinearity(points)
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val width = maxX - minX
        val height = maxY - minY
        val aspectRatio = if (width > 0) height / width else 999f
        Log.d("ExclCheck", "vertical check: lin=$lin, aspectRatio(h/w)=$aspectRatio, height=$height")
        return lin > 0.75f && aspectRatio > 2.5f && height > 40f
    }

    // A dot/tap: narrow and short path — could be a small tick or press
    // We intentionally don't restrict height since users may draw a small vertical dash as their dot
    private fun isDotStroke(points: List<Point>): Boolean {
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val width = maxX - minX
        val height = maxY - minY
        val pathLen = calculatePathLength(points)
        // Must be significantly smaller than a full vertical line
        // Use pathLen < 100 and width < 50 to catch small ticks/dashes
        Log.d("ExclCheck", "dot check: width=$width, height=$height, pathLen=$pathLen")
        return width < 50f && pathLen < 100f
    }

    // ✓ Checkmark: genuine V-bend, NOT a straight line
    private fun scoreCheckmark(linearity: Float, dirChanges: Int, aspectRatio: Float, closedRatio: Float): Float {
        var score = 0f
        if (dirChanges < 1) return 0f
        if (linearity > 0.88f) return 0f  // straight line → never a checkmark
        if (dirChanges in 1..3) score += 0.25f
        if (linearity in 0.35f..0.80f) score += 0.35f
        if (aspectRatio < 2.0f) score += 0.2f
        if (closedRatio < 0.4f) score += 0.2f
        return score
    }

    private fun calculateLinearity(points: List<Point>): Float {
        if (points.size < 2) return 0f
        val start = points.first()
        val end = points.last()
        val directDistance = sqrt((end.x - start.x).pow(2) + (end.y - start.y).pow(2))
        val pathLength = calculatePathLength(points)
        return if (pathLength > 0) directDistance / pathLength else 0f
    }

    private fun countDirectionChanges(points: List<Point>): Int {
        if (points.size < 3) return 0
        var changes = 0
        for (i in 1 until points.size - 1) {
            val angle1 = atan2(points[i].y - points[i-1].y, points[i].x - points[i-1].x)
            val angle2 = atan2(points[i+1].y - points[i].y, points[i+1].x - points[i].x)
            val angleDiff = abs(angle2 - angle1)
            if (angleDiff > 0.7f && angleDiff < 3.14f - 0.7f) changes++
        }
        return changes
    }

    private fun calculateClosedLoopRatio(points: List<Point>): Float {
        if (points.size < 10) return 0f
        val start = points.first()
        val end = points.last()
        val distance = sqrt((end.x - start.x).pow(2) + (end.y - start.y).pow(2))
        val pathLength = calculatePathLength(points)
        return (pathLength - distance) / pathLength
    }

    private fun calculateAspectRatio(points: List<Point>): Float {
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val width = maxX - minX; val height = maxY - minY
        return if (width > 0 && height > 0) height / width else 1f
    }

    private fun calculatePathLength(points: List<Point>): Float {
        var length = 0f
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i-1].x
            val dy = points[i].y - points[i-1].y
            length += sqrt(dx * dx + dy * dy)
        }
        return length
    }

    private fun Float.pow(exp: Int): Float {
        var result = 1f
        repeat(exp) { result *= this }
        return result
    }
}