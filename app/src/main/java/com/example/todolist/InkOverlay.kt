package com.example.todolist

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

data class TimedPoint(val x: Float, val y: Float, val t: Long)
data class Stroke(val points: List<TimedPoint>) {
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
    onShapeRecognized: (RecognizedShape, Stroke) -> Unit = { _, _ -> },
    onStrokesSettled: (List<Stroke>) -> Unit = { _ -> }
) {
    var currentPoints by remember { mutableStateOf<List<TimedPoint>>(emptyList()) }
    var strokes by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    val shapeRecognizer = remember { ShapeRecognizer() }
    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(clearInkSignal) {
        debounceJob?.cancel()
        currentPoints = emptyList()
        strokes = emptyList()
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        currentPoints = listOf(
                            TimedPoint(offset.x, offset.y, SystemClock.uptimeMillis())
                        )
                    },
                    onDrag = { change, _ ->
                        val p = change.position
                        currentPoints = currentPoints + TimedPoint(p.x, p.y, SystemClock.uptimeMillis())
                        change.consume()
                    },
                    onDragEnd = {
                        if (currentPoints.isNotEmpty() && currentPoints.size > 2) {
                            val newStroke = Stroke(currentPoints)
                            val newStrokes = strokes + newStroke
                            strokes = newStrokes
                            onInkFinished(newStrokes)

                            // Checkmark is 1 stroke — fire fast if it looks like one
                            // Single non-checkmark stroke might get a second (X or !) — wait longer
                            // 2+ strokes — gesture is likely complete
                            val looksLikeCheckmark = newStrokes.size == 1 &&
                                    shapeRecognizer.looksLikeCheckmarkFast(newStrokes.last())
                            val debounceMs = when {
                                looksLikeCheckmark -> 500L
                                newStrokes.size == 1 -> 2500L
                                else -> 1200L
                            }

                            debounceJob?.cancel()
                            debounceJob = scope.launch {
                                delay(debounceMs)
                                val shape = shapeRecognizer.recognizeAll(newStrokes)
                                onShapeRecognized(shape, newStroke)
                                // Always send to ML Kit — TaskListScreen ignores text if a gesture already fired
                                onStrokesSettled(newStrokes)
                            }
                        }
                        currentPoints = emptyList()
                    }
                )
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