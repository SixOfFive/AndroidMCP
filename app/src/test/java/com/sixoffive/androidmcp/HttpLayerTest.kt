package com.sixoffive.androidmcp

import com.sixoffive.androidmcp.core.AppConfig
import com.sixoffive.androidmcp.core.ClientToken
import com.sixoffive.androidmcp.core.ConfigStore
import com.sixoffive.androidmcp.core.TokenStore
import com.sixoffive.androidmcp.server.Mcp
import com.sixoffive.androidmcp.server.installRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The HTTP layer in front of [Mcp]: bearer auth, the DNS-rebinding Origin guard, CORS, the
 * protocol-version header, body limits, and the media capability URL.
 *
 * Reachable without a device because `installRoutes` takes the handler as a lambda rather than a
 * `Context` — the whole point of that seam. `TokenStore.tokens` and `ConfigStore.state` are public
 * `MutableStateFlow`s, so the test seeds them directly.
 */
class HttpLayerTest {

    private val token = "test-token-value"

    @Before
    fun seed() {
        val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
        TokenStore.tokens.value = listOf(ClientToken(name = "laptop", hashHex = hash))
        ConfigStore.state.value = AppConfig(allowBrowser = false)
    }

    /** Routes wired to a handler that just echoes which client authenticated. */
    private fun app(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application { installRoutes { _, client -> Mcp.Reply.Body("""{"ok":true,"client":"$client"}""") } }
        block(client)
    }

    private val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""

    // ---- auth ----

