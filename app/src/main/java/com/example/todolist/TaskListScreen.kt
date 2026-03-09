package com.example.todolist

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var editingTaskId by remember { mutableStateOf<Long?>(null) }

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

    // Dialogs live here, outside all Boxes, so nothing blocks them
    if (showNewTask) {
        NewTaskDialog(
            onAdd = { title ->
                tasks = tasks + Task(id = System.currentTimeMillis(), title = title)
                showNewTask = false
            },
            onDismiss = { showNewTask = false }
        )
    }

    editingTaskId?.let { id ->
        val taskToEdit = tasks.firstOrNull { it.id == id }
        if (taskToEdit != null) {
            NewTaskDialog(
                initialText = taskToEdit.title,
                title = "Edit Task",
                confirmLabel = "Save",
                onAdd = { newTitle ->
                    tasks = tasks.map { if (it.id == id) it.copy(title = newTitle) else it }
                    editingTaskId = null
                },
                onDismiss = { editingTaskId = null }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Text(
            text = "Tasks",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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
                },
                onEditTask = { id ->
                    editingTaskId = id
                }
            )
        }

        HorizontalDivider(thickness = 2.dp, color = Color(0xFFE0E0E0))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(Color(0xFFFAFAFA))
                .border(2.dp, Color(0xFFCCCCCC), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            InkOverlay(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White, RoundedCornerShape(4.dp)),
                clearInkSignal = clearInkSignal,
                onShapeRecognized = { shape, stroke ->
                    Log.d("ShapeRecognition", "Shape: $shape, selectedTask: $selectedTaskId")

                    val strokeLength = stroke.points.size

                    if (shape is RecognizedShape.Unknown && strokeLength > 25) {
                        Log.d("Text", "Unknown shape - likely text")
                        return@InkOverlay
                    }

                    val targetTask = if (selectedTaskId != null) {
                        tasks.firstOrNull { it.id == selectedTaskId }
                    } else {
                        Log.d("Shape", "No task selected - ignoring gesture")
                        return@InkOverlay
                    }

                    if (targetTask == null) return@InkOverlay

                    when (shape) {
                        is RecognizedShape.Checkmark -> {
                            tasks = tasks.map { task ->
                                if (task.id == targetTask.id) task.copy(completed = true) else task
                            }
                            selectedTaskId = null
                            clearInkSignal++
                        }
                        is RecognizedShape.XMark -> {
                            tasks = tasks.filter { it.id != targetTask.id }
                            selectedTaskId = null
                            clearInkSignal++
                        }
                        is RecognizedShape.UpArrow -> {
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
                        else -> {}
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
                            val best = result.candidates.firstOrNull()?.text?.trim()?.lowercase()
                                ?: return@addOnSuccessListener
                            if (best == "new" || best.startsWith("new")) {
                                showNewTask = true
                                clearInkSignal++
                            }
                        }
                }
            )
        }
    }
}