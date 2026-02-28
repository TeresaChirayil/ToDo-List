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
            selectedTaskId = selectedTaskId,
            onSelectTask = { id ->
                Log.d("TaskSelection", "Task clicked: $id, currently selected: $selectedTaskId")
                selectedTaskId = if (selectedTaskId == id) null else id  // toggle on/off
                Log.d("TaskSelection", "Now selected: $selectedTaskId")
            },
            onToggleComplete = { id, checked ->
                tasks = tasks.map { if (it.id == id) it.copy(completed = checked) else it }
            }
        )

        InkOverlay(
            modifier = Modifier.fillMaxSize(),
            clearInkSignal = clearInkSignal,
            onShapeRecognized = { shape, stroke ->
                Log.d("ShapeRecognition", "Shape recognized: $shape")
                // If a checkmark was drawn, find the closest task and mark it complete
                if (shape is RecognizedShape.Checkmark) {
                    val strokeCenter = stroke.getCenter()
                    Log.d("Checkmark", "Checkmark drawn at position: ${strokeCenter.x}, ${strokeCenter.y}")

                    // Find the closest task by vertical distance (tasks are arranged vertically)
                    val activeTasks = tasks.filter { !it.completed }
                    if (activeTasks.isNotEmpty()) {
                        // Simple heuristic: checkmark in upper half likely belongs to task above middle
                        // For now, just pick the first task (you can improve this with actual task positions)
                        val closestTask = activeTasks.firstOrNull()

                        if (closestTask != null) {
                            Log.d("Checkmark", "Marking task '${closestTask.title}' as complete")
                            tasks = tasks.map { task ->
                                if (task.id == closestTask.id) {
                                    task.copy(completed = true)
                                } else {
                                    task
                                }
                            }
                            clearInkSignal++  // clear ink overlay
                        }
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