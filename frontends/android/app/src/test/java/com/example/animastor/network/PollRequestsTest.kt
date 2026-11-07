package com.example.animastor.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * О1 (reports/2026-11-07/orch-v2-image-scene-run.md). Same fixtures as web
 * `frontends/app/src/api/client.nostore.test.ts` and `state/panelRefresh.test.ts`.
 */
class PollRequestsTest {

    private val pollPaths = listOf(
        "/book/ba_1/progress-panel",
        "/book/ba_1/progress-stream",
        "/book/ba_1/assets-state",
        "/book/ba_1/status",
        "/book/ba_1/agent-status",
        "/book/ba_1/layer-config",
        "/book/ba_1/generation-status",
        "/book/ba%201/progress-panel?x=1",
        "/scene/ba_1/ch-1/sc-1/status",
        "/scene/ba_1/ch-1/sc-1/status?build_id=b1",
        "/worker/counts",
        "/generate/commands/abc",
        "/generate",
        "/api/v1/book/ba_1/progress-panel",
    )
    private val cacheablePaths = listOf(
        "/book/ba_1",
        "/books",
        "/scene/ba_1/ch-1/sc-1/waveform?build_id=b1",
        "/scene/ba_1/ch-1/sc-1/storyboard",
        "/scene/ba_1/ch-1/sc-1/timings",
        "/connectors/x/status-page",
        "/worker/counts-history",
        "/generated/x",
    )

    @Test
    fun isNoStorePath_matchesPollingAndRestorePaths() {
        for (p in pollPaths) assertTrue(p, PollRequests.isNoStorePath(p))
        for (p in cacheablePaths) assertFalse(p, PollRequests.isNoStorePath(p))
    }

    @Test
    fun sseEventRequestsPanelRefresh_sameFixturesAsWeb() {
        assertTrue(PollRequests.sseEventRequestsPanelRefresh("generation_complete", null))
        assertTrue(PollRequests.sseEventRequestsPanelRefresh("progress", "c-1"))
        assertFalse(PollRequests.sseEventRequestsPanelRefresh("progress", null))
        assertFalse(PollRequests.sseEventRequestsPanelRefresh("progress", ""))
        assertFalse(PollRequests.sseEventRequestsPanelRefresh("vbook", "c-1"))
        assertFalse(PollRequests.sseEventRequestsPanelRefresh("import_complete", null))
        assertFalse(PollRequests.sseEventRequestsPanelRefresh(null, null))
    }

    @Test
    fun pollTimeout_is10s() {
        assertEquals(10_000L, PollRequests.POLL_REQUEST_TIMEOUT_MS)
    }

    @Test
    fun appClient_hasNoHttpCache_andTheNoStoreInterceptor() {
        val c = RetrofitClient.httpClient
        assertNull("OkHttp cache must stay off for the app client", c.cache)
        assertTrue(c.interceptors.any { it is NoStoreInterceptor })
    }

    @Test
    fun noStoreInterceptor_setsHeaderOnlyOnPollingGets() {
        val server = MockWebServer()
        repeat(3) { server.enqueue(MockResponse().setBody("{}")) }
        server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor(NoStoreInterceptor()).build()
            client.newCall(Request.Builder().url(server.url("/api/v1/book/b1/progress-panel")).build()).execute().close()
            client.newCall(Request.Builder().url(server.url("/api/v1/book/b1")).build()).execute().close()
            client.newCall(Request.Builder().url(server.url("/api/v1/worker/counts")).build()).execute().close()
            assertEquals("no-store", server.takeRequest().getHeader("Cache-Control"))
            assertNull(server.takeRequest().getHeader("Cache-Control"))
            assertEquals("no-store", server.takeRequest().getHeader("Cache-Control"))
        } finally {
            server.shutdown()
        }
    }
}
