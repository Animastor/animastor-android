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
import okio.buffer

/**
 * SSE client for POST /api/v1/ai/chat/stream (LLM Sharing Phase 3).
 *
 * Web parity: frontends/app/src/api/client.ts postChatStream.
 *
 * Performs a manual OkHttp call so HTTP errors (non-2xx) are inspected and
 * surfaced via [onError] with the sanitized backend code + HTTP status —
 * exactly like the web client (which reads the error body on !res.ok).
 * On 2xx, the text/event-stream body is parsed frame by frame (manual SSE
 * parsing, the allowed parity path) and dispatched via [onMeta]/[onDelta]/
 * [onDone]/[onError].
 *
 * Terminality guarantee: exactly one terminal call (onDone or onError) per
 * stream. Once done/error is delivered, [terminal] is set and no further
 * handler is invoked. Cancel invokes no handler.
 */
class AiChatSseClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = BuildConfig.BASE_URL.trimEnd('/')
) {

    private val gson: Gson = Gson()

    private var call: okhttp3.Call? = null
    @Volatile private var isActive = false
    @Volatile private var terminal = false

    /** Handlers set by the caller before [start]. */
    var onMeta: ((AiChatMeta) -> Unit)? = null
    var onDelta: ((String) -> Unit)? = null
    var onDone: ((AiChatDone) -> Unit)? = null
    var onError: ((AiChatError) -> Unit)? = null

    /** Start a stream for [request]. Stops any previous stream first. */
    fun start(request: AiChatRequest) {
        cancel()
        isActive = true
        terminal = false

        // contentType is null; the Content-Type header is set explicitly below.
        val body = RequestBody.create(null, gson.toJson(request))
        val req = Request.Builder()
            .url("$baseUrl/api/v1/ai/chat/stream")
            .post(body)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "text/event-stream")
            .build()

        call = httpClient.newCall(req)
        call?.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (!isActive || terminal) return
                terminal = true
                isActive = false
                Log.w("AiChatSseClient", "SSE stream failed", e)
                onError?.invoke(AiChatError(code = null, message = e.message ?: "stream failed", httpStatus = null))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!isActive || terminal) {
                    response.close()
                    return
                }
                try {
                    if (!response.isSuccessful) {
                        terminal = true
                        isActive = false
                        val httpStatus = response.code
                        var error = AiChatError(code = null, message = "HTTP $httpStatus", httpStatus = httpStatus)
                        val rb = response.body
                        if (rb != null) {
                            try {
                                error = parseErrorBody(rb.string())
                                error = error.copy(httpStatus = httpStatus)
                            } catch (_: Exception) {
                                // ignore unparseable body — keep transport message
                            }
                            rb.close()
                        }
                        response.close()
                        Log.w("AiChatSseClient", "SSE stream HTTP $httpStatus: ${error.message} (code=${error.code})")
                        onError?.invoke(error)
                        return
                    }

                    val rb = response.body
                    if (rb == null) {
                        terminal = true
                        isActive = false
                        response.close()
                        onError?.invoke(AiChatError(code = null, message = "empty response", httpStatus = response.code))
                        return
                    }

                    // Manual SSE parsing of the text/event-stream body (web parity:
                    // postChatStream splits the stream on '\n\n' into frames).
                    readSseEvents(rb.string())
                } catch (e: Exception) {
                    terminal = true
                    isActive = false
                    response.close()
                    Log.w("AiChatSseClient", "SSE stream failed", e)
                    onError?.invoke(AiChatError(code = null, message = e.message ?: "response failed", httpStatus = response.code))
                } finally {
                    if (!terminal) {
                        terminal = true
                        isActive = false
                    }
                }
            }
        })
    }

    /** Reads SSE frames from the whole body and dispatches them (manual SSE parsing). */
    private fun readSseEvents(body: String) {
        val frames = body.split("\n\n")
        var index = 0
        while (isActive && !terminal && index < frames.size) {
            val frame = frames[index]
            index++
            if (frame.isBlank()) continue
            val result = AiChatStream.parseFrame(frame)
            when (result) {
                is AiChatStreamResult.Meta -> {
                    val dataLine = frame.lines().firstOrNull { it.startsWith("data:") } ?: continue
                    val obj = gson.fromJson(dataLine.substringAfter("data:"), Any::class.java) as? Map<*, *>
                    onMeta?.invoke(AiChatMeta(
                        sessionId = obj?.get("session_id") as? String,
                        aiSource = obj?.get("ai_source") as? String
                    ))
                }
                is AiChatStreamResult.Delta -> onDelta?.invoke(result.text)
                is AiChatStreamResult.Done -> {
                    terminal = true
                    isActive = false
                    onDone?.invoke(result.done)
                }
                is AiChatStreamResult.Error -> {
                    terminal = true
                    isActive = false
                    onError?.invoke(result.error)
                }
                AiChatStreamResult.Skip -> {}
            }
        }
        // Stream ended normally without a done frame → empty done payload.
        if (isActive && !terminal) {
            terminal = true
            isActive = false
            onDone?.invoke(AiChatDone())
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
        terminal = true
        isActive = false
        call?.cancel()
        call = null
    }
}
