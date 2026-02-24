package com.example.todolist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider

import androidx.compose.ui.graphics.Color


private val DividerColor = Color(0xFFE9E9EE)
private val HintColor = Color(0xFF9AA0A6)

@Composable
fun TasksScreenMock(
    tasks: List<Task>,
    selectedTaskId: Long? = null,
    onSelectTask: (Long) -> Unit = {},
    onToggleComplete: (Long, Boolean) -> Unit = { _, _ -> }
) {
    val active = tasks.filter { !it.completed }
    val completed = tasks.filter { it.completed }

    var completedExpanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp) // leave room for hint text area
        ) {
            // Title header
            item {
                Text(
                    text = "Tasks",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 12.dp)
                )
                HorizontalDivider(color = DividerColor, thickness = 1.dp)
            }

            // Active tasks
            items(active, key = { it.id }) { task ->
                TaskRow(
                    title = task.title,
                    selected = task.id == selectedTaskId,
                    checked = false,
                    onCheckedChange = { checked -> onToggleComplete(task.id, checked) },
                    onClick = { onSelectTask(task.id) }
                )
            }

            // Completed header
            item {
                Spacer(Modifier.height(6.dp))
                CompletedHeader(
                    count = completed.size,
                    expanded = completedExpanded,
                    onToggle = { completedExpanded = !completedExpanded }
                )
            }

            // Completed items (optional)
            if (completedExpanded) {
                items(completed, key = { it.id }) { task ->
                    TaskRow(
                        title = task.title,
                        selected = false,
                        checked = true,
                        onCheckedChange = { checked -> onToggleComplete(task.id, checked) },
                        onClick = { onSelectTask(task.id) }
                    )
                }
            }
        }

        // Hint text centered lower on screen like the mock
        Text(
            text = "Write 'new' to add a task",
            color = HintColor,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 180.dp)
        )
    }
}