package com.example.todolist

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.launch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog


private val TAG_OPTIONS = listOf("work", "personal", "urgent", "school", "home", "other")

@Composable
fun TagPickerDialog(
    taskTitle: String,
    currentTag: String,
    onTagSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 8.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Tag: \"$taskTitle\"",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                TAG_OPTIONS.forEach { tag ->
                    val isSelected = tag == currentTag
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTagSelected(tag) }
                            .background(
                                if (isSelected) Color(0xFFE8D5F2) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onTagSelected(tag) },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF7C3AED))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(tag, fontSize = 15.sp)
                    }
                }
                if (currentTag.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { onTagSelected("") },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Clear tag", color = Color(0xFF888888))
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
fun TaskListScreen() {
    var tasks by remember { mutableStateOf(listOf<Task>()) }
    var showNewTask by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<Task?>(null) }
    var recognizer by remember { mutableStateOf<DigitalInkRecognizer?>(null) }
    var clearInkSignal by remember { mutableStateOf(0) }
    var selectedTaskId by remember { mutableStateOf<Long?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Saves the task list before each gesture action for undo
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
                        Log.d("TaskSelection", "Selected task: $id")
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
                    onShapeRecognized = { shape, stroke ->
                        Log.d("ShapeRecognition", "Shape: $shape, selectedTask: $selectedTaskId")

                        val strokeLength = stroke.points.size

                        if (shape is RecognizedShape.Unknown && strokeLength > 25) {
                            Log.d("Text", "Unknown shape - likely text")
                            return@InkOverlay
                        }

                        val targetTask = selectedTaskId?.let { id ->
                            tasks.firstOrNull { it.id == id }
                        } ?: run {
                            Log.d("Shape", "No task selected - ignoring gesture")
                            return@InkOverlay
                        }

                        when (shape) {
                            is RecognizedShape.Checkmark -> {
                                Log.d("Checkmark", "Marking '${targetTask.title}' as complete")
                                previousTasks = tasks
                                moveTaskToCompleted(targetTask.id)
                                selectedTaskId = null
                                clearInkSignal++
                                showUndoSnackbar("\"${targetTask.title}\" completed")
                            }

                            is RecognizedShape.XMark -> {
                                Log.d("XMark", "Deleting '${targetTask.title}'")
                                previousTasks = tasks
                                tasks = tasks.filter { it.id != targetTask.id }
                                selectedTaskId = null
                                clearInkSignal++
                                showUndoSnackbar("\"${targetTask.title}\" deleted")
                            }

                            is RecognizedShape.UpArrow -> {
                                Log.d("UpArrow", "Moving '${targetTask.title}' up")
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
                    },
                    onInkFinished = { _ -> },
                    onStrokesSettled = { strokes ->
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
                                Log.d("MLKit", "Settled: $best")
                                when {
                                    best == "new" || best.startsWith("new") -> {
                                        showNewTask = true
                                        clearInkSignal++
                                    }
                                    best == "tag" || best.startsWith("tag") ||
                                            best == "taq" || best == "lag" || best == "lay" ||
                                            best == "lug" || best == "log" || best.startsWith("lag") -> {
                                        if (selectedTaskId != null) showTagPicker = true
                                        clearInkSignal++
                                    }
                                }
                            }
                    }
                )

                // Clear button — top left corner of drawing box
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

                if (showNewTask) {
                    TaskDialog(
                        title = "New Task",
                        onConfirm = { title ->
                            tasks = tasks + Task(
                                id = System.currentTimeMillis(),
                                title = title,
                                completed = false
                            )
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
                            tasks = tasks.map {
                                if (it.id == task.id) it.copy(title = newTitle) else it
                            }
                            editingTask = null
                        },
                        onDismiss = { editingTask = null }
                    )
                }

                if (showTagPicker) {
                    val target = selectedTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
                    if (target != null) {
                        TagPickerDialog(
                            taskTitle = target.title,
                            currentTag = target.tag,
                            onTagSelected = { newTag ->
                                tasks = tasks.map {
                                    if (it.id == target.id) it.copy(tag = newTag) else it
                                }
                                showTagPicker = false
                                selectedTaskId = null
                            },
                            onDismiss = { showTagPicker = false }
                        )
                    } else {
                        showTagPicker = false
                    }
                }
            }
        }
    }
}