package com.sixoffive.androidmcp

import android.content.Context
import com.sixoffive.androidmcp.server.Mcp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JSON-RPC / MCP envelope conformance.
 *
 * These exercise only the paths that never touch Android: the envelope, dispatch, version
 * negotiation, and the unknown-tool branch. Anything that reaches a real capability needs a
 * device and stays in the manual on-device checks.
 *
 * The mocked [Context] is never dereferenced on these paths — if a change starts touching it,
 * these tests fail loudly rather than silently exercising a stub.
 */
class McpProtocolTest {

    private val ctx: Context = Mockito.mock(Context::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private fun call(body: String): Mcp.Reply = runBlocking { Mcp.handle(ctx, body, "test-client") }

    private fun bodyOf(reply: Mcp.Reply): JsonObject = when (reply) {
        is Mcp.Reply.Body -> json.parseToJsonElement(reply.json).jsonObject
        is Mcp.Reply.Rejected -> json.parseToJsonElement(reply.json).jsonObject
        Mcp.Reply.None -> error("expected a body, got a notification")
    }

    private fun errorCode(reply: Mcp.Reply): Int =
        bodyOf(reply)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()

    // ---- notifications (JSON-RPC 2.0 §4.1: a notification MUST NOT be answered) ----

    @Test
    fun `known notification is not answered`() {
        assertIs<Mcp.Reply.None>(call("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun `unknown notification is not answered`() {
        // The regression that motivated this: every roots-capable client sends
        // notifications/roots/list_changed right after initialize. It used to come back as a
        // -32601 error carrying "id": null — a response to a message that forbids one.
        assertIs<Mcp.Reply.None>(call("""{"jsonrpc":"2.0","method":"notifications/roots/list_changed"}"""))
        assertIs<Mcp.Reply.None>(call("""{"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":1}}"""))
        assertIs<Mcp.Reply.None>(call("""{"jsonrpc":"2.0","method":"totally/unknown"}"""))
    }

    @Test
    fun `a request with an id IS answered`() {
        assertIs<Mcp.Reply.Body>(call("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
    }

    // ---- malformed envelopes reject at the HTTP layer, and never invent an id ----

    @Test
    fun `unparseable body is a 400 parse error with no id key`() {
        val r = call("""{"broken""")
        assertIs<Mcp.Reply.Rejected>(r)
        assertEquals(400, r.status)
        assertEquals(-32700, errorCode(r))
        assertFalse("id" in bodyOf(r), "id must be omitted, not null: RequestId is string | number")
    }

    @Test
    fun `top-level array is rejected — 2025-06-18 removed batching`() {
        val r = call("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]""")
        assertIs<Mcp.Reply.Rejected>(r)
        assertEquals(400, r.status)
        assertEquals(-32600, errorCode(r))
        assertFalse("id" in bodyOf(r))
    }

    @Test
    fun `an id of the wrong JSON type is rejected`() {
        val r = call("""{"jsonrpc":"2.0","id":{"nested":true},"method":"ping"}""")
        assertIs<Mcp.Reply.Rejected>(r)
        assertEquals(-32600, errorCode(r))
    }

    @Test
    fun `a non-string method does not become an HTTP 500`() {
        // `.jsonPrimitive` on an object throws; that used to escape as a 500 with no id to
        // correlate. It must be a JSON-RPC error instead.
        val r = call("""{"jsonrpc":"2.0","id":1,"method":{"a":1}}""")
        assertEquals(-32600, errorCode(r))
    }

    // ---- ids are echoed with their original JSON type ----

    @Test
    fun `id type is preserved`() {
        assertEquals(
            JsonPrimitive(7),
            bodyOf(call("""{"jsonrpc":"2.0","id":7,"method":"ping"}"""))["id"],
        )
        val strId = bodyOf(call("""{"jsonrpc":"2.0","id":"abc","method":"ping"}"""))["id"]
        assertEquals("abc", strId!!.jsonPrimitive.content)
        assertTrue(strId.jsonPrimitive.isString, "a string id must stay a string")
    }

    // ---- initialize: real version negotiation ----

    @Test
    fun `initialize echoes a supported version the client asked for`() {
        val r = bodyOf(call(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""
        ))
        assertEquals("2025-06-18", r["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `initialize answers with its own version when the client asks for one it cannot speak`() {
        // The old code hardcoded its reply, so this looked identical — but for the wrong reason:
        // it would also have "agreed" to a version it does not implement.
        val r = bodyOf(call(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
        ))
        val agreed = r["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        assertEquals(Mcp.PROTOCOL, agreed)
        assertTrue(agreed in Mcp.SUPPORTED)
    }

    @Test
    fun `initialize declares only what is actually implemented`() {
        val result = bodyOf(call("""{"jsonrpc":"2.0","id":1,"method":"initialize"}"""))["result"]!!.jsonObject
        val caps = result["capabilities"]!!.jsonObject
        assertTrue("tools" in caps)
        // No SSE stream exists (GET /mcp is a 405), so listChanged would be a false promise, and
        // there is no resources/prompts/logging implementation to advertise.
        assertFalse("listChanged" in caps["tools"]!!.jsonObject)
        assertFalse("resources" in caps)
        assertFalse("prompts" in caps)
        val info = result["serverInfo"]!!.jsonObject
        assertEquals("androidmcp", info["name"]!!.jsonPrimitive.content)
        assertNotNull(info["title"])
        // Clients surface `instructions` to the model; it carries the default-deny contract.
        assertTrue(result["instructions"]!!.jsonPrimitive.content.contains("default-deny", ignoreCase = true))
    }

    // ---- method dispatch ----

    @Test
    fun `unknown method with an id is method-not-found`() {
        assertEquals(-32601, errorCode(call("""{"jsonrpc":"2.0","id":1,"method":"resources/read"}""")))
    }

    @Test
    fun `ping returns an empty result object`() {
        val r = bodyOf(call("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
        assertEquals(0, r["result"]!!.jsonObject.size)
    }

    // ---- tools/call argument validation ----

    @Test
    fun `unknown tool is a protocol error, not a tool result`() {
        // The tool never ran, so there is no execution outcome to report as isError. A result
        // envelope here also let a hallucinated tool name look like a normal refusal.
        val r = call("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"no_such_tool"}}""")
        val b = bodyOf(r)
        assertEquals(-32602, errorCode(r))
        assertNull(b["result"])
    }

    @Test
    fun `tools_call with a non-string name is invalid params`() {
        assertEquals(-32602, errorCode(
            call("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":{"a":1}}}""")
        ))
    }

    @Test
    fun `tools_call with no params is invalid params`() {
        assertEquals(-32602, errorCode(call("""{"jsonrpc":"2.0","id":1,"method":"tools/call"}""")))
    }

    @Test
    fun `tools_call with non-object arguments is rejected, not silently defaulted`() {
        // Previously `arguments` that was not an object was replaced with {} — every argument
        // vanished and the tool ran on defaults, burning a user approval on the wrong action.
        assertEquals(-32602, errorCode(call(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"torch","arguments":"on"}}"""
        )))
    }

    @Test
    fun `unsupported protocol version rejection names what is supported`() {
        val b = json.parseToJsonElement(Mcp.unsupportedProtocolVersion("2099-01-01")).jsonObject
        assertFalse("id" in b)
        val err = b["error"]!!.jsonObject
        assertEquals(-32600, err["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(err["data"]!!.jsonObject["supported"].toString().contains(Mcp.PROTOCOL))
    }
}
