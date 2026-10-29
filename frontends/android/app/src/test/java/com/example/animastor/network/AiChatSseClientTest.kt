package com.example.animastor.network

import com.example.animastor.repository.AiChatRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class AiChatSseClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newClient(): AiChatSseClient = AiChatSseClient(client, baseUrl)

    /** Waits for a latch up to [timeoutMs] ms. MockWebServer is local, so completion is fast. */
    private fun awaitLatch(latch: CountDownLatch, timeoutMs: Long = 5000): Boolean =
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)

    // ── Streaming: first onDelta must arrive BEFORE the server finishes (done) ──
    @Test
    fun streaming_dispatchesDeltaBeforeDone() {
        val body =
            "event:meta\ndata:{\"session_id\":\"s1\",\"ai_source\":\"private-local\"}\n\n" +
                "event:delta\ndata:{\"delta\":\"Hello\"}\n\n" +
                "event:delta\ndata:{\"delta\":\" World\"}\n\n" +
                "event:done\ndata:{\"reply\":\"Hello World\",\"session_id\":\"s1\"}\n\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                // Slow delivery so OkHttp sees the body incrementally and the client
                // dispatches frames as they complete rather than all at once.
                .throttleBody(32, 32, TimeUnit.MILLISECONDS)
                .setBody(body)
        )

        val metaCount = AtomicInteger(0)
        val deltaTimes = mutableListOf<Long>()
        var doneReply: String? = null
        val deltaLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(1)
        val errorLatch = CountDownLatch(1)
        val onError = AtomicInteger(0)
        val sse = newClient()
        sse.onMeta = { metaCount.incrementAndGet() }
        sse.onDelta = { deltaTimes.add(System.nanoTime()); deltaLatch.countDown() }
        sse.onDone = { doneReply = it.reply; doneLatch.countDown() }
        sse.onError = { e -> onError.incrementAndGet(); errorLatch.countDown() }
        sse.start(AiChatRequest(emptyList()))

        // First delta must arrive while the server is still streaming.
        val deltaFirst = awaitLatch(deltaLatch)
        val errorFirst = awaitLatch(errorLatch, 1000)
        System.out.println("DEBUG streaming meta=$metaCount deltas=${deltaTimes.size} errors=$onError deltaFirst=$deltaFirst errorFirst=$errorFirst")
        assertTrue("first delta never arrived (meta=$metaCount errors=$onError)", deltaFirst)
        val firstDelta = deltaTimes.first()
        assertEquals(0, onError.get())

        // done must arrive strictly after the first delta (proves incremental dispatch).
        assertTrue("done never arrived", awaitLatch(doneLatch))
        val done = System.nanoTime()
        assertEquals("Hello World", doneReply)
        assertTrue("done must follow first delta ($firstDelta >= $done)", firstDelta < done)
        assertEquals(0, onError.get())
    }

    // ── HTTP 503 with a JSON error body → onError(code, httpStatus=503) ──
    @Test
    fun http503_reportsCodeAndStatus() {
        val errBody = "{\"code\":\"local_ai_not_ready\",\"error\":\"Local AI not ready\"}"
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setHeader("Content-Length", errBody.length.toString())
                .setBody(errBody)
        )
        val onError = AtomicInteger(0)
        var errCode: String? = null
        var httpStatus: Int? = null
        val latch = CountDownLatch(1)
        val sse = newClient()
        sse.onError = { e -> onError.incrementAndGet(); errCode = e.code; httpStatus = e.httpStatus; latch.countDown() }
        sse.onDone = { latch.countDown() }
        sse.start(AiChatRequest(emptyList()))

        val fired = awaitLatch(latch)
        assertTrue("terminal handler did not fire (onError=$onError, errCode=$errCode, httpStatus=$httpStatus)", fired)
        assertEquals(1, onError.get())
        assertEquals("local_ai_not_ready", errCode)
        assertEquals(503, httpStatus)
    }

    // ── Cancel mid-stream: no handlers after cancel ──
    @Test
    fun cancel_midStream_noHandlersAfterCancel() {
        val body =
            "event:delta\ndata:{\"delta\":\"Hello\"}\n\n" +
                "event:delta\ndata:{\"delta\":\" World\"}\n\n" +
                "event:done\ndata:{\"reply\":\"Hello World\"}\n\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(16, 32, TimeUnit.MILLISECONDS)
                .setBody(body)
        )
        val deltaCount = AtomicInteger(0)
        val doneCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val sse = newClient()
        sse.onDelta = { deltaCount.incrementAndGet() }
        sse.onDone = { doneCount.incrementAndGet() }
        sse.onError = { errorCount.incrementAndGet() }
        sse.start(AiChatRequest(emptyList()))

        // Let headers arrive and rb.string() start blocking on the body, then cancel
        // mid-stream. The cancelled read must swallow silently — no handler at all.
        Thread.sleep(100)
        sse.cancel()
        Thread.sleep(500)

        assertEquals(0, deltaCount.get())   // no delta dispatched before cancel
        assertEquals(0, doneCount.get())    // no done after cancel
        assertEquals(0, errorCount.get())   // no error after cancel
    }

    // ── done → EOF: exactly one onDone ──
    @Test
    fun done_toEOF_oneOnDone() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event:done\ndata:{\"reply\":\"Hi\"}\n\n")
        )
        val onDone = AtomicInteger(0)
        val onError = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val sse = newClient()
        sse.onDone = { onDone.incrementAndGet(); latch.countDown() }
        sse.onError = { onError.incrementAndGet(); latch.countDown() }
        sse.start(AiChatRequest(emptyList()))

        assertTrue(awaitLatch(latch))
        assertEquals(1, onDone.get())
        assertEquals(0, onError.get())
    }

    // ── error → EOF: only onError ──
    @Test
    fun error_toEOF_onlyOnError() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event:error\ndata:{\"code\":\"timeout\",\"message\":\"AI timed out\"}\n\n")
        )
        val onDone = AtomicInteger(0)
        val onError = AtomicInteger(0)
        var errCode: String? = null
        val latch = CountDownLatch(1)
        val sse = newClient()
        sse.onDone = { onDone.incrementAndGet(); latch.countDown() }
        sse.onError = { e -> onError.incrementAndGet(); errCode = e.code; latch.countDown() }
        sse.start(AiChatRequest(emptyList()))

        assertTrue(awaitLatch(latch))
        assertEquals(1, onError.get())
        assertEquals(0, onDone.get())
        assertEquals("timeout", errCode)
    }

    // ── parseErrorBody unit tests ──
    @Test
    fun parseErrorBody_parsesCodeAndMessage() {
        val sse = newClient()
        val error = sse.parseErrorBody("{\"code\":\"local_ai_not_ready\",\"error\":\"Local AI not ready\"}")
        assertEquals("local_ai_not_ready", error.code)
        assertEquals("Local AI not ready", error.message)
    }

    @Test
    fun parseErrorBody_unparseableKeepsTransportMessage() {
        val sse = newClient()
        val error = sse.parseErrorBody("not json at all")
        assertNull(error.code)
        assertEquals("not json at all", error.message)
    }

    @Test
    fun parseErrorBody_errorKeyPreferred() {
        val sse = newClient()
        val error = sse.parseErrorBody("{\"error\":\"boom\",\"code\":\"busy\"}")
        assertEquals("busy", error.code)
        assertEquals("boom", error.message)
    }

    @Test
    fun cancel_invokesNoHandler_whenNothingEnqueued() {
        // Request hangs (no response enqueued); cancel must not invoke any handler.
        val onMeta = AtomicInteger(0)
        val onDelta = AtomicInteger(0)
        val onDone = AtomicInteger(0)
        val onError = AtomicInteger(0)
        val sse = newClient()
        sse.onMeta = { onMeta.incrementAndGet() }
        sse.onDelta = { onDelta.incrementAndGet() }
        sse.onDone = { onDone.incrementAndGet() }
        sse.onError = { onError.incrementAndGet() }
        sse.start(AiChatRequest(emptyList()))
        sse.cancel()
        Thread.sleep(300)

        assertEquals(0, onMeta.get())
        assertEquals(0, onDelta.get())
        assertEquals(0, onDone.get())
        assertEquals(0, onError.get())
    }
}
