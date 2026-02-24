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
            onToggleComplete = { id, checked ->
                tasks = tasks.map { if (it.id == id) it.copy(completed = checked) else it }
            }
        )

        InkOverlay(
            modifier = Modifier.fillMaxSize(),
            clearInkSignal = clearInkSignal,
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