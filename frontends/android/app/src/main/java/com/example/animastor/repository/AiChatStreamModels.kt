package com.example.animastor.repository

/**
 * Pure models for the streaming AI chat contract (LLM Sharing Phase 3).
 * Web parity: packages/animastor-web-ai-chat/src/chatStream.ts.
 *
 * The backend may emit these SSE event types on POST /api/v1/ai/chat/stream:
 *   meta    { session_id?, ai_source? }   — first frame; ai_source is a SAFE
 *                                         token only ('private-local'|'shared'|
 *                                         'cloud'|'system'), never endpoint/owner.
 *   delta   { delta: string }             — incremental assistant text.
 *   done    { ...AiChatResponse fields }  — terminal frame with full response.
 *   error   { code?, message? }           — terminal error; code is a sanitized
 *                                         backend failure code.
 *
 * ai_source tokens the backend may put on meta/done frames:
 *   'private-local' | 'shared' | 'cloud' | 'system'
 */
data class AiChatSseFrame(
    val event: String = "", // meta | delta | done | error
    val data: String = ""
)

data class AiChatMeta(
    val sessionId: String? = null,
    val aiSource: String? = null
)

data class AiChatDone(
    val reply: String? = null,
    val bookId: String? = null,
    val sessionId: String? = null,
    val toolResults: List<Any>? = null,
    val patchesApplied: Int = 0
)

data class AiChatError(
    val code: String? = null,
    val message: String? = null
)

/** Result of applying one SSE frame to the stream state machine. */
sealed class AiChatStreamResult {
    object Meta : AiChatStreamResult()
    data class Delta(val text: String) : AiChatStreamResult()
    data class Done(val done: AiChatDone) : AiChatStreamResult()
    data class Error(val error: AiChatError) : AiChatStreamResult()
    object Skip : AiChatStreamResult() // heartbeat / unparseable frame
}
