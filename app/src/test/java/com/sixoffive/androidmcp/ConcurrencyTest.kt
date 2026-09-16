package com.sixoffive.androidmcp

import com.sixoffive.androidmcp.core.AuditLog
import com.sixoffive.androidmcp.server.ApprovalManager
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Multi-client concurrency invariants the cancellation-scoping work rests on. The full
 * `ApprovalManager.require` / cancel flow needs a real Context (it posts a notification) and is
 * exercised on-device; here we pin the two pieces that are pure and race-sensitive: the audit log's
 * atomic record, and the per-client approval key that keeps one client from cancelling another's.
 */
class ConcurrencyTest {

    @Test
    fun `concurrent audit records from many clients never drop an entry`() {
        AuditLog.entries.value = emptyList()
        val clients = 16
        val perClient = 10 // 160 total, under the 200-entry ring, so every one must survive
        val pool = Executors.newFixedThreadPool(clients)
        val start = CountDownLatch(1)
        val done = CountDownLatch(clients)
        repeat(clients) { c ->
            pool.submit {
                start.await() // release all workers at once, for real contention on the CAS loop
                repeat(perClient) { i -> AuditLog.record("device_info", "client-$c", true, "$c:$i") }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS), "workers did not finish in time")
        pool.shutdown()
        val entries = AuditLog.entries.value
        // A plain read-modify-write (value = …) instead of update{} would lose some of these.
        assertEquals(clients * perClient, entries.size, "a race dropped audit entries")
        assertEquals(clients * perClient, entries.map { it.detail }.toSet().size, "an entry was lost or duplicated")
    }

    @Test
    fun `approval keys isolate clients and id types, so one client cannot cancel another's`() {
        val a = ApprovalManager.rpcKey("client-1", "7", numeric = true)
        assertNotEquals(a, ApprovalManager.rpcKey("client-2", "7", numeric = true),
            "the same JSON-RPC id from two clients must not share an approval key")
        assertNotEquals(a, ApprovalManager.rpcKey("client-1", "7", numeric = false),
            "numeric 7 and string \"7\" are different requests")
        assertEquals(a, ApprovalManager.rpcKey("client-1", "7", numeric = true),
            "the key must be deterministic")
    }
}
