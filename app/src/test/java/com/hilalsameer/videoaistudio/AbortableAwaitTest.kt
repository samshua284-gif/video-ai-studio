package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.AbortableAwait
import com.hilalsameer.videoaistudio.media.MtAbortException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** TD-019: the cancel escape hatch must release a stuck bridge
 *  await in about a poll slice, while the old timeout behaviour
 *  is preserved for the non-abort path. Timing bounds are
 *  deliberately generous — the point is "seconds, not minutes". */
class AbortableAwaitTest {

    @Test
    fun completesWithTheDeferredValue() = runBlocking {
        val d = CompletableDeferred<String>()
        launch { delay(100); d.complete("ok") }
        val v = AbortableAwait.await(d, 10_000) { false }
        assertEquals("ok", v)
    }

    @Test
    fun abortReleasesPromptly() = runBlocking {
        val d = CompletableDeferred<String>() // never completes
        val abort = AtomicBoolean(false)
        launch { delay(300); abort.set(true) }
        val t0 = System.nanoTime()
        try {
            AbortableAwait.await(d, 60_000) { abort.get() }
            fail("expected MtAbortException")
        } catch (e: MtAbortException) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("abort took ${ms}ms", ms < 5_000)
        }
    }

    @Test
    fun timeoutStillAppliesWithoutAbort() = runBlocking {
        val d = CompletableDeferred<String>() // never completes
        val t0 = System.nanoTime()
        try {
            AbortableAwait.await(d, 600) { false }
            fail("expected TimeoutCancellationException")
        } catch (e: TimeoutCancellationException) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("timeout fired at ${ms}ms", ms >= 500 && ms < 10_000)
        }
    }

    @Test
    fun exceptionalDeferredPropagatesItsError() = runBlocking {
        val d = CompletableDeferred<String>()
        d.completeExceptionally(IllegalStateException("boom"))
        try {
            AbortableAwait.await(d, 10_000) { false }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }
}
