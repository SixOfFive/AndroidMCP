package com.sixoffive.androidmcp

import com.sixoffive.androidmcp.core.Capabilities
import com.sixoffive.androidmcp.server.ToolSchemas
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Quality gates on the model-facing tool contract.
 *
 * Before this table existed, `tools/list` emitted 47 bare `{"type":"string"}` properties across
 * 25 tools with no `required`, no descriptions and no bounds. A model could only learn the
 * semantics by making failed calls — and for a high-impact tool every wrong guess costs the
 * device owner a real Allow/Deny prompt.
 */
class ToolSchemaTest {

    private val mcpKt = File("src/main/java/com/sixoffive/androidmcp/server/Mcp.kt").readText()

    /** Source with comment lines stripped — so these checks test the code, not the prose about it. */
    private val mcpCode = mcpKt.lineSequence()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    @Test
    fun `every capability has a usage line`() {
        Capabilities.REGISTRY.forEach {
            val spec = ToolSchemas.specFor(it.id)
            assertTrue(spec.usage.isNotBlank(), "'${it.id}' has no usage description")
            assertTrue(spec.usage.length > 20, "'${it.id}' usage is too thin to be useful: ${spec.usage}")
        }
    }

    @Test
    fun `the schema table has no entry for a tool that does not exist`() {
        val ids = Capabilities.REGISTRY.map { it.id }.toSet()
        ToolSchemas.SPECS.keys.forEach {
            assertTrue(it in ids, "ToolSchemas describes '$it', which is not in the registry")
        }
    }

    @Test
    fun `every declared argument has a description`() {
        ToolSchemas.SPECS.forEach { (id, spec) ->
            spec.args.forEach { a ->
                assertTrue(a.description.isNotBlank(), "$id.${a.name} has no description")
                assertTrue(a.description.length > 10, "$id.${a.name} description is too thin: ${a.description}")
            }
        }
    }

    @Test
    fun `argument names are unique per tool and snake_case`() {
        ToolSchemas.SPECS.forEach { (id, spec) ->
            val names = spec.args.map { it.name }
            assertEquals(names.size, names.toSet().size, "$id has a duplicate argument name")
            names.forEach { assertTrue(Regex("^[a-z][a-z0-9_]*$").matches(it), "$id.$it is not snake_case") }
        }
    }

    @Test
    fun `types are valid JSON Schema types and bounds only apply to integers`() {
        ToolSchemas.SPECS.forEach { (id, spec) ->
            spec.args.forEach { a ->
                assertTrue(a.type in setOf("string", "integer", "boolean"), "$id.${a.name} has type '${a.type}'")
                if (a.min != null || a.max != null) {
                    assertEquals("integer", a.type, "$id.${a.name} has bounds but is not an integer")
                }
                if (a.min != null && a.max != null) {
                    assertTrue(a.min <= a.max, "$id.${a.name} has min > max")
                }
                if (a.enum != null) assertEquals("string", a.type, "$id.${a.name} has an enum but is not a string")
                // A required argument with a default is contradictory — the default can never apply.
                if (a.required) assertTrue(a.default == null, "$id.${a.name} is required but also has a default")
            }
        }
    }

    @Test
    fun `every argument the handlers read is declared in the schema`() {
        // Catches an argument added to a handler but never exposed — invisible to the model.
        // Scoped to the tools the table describes; the mapping from handler to tool is by hand.
        val readByHandlers = Regex("""args\["([a-z0-9_]+)"]""").findAll(mcpCode).map { it.groupValues[1] }.toSet()
        val declared = ToolSchemas.SPECS.values.flatMap { it.args }.map { it.name }.toSet()
        val undeclared = readByHandlers - declared
        assertTrue(undeclared.isEmpty(), "handlers read arguments no schema declares: $undeclared")
    }

    @Test
    fun `every declared argument is actually read by a handler`() {
        // The mirror: a schema entry no handler reads is dead weight the model will try to use.
        val readByHandlers = Regex("""args\["([a-z0-9_]+)"]""").findAll(mcpCode).map { it.groupValues[1] }.toSet()
        val declared = ToolSchemas.SPECS.values.flatMap { it.args }.map { it.name }.toSet()
        val dead = declared - readByHandlers
        assertTrue(dead.isEmpty(), "schema declares arguments no handler reads: $dead")
    }

