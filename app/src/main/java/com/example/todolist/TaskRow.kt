package com.example.todolist
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.compose.ui.text.style.TextDecoration

private val PurpleAccent = Color(0xFF7C3AED)
private val DividerColor = Color(0xFFE9E9EE)

@Composable
fun TaskRow(
    title: String,
    selected: Boolean,
    checked: Boolean,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    when {
                        selected -> Color(0xFFE8D5F2)
                        highlighted -> Color(0xFFFFF9C4)
                        else -> Color.Transparent
                    }
                )
                .clickable(onClick = {
                    Log.d("TaskRowClick", "Clicked on task: $title, selected=$selected")
                    onClick()
                })
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left selection bar
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(if (selected) PurpleAccent else Color.Transparent)
            )

            Spacer(Modifier.width(12.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (checked) Color.Gray else Color.Black,
                textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None
            )
        }

        HorizontalDivider(color = DividerColor, thickness = 1.dp)
    }
}