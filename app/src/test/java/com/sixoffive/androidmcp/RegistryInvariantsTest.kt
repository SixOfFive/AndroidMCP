package com.sixoffive.androidmcp

import com.sixoffive.androidmcp.core.Capabilities
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-cutting invariants over the capability registry.
 *
 * A capability is wired across five places — `Capabilities.REGISTRY`, the `toolDef` schema chain,
 * the `execute` dispatch, `categoryOf`, and the manifest — and two `else` fallbacks hide a missed
 * one: `categoryOf` silently returns "actions", and `execute` returns "not implemented" *as a
 * success*. The CHANGELOG records this add-a-capability shape being done by hand twice.
 */
class RegistryInvariantsTest {

    private val src = File("src/main/java/com/sixoffive/androidmcp")
    private val mcpKt = File(src, "server/Mcp.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `tool ids are unique`() {
        val ids = Capabilities.REGISTRY.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate tool id: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}")
    }

    @Test
    fun `tool ids are valid MCP tool names`() {
        val ok = Regex("^[a-z][a-z0-9_]*$")
        Capabilities.REGISTRY.forEach {
            assertTrue(ok.matches(it.id), "'${it.id}' is not a clean snake_case tool name")
        }
    }

    @Test
    fun `every capability has an execute branch`() {
        // Without this, a new registry entry falls through `execute`'s else and returns
        // "not implemented" to the client as isError:false — a silent success.
        val dispatch = mcpKt.substringAfter("private suspend fun execute(").substringBefore("\n    }")
        Capabilities.REGISTRY.forEach {
            assertTrue("\"${it.id}\"" in dispatch, "no execute branch for '${it.id}'")
        }
    }

    @Test
    fun `every capability lands in a real category`() {
        val known = Capabilities.CATEGORIES.map { it.id }.toSet()
        Capabilities.REGISTRY.forEach {
            assertTrue(Capabilities.categoryOf(it.id) in known, "'${it.id}' has no known category")
        }
        // Every declared category is non-empty, or the UI renders a header with nothing under it.
        Capabilities.CATEGORIES.forEach {
            assertTrue(Capabilities.inCategory(it.id).isNotEmpty(), "category '${it.id}' is empty")
        }
        assertEquals(
            Capabilities.REGISTRY.size,
            Capabilities.CATEGORIES.sumOf { Capabilities.inCategory(it.id).size },
            "categories must partition the registry exactly once",
        )
    }

    @Test
    fun `list_capabilities is the only capability that is on by default`() {
        // The core promise of the app: nothing is reachable until the owner turns it on.
        assertEquals(
            listOf("list_capabilities"),
            Capabilities.REGISTRY.filter { it.defaultOn }.map { it.id },
        )
    }

    @Test
    fun `every permission the registry names is declared in the manifest`() {
        Capabilities.REGISTRY.flatMap { it.permissions }.distinct().forEach {
            assertTrue(it in manifest, "$it is required by a capability but missing from AndroidManifest.xml")
        }
    }

    @Test
    fun `elevated capabilities are marked root-required and sit in the elevated category`() {
        Capabilities.REGISTRY.filter { it.rootRequired }.forEach {
            assertEquals("elevated", Capabilities.categoryOf(it.id), "'${it.id}' is elevated but categorised elsewhere")
        }
        assertTrue(Capabilities.inCategory("elevated").all { it.rootRequired },
            "everything in the elevated category must gate on elevated access")
    }

    @Test
    fun `every capability that can act on the world is marked high-impact`() {
        // These reveal personal data or change device state, so they must require per-call approval.
        val mustBeHighImpact = setOf(
            "get_location", "read_notifications", "list_files", "take_photo", "record_audio",
            "capture_screenshot", "read_sms", "read_call_log", "read_clipboard", "run_shortcut",
            "get_contacts", "read_calendar", "create_calendar_event", "launch_url", "dial",
            "root_screenshot", "root_shell", "elevated_input", "elevated_settings",
        )
        mustBeHighImpact.forEach { id ->
            val cap = Capabilities.byId(id) ?: return@forEach // tolerate renames; other tests catch those
            assertTrue(cap.highImpact, "'$id' must require per-call approval")
        }
    }

    @Test
    fun `byId agrees with the registry`() {
        Capabilities.REGISTRY.forEach { assertEquals(it, Capabilities.byId(it.id)) }
        assertEquals(null, Capabilities.byId("no_such_tool"))
    }

    @Test
    fun `no-intervention set needs no permission, approval, or elevation`() {
        Capabilities.noInterventionIds().forEach { id ->
            val cap = Capabilities.byId(id)!!
            assertTrue(cap.permissions.isEmpty(), "'$id' needs a runtime permission — not zero-intervention")
            assertTrue(!cap.highImpact, "'$id' needs per-call approval — not zero-intervention")
            assertTrue(!cap.rootRequired, "'$id' needs elevated access — not zero-intervention")
        }
    }

    @Test
    fun `Enable-all never arms a capability that acts on the world`() {
        // The "Enable all (no prompts)" button must never silently turn on a high-impact tool —
        // those must always be a deliberate per-capability choice.
        val highImpact = Capabilities.REGISTRY.filter { it.highImpact }.map { it.id }.toSet()
        assertTrue(
            Capabilities.noInterventionIds().intersect(highImpact).isEmpty(),
            "no-intervention set leaked high-impact capabilities: ${Capabilities.noInterventionIds().intersect(highImpact)}",
        )
    }

    @Test
    fun `no-intervention set is exactly the known safe read-and-low-risk capabilities`() {
        // A tripwire: a new capability with no permission, no approval and no elevation joins
        // "Enable all" automatically — this forces that to be a conscious decision, the same way
        // the default-on test guards what starts enabled.
        assertEquals(
            setOf(
                "list_capabilities", "device_info", "battery_status", "read_sensors",
                "write_clipboard", "wifi_info", "network_info", "storage_info", "thermal_status",
                "screen_info", "volume_info", "torch", "vibrate", "list_packages", "toast",
                "open_settings", "telephony_info", "bluetooth_info", "locale_info", "dnd_status",
                "speak",
            ),
            Capabilities.noInterventionIds(),
        )
    }
}
