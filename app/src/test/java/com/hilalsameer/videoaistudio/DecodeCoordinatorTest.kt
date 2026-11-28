package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.DecodeCoordinator
import com.hilalsameer.videoaistudio.media.MtAbortException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The detached-decode orchestration (0.9.5, TD-023), driven by
 * fake sleep tasks — the CI twin of the host harness in
 * vas-host-test/mt095 (which uses the brief's full 10 s decode).
 * The process gate is companion-level, so every test frees it
 * (drain or close) before it ends.
 */
class DecodeCoordinatorTest {

    /** Set [flag] after [ms] on a side thread; returns the thread. */
    private fun flipAfter(flag: AtomicBoolean, ms: Long): Thread {
        val t = Thread {
            Thread.sleep(ms)
            flag.set(true)
        }
        t.isDaemon = true
        t.start()
        return t
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(25)
        }
        return cond()
    }

    @Test(timeout = 30_000)
    fun normal_tasks_return_results_in_order() {
        val c = DecodeCoordinator()
        try {
            assertTrue(c.acquireGate { false })
            val out = mutableListOf<String>()
            for (s in listOf("a", "b", "c")) {
                out += c.runTask({ false }, poisonOnAbandon = true) { s }
            }
            assertEquals(listOf("a", "b", "c"), out)
            assertFalse(c.isPoisoned)
        } finally {
            c.releaseGate()
            c.close { c.releaseGate() }
        }
    }

    @Test(timeout = 30_000)
    fun cancel_abandons_promptly_and_orphan_drain_frees_the_gate() {
        val c1 = DecodeCoordinator()
        val c2 = DecodeCoordinator()
        try {
            assertTrue(c1.acquireGate { false })
            val cancel = AtomicBoolean(false)
            flipAfter(cancel, 250)
            val drained = AtomicBoolean(false)
            val start = System.currentTimeMillis()
            try {
                c1.runTask({ cancel.get() }, poisonOnAbandon = true, onAbandoned = {
                    drained.set(true)
                    c1.releaseGate()
                }) {
                    Thread.sleep(3000)
                    "late"
                }
                fail("the await should have been abandoned")
            } catch (e: MtAbortException) {
                // Expected: the screen-side wait ends, the decode does not.
            }
            val waited = System.currentTimeMillis() - start
            assertTrue("abandon took ${waited}ms of a 3000ms decode", waited < 2000)
            assertTrue(c1.isPoisoned)
            // The orphan finishes in the background; its drain
            // releases the gate, and a second engine proceeds.
            assertTrue(
                "drain never completed",
                waitUntil(6000) { drained.get() && !c1.isPoisoned }
            )
            assertTrue(c2.acquireGate { false })
        } finally {
            c2.releaseGate()
            c1.close { c1.releaseGate() }
            c2.close { c2.releaseGate() }
        }
    }

    @Test(timeout = 30_000)
    fun gate_wait_aborts_on_cancel_while_predecessor_drains() {
        val c1 = DecodeCoordinator()
        val c2 = DecodeCoordinator()
        try {
            assertTrue(c1.acquireGate { false })
            // Poison c1 with a slow decode abandoned at once.
            val cancel1 = AtomicBoolean(false)
            flipAfter(cancel1, 200)
            try {
                c1.runTask({ cancel1.get() }, poisonOnAbandon = true, onAbandoned = {
                    c1.releaseGate()
                }) {
                    Thread.sleep(3000)
                    "late"
                }
                fail("the await should have been abandoned")
            } catch (e: MtAbortException) {
                // Expected.
            }
            assertTrue(c1.isPoisoned)
            // c2 waits for the gate; its own cancel aborts the
            // wait cleanly while the orphan keeps draining.
            val cancel2 = AtomicBoolean(false)
            flipAfter(cancel2, 250)
            val start = System.currentTimeMillis()
            assertFalse(c2.acquireGate { cancel2.get() })
            assertTrue(System.currentTimeMillis() - start < 2000)
            // The drain still lands; then the gate is free.
            assertTrue("drain never completed", waitUntil(6000) { !c1.isPoisoned })
            assertTrue(c2.acquireGate { false })
        } finally {
            c2.releaseGate()
            c1.close { c1.releaseGate() }
            c2.close { c2.releaseGate() }
        }
    }

    @Test(timeout = 30_000)
    fun prepare_abandon_does_not_poison() {
        val c = DecodeCoordinator()
        try {
            assertTrue(c.acquireGate { false })
            val cancel = AtomicBoolean(false)
            flipAfter(cancel, 200)
            try {
                c.runTask({ cancel.get() }, poisonOnAbandon = false) {
                    Thread.sleep(1500)
                    "built"
                }
                fail("the construction await should have been abandoned")
            } catch (e: MtAbortException) {
                // Expected: the await ends; the build completes
                // into the void and stays usable.
            }
            assertFalse(c.isPoisoned)
            // And the coordinator still runs new work afterwards.
            assertEquals("ok", c.runTask({ false }, poisonOnAbandon = false) { "ok" })
        } finally {
            c.releaseGate()
            c.close { c.releaseGate() }
        }
    }
}
