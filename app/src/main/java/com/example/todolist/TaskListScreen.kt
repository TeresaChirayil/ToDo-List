package com.example.todolist

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TaskListScreen() {
    var tasks by remember { mutableStateOf(listOf<Task>()) }
    var showNewTask by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<Task?>(null) }
    var taggingTaskId by remember { mutableStateOf<Long?>(null) }
    var recognizer by remember { mutableStateOf<DigitalInkRecognizer?>(null) }
    var clearInkSignal by remember { mutableStateOf(0) }
    var selectedTaskId by remember { mutableStateOf<Long?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val shapeRecognizer = remember { ShapeRecognizer() }

    var previousTasks by remember { mutableStateOf<List<Task>?>(null) }

    fun showUndoSnackbar(message: String) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                previousTasks?.let { tasks = it }
            }
            previousTasks = null
        }
    }

    fun moveTaskToCompleted(taskId: Long) {
        val updated = tasks.map { task ->
            if (task.id == taskId) task.copy(completed = true) else task
        }
        val (activeTasks, completedTasks) = updated.partition { !it.completed }
        tasks = activeTasks + completedTasks
    }

    LaunchedEffect(Unit) {
        val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
            ?: return@LaunchedEffect

        val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
        RemoteModelManager.getInstance()
            .download(model, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(model).build()
                )
            }
            .addOnFailureListener { e -> Log.e("MLKit", "Error downloading model", e) }
    }

    // --- Dialogs moved OUTSIDE the Scaffold/ink Box so touch events are not consumed by the overlay ---
    if (showNewTask) {
        TaskDialog(
            title = "New Task",
            onConfirm = { title ->
                tasks = tasks + Task(id = System.currentTimeMillis(), title = title)
                showNewTask = false
            },
            onDismiss = { showNewTask = false }
        )
    }

    editingTask?.let { task ->
        TaskDialog(
            title = "Edit Task",
            initialText = task.title,
            onConfirm = { newTitle ->
                tasks = tasks.map { if (it.id == task.id) it.copy(title = newTitle) else it }
                editingTask = null
            },
            onDismiss = { editingTask = null }
        )
    }

    taggingTaskId?.let { id ->
        var expanded by remember { mutableStateOf(true) }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                taggingTaskId = null
            }
        ) {
            listOf("School", "Work", "Personal", "None").forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        tasks = tasks.map { task ->
                            if (task.id == id) {
                                task.copy(tag = if (option == "None") null else option)
                            } else task
                        }
                        expanded = false
                        taggingTaskId = null
                        selectedTaskId = null
                    }
                )
            }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    modifier = Modifier.padding(12.dp),
                    action = {
                        TextButton(onClick = { data.performAction() }) {
                            Text(
                                text = data.visuals.actionLabel ?: "",
                                color = Color(0xFF6650A4),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    containerColor = Color(0xFF323232),
                    contentColor = Color.White
                ) {
                    Text(data.visuals.message)
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(innerPadding)
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
                        selectedTaskId = if (selectedTaskId == id) null else id
                    },
                    onEditTask = { task -> editingTask = task }
                )
            }

            HorizontalDivider(thickness = 2.dp, color = Color(0xFFE0E0E0))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(Color(0xFFF0F0F0))
                    .border(2.dp, Color(0xFFCCCCCC), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                InkOverlay(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFF0F0F0), RoundedCornerShape(4.dp)),
                    clearInkSignal = clearInkSignal,
                    onShapeRecognized = { _, _ ->
                        // We ignore immediate shape recognition to avoid conflicts with writing "tag" or "new"
                    },
                    onInkFinished = { strokes ->
                        // FIX 1: Capture selectedTaskId immediately before the coroutine/delay
                        // so it isn't stale by the time recognition finishes
                        val capturedSelectedId = selectedTaskId

                        scope.launch {
                            delay(600)

                            val r = recognizer ?: return@launch
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
                                    // FIX 2: Strip punctuation so "tag." / "Tag," etc. all match
                                    val best = result.candidates.firstOrNull()?.text
                                        ?.trim()
                                        ?.lowercase()
                                        ?.replace(Regex("[^a-z]"), "")
                                        ?: ""

                                    Log.d("MLKit", "Recognized: '$best', capturedSelectedId=$capturedSelectedId")

                                    if (best == "new" || best.startsWith("new")) {
                                        showNewTask = true
                                        clearInkSignal++
                                    } else if (best == "tag" || best.startsWith("tag")) {
                                        // FIX 3: Use capturedSelectedId instead of selectedTaskId
                                        if (capturedSelectedId != null) {
                                            taggingTaskId = capturedSelectedId
                                            clearInkSignal++
                                        } else {
                                            Log.d("MLKit", "Tag recognized but no task was selected")
                                        }
                                    } else {
                                        val shape = shapeRecognizer.recognizeAll(strokes)
                                        val targetTask = capturedSelectedId?.let { id -> tasks.firstOrNull { it.id == id } }

                                        if (targetTask != null) {
                                            when (shape) {
                                                is RecognizedShape.Checkmark -> {
                                                    previousTasks = tasks
                                                    moveTaskToCompleted(targetTask.id)
                                                    selectedTaskId = null
                                                    clearInkSignal++
                                                    showUndoSnackbar("\"${targetTask.title}\" completed")
                                                }
                                                is RecognizedShape.XMark -> {
                                                    previousTasks = tasks
                                                    tasks = tasks.filter { it.id != targetTask.id }
                                                    selectedTaskId = null
                                                    clearInkSignal++
                                                    showUndoSnackbar("\"${targetTask.title}\" deleted")
                                                }
                                                is RecognizedShape.UpArrow -> {
                                                    val taskIndex = tasks.indexOfFirst { it.id == targetTask.id }
                                                    if (taskIndex > 0) {
                                                        previousTasks = tasks
                                                        val newTasks = tasks.toMutableList()
                                                        val movedTask = newTasks.removeAt(taskIndex)
                                                        newTasks.add(taskIndex - 1, movedTask)
                                                        tasks = newTasks
                                                        showUndoSnackbar("\"${targetTask.title}\" moved up")
                                                    }
                                                    selectedTaskId = null
                                                    clearInkSignal++
                                                }
                                                else -> {}
                                            }
                                        }
                                    }
                                }
                        }
                    }
                )

                Text(
                    text = "✕ clear",
                    fontSize = 11.sp,
                    color = Color(0xFF888888),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                        .clickable { clearInkSignal++ }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}