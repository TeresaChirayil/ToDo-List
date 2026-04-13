package com.example.todolist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Color

private val DividerColor = Color(0xFFE9E9EE)

@Composable
fun TasksScreenMock(
    tasks: List<Task>,
    selectedTaskId: Long? = null,
    onSelectTask: (Long) -> Unit = {},
    onEditTask: (Task) -> Unit = {}
) {
    val (activeTasks, completedTasks) = tasks.partition { !it.completed }
    var completedExpanded by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                Text(
                    text = "Tasks",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF444444),
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
                )
                HorizontalDivider(color = DividerColor, thickness = 1.dp)
            }

            items(activeTasks, key = { it.id }) { task ->
                TaskRow(
                    title = task.title,
                    selected = task.id == selectedTaskId,
                    checked = false,
                    tag = task.tag,
                    onClick = { onSelectTask(task.id) },
                    onDoubleClick = { onEditTask(task) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(6.dp))
                CompletedHeader(
                    count = completedTasks.size,
                    expanded = completedExpanded,
                    onToggle = { completedExpanded = !completedExpanded }
                )
            }

            if (completedExpanded) {
                items(completedTasks, key = { it.id }) { task ->
                    TaskRow(
                        title = task.title,
                        selected = task.id == selectedTaskId,
                        checked = true,
                        tag = task.tag,
                        onClick = { onSelectTask(task.id) },
                        onDoubleClick = { onEditTask(task) }
                    )
                }
            }
        }

        Text(
            text = "Write 'new' to add a task",
            color = Color(0xFF444444),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}