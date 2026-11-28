package com.hilalsameer.videoaistudio.media

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestration for work that must live on ONE dedicated thread
 * (0.9.5, TD-023): the whisper recognizer's whole life —
 * construction, every stream's decode, the recognizer's own
 * release — runs on a single-thread executor owned here, and the
 * job thread only submits tasks and polls their futures.
 *
 * Why: a single chunk's sherpa decode is one long, opaque,
 * uninterruptible native call (atoms up to 25 s of speech, the
 * medium model). Until 0.9.5 the job thread sat INSIDE that call,
 * so the progress counter could not move during a chunk and a
 * cancel pressed mid-chunk waited for the whole decode — the
 * owner's two standing field complaints. Detaching the decode
 * changes neither the atoms nor the total time; it changes who
 * waits, and how the wait can end.
 *
 * The await is [AbortableAwait]'s pattern — poll in 250 ms
 * slices, raise [MtAbortException] when the owner's cancel flag
 * rises — applied to a [CompletableFuture] instead of a
 * coroutine Deferred, and WITHOUT AbortableAwait's timeout
 * ceiling: that ceiling exists because the MT bridge's awaits
 * needed one, while a decode has no honest ceiling (TD-023
 * states the residual bound instead: a native decode that hung
 * forever would gate transcription until process death — no
 * fake timeout, no unsafe kill).
 *
 * Poison and drain: abandoning a decode await does not touch
 * the running task. The coordinator marks itself poisoned; when
 * the orphaned task returns on the decode thread, its result is
 * discarded, the caller's drain callback runs THERE (the engine
 * releases the recognizer and the process gate), the poison
 * clears, and — if [close] was already called — the close's
 * finalise runs and the executor shuts down. No new decode may
 * start on a poisoned coordinator.
 *
 * The process gate is a companion-level fair semaphore: at most
 * one recognizer is alive in the process across all coordinator
 * instances. It is acquired before construction and released
 * only when the recognizer is actually released (normal close,
 * or the poison drain).
 */
class DecodeCoordinator(private val pollMs: Long = POLL_MS) {

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "vas-decode").apply { isDaemon = true }
        }

    /** True while an abandoned decode is still draining. */
    @Volatile
    var isPoisoned: Boolean = false
        private set

    /** True once [close] has been called. */
    @Volatile
    var isClosed: Boolean = false
        private set

    /** Whether THIS coordinator currently holds the process gate. */
    @Volatile
    private var gateHeld = false

    /** Close-time cleanup deferred until a poison drain ends. */
    @Volatile
    private var pendingFinalise: (() -> Unit)? = null

    /**
     * Take the process gate, waiting in poll slices while a
     * predecessor's recognizer is still alive (e.g. a poisoned
     * engine still draining). Returns false when [isCancelled]
     * fires during the wait — the predecessor drains regardless.
     * Idempotent for the current holder.
     */
    fun acquireGate(isCancelled: () -> Boolean): Boolean {
        if (gateHeld) return true
        while (true) {
            if (isCancelled()) return false
            try {
                if (GATE.tryAcquire(pollMs, TimeUnit.MILLISECONDS)) {
                    gateHeld = true
                    return true
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    /** Free the process gate. Idempotent; safe from any thread. */
    fun releaseGate() {
        synchronized(this) {
            if (!gateHeld) return
            gateHeld = false
        }
        GATE.release()
    }

    /**
     * Wait until a poison on THIS coordinator has drained (its
     * orphan returned and the drain callback ran). Returns false
     * when [isCancelled] fires during the wait.
     */
    fun awaitUnpoisoned(isCancelled: () -> Boolean): Boolean {
        while (isPoisoned) {
            if (isCancelled()) return false
            try {
                Thread.sleep(pollMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    /**
     * Submit [task] to the decode thread and await it in
     * [pollMs] slices, polling [isCancelled] between them.
     *
     * When the cancel flag rises first, the await is abandoned
     * within one slice and [MtAbortException] is thrown; the
     * task itself keeps running on the decode thread. With
     * [poisonOnAbandon] the coordinator poisons itself and, when
     * the orphan returns, runs [onAbandoned] on the decode
     * thread (the drain), then clears the poison. Without it
     * (construction), the task simply completes into the void —
     * its product stays valid and owned by the caller.
     *
     * A task that throws has its exception rethrown here
     * unwrapped, exactly as if it had run on this thread.
     */
    fun <T> runTask(
        isCancelled: () -> Boolean,
        poisonOnAbandon: Boolean,
        onAbandoned: (() -> Unit)? = null,
        task: () -> T
    ): T {
        if (poisonOnAbandon) {
            check(!isPoisoned) { "decode submitted while a previous decode is still draining" }
        }
        val future = CompletableFuture<T>()
        val abandoned = AtomicBoolean(false)
        try {
            executor.execute {
                val result = try {
                    Result.success(task())
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                if (abandoned.get() && poisonOnAbandon) {
                    // The drain, on the decode thread: the
                    // orphan's result is discarded (the future
                    // completes into an await nobody holds), the
                    // caller's cleanup runs here where the
                    // recognizer lives, then the poison clears.
                    try { onAbandoned?.invoke() } catch (_: Throwable) { }
                    isPoisoned = false
                    if (isClosed) {
                        val fin = pendingFinalise
                        pendingFinalise = null
                        if (fin != null) {
                            try { fin.invoke() } catch (_: Throwable) { }
                        }
                        try { executor.shutdown() } catch (_: Throwable) { }
                    }
                }
                result.onSuccess { future.complete(it) }
                result.onFailure { future.completeExceptionally(it) }
            }
        } catch (e: RejectedExecutionException) {
            throw IllegalStateException("decode executor is shut down", e)
        }
        while (true) {
            try {
                return future.get(pollMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                if (isCancelled()) {
                    abandoned.set(true)
                    if (poisonOnAbandon) isPoisoned = true
                    throw MtAbortException()
                }
            } catch (e: ExecutionException) {
                throw (e.cause ?: e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                abandoned.set(true)
                if (poisonOnAbandon) isPoisoned = true
                throw MtAbortException()
            }
        }
    }

    /**
     * Close the coordinator. [finalise] runs on the decode
     * thread (it is where the recognizer lives) and releases the
     * gate via [releaseGate]. When a poison is still draining,
     * the finalise is deferred until the drain completes the
     * orphan; otherwise it is submitted behind any queued work.
     * Either way the executor shuts down right after it. A
     * second call is a no-op.
     */
    fun close(finalise: () -> Unit) {
        if (isClosed) return
        isClosed = true
        if (isPoisoned) {
            pendingFinalise = finalise
            return
        }
        try {
            executor.execute {
                try { finalise.invoke() } catch (_: Throwable) { }
                try { executor.shutdown() } catch (_: Throwable) { }
            }
        } catch (e: RejectedExecutionException) {
            // The executor is already gone; nothing can run the
            // finalise on the decode thread. Reachable only on a
            // double-shutdown race.
        }
    }

    companion object {
        /** Cancel-poll slice, the same 250 ms as [AbortableAwait]. */
        const val POLL_MS = 250L

        /** The one-recognizer-per-process gate (TD-023). */
        private val GATE = Semaphore(1, true)
    }
}
