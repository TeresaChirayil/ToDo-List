package com.example.todolist

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
        // Top half: Task list
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .align(Alignment.TopCenter)
        ) {
            TasksScreenMock(
                tasks = tasks,
                selectedTaskId = selectedTaskId,
                onSelectTask = { id ->
                    Log.d("TaskSelection", "Selected task: $id")
                    selectedTaskId = if (selectedTaskId == id) null else id
                },
                onToggleComplete = { id, checked ->
                    tasks = tasks.map { if (it.id == id) it.copy(completed = checked) else it }
                }
            )
        }

        // Bottom half: Drawing area
        InkOverlay(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .align(Alignment.BottomCenter),
            clearInkSignal = clearInkSignal,
            onShapeRecognized = { shape, stroke ->
                Log.d("ShapeRecognition", "Shape: $shape, selectedTask: $selectedTaskId")

                val strokeLength = stroke.points.size

                // Skip text
                if (shape is RecognizedShape.Unknown && strokeLength > 25) {
                    Log.d("Text", "Unknown shape - likely text")
                    return@InkOverlay
                }

                // Get selected task - if none selected, ignore gesture
                val targetTask = if (selectedTaskId != null) {
                    tasks.firstOrNull { it.id == selectedTaskId }
                } else {
                    Log.d("Shape", "No task selected - ignoring gesture")
                    return@InkOverlay
                }

                if (targetTask == null) return@InkOverlay

                when (shape) {
                    // ✓ Checkmark - mark selected task complete
                    is RecognizedShape.Checkmark -> {
                        Log.d("Checkmark", "Marking '${targetTask.title}' as complete")
                        tasks = tasks.map { task ->
                            if (task.id == targetTask.id) {
                                task.copy(completed = true)
                            } else {
                                task
                            }
                        }
                        selectedTaskId = null
                        clearInkSignal++
                    }

                    // ✗ X mark - delete selected task
                    is RecognizedShape.ArrowLeft -> {
                        Log.d("XMark", "Deleting '${targetTask.title}'")
                        tasks = tasks.filter { it.id != targetTask.id }
                        selectedTaskId = null
                        clearInkSignal++
                    }

                    // ● Circle - move selected task up one position
                    is RecognizedShape.Circle -> {
                        Log.d("Circle", "Moving '${targetTask.title}' up one position")
                        val taskIndex = tasks.indexOfFirst { it.id == targetTask.id }
                        if (taskIndex > 0) {
                            val newTasks = tasks.toMutableList()
                            newTasks.removeAt(taskIndex)
                            newTasks.add(taskIndex - 1, targetTask)
                            tasks = newTasks
                        }
                        selectedTaskId = null
                        clearInkSignal++
                    }

                    else -> {
                        // Ignore other shapes
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