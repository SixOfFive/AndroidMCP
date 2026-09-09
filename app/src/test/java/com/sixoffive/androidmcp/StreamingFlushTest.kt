package com.sixoffive.androidmcp

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Does `respondTextWriter` actually put bytes on the wire before the handler returns?
 *
 * The whole point of streaming progress notifications is that the client hears something DURING
 * the 25 s approval wait. If either engine buffers the response until the handler completes, the
 * feature is worse than useless: it would replace a silent stall with a silent stall that also
 * changed the content type, and no unit test using `testApplication` could tell — the test engine
 * does not go through a socket at all, so it cannot observe buffering.
 *
 * So this drives BOTH real engines over a real loopback socket and times when each marker lands.
 * CIO serves plain HTTP and Netty serves TLS ([McpService.startServer]), so the answer has to hold
 * for both or the feature can only be offered on one.
 */
class StreamingFlushTest {

    private val gapMs = 700L

    @Test fun `CIO flushes each write before the handler returns`() = assertStreams(CIO)

    @Test fun `Netty flushes each write before the handler returns`() = assertStreams(Netty)

    private fun <T : io.ktor.server.engine.ApplicationEngine.Configuration> assertStreams(
        factory: ApplicationEngineFactory<ApplicationEngine, T>,
    ) {
        val server = embeddedServer(factory, host = "127.0.0.1", port = 0, module = streamingModule())
        server.start(wait = false)
        try {
            val port = runBlocking { server.resolvedConnectors().first().port }
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                s.soTimeout = 10_000
                s.getOutputStream().apply {
                    write("GET /stream HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                val started = System.nanoTime()
                val input = s.getInputStream()
                val seen = StringBuilder()
                val firstAt = input.msUntil(seen, "MARKER-FIRST", started)
                val secondAt = input.msUntil(seen, "MARKER-SECOND", started)

                // The load-bearing assertion. If the engine buffered, both markers arrive together
                // at the end and this gap collapses to ~0.
                assertTrue(
                    secondAt - firstAt > gapMs / 2,
                    "the two writes arrived ${secondAt - firstAt} ms apart; the handler held them " +
                        "$gapMs ms apart, so the engine buffered the response",
                )
                assertTrue(
                    firstAt < gapMs,
                    "the first write took $firstAt ms to arrive but the handler emitted it " +
                        "immediately — it was not flushed when written",
                )
            }
        } finally {
            server.stop(100, 500)
        }
    }

    private fun streamingModule(): Application.() -> Unit = {
        routing {
            get("/stream") {
                call.respondTextWriter(ContentType.Text.EventStream) {
                    write("MARKER-FIRST\n")
                    flush()
                    delay(gapMs)
                    write("MARKER-SECOND\n")
                    flush()
                }
            }
        }
    }

    /** Read until [marker] appears, returning how many ms after [started] that happened. */
    private fun InputStream.msUntil(seen: StringBuilder, marker: String, started: Long): Long {
        val buf = ByteArray(256)
        while (!seen.contains(marker)) {
            val n = read(buf)
            if (n < 0) fail("stream closed before '$marker' arrived; saw:\n$seen")
            seen.append(String(buf, 0, n, Charsets.ISO_8859_1))
        }
        return (System.nanoTime() - started) / 1_000_000
    }
}
