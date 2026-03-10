package com.example.todolist

data class Task(
    val id: Long,
    val title: String,
    val completed: Boolean = false,
    val isHighlighted: Boolean = false,
    val tag: String? = null
)