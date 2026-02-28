package com.example.todolist

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink

@Composable
fun TaskListScreen() {
    var tasks by remember { mutableStateOf(listOf<Task>()) }
    var showNewTask by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<DigitalInkRecognizer?>(null) }
    var clearInkSignal by remember { mutableStateOf(0) }
    var selectedTaskId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
        if (modelIdentifier == null) return@LaunchedEffect

        val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
        val remoteModelManager = RemoteModelManager.getInstance()

        remoteModelManager.download(model, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(model).build()
                )
            }
            .addOnFailureListener { e ->
                Log.e("MLKit", "Error downloading model", e)
            }
    }

    Box(Modifier.fillMaxSize()) {
        TasksScreenMock(
            tasks = tasks,
            selectedTaskId = null,  // We're using proximity, not selection
            onSelectTask = { },     // No-op
            onToggleComplete = { id, checked ->
                tasks = tasks.map { if (it.id == id) it.copy(completed = checked) else it }
            }
        )

        InkOverlay(
            modifier = Modifier.fillMaxSize(),
            clearInkSignal = clearInkSignal,
            onShapeRecognized = { shape, stroke ->
                Log.d("ShapeRecognition", "Shape recognized: $shape, points: ${stroke.points.size}")

                // Skip shape recognition for VERY long strokes that are definitely text (>60 points)
                // But allow medium-length strokes for zigzag (which can be 30-50 points)
                if (stroke.points.size > 60) {
                    Log.d("ShapeRecognition", "Skipping - likely text (${stroke.points.size} points)")
                    return@InkOverlay
                }

                val firstIncompleteTask = tasks.firstOrNull { !it.completed }

                when (shape) {
                    // ✓ Checkmark - mark first task complete
                    is RecognizedShape.Checkmark -> {
                        if (firstIncompleteTask != null) {
                            Log.d("Checkmark", "Marking '${firstIncompleteTask.title}' as complete")
                            tasks = tasks.map { task ->
                                if (task.id == firstIncompleteTask.id) {
                                    task.copy(completed = true)
                                } else {
                                    task
                                }
                            }
                            clearInkSignal++
                        }
                    }

                    // ⚡ Zigzag - delete first task
                    is RecognizedShape.Zigzag -> {
                        if (firstIncompleteTask != null) {
                            Log.d("Zigzag", "Deleting '${firstIncompleteTask.title}'")
                            tasks = tasks.filter { it.id != firstIncompleteTask.id }
                            clearInkSignal++
                        }
                    }

                    // ↑ Arrow Up - prioritize first task (move to top)
                    is RecognizedShape.ArrowUp -> {
                        if (firstIncompleteTask != null) {
                            Log.d("ArrowUp", "Prioritizing '${firstIncompleteTask.title}' to top")
                            val otherTasks = tasks.filter { it.id != firstIncompleteTask.id }
                            tasks = listOf(firstIncompleteTask) + otherTasks
                            clearInkSignal++
                        }
                    }

                    // ↓ Arrow Down - deprioritize first task (move to bottom)
                    is RecognizedShape.ArrowDown -> {
                        if (firstIncompleteTask != null) {
                            Log.d("ArrowDown", "Moving '${firstIncompleteTask.title}' to bottom")
                            val otherTasks = tasks.filter { it.id != firstIncompleteTask.id }
                            tasks = otherTasks + listOf(firstIncompleteTask)
                            clearInkSignal++
                        }
                    }

                    // Other directions (left/right) - no-op for now
                    is RecognizedShape.ArrowLeft, is RecognizedShape.ArrowRight -> {
                        Log.d("Arrow", "Arrow detected but not implemented")
                    }

                    // ● Circle - toggle highlight on first task
                    is RecognizedShape.Circle -> {
                        if (firstIncompleteTask != null) {
                            Log.d("Circle", "Highlighting '${firstIncompleteTask.title}'")
                            tasks = tasks.map { task ->
                                if (task.id == firstIncompleteTask.id) {
                                    // Toggle a highlight flag (we can show this visually later)
                                    task.copy(isHighlighted = !task.isHighlighted)
                                } else {
                                    task
                                }
                            }
                            clearInkSignal++
                        }
                    }

                    else -> {
                        Log.d("Shape", "Unknown shape: $shape")
                    }
                }
            },
            onInkFinished = { strokes ->
                val r = recognizer ?: return@InkOverlay
                val inkBuilder = Ink.builder()
                strokes.forEach { stroke ->
                    val strokeBuilder = Ink.Stroke.builder()
                    stroke.points.forEach { p ->
                        strokeBuilder.addPoint(Ink.Point.create(p.x, p.y, p.t))
                    }
                    inkBuilder.addStroke(strokeBuilder.build())
                }

                r.recognize(inkBuilder.build())
                    .addOnSuccessListener { result ->
                        val best = result.candidates.firstOrNull()?.text?.trim()?.lowercase() ?: return@addOnSuccessListener
                        if (best == "new" || best.startsWith("new")) {
                            showNewTask = true
                            clearInkSignal++
                        }
                    }
            }
        )

        if (showNewTask) {
            NewTaskDialog(
                onAdd = { title ->
                    tasks = tasks + Task(id = System.currentTimeMillis(), title = title)
                    showNewTask = false
                },
                onDismiss = { showNewTask = false }
            )
        }
    }
}