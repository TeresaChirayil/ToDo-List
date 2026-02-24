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

data class TimedPoint(val x: Float, val y: Float, val t: Long)
data class Stroke(val points: List<TimedPoint>)

@Composable
fun InkOverlay(
    modifier: Modifier = Modifier,
    clearInkSignal: Int,
    onInkFinished: (List<Stroke>) -> Unit
) {
    var currentPoints by remember { mutableStateOf<List<TimedPoint>>(emptyList()) }
    var strokes by remember { mutableStateOf<List<Stroke>>(emptyList()) }

    LaunchedEffect(clearInkSignal) {
        currentPoints = emptyList()
        strokes = emptyList()
    }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                currentPoints = listOf(
                    TimedPoint(down.position.x, down.position.y, SystemClock.uptimeMillis())
                )

                drag(down.id) { change ->
                    change.consume()
                    val p = change.position
                    currentPoints = currentPoints + TimedPoint(p.x, p.y, SystemClock.uptimeMillis())
                }

                if (currentPoints.isNotEmpty()) {
                    val newStrokes = strokes + Stroke(currentPoints)
                    strokes = newStrokes
                    currentPoints = emptyList()
                    onInkFinished(newStrokes)
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