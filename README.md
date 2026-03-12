# To Do List with Gestures and Handwriting

A gesture and handwriting based to-do list Android app built for CS423. Users draw gestures and write words directly on the touchscreen to manage tasks.

**Group Members:** Ammani Khan, Teresa Chirayil, Sanjana Challagundla

## Features

| Gesture / Input | Action |
|---|---|
| Write `new` | Opens dialog to create a new task |
| Draw checkmark | Marks selected task as complete |
| Draw X | Deletes selected task |
| Draw exclamation mark | Moves selected task up one position |
| Write `tag` | Opens tag dropdown (work, personal, urgent, school, home, other) |
| Double tap a task | Opens task for editing |
| Tap Undo in snackbar | Reverses the last action |

All gesture actions show a snackbar at the bottom with an Undo button.

## Tech Stack

| Tool | Purpose |
|---|---|
| Jetpack Compose | All UI including screens, layouts, task list, dialogs, and snackbar |
| Android Pointer API | Captures raw touch input as timed stroke points in InkOverlay.kt |
| ML Kit Digital Ink Recognition | Recognizes handwritten text (new, tag) |
| Kotlin Coroutines | Debounce timer for multi-stroke gestures and snackbar lifecycle |

## Architecture

The app follows the NUI pipeline: touchscreen input is captured by the Android Pointer API, routed to the recognition engine, interpreted as a command, and confirmed with visual feedback.

InkOverlay.kt records touch events as time-ordered stroke points. A debounce timer waits after each stroke lifts to allow for multi-stroke gestures like X and exclamation mark before sending to the recognizer.

Strokes are routed to one of two recognizers. ShapeRecognizer.kt handles gestures using the $P Point-Cloud algorithm. ML Kit handles text input, detecting new and tag.

TaskListScreen.kt maps recognized input to task actions. After every action a snackbar appears with an Undo button.

## Project Structure

```
app/src/main/java/com/example/todolist/
├── MainActivity.kt        # App entry point
├── TaskListScreen.kt      # Main screen, gesture-to-action mapping, ML Kit integration
├── TaskScreenMock.kt      # Task list UI (active + completed sections)
├── TaskRow.kt             # Individual task row with tag badge
├── CompletedHeader.kt     # Completed section header
├── NewTaskDialog.kt       # New task dialog
├── InkOverlay.kt          # Touch capture, stroke recording, debounce timer
├── ShapeRecognizer.kt     # $P Point-Cloud gesture recognition
└── Task.kt                # Task data model
```

## Team Contributions

**Ammani Khan** built ShapeRecognizer.kt and InkOverlay.kt from scratch. She designed the geometric feature pipeline, implemented the $P Point-Cloud algorithm, tuned gesture thresholds, and built the debounce timer logic.

**Teresa Chirayil** integrated ML Kit Digital Ink Recognition. She handled model downloading, the recognizer lifecycle, and the ink-to-text pipeline for detecting new and tag.

**Sanjana Challagundla** implemented task state management and the gesture-to-action mapping in TaskListScreen.kt.

All three collaborated on the Jetpack Compose UI including layouts, drawing canvas, task list display, tag badges, and the snackbar undo system.

## Setup

1. Clone the repository
2. Open in Android Studio
3. Build and run on an Android device or emulator (API 26+)
4. ML Kit will download the handwriting recognition model on first run and requires an internet connection

