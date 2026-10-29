@file:JvmName("AiChatStream")

package com.example.animastor.repository

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Pure AI chat streaming helpers (LLM Sharing Phase 3).
 * Web parity: packages/animastor-web-ai-chat/src/chatStream.ts.
 *
 * Zero Android / host dependencies — pure functions and a pure SSE frame
 * parser, unit-testable on the JVM. Host consumers (AiAssistantFragment,
 * AiChatSseClient) supply the UI callbacks.
 *
 * Contract:
 *   - sourceBadgeKey: the `ai_source` token → honest source badge string
 *     resource key (a SAFE token only — the backend never sends endpoint or
 *     owner detail).
 *   - streamErrorKey: sanitized backend error codes → honest localized string
 *     resource keys; unknown codes fall back to the backend's message.
 *   - isUserCancelled: true only when the user pressed stop — the UI keeps the
 *     partial answer and marks it cancelled instead of showing an error.
 *   - parseFrame: parses an SSE data block into an [AiChatStreamResult].
 */
object AiChatStream {

    // ─────────────────────────────────────────────────────────────────────
    // source badge mapping (chatStream.ts sourceBadgeKey)
    // ─────────────────────────────────────────────────────────────────────

    /** Map a safe ai_source token to the i18n string-resource key for the badge. */
    fun sourceBadgeKey(source: String?): String? = when (source) {
        "private-local" -> "ai_source_private"
        "shared" -> "ai_source_shared"
        "cloud" -> "ai_source_cloud"
        "system" -> "ai_source_system"
        else -> null
    }

    // ─────────────────────────────────────────────────────────────────────
    // error code mapping (chatStream.ts streamErrorKey)
    // ─────────────────────────────────────────────────────────────────────

    /** Map a sanitized backend failure code to the i18n string-resource key. */
    fun streamErrorKey(code: String?): String? = when (code) {
        "ai_unavailable" -> "ai_state_unavailable"
        "local_ai_not_ready" -> "ai_state_local_not_ready"
        "shared_unavailable" -> "ai_state_shared_unavailable"
        "connector_offline", "session_closed" -> "ai_state_offline"
        "runtime_unreachable" -> "ai_state_runtime_unreachable"
        "timeout" -> "ai_state_timeout"
        "busy" -> "ai_state_busy"
        "model_not_found" -> "ai_state_model_not_found"
        "stream_failed" -> "ai_state_stream_failed"
        "cancelled" -> "ai_cancelled"
        else -> null
    }

    // ─────────────────────────────────────────────────────────────────────
    // user-cancel detection (chatStream.ts isUserCancelled)
    // ─────────────────────────────────────────────────────────────────────

    /** True when the stream ended because the user pressed stop. */
    fun isUserCancelled(cancelled: Boolean): Boolean = cancelled

    // ─────────────────────────────────────────────────────────────────────
    // SSE frame parsing (mirrors web postChatStream frame handling)
    // ─────────────────────────────────────────────────────────────────────

    private val gson = Gson()

    /**
     * Parse one SSE data block (already split on '\n\n') into classified results.
     * Handles `event:` and `data:` prefixes, multi-line data, and JSON parsing.
     * Returns [AiChatStreamResult.Skip] for comments / keep-alives / unparseable
     * JSON so the caller can ignore them.
     */
    fun parseFrame(block: String): AiChatStreamResult {
        var eventName = "message"
        var data = ""
        for (line in block.lines()) {
            when {
                line.startsWith("event:") -> eventName = line.substringAfter("event:").trim()
                line.startsWith("data:") -> data += line.substringAfter("data:").trim()
            }
        }
        if (data.isBlank()) return AiChatStreamResult.Skip

        val parsed: Any?
        try {
            parsed = gson.fromJson(data, Any::class.java)
        } catch (_: JsonSyntaxException) {
            return AiChatStreamResult.Skip
        }

        return when (eventName) {
            "meta" -> {
                if (parsed !is Map<*, *>) return AiChatStreamResult.Skip
                AiChatStreamResult.Meta
            }
            "delta" -> {
                val obj = parsed as? Map<*, *>
                val delta = obj?.get("delta")
                if (delta is String) AiChatStreamResult.Delta(delta) else AiChatStreamResult.Skip
            }
            "done" -> {
                val obj = parsed as? Map<*, *>
                AiChatStreamResult.Done(
                    AiChatDone(
                        reply = obj?.get("reply") as? String,
                        bookId = obj?.get("book_id") as? String,
                        sessionId = obj?.get("session_id") as? String,
                        patchesApplied = (obj?.get("patches_applied") as? Number)?.toInt() ?: 0
                    )
                )
            }
            "error" -> {
                val obj = parsed as? Map<*, *>
                AiChatStreamResult.Error(
                    AiChatError(
                        code = obj?.get("code") as? String,
                        message = obj?.get("message") as? String
                    )
                )
            }
            else -> AiChatStreamResult.Skip
        }
    }
}
