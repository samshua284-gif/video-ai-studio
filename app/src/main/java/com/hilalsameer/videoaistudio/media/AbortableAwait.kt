package com.hilalsameer.videoaistudio.media

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Raised when an [AbortableAwait] is released by its abort check
 * rather than by the deferred completing. A plain [Exception] (not
 * a CancellationException) so callers can tell "the owner asked to
 * stop" apart from coroutine machinery cancelling the job itself.
 */
class MtAbortException : Exception("operation aborted by owner request")

/**
 * Awaits a bridge deferred with an owner-abort escape hatch
 * (TD-019). The MT engine's WebView bridge offers no way to
 * interrupt a load or a segment once posted, and its pathological
 * timeouts (15 min load / 20 min segment) are far too long for a
 * stop button to wait on: in 0.9.0 the cancel flag was only
 * consulted after those awaits returned, so «جارٍ الإيقاف» could
 * hang for many minutes.
 *
 * This await polls the deferred in short slices and checks
 * [shouldAbort] between them, so an abort lands within about
 * [POLL_MS]. The timeout is unchanged — it still applies, via
 * [withTimeout], as the ceiling for the non-abort path and throws
 * the same TimeoutCancellationException the old code threw.
 */
object AbortableAwait {

    const val POLL_MS = 250L

    suspend fun <T> await(
        deferred: Deferred<T>,
        timeoutMs: Long,
        shouldAbort: () -> Boolean
    ): T {
        return withTimeout(timeoutMs) {
            while (!deferred.isCompleted) {
                if (shouldAbort()) throw MtAbortException()
                delay(POLL_MS)
            }
            deferred.await()
        }
    }
}
