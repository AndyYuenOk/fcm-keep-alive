package com.example.fcmkeepalive

data class LogEntry(
    val timestamp: Long,
    val level: String,
    val tag: String,
    val event: String,
    val message: String,
    val meta: String?
)

