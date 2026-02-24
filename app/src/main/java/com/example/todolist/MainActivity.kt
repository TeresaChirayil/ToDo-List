package com.example.todolist

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.todolist.ui.theme.ToDoListTheme
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink

// Point includes a timestamp (required by ML Kit)
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

    // When clearInkSignal changes, wipe ink
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

                // finger/pen lifted -> commit stroke
                if (currentPoints.isNotEmpty()) {
                    val newStrokes = strokes + Stroke(currentPoints)
                    strokes = newStrokes
                    currentPoints = emptyList()

                    onInkFinished(newStrokes)
                }
            }
        }
    ) {
        // Draw committed strokes
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

        // Draw current stroke in progress
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ToDoListTheme {
                TaskListScreen()
            }
        }
    }
}

@Composable
fun NewTaskDialog(
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Task") },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Type your task…") },
                modifier = Modifier.focusRequester(focusRequester),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = text.trim()
                if (trimmed.isNotEmpty()) onAdd(trimmed)
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
fun TaskListScreen() {
    var tasks by remember { mutableStateOf(listOf<String>()) }
    var showNewTask by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<DigitalInkRecognizer?>(null) }

    // Used to clear ink overlay
    var clearInkSignal by remember { mutableStateOf(0) }

    // Initialize ML Kit Recognizer
    LaunchedEffect(Unit) {
        val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
        if (modelIdentifier == null) {
            Log.e("MLKit", "Model identifier is null for en-US")
            return@LaunchedEffect
        }

        val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
        val remoteModelManager = RemoteModelManager.getInstance()

        remoteModelManager.download(model, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(model).build()
                )
                Log.d("MLKit", "Digital ink model downloaded & recognizer ready")
            }
            .addOnFailureListener { e ->
                Log.e("MLKit", "Error downloading model", e)
            }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(tasks) { task ->
                Text(task, modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        InkOverlay(
            modifier = Modifier.fillMaxSize(),
            clearInkSignal = clearInkSignal,
            onInkFinished = { strokes ->
                val r = recognizer ?: return@InkOverlay

                // Build Ink (x,y,t)
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
                        Log.d("MLKit", "Recognized: $best")

                        // Trigger only when it's basically "new"
                        if (best == "new" || best.startsWith("new")) {
                            showNewTask = true
                            clearInkSignal++ // clear ink so it doesn't retrigger
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("MLKit", "Recognition failed", e)
                    }
            }
        )

        if (showNewTask) {
            NewTaskDialog(
                onAdd = { newTask ->
                    tasks = tasks + newTask
                    showNewTask = false
                },
                onDismiss = { showNewTask = false }
            )
        }
    }
}