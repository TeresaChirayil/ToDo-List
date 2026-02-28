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

private val PurpleAccent = Color(0xFF7C3AED)   // close to screenshot
private val DividerColor = Color(0xFFE9E9EE)
private val HintColor = Color(0xFF9AA0A6)

@Composable
fun TaskRow(
    title: String,
    selected: Boolean,
    checked: Boolean,
    highlighted: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    when {
                        selected -> Color(0xFFE8D5F2)  // Light purple for selection
                        highlighted -> Color(0xFFFFF9C4)  // Light yellow for highlight
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
            // Left selection bar - MUCH THICKER
            Box(
                modifier = Modifier
                    .width(6.dp)  // was 4dp, now 6dp - thicker!
                    .fillMaxHeight()
                    .background(if (selected) PurpleAccent else Color.Transparent)
            )

            Spacer(Modifier.width(12.dp))

            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = PurpleAccent,
                    uncheckedColor = if (selected) PurpleAccent else Color(0xFF9CA3AF),
                    checkmarkColor = Color.White
                )
            )

            Spacer(Modifier.width(12.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        HorizontalDivider(color = DividerColor, thickness = 1.dp)
    }
}