package com.sixoffive.androidmcp

import com.sixoffive.androidmcp.core.Capabilities
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The refusal envelope is the app's contract with the model: when a call is blocked, the reply
 * carries `structuredContent` naming the exact gate that failed and whether retrying can help.
 * Its fields must agree with each other — a model acts on them.
 */
class RefusalEnvelopeTest {

    private val mcpKt = File("src/main/java/com/sixoffive/androidmcp/server/Mcp.kt").readText()
    private val gateKt = File("src/main/java/com/sixoffive/androidmcp/core/GateEngine.kt").readText()

    @Test
    fun `gate_failed is derived from the reason code, not guessed from the booleans`() {
        // Measured on the K70 (a Wi-Fi-only tablet) before the fix: read_sms refused with
        // reason_code HARDWARE_UNAVAILABLE and retriable:false, but gate_failed "app_toggle" —
        // telling a model to flip a switch that cannot possibly help, and contradicting retriable.
        val fn = mcpKt.substringAfter("private fun gateFailed(").substringBefore("\n    }")
        assertTrue("d.reason" in fn, "gateFailed must branch on the reason code")
        listOf("HARDWARE_UNAVAILABLE", "NOT_SUPPORTED_WITHOUT_ROOT").forEach {
            assertTrue(it in fn, "gateFailed must map $it to its own token")
        }
    }

    @Test
    fun `non-retriable refusals are exactly the ones a user cannot act on`() {
        // HARDWARE_UNAVAILABLE and NOT_SUPPORTED_WITHOUT_ROOT are the two the gate marks
        // retriable=false. Everything else names something the owner can change.
        // Window rather than substringBefore(")"): the Denied(...) call contains nested parens.
        val hw = gateKt.substringAfter("ReasonCode.HARDWARE_UNAVAILABLE").take(300)
        assertTrue("retriable = false" in hw, "a missing-hardware refusal must not invite a retry")
        val root = gateKt.substringAfter("ReasonCode.NOT_SUPPORTED_WITHOUT_ROOT").take(600)
        assertTrue("false," in root, "a no-elevated-access refusal must not invite a retry")
    }

    @Test
    fun `every capability can describe why it exists and what it exposes`() {
        // These two fields are re-sent in every refusal, so they carry the whole explanation once
        // the volatile status suffix was removed from tool descriptions.
        Capabilities.REGISTRY.forEach {
            assertTrue(it.why.isNotEmpty(), "'${it.id}' has no why[] to put in a refusal")
            assertTrue(it.dataExposed.isNotBlank(), "'${it.id}' does not say what it exposes")
            assertTrue(it.risk.isNotBlank(), "'${it.id}' does not state its risk")
        }
    }

    @Test
    fun `the refusal envelope carries every field the README documents`() {
        val envelope = mcpKt.substringAfter("private fun refusalResult(").substringBefore("\n    }")
        listOf(
            "status", "capability", "reason_code", "gate_failed", "app_toggle",
            "app_toggle_enabled", "os_permission", "os_permission_granted",
            "why_required", "data_exposed", "remediation", "retriable",
        ).forEach { assertEquals(true, "\"$it\"" in envelope, "refusal envelope is missing '$it'") }
    }
}
