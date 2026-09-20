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
            "torch" to "on", "post_notification" to "title", "speak" to "text",
            "notification_action" to "key", "global_action" to "action",
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
    fun `wave-1 context reads are marked read-only and speak is not`() {
        // The four new context reads must not claim to change state; speak does (it plays audio),
        // so it must not be read-only. A regression either way misinforms a host's auto-approve.
        listOf("telephony_info", "bluetooth_info", "locale_info", "dnd_status").forEach {
            assertTrue(ToolSchemas.specFor(it).readOnly, "'$it' must be marked readOnly")
        }
        assertTrue(!ToolSchemas.specFor("speak").readOnly, "speak plays audio; it is not read-only")
    }

    @Test
    fun `get_location address flag is optional and defaults to false`() {
        // get_location gained an argument but must still be callable with none — reverse geocoding
        // is opt-in, so a bare call keeps returning coordinates as it always did.
        val p = ToolSchemas.specFor("get_location").args.first { it.name == "address" }
        assertTrue(!p.required, "get_location.address is opt-in, not required")
        assertEquals("false", p.default?.toString())
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
        // global_action's enum must match exactly the actions McpAccessibilityService.globalAction() maps.
        assertEquals(
            listOf("back", "home", "recents", "notifications", "quick_settings", "lock_screen", "screenshot", "power_dialog"),
            ToolSchemas.specFor("global_action").args.first { it.name == "action" }.enum,
        )
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
    fun `failures are thrown, never returned as an ordinary string`() {
        // A handler that RETURNS its complaint produces a *successful* tool result whose text
        // merely reads like an error — isError:false, audited "ok".
        //
        // This matcher has now been wrong twice. v1 matched only "provide"/"unknown", missing nine
        // sites saying "needs"/"invalid". v2 matched only `return "..."`, so it could not see
        // `else -> "unknown action…"` (expression position) or `return listOf(textBlk("…"))` — and
        // reported zero offenders while nine lived. It scans every string literal now, and the
        // whitelist matches whole messages so one word cannot blanket-exempt unrelated lines.
        val literals = Regex(""""([^"\\]{12,})"""")
        val failureWords = Regex(
            "(?i)\\b(needs|need a|provide|unknown|invalid|could not|cannot|can't|failed|" +
                "not installed|not launchable|no writable|isn't active|not available|refused)\\b"
        )
        // Whole-message exemptions: each is a claim that the string is a legitimate RESULT.
        val allowed = setOf(
            "contacts provider not accessible",
            "calendar provider not accessible",
            "SMS provider not accessible",
            "call log provider not accessible",
            "no folders granted — add one in androidmcp (Shared folders → Add folder)",
            "granted folders are empty",
        )
        // Substrings that are legitimate field VALUES rather than outcomes — a Wi-Fi SSID the OS
        // withholds, a settings value that cannot be disambiguated.
        val valueFragments = listOf("unknown ssid", "redacted", "cannot distinguish")
        // Scope to the capability runners. Above them is the JSON-RPC layer, whose `error(...)`
        // builders legitimately carry "Invalid Request" / "Unknown tool" text — those ARE the
        // failure path, not a failure disguised as success.
        val handlers = mcpCode.substringAfter("private suspend fun execute(")
        val offenders = handlers.lineSequence().withIndex().flatMap { (i, line) ->
            // Only lines that PRODUCE a value: a return, or a `->` arm. Assignments and calls that
            // merely mention a string are not results.
            if (!line.contains("return ") && !line.contains("->")) return@flatMap emptySequence<String>()
            if (line.contains("throw ")) return@flatMap emptySequence<String>()
            literals.findAll(line).map { it.groupValues[1] }
                .filter { msg ->
                    failureWords.containsMatchIn(msg) && msg !in allowed &&
                        valueFragments.none { msg.contains(it, ignoreCase = true) }
                }
                .map { "  line ${i + 1}: $it" }
        }.toList()
        assertTrue(offenders.isEmpty(),
            "these report a failure as a SUCCESS; throw ToolArgError / ToolExecError instead:\n" +
                offenders.joinToString("\n"))
    }

    @Test
    fun `both failure types are routed to isError and audited distinctly`() {
        val dispatch = File("src/main/java/com/sixoffive/androidmcp/server/Mcp.kt").readText()
        assertTrue("INVALID_ARGUMENT" in dispatch, "argument errors need their own audit reason")
        assertTrue("EXECUTION_ERROR" in dispatch, "execution failures need their own audit reason")
        // The audit log must be able to separate "you called it wrong" from "the device failed".
        assertTrue(dispatch.indexOf("is ToolArgError") < dispatch.indexOf("is ToolExecError"))
    }

    @Test
    fun `descriptions do not embed volatile enabled state`() {
        // Clients cache tools/list at connect and there is no listChanged channel, so a
        // "[currently disabled]" suffix would be wrong for the rest of the session.
        assertTrue("[currently " !in mcpCode, "tool descriptions must not embed live on/off state")
    }
}
