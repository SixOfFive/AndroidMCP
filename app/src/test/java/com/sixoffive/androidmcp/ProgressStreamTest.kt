package com.sixoffive.androidmcp

import android.content.Context
import com.sixoffive.androidmcp.server.Mcp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `notifications/progress` during a tool call.
 *
 * 22 of the 40 tools block on a human tapping "Allow", so the interesting cases are all about a
 * call that is slow for a reason the client cannot see. Two separable things are tested here: WHEN
 * a stream is offered at all (a decision, made from two independent client signals), and WHAT the
 * heartbeat emits while work runs (timing, which is why it was pulled out of the dispatch).
 */
class ProgressStreamTest {

    private val ctx: Context = Mockito.mock(Context::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** An unknown tool errors before anything touches [Context] — enough to reach the decision. */
    private fun callTool(meta: String, acceptsSse: Boolean): Mcp.Reply = runBlocking {
        Mcp.handle(
            ctx,
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"no_such_tool"$meta}}""",
            "test-client",
            null,
            acceptsSse,
        )
    }

    // ---- when a stream is offered ----

    @Test
    fun `both signals present streams`() {
        assertIs<Mcp.Reply.Streamed>(callTool(""","_meta":{"progressToken":"p1"}""", acceptsSse = true))
    }

    @Test
    fun `a progress token without an SSE Accept does NOT stream`() {
        // The compatibility case that makes this safe to ship: asking for progress must never
        // change the response shape for a client that cannot read the new one.
        assertIs<Mcp.Reply.Body>(callTool(""","_meta":{"progressToken":"p1"}""", acceptsSse = false))
    }

    @Test
    fun `an SSE Accept without a progress token does NOT stream`() {
        // Claude Code sends `Accept: application/json, text/event-stream` on EVERY request. If
        // that alone triggered streaming, every ordinary call would change shape.
        assertIs<Mcp.Reply.Body>(callTool("", acceptsSse = true))
    }

    @Test
    fun `neither signal streams`() {
        assertIs<Mcp.Reply.Body>(callTool("", acceptsSse = false))
    }

    @Test
    fun `an explicitly null progress token is not a token`() {
        // JsonNull IS a JsonPrimitive, so this is a real trap: without the explicit check the
        // stream would be opened and every notification tagged with a token of `null`, which
        // correlates to nothing.
        assertIs<Mcp.Reply.Body>(callTool(""","_meta":{"progressToken":null}""", acceptsSse = true))
    }

    @Test
    fun `a non-scalar progress token is rejected rather than echoed`() {
        assertIs<Mcp.Reply.Body>(callTool(""","_meta":{"progressToken":{"a":1}}""", acceptsSse = true))
        assertIs<Mcp.Reply.Body>(callTool(""","_meta":{"progressToken":[1,2]}""", acceptsSse = true))
    }

    @Test
    fun `streaming does not change the response itself`() {
        // The stream must be a delivery change and nothing more: same JSON-RPC response, same id,
        // same content — otherwise a client sees different results depending on its Accept header.
        val plain = callTool(""","_meta":{"progressToken":"p1"}""", acceptsSse = false)
        val streamed = callTool(""","_meta":{"progressToken":"p1"}""", acceptsSse = true)
        val plainJson = (plain as Mcp.Reply.Body).json
        val finalJson = runBlocking { (streamed as Mcp.Reply.Streamed).produce { } }
        assertEquals(
            json.parseToJsonElement(plainJson).jsonObject,
            json.parseToJsonElement(finalJson).jsonObject,
        )
    }

    // ---- what the heartbeat emits ----

    private fun heartbeat(
        token: JsonPrimitive,
        approvalPending: Boolean = false,
        tickMs: Long = 60,
        work: suspend () -> String,
    ): Pair<List<String>, String> {
        val seen = CopyOnWriteArrayList<String>()
        val last = runBlocking {
            Mcp.streamWithProgress(token, { seen.add(it) }, { approvalPending }, tickMs, work)
        }
        return seen.toList() to last
    }

    @Test
    fun `work that finishes inside one tick emits no progress at all`() {
        // Most tools are armed or instant. They must not pay for this feature with a notification
        // the client has to parse and discard.
        val (seen, last) = heartbeat(JsonPrimitive("p1")) { "DONE" }
        assertTrue(seen.isEmpty(), "a fast call emitted ${seen.size} progress notifications")
        assertEquals("DONE", last)
    }

    @Test
    fun `slow work emits progress before its result, with an increasing value`() {
        val (seen, last) = heartbeat(JsonPrimitive("p1"), tickMs = 60) { delay(400); "DONE" }
        assertTrue(seen.size >= 2, "expected several notifications, got ${seen.size}")
        assertEquals("DONE", last)

        val values = seen.map { json.parseToJsonElement(it).jsonObject["params"]!!.jsonObject }
        values.forEach {
            assertEquals("p1", it["progressToken"]!!.jsonPrimitive.content)
            assertEquals("working", it["message"]!!.jsonPrimitive.content)
        }
        // The spec requires progress to increase with every notification for a given token.
        val progress = values.map { it["progress"]!!.jsonPrimitive.content.toDouble() }
        assertEquals(progress.sorted(), progress, "progress went backwards: $progress")
        assertEquals(progress.distinct().size, progress.size, "progress repeated a value: $progress")
    }

    @Test
    fun `a numeric progress token stays numeric`() {
        // Echoing `7` back as `"7"` breaks correlation on a strictly-typed client.
        val (seen, _) = heartbeat(JsonPrimitive(7), tickMs = 60) { delay(150); "DONE" }
        val token = json.parseToJsonElement(seen.first()).jsonObject["params"]!!
            .jsonObject["progressToken"]!!.jsonPrimitive
        assertTrue(token.isString.not(), "numeric token came back as a string")
        assertEquals("7", token.content)
    }

    @Test
    fun `a pending approval is named as such rather than reported as work`() {
        // The difference between "pick up your phone" and "the server is broken".
        val (seen, _) = heartbeat(JsonPrimitive("p1"), approvalPending = true, tickMs = 60) {
            delay(150); "DONE"
        }
        assertEquals(
            "waiting for approval on the device",
            json.parseToJsonElement(seen.first()).jsonObject["params"]!!
                .jsonObject["message"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a notification is well-formed JSON-RPC and carries no id`() {
        // A notification with an id is a REQUEST, and a client is entitled to answer it.
        val (seen, _) = heartbeat(JsonPrimitive("p1"), tickMs = 60) { delay(150); "DONE" }
        val n = json.parseToJsonElement(seen.first()).jsonObject
        assertEquals("2.0", n["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("notifications/progress", n["method"]!!.jsonPrimitive.content)
        assertTrue("id" !in n, "a progress notification must not carry an id")
    }

    @Test
    fun `a client that vanishes mid-stream cancels the work`() {
        // The emit throws because the socket is gone. The tool must not keep running — and must
        // not be reported as having completed.
        var finished = false
        val boom = runCatching {
            runBlocking {
                Mcp.streamWithProgress(
                    JsonPrimitive("p1"),
                    { throw java.io.IOException("client went away") },
                    { false },
                    60,
                ) { delay(5_000); finished = true; "DONE" }
            }
        }
        assertTrue(boom.isFailure, "a dead client should surface as a failure, not a result")
        assertTrue(!finished, "the work kept running after the client disconnected")
    }
}
