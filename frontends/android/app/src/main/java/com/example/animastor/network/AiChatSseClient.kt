package com.example.animastor.network

import android.util.Log
import com.example.animastor.BuildConfig
import com.example.animastor.repository.AiChatRequest
import com.example.animastor.repository.AiChatStream
import com.example.animastor.repository.AiChatDone
import com.example.animastor.repository.AiChatError
import com.example.animastor.repository.AiChatMeta
import com.example.animastor.repository.AiChatStreamResult
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * SSE client for POST /api/v1/ai/chat/stream (LLM Sharing Phase 3).
 *
 * Web parity: frontends/app/src/api/client.ts postChatStream.
 *
 * Sends the chat request as JSON, reads the text/event-stream response frame
 * by frame, and dispatches meta/delta/done/error to the handlers. Cancel
 * closes the EventSource; an [isActive] flag prevents stale callbacks from a
 * superseded stream session.
 */
class AiChatSseClient(private val httpClient: OkHttpClient) {

    private val gson: Gson = Gson()

    private var eventSource: EventSource? = null
    @Volatile private var isActive = false

    /** Handlers set by the caller before [start]. */
    var onMeta: ((AiChatMeta) -> Unit)? = null
    var onDelta: ((String) -> Unit)? = null
    var onDone: ((AiChatDone) -> Unit)? = null
    var onError: ((AiChatError) -> Unit)? = null

    /** Start a stream for [request]. Stops any previous stream first. */
    fun start(request: AiChatRequest) {
        cancel()
        isActive = true

        // contentType is null; the Content-Type header is set explicitly below.
        val body = RequestBody.create(null, gson.toJson(request))
        val req = Request.Builder()
            .url("${BuildConfig.BASE_URL.trimEnd('/')}/api/v1/ai/chat/stream")
            .post(body)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "text/event-stream")
            .build()

        eventSource = EventSources.createFactory(httpClient)
            .newEventSource(req, object : EventSourceListener() {

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    if (!isActive) return
                    val result = AiChatStream.parseFrame(
                        "${if (type.isNullOrBlank()) "message" else type}\ndata:$data"
                    )
                    when (result) {
                        is AiChatStreamResult.Meta -> {
                            val obj = gson.fromJson(data, Any::class.java) as? Map<*, *>
                            onMeta?.invoke(AiChatMeta(
                                sessionId = obj?.get("session_id") as? String,
                                aiSource = obj?.get("ai_source") as? String
                            ))
                        }
                        is AiChatStreamResult.Delta -> onDelta?.invoke(result.text)
                        is AiChatStreamResult.Done -> onDone?.invoke(result.done)
                        is AiChatStreamResult.Error -> onError?.invoke(result.error)
                        AiChatStreamResult.Skip -> {}
                    }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: okhttp3.Response?
                ) {
                    if (!isActive) return
                    Log.w("AiChatSseClient", "SSE stream failed", t)
                    isActive = false
                    onError?.invoke(AiChatError(code = null, message = t?.message ?: "stream failed"))
                }

                override fun onClosed(eventSource: EventSource) {
                    Log.i("AiChatSseClient", "SSE stream closed")
                    if (isActive) {
                        isActive = false
                        // Normal completion without a done frame → empty done payload.
                        onDone?.invoke(AiChatDone())
                    }
                }
            })
    }

    /** Cancel the in-flight stream. Safe to call multiple times. */
    fun cancel() {
        isActive = false
        eventSource?.cancel()
        eventSource = null
    }
}
