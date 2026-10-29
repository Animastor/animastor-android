package com.example.animastor.network

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
import okio.BufferedSource

/**
 * SSE client for POST /api/v1/ai/chat/stream (LLM Sharing Phase 3).
 *
 * Web parity: frontends/app/src/api/client.ts postChatStream.
 *
 * Performs a manual OkHttp call so HTTP errors (non-2xx) are inspected and
 * surfaced via [onError] with the sanitized backend code + HTTP status —
 * exactly like the web client (which reads the error body on !res.ok).
 * On 2xx, the text/event-stream body is read line by line from
 * `rb.source()`; an empty line ends a frame, which is dispatched immediately
 * (web: postChatStream reader loop). A trailing frame at EOF is dispatched too.
 *
 * [httpClient] must not carry a BODY-level HttpLoggingInterceptor — in OkHttp
 * 4.x it buffers the whole body before returning it (use
 * RetrofitClient.streamHttpClient).
 *
 * Terminality guarantee: exactly one terminal call (onDone or onError) per
 * stream. Each [start] gets its own [StreamState], so a cancelled/superseded
 * stream can never fire handlers; exceptions after cancel are swallowed.
 */
class AiChatSseClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = BuildConfig.BASE_URL.trimEnd('/')
) {

    private val gson: Gson = Gson()

    /** Per-stream state: [live] is false once the stream is cancelled or terminal. */
    private class StreamState(val call: okhttp3.Call) {
        @Volatile var live = true
    }

    @Volatile private var current: StreamState? = null

    /** Handlers set by the caller before [start]. */
    var onMeta: ((AiChatMeta) -> Unit)? = null
    var onDelta: ((String) -> Unit)? = null
    var onDone: ((AiChatDone) -> Unit)? = null
    var onError: ((AiChatError) -> Unit)? = null

    /** Start a stream for [request]. Stops any previous stream first. */
    fun start(request: AiChatRequest) {
        cancel()

        val body = RequestBody.create(null, gson.toJson(request))
        val req = Request.Builder()
            .url("$baseUrl/api/v1/ai/chat/stream")
            .post(body)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "text/event-stream")
            .build()

        val state = StreamState(httpClient.newCall(req))
        current = state
        state.call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                // Cancelled / superseded streams fire no handler.
                terminate(state) { onError?.invoke(AiChatError(code = null, message = e.message ?: "stream failed", httpStatus = null)) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    try {
                        deliverResponse(state, it)
                    } catch (e: Exception) {
                        // After cancel the read fails — swallow silently; report
                        // genuine failures exactly once.
                        terminate(state) { onError?.invoke(AiChatError(code = null, message = e.message ?: "response failed", httpStatus = it.code)) }
                    }
                }
            }
        })
    }

    /** Marks [state] terminal and runs [handler] only if it was still live (exactly-once). */
    private inline fun terminate(state: StreamState, handler: () -> Unit) {
        synchronized(state) {
            if (!state.live) return
            state.live = false
        }
        handler()
    }

    /** Handles a single response: HTTP errors → onError, 2xx → frame dispatch. */
    private fun deliverResponse(state: StreamState, response: okhttp3.Response) {
        if (!state.live) return
        val rb = response.body
        if (!response.isSuccessful) {
            val httpStatus = response.code
            var error = AiChatError(code = null, message = "HTTP $httpStatus", httpStatus = httpStatus)
            if (rb != null) {
                try {
                    error = parseErrorBody(rb.string()).copy(httpStatus = httpStatus)
                } catch (_: Exception) {
                    // ignore unparseable body — keep transport message
                }
            }
            terminate(state) { onError?.invoke(error) }
            return
        }
        if (rb == null) {
            terminate(state) { onError?.invoke(AiChatError(code = null, message = "empty response", httpStatus = response.code)) }
            return
        }
        readSseEvents(state, rb.source())
    }

    /** Reads SSE frames line by line and dispatches each as soon as its blank line arrives. */
    private fun readSseEvents(state: StreamState, source: BufferedSource) {
        val frame = StringBuilder()
        while (state.live) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty()) {
                // Empty line = end of frame → dispatch immediately.
                dispatchFrame(state, frame.toString())
                frame.setLength(0)
            } else {
                if (frame.isNotEmpty()) frame.append('\n')
                frame.append(line)
            }
        }
        // Rest after EOF = last frame (web: if (buffer.trim()) handleFrame(buffer)).
        if (frame.isNotEmpty()) dispatchFrame(state, frame.toString())
        // Stream ended normally without a done frame → empty done payload.
        terminate(state) { onDone?.invoke(AiChatDone()) }
    }

    /** Parses one accumulated SSE frame block and dispatches it to its handler. */
    private fun dispatchFrame(state: StreamState, block: String) {
        if (!state.live) return
        if (block.isBlank()) return
        when (val result = AiChatStream.parseFrame(block)) {
            is AiChatStreamResult.Meta -> {
                val dataLine = block.lines().firstOrNull { it.startsWith("data:") } ?: return
                val obj = gson.fromJson(dataLine.substringAfter("data:"), Any::class.java) as? Map<*, *>
                onMeta?.invoke(AiChatMeta(
                    sessionId = obj?.get("session_id") as? String,
                    aiSource = obj?.get("ai_source") as? String
                ))
            }
            is AiChatStreamResult.Delta -> onDelta?.invoke(result.text)
            is AiChatStreamResult.Done -> terminate(state) { onDone?.invoke(result.done) }
            is AiChatStreamResult.Error -> terminate(state) { onError?.invoke(result.error) }
            AiChatStreamResult.Skip -> {}
        }
    }

    /** Parse an error response body (web parity: postChatStream reads the error body on !res.ok). */
    fun parseErrorBody(raw: String): AiChatError {
        var code: String? = null
        var message: String? = raw
        try {
            val obj = gson.fromJson(raw, Any::class.java) as? Map<*, *>
            message = obj?.get("error") as? String
                ?: obj?.get("message") as? String
                ?: message
            code = obj?.get("code") as? String
        } catch (_: Exception) {
            // ignore unparseable body — keep raw text as message
        }
        return AiChatError(code = code, message = message)
    }

    /** Cancel the in-flight stream. Safe to call multiple times. Invokes no handler. */
    fun cancel() {
        val state = current ?: return
        current = null
        synchronized(state) { state.live = false }
        state.call.cancel()
    }
}
