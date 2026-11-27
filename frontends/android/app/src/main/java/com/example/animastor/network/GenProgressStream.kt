package com.example.animastor.network

import android.util.Log
import com.example.animastor.repository.GenProgress
import com.example.animastor.repository.GenerateApiV2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * SSE client of the generate API v2: `GET /api/v1/book/{bookId}/generate/stream`
 * (ADR ORCHESTRATION_V2 §9, §11.1). Web parity: generateStore runGenStream.
 *
 * The server sends `{type:"gen_progress", reason:"connect"|"notify", progress}`:
 * the fresh §9 aggregate right after every connect / reconnect, then one per
 * change (PG NOTIFY on the server — no Redis on this path). Each aggregate goes
 * to [onGenProgress]. Reconnects with backoff 1s → 2s → 4s → 8s → 15s cap while
 * active; 401/403/404/409 (e.g. book_not_v2) stop it. The VBook / analysis
 * events stay on [ProgressStream] (/progress-stream) until задача H.
 */
class GenProgressStream(
    private val scope: CoroutineScope
) {
    private var eventSource: EventSource? = null
    @Volatile private var isActive = false
    private var retryCount = 0
    private var reconnectJob: Job? = null
    private var currentBookId: String = ""
    @Volatile private var epoch = 0

    /** Called (on OkHttp's thread) with the aggregate of every event. */
    var onGenProgress: (GenProgress) -> Unit = {}

    fun start(bookId: String) {
        if (bookId.isBlank()) return
        cancel()
        currentBookId = bookId
        isActive = true
        retryCount = 0
        connect()
    }

    fun cancel() {
        epoch++
        isActive = false
        reconnectJob?.cancel()
        eventSource?.cancel()
        eventSource = null
        currentBookId = ""
        retryCount = 0
    }

    private fun connect() {
        val bookId = currentBookId
        if (bookId.isBlank() || !isActive) return
        val myEpoch = epoch
        val url = "${RetrofitClient.baseUrl.trimEnd('/')}/api/v1/book/$bookId/generate/stream"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-store")
            .build()
        val factory = EventSources.createFactory(RetrofitClient.streamHttpClient)
        eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.i(TAG, "connected for book $bookId")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (!isActive || epoch != myEpoch || data.isBlank()) return
                val evt = GenerateApiV2.parseGenStreamEvent(data, RetrofitClient.gson) ?: return
                retryCount = 0
                evt.progress?.let { onGenProgress(it) }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (!isActive || epoch != myEpoch) return
                val code = response?.code
                if (code != null && GIVE_UP_CODES.contains(code)) {
                    Log.w(TAG, "book $bookId: HTTP $code — not retrying")
                    return
                }
                Log.w(TAG, "disconnected for book $bookId (retry=$retryCount): ${t?.message ?: code}")
                scheduleReconnect(myEpoch)
            }

            override fun onClosed(eventSource: EventSource) {
                if (isActive && epoch == myEpoch) scheduleReconnect(myEpoch)
            }
        })
    }

    private fun scheduleReconnect(myEpoch: Int) {
        val delayMs = (1_000L * (1L shl minOf(retryCount, 4))).coerceAtMost(15_000L)
        retryCount++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (isActive && epoch == myEpoch) connect()
        }
    }

    companion object {
        private const val TAG = "GenProgressStream"
        /** 409 book_not_v2, auth / not found — the stream will not work for this book. */
        val GIVE_UP_CODES = setOf(401, 403, 404, 409)
    }
}
