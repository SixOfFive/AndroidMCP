package com.sixoffive.androidmcp.core

/** Stable, machine-readable reasons a tool call can be refused. */
enum class ReasonCode {
    FEATURE_DISABLED_IN_APP,
    OS_PERMISSION_NOT_GRANTED,
    OS_PERMISSION_PERMANENTLY_DENIED,
    SPECIAL_ACCESS_NOT_ENABLED,
    RESTRICTED_SETTINGS_BLOCK,
    REQUIRES_FOREGROUND,
    REQUIRES_PER_SESSION_CONSENT,
    REQUIRES_USER_APPROVAL,
    HARDWARE_UNAVAILABLE,
    NOT_SUPPORTED_WITHOUT_ROOT,
    NOT_IMPLEMENTED,
}

/** Rough phase this capability belongs to. */
enum class Phase { MVP, V1_1, DEFERRED }

/**
 * One capability = one MCP tool. Everything the gate engine, the UI consent card,
 * and the machine-readable refusal need lives here — a single source of truth.
 */
data class CapabilityMeta(
    val id: String,                 // MCP tool name, e.g. "device_info"
    val title: String,              // human label, e.g. "Device info"
    val why: List<String>,          // every reason this capability exists / is requested
    val permissions: List<String>,  // android.permission.* required (may be empty)
    val dataExposed: String,        // plain-language description of what it can reveal
    val risk: String,               // plain-language risk if enabled
    val defaultOn: Boolean,         // MVP: only list_capabilities is on by default
    val phase: Phase,
    val highImpact: Boolean,        // requires per-call approval once wired
    val requiresForegroundNote: Boolean = false,
) {
    val toggleLabel: String get() = "Capabilities → $title"
}

/** Result of evaluating the gate for one capability at call time. */
sealed interface GateResult {
    data object Allowed : GateResult
    data class Denied(
        val reason: ReasonCode,
        val cap: CapabilityMeta,
        val toggleEnabled: Boolean,
        val permissionGranted: Boolean,
        val remediation: String,
        val retriable: Boolean,
    ) : GateResult
}
