package com.example.animastor.repository

import com.example.animastor.network.RetrofitClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Bootstrap idempotency (animastor-docs architecture/BOOTSTRAP_IDEMPOTENCY.md,
 * ba_1795695606197): one POST per start, no OkHttp re-send, a repeated tap is
 * ignored while the POST is in flight, 409 bootstrap_in_progress → follow the
 * running window. Web parity: web-generator-vbook test/bootstrap-idempotency.test.ts.
 */
class BootstrapIdempotencyTest {

    @Test fun conflict409IsRecognisedByErrorOrCode() {
        assertTrue(BootstrapConflict.isBootstrapInProgress(409, """{"error":"bootstrap_in_progress","session_id":"sess-A"}"""))
        assertTrue(BootstrapConflict.isBootstrapInProgress(409, """{"code":"bootstrap_in_progress"}"""))
        assertFalse(BootstrapConflict.isBootstrapInProgress(400, """{"error":"bootstrap_in_progress"}"""))
        assertFalse(BootstrapConflict.isBootstrapInProgress(409, """{"error":"layer_disabled"}"""))
        assertFalse(BootstrapConflict.isBootstrapInProgress(409, "not json"))
        assertFalse(BootstrapConflict.isBootstrapInProgress(409, null))
    }

    @Test fun sessionIdIsReadWhenPresent() {
        assertEquals("sess-A", BootstrapConflict.sessionId("""{"error":"bootstrap_in_progress","session_id":"sess-A"}"""))
        assertNull(BootstrapConflict.sessionId("""{"error":"bootstrap_in_progress","session_id":null}"""))
        assertNull(BootstrapConflict.sessionId(null))
    }

    @Test fun bootstrapClientNeverRetriesOnConnectionFailure() {
        val base = RetrofitClient.httpClient
        val noRetry = RetrofitClient.noRetryHttpClient
        assertTrue("the shared client keeps its retry", base.retryOnConnectionFailure)
        assertFalse("POST /bootstrap must not be re-sent by OkHttp", noRetry.retryOnConnectionFailure)
        assertSame(base.cookieJar, noRetry.cookieJar)
        assertSame(base.connectionPool, noRetry.connectionPool)
        assertEquals(base.readTimeoutMillis, noRetry.readTimeoutMillis)
        assertEquals(base.callTimeoutMillis, noRetry.callTimeoutMillis)
        assertEquals(base.interceptors.size, noRetry.interceptors.size)
    }

    private fun mainSource(rel: String): String {
        val f = listOf(
            File("src/main/java/com/example/animastor/$rel"),
            File("app/src/main/java/com/example/animastor/$rel"),
        ).firstOrNull { it.isFile } ?: error("source not found: $rel")
        return f.readText()
    }

    @Test fun repositorySendsWindowRunsThroughTheNoRetryApi() {
        val repo = mainSource("repository/Repository.kt")
        assertTrue(repo.contains("bootstrapApi.bootstrapBook(bookId)"))
        assertTrue(repo.contains("bootstrapApi.bootstrapNextWindow(bookId)"))
        assertTrue(repo.contains("bootstrapApi.resumeBootstrap(bookId)"))
        assertFalse(repo.contains("= api.bootstrapBook("))
        // the production factories pass the no-retry API
        for (vm in listOf("ui/GenerateViewModel.kt", "ui/PlaybackViewModel.kt")) {
            assertTrue(vm, mainSource(vm).contains("Repository(RetrofitClient.api, diskCache, RetrofitClient.bootstrapApi)"))
        }
    }

    @Test fun startVBookGenerationGuardsRepeatedTapsAndFollowsA409() {
        val vm = mainSource("ui/GenerateViewModel.kt")
        val start = vm.indexOf("fun startVBookGeneration(")
        assertTrue(start >= 0)
        val body = vm.substring(start, vm.indexOf("\n    fun ", start + 10).takeIf { it > 0 } ?: vm.length)
        // the guard returns BEFORE generationJob?.cancel() (which used to abort the first POST)
        val guard = body.indexOf("if (bootstrapInFlight)")
        val cancel = body.indexOf("generationJob?.cancel()")
        assertTrue(guard in 0 until cancel)
        // the flag is cleared in finally right after the POST
        assertTrue(Regex("""finally \{\s*bootstrapInFlight = false""").containsMatchIn(body))
        // 409 → poll, not teardown
        val conflict = body.indexOf("e is BootstrapInProgressException")
        assertTrue(conflict > 0)
        assertTrue(body.indexOf("pollVBookProgress(bid, token)", conflict) > conflict)
        assertTrue(body.indexOf("pollVBookProgress(bid, token)", conflict) < body.indexOf("clearVBookProgress()"))
    }

    @Test fun bootstrapResponseReadsTheNoOpFlag() {
        val gson = com.google.gson.Gson()
        val r = gson.fromJson(
            """{"book_id":"b","state":"BOOTSTRAPPED","already_bootstrapped":true,"characters":null,
               "chapters":[{"chapter":"ch-1","chapter_id":"ch-1","chapter_index":0,"scene_count":2}]}""",
            BootstrapResponse::class.java,
        )
        assertTrue(r.already_bootstrapped)
        assertEquals(0, r.characters)
        assertEquals("ch-1", r.chapters!!.single().chapter_id)
    }
}
