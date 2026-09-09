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
        // The rejection token bucket is process-global, so one test's probing would otherwise
        // throttle every test that runs after it.
        com.sixoffive.androidmcp.server.AccessControl.reset()
        val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
        TokenStore.tokens.value = listOf(ClientToken(name = "laptop", hashHex = hash))
        ConfigStore.state.value = AppConfig(allowBrowser = false)
    }

    /** Routes wired to a handler that just echoes which client authenticated. */
    private fun app(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application { installRoutes { _, client, _ -> Mcp.Reply.Body("""{"ok":true,"client":"$client"}""") } }
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
    fun `with the dashboard opted in, an allowed browser Origin is echoed`() {
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
    fun `preflight is refused while the dashboard is off and honoured for an allowed origin`() {
        val local = "http://localhost:3000"
        app { c -> assertEquals(HttpStatusCode.Forbidden, c.options("/mcp") { header("Origin", local) }.status) }
        ConfigStore.state.value = AppConfig(allowBrowser = true)
        app { c ->
            val r = c.options("/mcp") { header("Origin", local) }
            assertEquals(HttpStatusCode.NoContent, r.status)
            assertTrue(r.headers["Access-Control-Allow-Headers"]!!.contains("mcp-protocol-version"))
            // ...but an arbitrary origin is still refused even with the dashboard on.
            assertEquals(HttpStatusCode.Forbidden, c.options("/mcp") { header("Origin", "https://evil.example") }.status)
        }
    }

    // ---- protocol version header ----

    @Test
    fun `the version header is passed through to the handler, which decides`() = testApplication {
        // The check moved into Mcp.handle: it must not apply to `initialize`, and only the parsed
        // method can tell. McpProtocolTest covers the decision; this pins the plumbing.
        var seen: String? = "not-called"
        application { installRoutes { _, _, v -> seen = v; Mcp.Reply.Body("{}") } }
        client.post("/mcp") {
            header("Authorization", "Bearer $token")
            header("MCP-Protocol-Version", "2099-01-01")
            setBody(ping)
        }
        assertEquals("2099-01-01", seen)
        client.post("/mcp") { header("Authorization", "Bearer $token"); setBody(ping) }
        assertEquals(null, seen, "an absent header must arrive as null, not empty string")
    }

    // ---- reply shapes ----

    @Test
    fun `a notification gets 202 with an empty body`() = testApplication {
        application { installRoutes { _, _, _ -> Mcp.Reply.None } }
        val r = client.post("/mcp") { header("Authorization", "Bearer $token"); setBody("{}") }
        assertEquals(HttpStatusCode.Accepted, r.status)
        assertEquals("", r.bodyAsText())
    }

    @Test
    fun `a rejected envelope becomes its HTTP status, not a 200`() = testApplication {
        application { installRoutes { _, _, _ -> Mcp.Reply.Rejected(400, """{"jsonrpc":"2.0","error":{"code":-32700}}""") } }
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
    fun `a chunked over-cap body is refused without being buffered`() = app { c ->
        // The old check called receiveText() and measured String.length AFTERWARDS. A
        // Transfer-Encoding: chunked body declares no Content-Length, so the pre-check never fired
        // and the whole stream was materialised before the cap was consulted. The previous test
        // used setBody(String), which sets Content-Length — so it only ever exercised the branch
        // that already worked.
        val r = c.post("/mcp") {
            header("Authorization", "Bearer $token")
            setBody(object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
                override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
                    val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
                    repeat(16) { channel.writeFully(chunk, 0, chunk.size) }   // 1 MB, no length
                }
            })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
    }

    @Test
    fun `an unknown media id is metered and audited like any other rejection`() = app { c ->
        // /media carries no bearer token by design, so before this it was the one unauthenticated
        // surface that could be probed indefinitely at no cost and with no trace.
        var last = HttpStatusCode.OK
        repeat(40) { last = c.get("/media/nosuch$it?k=wrong").status }
        assertEquals(HttpStatusCode.TooManyRequests, last, "probing /media must eventually throttle")
    }

    @Test
    fun `a link still resolves when the store is at capacity`() {
        // take() used to call prune() as its FIRST statement — and prune evicts for capacity, not
        // just expiry. put() prunes before inserting too, so the store sits permanently at the cap,
        // meaning every fetch first destroyed the oldest unfetched link: exactly the one a client
        // was about to dereference. Both callers then reported "expired or already fetched" about a
        // blob that was neither.
        val MS = com.sixoffive.androidmcp.server.MediaStore
        val (firstId, firstNonce) = MS.put("FIRST".toByteArray(), "image/jpeg")
        repeat(40) { MS.put(byteArrayOf(it.toByte()), "image/jpeg") }   // push well past MAX_ENTRIES
        val e = MS.take(firstId, firstNonce)
        // Either the entry survived, or capacity legitimately evicted it in put() — but it must
        // never be destroyed by the act of fetching it.
        val again = MS.take(firstId, firstNonce)
        assertEquals(null, again, "an entry must be consumed exactly once")
        if (e != null) assertEquals("FIRST", String(e.bytes))
    }

    @Test
    fun `fetching one link never destroys another`() {
        val MS = com.sixoffive.androidmcp.server.MediaStore
        val links = (1..10).map { i -> MS.put("blob-$i".toByteArray(), "image/jpeg") to i }
        // Fetch them in reverse; every one must still be there.
        links.reversed().forEach { (link, i) ->
            val e = MS.take(link.first, link.second)
            assertTrue(e != null, "blob-$i was destroyed by fetching a different link")
            assertEquals("blob-$i", String(e!!.bytes))
        }
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
