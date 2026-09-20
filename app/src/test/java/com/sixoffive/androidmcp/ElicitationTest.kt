package com.sixoffive.androidmcp

import android.content.Context
import com.sixoffive.androidmcp.core.Capabilities
import com.sixoffive.androidmcp.server.Mcp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.mockito.Mockito
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wave 4 — remote approval via MCP elicitation.
 *
 * Exercises only the device-free parts: the elicitation request builder, the decision parser, the
 * initialize-time capability capture, and the JSON-RPC response routing in [Mcp.handle]. The full
 * approval loop ([com.sixoffive.androidmcp.server.ApprovalManager.require]) posts a system
 * notification, so its end-to-end behaviour stays in the on-device checks.
 */
class ElicitationTest {

    private val ctx: Context = Mockito.mock(Context::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(s: String) = json.parseToJsonElement(s).jsonObject

    private fun call(body: String, client: String = "test-client"): Mcp.Reply =
        runBlocking { Mcp.handle(ctx, body, client) }

    // ---- the elicitation/create request we put to the client ----

    @Test
    fun `elicitation request is a well-formed elicitation-create with a boolean approve schema`() {
        val cap = Capabilities.byId("read_screen")!!
        val req = obj(Mcp.elicitationRequest("req-42", cap, "phone"))

        assertEquals("2.0", req["jsonrpc"]!!.jsonPrimitive.content)
        // The id IS the internal approval id, so the client's response correlates with no extra map.
        assertEquals("req-42", req["id"]!!.jsonPrimitive.content)
        assertEquals("elicitation/create", req["method"]!!.jsonPrimitive.content)

        val params = req["params"]!!.jsonObject
        val message = params["message"]!!.jsonPrimitive.content
        assertContains(message, "phone")        // names the requesting client
        assertContains(message, cap.title)       // names the capability

        val schema = params["requestedSchema"]!!.jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        val approve = schema["properties"]!!.jsonObject["approve"]!!.jsonObject
        assertEquals("boolean", approve["type"]!!.jsonPrimitive.content)
        assertTrue(schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("approve"))
    }

    // ---- parsing the client's answer ----

    @Test
    fun `accept with approve-true is an allow`() {
        assertEquals(true, Mcp.parseElicitDecision(obj(
            """{"id":"req-1","result":{"action":"accept","content":{"approve":true}}}""")))
    }

    @Test
    fun `accept with approve-false is a deny`() {
        assertEquals(false, Mcp.parseElicitDecision(obj(
            """{"id":"req-1","result":{"action":"accept","content":{"approve":false}}}""")))
    }

    @Test
    fun `decline and cancel are denies`() {
        assertEquals(false, Mcp.parseElicitDecision(obj("""{"id":"r","result":{"action":"decline"}}""")))
        assertEquals(false, Mcp.parseElicitDecision(obj("""{"id":"r","result":{"action":"cancel"}}""")))
    }

    @Test
    fun `accept with no content is a deny, not an allow`() {
        // A malformed accept must never be read as consent.
        assertEquals(false, Mcp.parseElicitDecision(obj("""{"id":"r","result":{"action":"accept"}}""")))
    }

    @Test
    fun `an error response yields no decision, so the phone still decides`() {
        // null = do not resolve — a client that errored (or cannot elicit) must not deny a call the
        // human might still approve on the device.
        assertNull(Mcp.parseElicitDecision(obj(
            """{"id":"r","error":{"code":-32601,"message":"Method not found"}}""")))
    }

    // ---- JSON-RPC response routing in handle() ----

    @Test
    fun `a response envelope (id + result, no method) is accepted as a notification, not answered`() {
        // Before Wave 4 this hit "Invalid Request: missing method". Now it routes to the approval
        // manager and is answered with 202/None. An unknown id simply resolves nothing.
        val r = call("""{"jsonrpc":"2.0","id":"req-99999","result":{"action":"accept","content":{"approve":true}}}""")
        assertIs<Mcp.Reply.None>(r)
    }

    @Test
    fun `an error response envelope is also accepted, not answered`() {
        val r = call("""{"jsonrpc":"2.0","id":"req-99998","error":{"code":-32601,"message":"nope"}}""")
        assertIs<Mcp.Reply.None>(r)
    }

    @Test
    fun `an id with neither method nor result is still the missing-method error`() {
        // Not a response and not a request: the old envelope error must still fire.
        val r = call("""{"jsonrpc":"2.0","id":7}""")
        val body = when (r) {
            is Mcp.Reply.Body -> json.parseToJsonElement(r.json).jsonObject
            else -> error("expected a Body error, got $r")
        }
        assertEquals(-32600, body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    // ---- initialize captures the client's elicitation capability ----

    @Test
    fun `initialize records whether the client can be elicited`() {
        val withCap = "client-elicits-${System.nanoTime()}"
        call("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{"elicitation":{}},"clientInfo":{"name":"c","version":"1"}}}""", withCap)
        assertTrue(Mcp.clientSupportsElicitation(withCap))

        val without = "client-noelicit-${System.nanoTime()}"
        call("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"c","version":"1"}}}""", without)
        assertFalse(Mcp.clientSupportsElicitation(without))
    }
}
