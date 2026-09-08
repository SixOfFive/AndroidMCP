package com.sixoffive.androidmcp.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

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
        // `update` (not `value =`) — Ktor serves requests concurrently, and a plain
        // read-modify-write drops entries from the log the UI presents as the trust record.
        entries.update { (listOf(e) + it).take(200) }
    }
}