    @Test
    fun `tools whose handler hard-fails without an argument mark it required`() {
        // Each of these returns an error string when the argument is absent, so the model must be
        // told up front rather than discovering it by burning a call (and an approval).
        val mustRequire = mapOf(
            "write_clipboard" to "text", "launch_url" to "url", "dial" to "number",
            "elevated_input" to "action", "set_volume" to "level", "media_control" to "action",
            "toast" to "text", "share_text" to "text", "create_calendar_event" to "title",
            "root_shell" to "command", "run_shortcut" to "package",
            "torch" to "on", "post_notification" to "title",
        )
        mustRequire.forEach { (tool, arg) ->
            val spec = ToolSchemas.specFor(tool)
            val p = spec.args.firstOrNull { it.name == arg }
            assertTrue(p != null && p.required, "$tool.$arg must be marked required")
        }
    }

    @Test
    fun `arguments the handler defaults are optional and declare that default`() {
        // The mirror of the required check: marking something required when the handler happily
        // falls back would make the model believe a call is impossible when it is not.
        val p = ToolSchemas.specFor("open_settings").args.first { it.name == "screen" }
        assertTrue(!p.required, "open_settings.screen falls back to \"apps\"; it is not required")
        assertEquals("\"apps\"", p.default?.toString())
    }

    @Test
    fun `integer bounds mirror the handler clamps`() {
        // These are the actual coerceIn ranges in Mcp.kt. If a handler clamp changes without the
        // schema, the model keeps sending values that get silently narrowed.
        fun bounds(tool: String, arg: String): Triple<Int?, Int?, String?> {
            val p = ToolSchemas.specFor(tool).args.first { it.name == arg }
            return Triple(p.min, p.max, p.default?.toString())
        }
        assertEquals(Triple(1, 30, "5"), bounds("record_audio", "seconds"))
        assertEquals(Triple(1, 5000, "300"), bounds("vibrate", "milliseconds"))
        assertEquals(Triple(1, 2000, "100"), bounds("list_packages", "limit"))
        assertEquals(Triple(1, 500, "30"), bounds("get_contacts", "limit"))
        assertEquals(Triple(1, 365, "7"), bounds("read_calendar", "days_ahead"))
        assertEquals(Triple(1, 43200, "60"), bounds("create_calendar_event", "duration_minutes"))
        // Newly clamped — previously unbounded, where limit:-1 was a live bug.
        assertEquals(Triple(1, 200, "20"), bounds("read_sms", "limit"))
        assertEquals(Triple(1, 200, "20"), bounds("read_call_log", "limit"))
        assertEquals(Triple(1, 200, "20"), bounds("read_notifications", "limit"))
    }

    @Test
    fun `enums match the values the handlers actually accept`() {
        // set_volume used to reject voice_call while volume_info advertised it.
        assertEquals(
            listOf("music", "ring", "alarm", "notification", "voice_call", "system"),
            ToolSchemas.specFor("set_volume").args.first { it.name == "stream" }.enum,
        )
        assertEquals(listOf("back", "front"), ToolSchemas.specFor("take_photo").args.first { it.name == "camera" }.enum)
        assertEquals(listOf("get", "put"), ToolSchemas.specFor("elevated_settings").args.first { it.name == "action" }.enum)
        assertEquals(listOf("tap", "swipe", "text", "key"), ToolSchemas.specFor("elevated_input").args.first { it.name == "action" }.enum)
    }

    @Test
    fun `annotations are consistent with the registry`() {
        Capabilities.REGISTRY.forEach { cap ->
            val spec = ToolSchemas.specFor(cap.id)
            // Nothing read-only may also be destructive.
            assertTrue(!(spec.readOnly && spec.destructive), "'${cap.id}' is both readOnly and destructive")
        }
        // The tools that change state outside this app must not claim to be read-only.
        listOf("write_clipboard", "root_shell", "elevated_input", "elevated_settings",
            "create_calendar_event", "set_volume", "torch", "vibrate", "launch_url", "run_shortcut")
            .forEach { assertTrue(!ToolSchemas.specFor(it).readOnly, "'$it' must not be marked readOnly") }
        // ...and the highest-blast-radius ones must be flagged destructive.
        listOf("root_shell", "elevated_input", "elevated_settings", "write_clipboard", "create_calendar_event")
            .forEach { assertTrue(ToolSchemas.specFor(it).destructive, "'$it' must be marked destructive") }
    }

    @Test
    fun `descriptions do not embed volatile enabled state`() {
        // Clients cache tools/list at connect and there is no listChanged channel, so a
        // "[currently disabled]" suffix would be wrong for the rest of the session.
        assertTrue("[currently " !in mcpCode, "tool descriptions must not embed live on/off state")
    }
}
