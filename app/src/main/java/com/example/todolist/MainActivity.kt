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

// ML Kit Digital Ink imports:
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink

/*
// A single point captured from the screen, including a timestamp.
// ML Kit requires strokes to include timing information.
data class TimedPoint(val x: Float, val y: Float, val t: Long)

// A stroke is a list of timed points (one continuous pen/finger movement).
data class Stroke(val points: List<TimedPoint>)

@Composable
fun InkOverlay(
    modifier: Modifier = Modifier,
    clearInkSignal: Int,                  // changing this value tells the overlay to clear
    onInkFinished: (List<Stroke>) -> Unit // callback when a stroke is completed (finger lifted)
) {
    // Points currently being drawn (stroke in progress)
    var currentPoints by remember { mutableStateOf<List<TimedPoint>>(emptyList()) }

    // All completed strokes currently on screen
    var strokes by remember { mutableStateOf<List<Stroke>>(emptyList()) }

    // Whenever clearInkSignal changes, wipe everything.
    // You increment clearInkSignal from parent to "signal" a clear.
    LaunchedEffect(clearInkSignal) {
        currentPoints = emptyList()
        strokes = emptyList()
    }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            // awaitEachGesture lets you handle a full gesture lifecycle:
            // down -> drag -> up
            awaitEachGesture {

                // Wait for initial touch/pen down
                val down = awaitFirstDown()

                // Start a new current stroke with first point + timestamp
                currentPoints = listOf(
                    TimedPoint(down.position.x, down.position.y, SystemClock.uptimeMillis())
                )

                // Track drag until finger/pen lifts
                drag(down.id) { change ->
                    change.consume() // marks the event as handled so others don't intercept

                    val p = change.position
                    currentPoints = currentPoints + TimedPoint(
                        p.x, p.y, SystemClock.uptimeMillis()
                    )
                }

                // When drag ends (lift), commit the stroke:
                if (currentPoints.isNotEmpty()) {
                    val newStrokes = strokes + Stroke(currentPoints) // add to committed strokes
                    strokes = newStrokes
                    currentPoints = emptyList() // reset for next stroke

                    // Tell parent "here are the strokes so far"
                    onInkFinished(newStrokes)
                }
            }
        }
    ) {
        // DRAWING SECTION:

        // Draw committed strokes (all finished strokes)
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

        // Draw the current stroke-in-progress (while user is still dragging)
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
*/

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Makes content draw behind system bars, etc.
        enableEdgeToEdge()

        setContent {
            ToDoListTheme {
                TaskListScreen()
            }
        }
    }
}

/*
@Composable
fun NewTaskDialog(
    onAdd: (String) -> Unit, // callback when user confirms adding a task
    onDismiss: () -> Unit    // callback when dialog is closed/cancelled
) {
    var text by remember { mutableStateOf("") }

    // Used to auto-focus the text field when dialog appears
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

    // Run once when dialog is shown: focus the TextField.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
fun TaskListScreen() {
    // The list of tasks shown in the LazyColumn
    var tasks by remember { mutableStateOf(listOf<String>()) }

    // Controls whether the new task dialog is visible
    var showNewTask by remember { mutableStateOf(false) }

    // ML Kit recognizer (null until model downloads and client is ready)
    var recognizer by remember { mutableStateOf<DigitalInkRecognizer?>(null) }

    // Changing this integer clears the ink overlay (signal)
    var clearInkSignal by remember { mutableStateOf(0) }

    // Initialize ML Kit recognizer once when screen appears.
    LaunchedEffect(Unit) {
        // Pick language model (en-US)
        val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
        if (modelIdentifier == null) {
            Log.e("MLKit", "Model identifier is null for en-US")
            return@LaunchedEffect
        }

        // Build a model reference
        val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()

        // Manager used to download the model if needed
        val remoteModelManager = RemoteModelManager.getInstance()

        // Download model to device
        remoteModelManager.download(model, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                // Once downloaded, create recognizer client
                recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(model).build()
                )
                Log.d("MLKit", "Digital ink model downloaded & recognizer ready")
            }
            .addOnFailureListener { e ->
                Log.e("MLKit", "Error downloading model", e)
            }
    }

    // Box lets you "stack" UI: list in back, ink overlay on top, dialog above.
    Box(Modifier.fillMaxSize()) {

        // Task list UI
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(tasks) { task ->
                Text(task, modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        // Ink overlay on top of the list
        InkOverlay(
            modifier = Modifier.fillMaxSize(),
            clearInkSignal = clearInkSignal,
            onInkFinished = { strokes ->

                // If recognizer isn't ready, do nothing
                val r = recognizer ?: return@InkOverlay

                // Convert your strokes -> ML Kit Ink object
                val inkBuilder = Ink.builder()
                strokes.forEach { stroke ->
                    val strokeBuilder = Ink.Stroke.builder()
                    stroke.points.forEach { p ->
                        // ML Kit wants Ink.Point.create(x, y, t)
                        strokeBuilder.addPoint(Ink.Point.create(p.x, p.y, p.t))
                    }
                    inkBuilder.addStroke(strokeBuilder.build())
                }

                // Run recognition
                r.recognize(inkBuilder.build())
                    .addOnSuccessListener { result ->
                        // Take best candidate text, lowercase it
                        val best = result.candidates
                            .firstOrNull()
                            ?.text
                            ?.trim()
                            ?.lowercase()
                            ?: return@addOnSuccessListener

                        Log.d("MLKit", "Recognized: $best")

                        // If it recognized "new" (or starts with new), open dialog
                        if (best == "new" || best.startsWith("new")) {
                            showNewTask = true
                            clearInkSignal++ // clear ink so it doesn't retrigger instantly
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("MLKit", "Recognition failed", e)
                    }
            }
        )

        // Dialog appears above everything when showNewTask is true
        if (showNewTask) {
            NewTaskDialog(
                onAdd = { newTask ->
                    tasks = tasks + newTask   // add to list
                    showNewTask = false       // close dialog
                },
                onDismiss = { showNewTask = false }
            )
        }
    }
}
*/
