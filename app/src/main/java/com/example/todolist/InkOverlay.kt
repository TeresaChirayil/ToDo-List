package com.example.todolist

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.sqrt

data class TimedPoint(val x: Float, val y: Float, val t: Long)
data class Stroke(val points: List<TimedPoint>) {
    // Calculate the center/average position of the stroke
    fun getCenter(): Offset {
        if (points.isEmpty()) return Offset(0f, 0f)
        val avgX = points.map { it.x }.average().toFloat()
        val avgY = points.map { it.y }.average().toFloat()
        return Offset(avgX, avgY)
    }
}

@Composable
fun InkOverlay(
    modifier: Modifier = Modifier,
    clearInkSignal: Int,
    onInkFinished: (List<Stroke>) -> Unit,
    onShapeRecognized: (RecognizedShape, Stroke) -> Unit = { _, _ -> }
) {
    var currentPoints by remember { mutableStateOf<List<TimedPoint>>(emptyList()) }
    var strokes by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    val shapeRecognizer = remember { ShapeRecognizer() }

    LaunchedEffect(clearInkSignal) {
        currentPoints = emptyList()
        strokes = emptyList()
    }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Final)
                currentPoints = listOf(
                    TimedPoint(down.position.x, down.position.y, SystemClock.uptimeMillis())
                )

                var hasMoved = false
                drag(down.id) { change ->
                    val p = change.position
                    currentPoints = currentPoints + TimedPoint(p.x, p.y, SystemClock.uptimeMillis())
                    hasMoved = true
                    change.consume()  // Only consume if actually dragging
                }

                // Only process as stroke if user actually moved/drew
                if (currentPoints.isNotEmpty() && hasMoved && currentPoints.size > 2) {
                    val newStroke = Stroke(currentPoints)
                    val newStrokes = strokes + newStroke
                    strokes = newStrokes
                    currentPoints = emptyList()

                    // Try to recognize the shape
                    val shape = shapeRecognizer.recognize(newStroke)
                    onShapeRecognized(shape, newStroke)

                    onInkFinished(newStrokes)
                } else {
                    // If no movement, reset without consuming (let tap pass through)
                    currentPoints = emptyList()
                }
            }
        }
    ) {
        strokes.forEach { stroke ->
            for (i in 0 until stroke.points.lastIndex) {
                drawLine(
                    color = Color.Black,
                    start = Offset(stroke.points[i].x, stroke.points[i].y),
                    end = Offset(stroke.points[i + 1].x, stroke.points[i + 1].y),
                    strokeWidth = 8f,
                    cap = StrokeCap.Round
                )
            }
        }

        for (i in 0 until currentPoints.lastIndex) {
            drawLine(
                color = Color.Black,
                start = Offset(currentPoints[i].x, currentPoints[i].y),
                end = Offset(currentPoints[i + 1].x, currentPoints[i + 1].y),
                strokeWidth = 8f,
                cap = StrokeCap.Round
            )
        }
    }
}