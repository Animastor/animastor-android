package com.example.animastor.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * О1 (reports/2026-11-07/orch-v2-image-scene-run.md) — web parity with
 * `frontends/app/src/api/client.ts` `isNoStorePath` and
 * `frontends/app/src/state/panelRefresh.ts`.
 *
 * Polling / restore GETs must never be answered from an HTTP cache. The app's
 * OkHttp client has no `okhttp3.Cache` (checked 07.11), so today every request
 * already reaches the network; [NoStoreInterceptor] pins that for the polling
 * paths even if a cache is configured later. Pure helpers — JVM-tested with
 * the same fixtures as the web tests.
 */
object PollRequests {

    /** One iteration of a polling loop never waits longer than this (web: POLL_REQUEST_TIMEOUT_MS). */
    const val POLL_REQUEST_TIMEOUT_MS: Long = 10_000L

    // Paths relative to /api/v1, query string ignored — same patterns as web.
    private val NO_STORE_GET_PATHS: List<Regex> = listOf(
        Regex("""^/book/[^/?]+/(?:progress-panel|progress-stream|assets-state|status|agent-status|layer-config|generation[^/?]*)(?:[/?]|$)"""),
        Regex("""^/scene/[^/?]+/[^/?]+/[^/?]+/status(?:[/?]|$)"""),
        Regex("""^/worker/counts(?:[/?]|$)"""),
        Regex("""^/generate(?:[/?]|$)"""),
    )

    /** `true` when a GET of [path] (endpoint path, with or without the `/api/v1` prefix) must bypass any HTTP cache. */
    fun isNoStorePath(path: String): Boolean {
        val p = if (path.startsWith("/api/v1/")) path.removePrefix("/api/v1") else path
        return NO_STORE_GET_PATHS.any { it.containsMatchIn(p) }
    }

    /**
     * Does a progress-stream event mean «re-poll the progress panel now»?
     * `generation_complete` (any scope) and v2 `progress` events (they carry
     * `command_id`) — yes; everything else — no. The panel poll stays authoritative.
     */
    fun sseEventRequestsPanelRefresh(type: String?, commandId: String?): Boolean = when (type) {
        "generation_complete" -> true
        "progress" -> !commandId.isNullOrEmpty()
        else -> false
    }
}

/** Adds `Cache-Control: no-store` to GETs of [PollRequests.isNoStorePath] paths. */
class NoStoreInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.method != "GET" || !PollRequests.isNoStorePath(req.url.encodedPath)) return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("Cache-Control", "no-store").build())
    }
}
