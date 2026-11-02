package com.example.animastor.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Layer flags for PUT /book/:id/layer-config.
 *
 * Android parity: web `features/generation/layerSync.ts` (`LayerFlags`).
 */
data class LayerFlags(
    val audio: Boolean = true,
    val image: Boolean = true,
    val video: Boolean = true,
    val vbook: Boolean = true
)

/**
 * Write side of the layer-config contour — one PUT per user action.
 *
 * E2E 02.11 (12:24:00.311, 12:39:24.724): a single toggle click produced
 * 2–3 PUTs, including a STALE snapshot (`image_enabled=true` for 0.36 s) —
 * the requests overlapped and the older body landed last, so the backend
 * briefly ran with a layer the user had already switched off.
 *
 * Rules (identical to web `createLayerConfigSync`):
 *  - serialized: at most one PUT in flight, so responses cannot land out of
 *    order and install an outdated snapshot;
 *  - every PUT carries the CURRENT desired flags, never a snapshot taken
 *    before an in-flight request; a change made meanwhile is sent once the
 *    in-flight PUT settles;
 *  - several setters of one action (e.g. "all layers") collapse into one
 *    PUT because only [flush] talks to the network and it re-reads [desired];
 *  - [State.pending] is true from the click until the server confirmed the
 *    final state — the UI disables the layer buttons for exactly that window;
 *  - a failed PUT keeps `pending` true and stores `lastError`; the next user
 *    request retries (no automatic retry storm).
 *
 * Pure JVM: no Android imports, `persist` is injected.
 * Parity: web `layerSync.ts` + `layerSync.test.ts` (same fixtures).
 */
class LayerConfigSync(
    initial: LayerFlags,
    private val persist: suspend (LayerFlags) -> Unit,
    private val onChange: (State) -> Unit = {}
) {
    data class State(
        val desired: LayerFlags,
        val confirmed: LayerFlags?,
        val inFlight: Boolean,
        val pending: Boolean,
        val lastError: String?
    )

    private val lock = Any()
    private val mutex = Mutex()

    private var desired: LayerFlags = initial
    private var confirmed: LayerFlags? = initial
    private var inFlight: Boolean = false
    private var lastError: String? = null

    fun state(): State = synchronized(lock) { snapshotLocked() }

    private fun snapshotLocked(): State {
        val pending = inFlight || confirmed == null || confirmed != desired
        return State(desired, confirmed, inFlight, pending, lastError)
    }

    private fun emit() {
        val snapshot = synchronized(lock) { snapshotLocked() }
        onChange(snapshot)
    }

    /**
     * Record the full flag set the user wants (the ViewModel merges its
     * signals itself — unlike the web patch helper); then run [flush].
     */
    fun request(next: LayerFlags) {
        synchronized(lock) { desired = next }
        emit()
    }

    /** Send the pending state; no-op when desired already equals confirmed. */
    suspend fun flush() {
        mutex.withLock {
            synchronized(lock) {
                if (confirmed != null && confirmed == desired) return@withLock
                inFlight = true
            }
            emit()
            try {
                while (true) {
                    val target = synchronized(lock) {
                        if (confirmed != null && confirmed == desired) null else desired
                    } ?: break
                    try {
                        persist(target)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        synchronized(lock) { lastError = e.message ?: e.toString() }
                        break
                    }
                    synchronized(lock) {
                        confirmed = target
                        lastError = null
                    }
                }
            } finally {
                synchronized(lock) { inFlight = false }
                emit()
            }
        }
    }

    /**
     * Adopt a fresh GET as the new baseline. No-op while a PUT is in flight
     * (an unsaved click must never be clobbered by a load).
     */
    fun reset(flags: LayerFlags): Boolean {
        val applied = synchronized(lock) {
            if (inFlight) return@synchronized false
            desired = flags
            confirmed = flags
            lastError = null
            true
        }
        if (applied) emit()
        return applied
    }
}
