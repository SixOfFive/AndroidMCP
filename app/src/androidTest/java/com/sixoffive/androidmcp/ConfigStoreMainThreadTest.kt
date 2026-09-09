package com.sixoffive.androidmcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sixoffive.androidmcp.core.ConfigStore
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `ConfigStore.currentBlocking()` called from the main thread, which is where it actually runs.
 *
 * It is `runBlocking { flow.first() }` over a DataStore, and [com.sixoffive.androidmcp.BootReceiver]
 * calls it from `onReceive` — on the main thread, inside the 10 s broadcast window. If that read
 * ever needs the main looper to make progress it deadlocks, and the symptom is not an exception
 * but an ANR at boot: the server silently fails to start, on a path that by definition nobody is
 * watching. A JVM test cannot see this at all, because there is no Looper to deadlock against.
 *
 * The assertion is a TIMEOUT rather than a return value — a deadlock has no exception to catch.
 *
 * Confirmed falsifying, unlike the capture tests beside it: rewritten as
 * `runBlocking { withContext(Dispatchers.Main) { flow.first() } }` — a plausible refactor — the run
 * goes red. The failure arrives as a process crash after the ANR watchdog fires rather than as
 * this test's own 10 s message, because the main thread is wedged and the instrumentation dies
 * with it. Red is the signal; do not expect a tidy assertion message.
 */
@RunWith(AndroidJUnit4::class)
class ConfigStoreMainThreadTest {

    @Before
    fun initStore() {
        ConfigStore.init(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun currentBlockingDoesNotDeadlockOnTheMainThread() {
        val done = CountDownLatch(1)
        val result = AtomicReference<Any?>(null)

        // The main-thread call is driven from a SPAWNED thread, so the test thread stays free to
        // time it out. Calling runOnMainSync directly would block the test thread as well, and a
        // deadlock would hang the whole run to the instrumentation timeout instead of failing
        // this one test with a usable message.
        Thread {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                result.set(runCatching { ConfigStore.currentBlocking() }.getOrElse { it })
            }
            done.countDown()
        }.start()

        assertTrue(
            done.await(10, TimeUnit.SECONDS),
            "currentBlocking() did not return on the main thread within 10 s — this is the boot ANR",
        )
        val value = result.get()
        assertTrue(value !is Throwable, "currentBlocking() threw on the main thread: $value")
        assertNotNull(value, "currentBlocking() returned null")
    }

    @Test
    fun currentBlockingIsRepeatableFromTheMainThread() {
        // The boot path can call it more than once (BootReceiver, then the app-launch resume).
        repeat(3) {
            val done = CountDownLatch(1)
            val result = AtomicReference<Any?>(null)
            Thread {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    result.set(runCatching { ConfigStore.currentBlocking() }.getOrElse { it })
                }
                done.countDown()
            }.start()
            assertTrue(done.await(10, TimeUnit.SECONDS), "call ${it + 1} deadlocked on the main thread")
            assertTrue(result.get() !is Throwable, "call ${it + 1} threw: ${result.get()}")
        }
    }
}
