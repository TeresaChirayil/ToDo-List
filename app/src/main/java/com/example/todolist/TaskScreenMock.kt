package com.example.todolist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider

private val DividerColor = Color(0xFFE9E9EE)
private val HintColor = Color(0xFF9AA0A6)

@Composable
fun TasksScreenMock(
    tasks: List<Task>,
    selectedTaskId: Long? = null,
    onSelectTask: (Long) -> Unit = {},
    onEditTask: (Task) -> Unit = {}
) {
    val (activeTasks, completedTasks) = tasks.partition { !it.completed }
    var completedExpanded by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                Text(
                    text = "Tasks",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 12.dp)
                )
                HorizontalDivider(color = DividerColor, thickness = 1.dp)
            }

            // Active tasks
            items(activeTasks, key = { it.id }) { task ->
                TaskRow(
                    task = task,
                    selected = task.id == selectedTaskId,
                    onClick = { onSelectTask(task.id) },
                    onDoubleClick = { onEditTask(task) }
                )
            }

            // Completed section - always visible
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
                        task = task,
                        selected = task.id == selectedTaskId,
                        onClick = { onSelectTask(task.id) },
                        onDoubleClick = { onEditTask(task) }
                    )
                }
            }
        }

        // Persistent Visual Scrollbar
        val showScrollbar = tasks.size > 5
        if (showScrollbar) {
            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(end = 4.dp, top = 60.dp, bottom = 100.dp)
                    .width(4.dp)
            ) {
                val viewHeight = maxHeight
                val totalItemsCount = (activeTasks.size + completedTasks.size + 3).coerceAtLeast(1)
                
                val thumbHeight = remember(totalItemsCount, viewHeight) {
                    (viewHeight / totalItemsCount.toFloat() * 2f).coerceIn(40.dp, viewHeight)
                }
                
                val scrollOffset by remember(listState.firstVisibleItemIndex, totalItemsCount, viewHeight, thumbHeight) {
                    derivedStateOf {
                        val scrollPercent = listState.firstVisibleItemIndex.toFloat() / (totalItemsCount.toFloat()).coerceAtLeast(1f)
                        (viewHeight - thumbHeight) * scrollPercent
                    }
                }

                Box(
                    modifier = Modifier
                        .offset(y = scrollOffset)
                        .size(width = 4.dp, height = thumbHeight)
                        .background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                )
            }
        }

        Text(
            text = "Write 'new' to add a task",
            color = HintColor,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}
