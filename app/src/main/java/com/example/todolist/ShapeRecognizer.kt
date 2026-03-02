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

    fun recognize(stroke: Stroke): RecognizedShape {
        if (stroke.points.size < 5) return RecognizedShape.Unknown

        try {
            val points = stroke.points.map { Point(it.x, it.y) }

            // Calculate geometric properties
            val linearity = calculateLinearity(points)
            val directionChanges = countDirectionChanges(points)
            val closedRatio = calculateClosedLoopRatio(points)
            val aspectRatio = calculateAspectRatio(points)

            Log.d("Geometry", "Linearity: $linearity, DirChanges: $directionChanges, Closed: $closedRatio, Aspect: $aspectRatio")

            return when {
                // ↑ Up Arrow: very tall & linear (vertical line)
                linearity > 0.75 && aspectRatio > 3.0 -> {
                    RecognizedShape.UpArrow(0.8f)
                }

                // ✗ X Mark: cross pattern with direction changes, roughly square
                directionChanges >= 3 && aspectRatio in 0.6..1.8 && linearity < 0.75 -> {
                    RecognizedShape.XMark(0.8f)
                }

                // ✓ Checkmark: diagonal with some curve, NOT perfectly straight
                linearity in 0.55..0.85 && aspectRatio > 1.2 && directionChanges <= 3 -> {
                    RecognizedShape.Checkmark(0.8f)
                }

                else -> RecognizedShape.Unknown
            }

        } catch (e: Exception) {
            Log.e("Geometry", "Recognition error: ${e.message}")
            return RecognizedShape.Unknown
        }
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

            // Significant direction change (>45 degrees)
            if (angleDiff > 0.7f && angleDiff < 3.14f - 0.7f) {
                changes++
            }
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
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }

        val width = maxX - minX
        val height = maxY - minY

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