    @Test
    fun `a valid bearer token authenticates and resolves the client name`() = app { c ->
        val r = c.post("/mcp") { header("Authorization", "Bearer $token"); setBody(ping) }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"client\":\"laptop\""))
    }

    @Test
    fun `the auth scheme is case-insensitive per RFC 7235`() = app { c ->
        assertEquals(
            HttpStatusCode.OK,
            c.post("/mcp") { header("Authorization", "bearer $token"); setBody(ping) }.status,
        )
    }

    @Test
    fun `a bare schemeless token is rejected`() = app { c ->
        // `removePrefix("Bearer ")` returns the string unchanged when the prefix is absent, so the
        // raw token used to authenticate with no scheme at all.
        assertEquals(HttpStatusCode.Unauthorized, c.post("/mcp") { header("Authorization", token); setBody(ping) }.status)
    }

    @Test
    fun `a wrong token, a wrong scheme, and no header are all 401`() = app { c ->
        for (h in listOf("Bearer wrong", "Basic $token", "Bearer ", "Bearer")) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                c.post("/mcp") { header("Authorization", h); setBody(ping) }.status,
                "should reject Authorization: $h",
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, c.post("/mcp") { setBody(ping) }.status)
    }

    @Test
    fun `401 names the scheme but advertises no authorization server`() = app { c ->
        val r = c.post("/mcp") { setBody(ping) }
        val h = r.headers["WWW-Authenticate"]
        assertNotNull(h)
        assertTrue(h.startsWith("Bearer"), "must name the scheme so a client knows how to authenticate")
        // There is no authorization server; pointing at one sends clients down a dead OAuth path.
        assertTrue(!h.contains("resource_metadata"))
    }

    // ---- DNS-rebinding guard ----

    @Test
    fun `a browser Origin is refused while the dashboard is off`() = app { c ->
        val r = c.post("/mcp") {
            header("Origin", "https://evil.example")
            header("Authorization", "Bearer $token")
            setBody(ping)
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `the Origin guard runs before auth, so it does not leak token validity`() = app { c ->
        val r = c.post("/mcp") { header("Origin", "https://evil.example"); setBody(ping) }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `with the dashboard opted in, a browser Origin is allowed and echoed`() {
        ConfigStore.state.value = AppConfig(allowBrowser = true)
        app { c ->
            val r = c.post("/mcp") {
                header("Origin", "http://localhost:3000")
                header("Authorization", "Bearer $token")
                setBody(ping)
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals("http://localhost:3000", r.headers["Access-Control-Allow-Origin"])
            assertEquals("Origin", r.headers["Vary"])
        }
    }

    @Test
    fun `preflight is refused while the dashboard is off and honoured when on`() {
        app { c -> assertEquals(HttpStatusCode.Forbidden, c.options("/mcp") { header("Origin", "http://x") }.status) }
        ConfigStore.state.value = AppConfig(allowBrowser = true)
        app { c ->
            val r = c.options("/mcp") { header("Origin", "http://x") }
            assertEquals(HttpStatusCode.NoContent, r.status)
            assertTrue(r.headers["Access-Control-Allow-Headers"]!!.contains("mcp-protocol-version"))
        }
    }

    // ---- protocol version header ----

    @Test
    fun `an unsupported MCP-Protocol-Version is a 400 naming what is supported`() = app { c ->
        val r = c.post("/mcp") {
            header("Authorization", "Bearer $token")
            header("MCP-Protocol-Version", "2099-01-01")
            setBody(ping)
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains(Mcp.PROTOCOL))
    }

    @Test
    fun `a supported version header passes, and an absent one is allowed for back-compat`() = app { c ->
        assertEquals(HttpStatusCode.OK, c.post("/mcp") {
            header("Authorization", "Bearer $token")
            header("MCP-Protocol-Version", Mcp.PROTOCOL)
            setBody(ping)
        }.status)
        assertEquals(HttpStatusCode.OK, c.post("/mcp") { header("Authorization", "Bearer $token"); setBody(ping) }.status)
    }

    // ---- reply shapes ----

    @Test
    fun `a notification gets 202 with an empty body`() = testApplication {
        application { installRoutes { _, _ -> Mcp.Reply.None } }
        val r = client.post("/mcp") { header("Authorization", "Bearer $token"); setBody("{}") }
        assertEquals(HttpStatusCode.Accepted, r.status)
        assertEquals("", r.bodyAsText())
    }

    @Test
    fun `a rejected envelope becomes its HTTP status, not a 200`() = testApplication {
        application { installRoutes { _, _ -> Mcp.Reply.Rejected(400, """{"jsonrpc":"2.0","error":{"code":-32700}}""") } }
        val r = client.post("/mcp") { header("Authorization", "Bearer $token"); setBody("nope") }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `an oversized body is refused`() = app { c ->
        val r = c.post("/mcp") {
            header("Authorization", "Bearer $token")
            setBody("x".repeat(600 * 1024))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
    }

    // ---- other routes ----

    @Test
    fun `GET on the endpoint declines the optional SSE stream`() = app { c ->
        assertEquals(HttpStatusCode.MethodNotAllowed, c.get("/mcp").status)
    }

    @Test
    fun `a media link works exactly once and only with the right nonce`() = app { c ->
        val (id, nonce) = com.sixoffive.androidmcp.server.MediaStore.put("JPEGBYTES".toByteArray(), "image/jpeg")

        assertEquals(HttpStatusCode.NotFound, c.get("/media/$id?k=wrong").status, "a bad nonce must not fetch")
        // ...and must not have evicted the entry either: with a token-free route, that was a
        // trivial way to delete every pending blob.
        val ok = c.get("/media/$id?k=$nonce")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("JPEGBYTES", ok.bodyAsText())
        assertEquals("no-store", ok.headers["Cache-Control"])
        assertEquals("nosniff", ok.headers["X-Content-Type-Options"])

        // Genuinely single-use: the second fetch must miss.
        assertEquals(HttpStatusCode.NotFound, c.get("/media/$id?k=$nonce").status)
    }

    @Test
    fun `media ids are not guessable`() {
        // Ids used to be a base36 counter — "m1", "m2", … — on a route that carries no bearer
        // token, so anyone reaching the port could enumerate and evict pending blobs.
        val ids = (1..200).map { com.sixoffive.androidmcp.server.MediaStore.put(byteArrayOf(it.toByte()), "image/jpeg").first }
        assertEquals(200, ids.toSet().size, "ids must not collide")
        assertTrue(ids.all { it.length >= 12 }, "ids must be long enough to be unguessable")
        // The old scheme was "m" + a short base36 counter, so every id was under ~6 characters
        // and fully predictable from the previous one. The length floor above rules that out;
        // asserting on the character content would only re-encode the encoding alphabet.
    }
}
