package com.sixoffive.androidmcp.core

import kotlinx.coroutines.flow.MutableStateFlow

data class AuditEntry(
    val time: Long,
    val tool: String,
    val client: String,
    val allowed: Boolean,
    val detail: String,
)

/** In-memory ring buffer of recent tool calls, surfaced in the UI as a trust feature. */
object AuditLog {
    val entries = MutableStateFlow<List<AuditEntry>>(emptyList())

    fun record(tool: String, client: String, allowed: Boolean, detail: String) {
        val e = AuditEntry(System.currentTimeMillis(), tool, client, allowed, detail)
        entries.value = (listOf(e) + entries.value).take(200)
    }
}
