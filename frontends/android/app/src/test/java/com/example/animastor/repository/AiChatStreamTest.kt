package com.example.animastor.repository

import com.example.animastor.repository.AiChatDone
import com.example.animastor.repository.AiChatError
import com.example.animastor.repository.AiChatStreamResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiChatStreamTest {

    // ── sourceBadgeKey (mirrors web chatStream.test.ts source-badge tests) ──

    @Test
    fun sourceBadgeKey_mapsSafeTokens() {
        assertEquals("ai_source_shared", AiChatStream.sourceBadgeKey("shared"))
        assertEquals("ai_source_private", AiChatStream.sourceBadgeKey("private-local"))
        assertEquals("ai_source_cloud", AiChatStream.sourceBadgeKey("cloud"))
        assertEquals("ai_source_system", AiChatStream.sourceBadgeKey("system"))
    }

    @Test
    fun sourceBadgeKey_unknownTokenReturnsNull() {
        assertNull(AiChatStream.sourceBadgeKey("unknown-garbage"))
        assertNull(AiChatStream.sourceBadgeKey("endpoint"))
        assertNull(AiChatStream.sourceBadgeKey("owner"))
        assertNull(AiChatStream.sourceBadgeKey("connector"))
        assertNull(AiChatStream.sourceBadgeKey("workspace"))
        assertNull(AiChatStream.sourceBadgeKey(null))
    }

    // ── streamErrorKey (mirrors web chatStream.test.ts error-code tests) ──

    @Test
    fun streamErrorKey_mapsSanitizedCodes() {
        assertEquals("ai_state_unavailable", AiChatStream.streamErrorKey("ai_unavailable"))
        assertEquals("ai_state_local_not_ready", AiChatStream.streamErrorKey("local_ai_not_ready"))
        assertEquals("ai_state_shared_unavailable", AiChatStream.streamErrorKey("shared_unavailable"))
        assertEquals("ai_state_offline", AiChatStream.streamErrorKey("connector_offline"))
        assertEquals("ai_state_offline", AiChatStream.streamErrorKey("session_closed"))
        assertEquals("ai_state_runtime_unreachable", AiChatStream.streamErrorKey("runtime_unreachable"))
        assertEquals("ai_state_timeout", AiChatStream.streamErrorKey("timeout"))
        assertEquals("ai_state_busy", AiChatStream.streamErrorKey("busy"))
        assertEquals("ai_state_model_not_found", AiChatStream.streamErrorKey("model_not_found"))
        assertEquals("ai_state_stream_failed", AiChatStream.streamErrorKey("stream_failed"))
        assertEquals("ai_cancelled", AiChatStream.streamErrorKey("cancelled"))
    }

    @Test
    fun streamErrorKey_unknownCodeReturnsNull() {
        assertNull(AiChatStream.streamErrorKey("mystery_code"))
        assertNull(AiChatStream.streamErrorKey(null))
    }

    // ── isUserCancelled (mirrors web chatStream.test.ts cancel tests) ──

    @Test
    fun isUserCancelled_userStop() {
        assertEquals(true, AiChatStream.isUserCancelled(true))
    }

    @Test
    fun isUserCancelled_notUser() {
        assertEquals(false, AiChatStream.isUserCancelled(false))
    }

    // ── parseFrame (mirrors web postChatStream frame handling) ──

    @Test
    fun parseFrame_meta() {
        val res = AiChatStream.parseFrame("event:meta\ndata:{\"session_id\":\"s1\",\"ai_source\":\"private-local\"}")
        assertEquals(AiChatStreamResult.Meta::class.java, res.javaClass)
    }

    @Test
    fun parseFrame_delta() {
        val res = AiChatStream.parseFrame("event:delta\ndata:{\"delta\":\"Hello\"}")
        assertEquals("Hello", (res as AiChatStreamResult.Delta).text)
    }

    @Test
    fun parseFrame_done() {
        val res = AiChatStream.parseFrame("event:done\ndata:{\"reply\":\"Hi\",\"book_id\":\"b1\",\"patches_applied\":2}")
        val done = (res as AiChatStreamResult.Done).done
        assertEquals("Hi", done.reply)
        assertEquals("b1", done.bookId)
        assertEquals(2, done.patchesApplied)
    }

    @Test
    fun parseFrame_error() {
        val res = AiChatStream.parseFrame("event:error\ndata:{\"code\":\"timeout\",\"message\":\"AI timed out\"}")
        val err = (res as AiChatStreamResult.Error).error
        assertEquals("timeout", err.code)
        assertEquals("AI timed out", err.message)
    }

    @Test
    fun parseFrame_skipBlankComment() {
        assertEquals(AiChatStreamResult.Skip::class.java, AiChatStream.parseFrame("").javaClass)
        assertEquals(AiChatStreamResult.Skip::class.java, AiChatStream.parseFrame("   ").javaClass)
    }

    @Test
    fun parseFrame_skipUnparseableJson() {
        assertEquals(AiChatStreamResult.Skip::class.java, AiChatStream.parseFrame("event:delta\ndata:not-json{").javaClass)
    }

    @Test
    fun parseFrame_deltaWithWhitespace() {
        val res = AiChatStream.parseFrame("event:delta\r\ndata:{\"delta\":\"  spaced  \"}")
        assertEquals("  spaced  ", (res as AiChatStreamResult.Delta).text)
    }

    // ── displayTextFor (web AiAssistantPage onDone priority) ──

    @Test
    fun displayTextFor_doneReplyWins() {
        val done = AiChatDone(reply = "Done reply")
        assertEquals("Done reply", AiChatStream.displayTextFor(done, "streamed", "fallback"))
    }

    @Test
    fun displayTextFor_streamedWhenNoReply() {
        val done = AiChatDone(reply = null)
        assertEquals("streamed", AiChatStream.displayTextFor(done, "streamed", "fallback"))
    }

    @Test
    fun displayTextFor_fallbackWhenNeither() {
        val done = AiChatDone(reply = null)
        assertEquals("fallback", AiChatStream.displayTextFor(done, "", "fallback"))
    }

    @Test
    fun displayTextFor_emptyReplyTreatedAsMissing() {
        val done = AiChatDone(reply = "")
        assertEquals("streamed", AiChatStream.displayTextFor(done, "streamed", "fallback"))
    }
}
