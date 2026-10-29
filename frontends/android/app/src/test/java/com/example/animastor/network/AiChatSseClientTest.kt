package com.example.animastor.network

import com.example.animastor.repository.AiChatRequest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.internal.http2.Http2Stream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.internal.duplex.DuplexResponseBody
import okio.buffer
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

    // ── Streaming, provably: the server sends a delta frame, then HOLDS the rest
    // of the body until the test has observed onDelta. A client that buffers the
    // body (rb.string(), BODY logging) can never see the delta → times out. ──
    // MockWebServer can only pause mid-body via DuplexResponseBody, which is
    // HTTP/2-only, hence H2_PRIOR_KNOWLEDGE on both ends for this test.
    @Test
    fun streaming_deltaArrivesWhileServerHoldsDone() {
        val release = CountDownLatch(1)
        val h2Server = MockWebServer().apply { protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE) }
        h2Server.enqueue(
            MockResponse()
                // MockResponse() defaults to Content-Length: 0; the duplex body is open-ended.
                .removeHeader("Content-Length")
                .setHeader("Content-Type", "text/event-stream")
                .setBody(object : DuplexResponseBody {
                    override fun onRequest(request: RecordedRequest, http2Stream: Http2Stream) {
                        http2Stream.getSink().buffer().use { sink ->
                            sink.writeUtf8("event:meta\ndata:{\"session_id\":\"s1\",\"ai_source\":\"private-local\"}\n\n")
                            sink.writeUtf8("event:delta\ndata:{\"delta\":\"Hello\"}\n\n")
                            sink.flush()
                            // Released by the test only after onDelta was received.
                            release.await(10, TimeUnit.SECONDS)
                            sink.writeUtf8("event:done\ndata:{\"reply\":\"Hello\",\"session_id\":\"s1\"}\n\n")
                        }
                    }
                })
        )
        h2Server.start()
        try {
            val h2Client = OkHttpClient.Builder()
                .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val sse = AiChatSseClient(h2Client, h2Server.url("/").toString().trimEnd('/'))
            val deltaLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(1)
            val errors = AtomicInteger(0)
            var deltaText: String? = null
            var doneReply: String? = null
            sse.onDelta = { deltaText = it; deltaLatch.countDown() }
            sse.onDone = { doneReply = it.reply; doneLatch.countDown() }
            sse.onError = { errors.incrementAndGet() }
            sse.start(AiChatRequest(emptyList()))

            assertTrue(
                "onDelta did not arrive while the server was holding the done frame — body is buffered, not streamed",
                awaitLatch(deltaLatch, 3000)
            )
            assertEquals("Hello", deltaText)
            assertEquals("done must not arrive before the server is released", 1L, doneLatch.count)

            release.countDown()
            assertTrue("done never arrived", awaitLatch(doneLatch))
            assertEquals("Hello", doneReply)
            assertEquals(0, errors.get())
        } finally {
            release.countDown()
            h2Server.shutdown()
        }
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

    // ── Cancel mid-stream (after the first delta): no handlers after cancel ──
    @Test
    fun cancel_midStream_noHandlersAfterCancel() {
        val first = "event:delta\ndata:{\"delta\":\"Hello\"}\n\n"
        val body = first +
            "event:delta\ndata:{\"delta\":\" World\"}\n\n" +
            "event:done\ndata:{\"reply\":\"Hello World\"}\n\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                // First frame goes out at once, the rest 500 ms later.
                .throttleBody(first.length.toLong(), 500, TimeUnit.MILLISECONDS)
                .setBody(body)
        )
        val deltaCount = AtomicInteger(0)
        val doneCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val firstDelta = CountDownLatch(1)
        val sse = newClient()
        sse.onDelta = { deltaCount.incrementAndGet(); firstDelta.countDown() }
        sse.onDone = { doneCount.incrementAndGet() }
        sse.onError = { errorCount.incrementAndGet() }
        sse.start(AiChatRequest(emptyList()))

        assertTrue("first delta never arrived", awaitLatch(firstDelta, 3000))
        sse.cancel()
        // Wait past the moment the rest of the body would have arrived.
        Thread.sleep(1000)

        assertEquals(1, deltaCount.get())   // nothing dispatched after cancel
        assertEquals(0, doneCount.get())    // no done after cancel
        assertEquals(0, errorCount.get())   // post-cancel read failure swallowed
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

    // ── Last frame without the closing blank line is dispatched at EOF ──
    @Test
    fun trailingFrameAtEOF_isDispatched() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event:delta\ndata:{\"delta\":\"A\"}\n\nevent:done\ndata:{\"reply\":\"A!\"}")
        )
        val deltas = mutableListOf<String>()
        var doneReply: String? = null
        val onDone = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val sse = newClient()
        sse.onDelta = { deltas.add(it) }
        sse.onDone = { doneReply = it.reply; onDone.incrementAndGet(); latch.countDown() }
        sse.onError = { latch.countDown() }
        sse.start(AiChatRequest(emptyList()))

        assertTrue(awaitLatch(latch))
        assertEquals(listOf("A"), deltas)
        assertEquals("A!", doneReply)
        assertEquals(1, onDone.get())
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
