package com.sixoffive.androidmcp

import android.content.Context
import com.sixoffive.androidmcp.core.AppConfig
import com.sixoffive.androidmcp.core.ClientToken
import com.sixoffive.androidmcp.core.ConfigStore
import com.sixoffive.androidmcp.core.TokenStore
import com.sixoffive.androidmcp.server.Mcp
import com.sixoffive.androidmcp.server.installRoutes
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mockito.Mockito
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Does a REAL MCP client accept the progress stream?
 *
 * Everything else about streaming is checked against this codebase's own idea of the wire format,
 * which is exactly the assumption worth distrusting: `HttpLayerTest` asserts the bytes match what
 * this server means to send, not that anything can read them. So this stands the real Ktor CIO
 * engine up on a real socket, wires it through the real [installRoutes], and points the reference
 * MCP Python SDK at it — the same SDK that found two `isError` defects 71 unit tests had missed.
 *
 * SKIPPED unless run with `-Dandroidmcp.sdk=1`, because it needs `uv` and network access to fetch
 * the SDK. The device-free suite has to keep working on a laptop with neither.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*SdkProgressHarness*' -Dandroidmcp.sdk=1
 */
class SdkProgressHarnessTest {

    private val token = "harness-token-value"
    private val json = Json { ignoreUnknownKeys = true }
    private val ctx: Context = Mockito.mock(Context::class.java)

    @Test
    fun `the reference SDK receives progress and then the result`() {
        assumeTrue("needs -Dandroidmcp.sdk=1 and uv", System.getProperty("androidmcp.sdk") == "1")

        val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
        TokenStore.tokens.value = listOf(ClientToken(name = "harness", hashHex = hash))
        ConfigStore.state.value = AppConfig(allowBrowser = false)

        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            installRoutes { body, _, header, sse -> reply(body, header, sse) }
        }
        server.start(wait = false)
        try {
            val port = runBlocking { server.resolvedConnectors().first().port }
            val out = runSdk(port)
            // The SDK only invokes its progress callback for notifications it could parse AND
            // correlate back to the token it generated for this call.
            assertTrue(
                out.contains("\"progress_seen\": true"),
                "the SDK saw no progress notifications:\n$out",
            )
            assertTrue(
                out.contains("STREAMED-RESULT"),
                "the SDK did not get the result off the stream:\n$out",
            )
        } finally {
            server.stop(100, 500)
        }
    }

    /** Real handshake via [Mcp.handle]; a hand-rolled slow tool, since a real one needs a device. */
    private suspend fun reply(body: String, header: String?, sse: Boolean): Mcp.Reply {
        val root = json.parseToJsonElement(body).jsonObject
        val method = root["method"]?.jsonPrimitive?.content
        val id = root["id"]?.jsonPrimitive?.content
        val result = """{"content":[{"type":"text","text":"STREAMED-RESULT"}],"isError":false}"""
        return when {
            // initialize and the notifications are the strict part of the handshake, so they go
            // through the real handler rather than a stub that might be politely wrong.
            method == "initialize" || id == null -> Mcp.handle(ctx, body, "harness", header, sse)
            method == "tools/list" -> Mcp.Reply.Body(
                """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"slow",""" +
                    """"description":"sleeps","inputSchema":{"type":"object","properties":{}}}]}}"""
            )
            method == "tools/call" && sse -> Mcp.Reply.Streamed { emit ->
                // The token the CLIENT generated, echoed back verbatim. The SDK correlates
                // strictly on it: hardcoding one here made the SDK drop every notification while
                // still reading the result off the same stream, which is how this harness earned
                // its keep — that is a silent half-failure no framing assertion would show.
                val clientToken = ((root["params"] as? kotlinx.serialization.json.JsonObject)
                    ?.get("_meta") as? kotlinx.serialization.json.JsonObject)
                    ?.get("progressToken") as? JsonPrimitive
                    ?: error("the SDK sent no progressToken despite a progress_callback")
                Mcp.streamWithProgress(
                    token = clientToken,
                    emit = emit,
                    approvalPending = { true },
                    tickMs = 150,
                ) { delay(700); """{"jsonrpc":"2.0","id":$id,"result":$result}""" }
            }
            else -> Mcp.Reply.Body("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }
    }

    private fun runSdk(port: Int): String {
        val script = """
import anyio, json, sys
from mcp import ClientSession
import contextlib
import mcp.client.streamable_http as sh

# The SDK both renamed this and changed its signature between releases: the old
# `streamablehttp_client` took `headers=`, the new `streamable_http_client` takes a prebuilt
# `http_client`. Accept either, so this harness survives an SDK bump.
_new = getattr(sh, "streamable_http_client", None)

@contextlib.asynccontextmanager
async def connect(url, headers):
    if _new is not None:
        import httpx2
        async with httpx2.AsyncClient(headers=headers) as hc:
            async with _new(url, http_client=hc) as streams:
                yield streams
    else:
        async with sh.streamablehttp_client(url, headers=headers) as streams:
            yield streams

async def main():
    seen = []
    async def on_progress(progress, total, message):
        seen.append({"progress": progress, "total": total, "message": message})
    url = "http://127.0.0.1:$port/mcp"
    hdrs = {"Authorization": "Bearer $token"}
    async with connect(url, hdrs) as streams:
        # Old SDKs yielded (read, write, get_session_id); new ones yield just (read, write).
        r, w = streams[0], streams[1]
        async with ClientSession(r, w) as s:
            await s.initialize()
            res = await s.call_tool("slow", {}, progress_callback=on_progress)
            text = "".join(getattr(c, "text", "") for c in res.content)
            print(json.dumps({"progress_seen": len(seen) > 0, "notifications": seen, "text": text}))

anyio.run(main)
""".trimIndent()
        val p = ProcessBuilder("uv", "run", "--quiet", "--with", "mcp", "python", "-c", script)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(180, TimeUnit.SECONDS)
        println(out)
        return out
    }
}
