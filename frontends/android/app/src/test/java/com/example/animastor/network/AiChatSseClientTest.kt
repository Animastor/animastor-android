package com.example.animastor.network

import com.example.animastor.repository.AiChatError
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
        client = OkHttpClient.Builder().build()
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newClient(): AiChatSseClient = AiChatSseClient(client, baseUrl)

    /** Waits for a latch (terminal handler) up to 5 s. MockWebServer is local, so completion is fast. */
    private fun awaitLatch(latch: CountDownLatch): Boolean =
        latch.await(5, TimeUnit.SECONDS)

    @Test
    fun done_then_closed_invokesOnDoneExactlyOnce() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event:meta\ndata:{\"session_id\":\"s1\",\"ai_source\":\"private-local\"}\n\n" +
                        "event:delta\ndata:{\"delta\":\"Hello\"}\n\n" +
                        "event:done\ndata:{\"reply\":\"Hi\",\"session_id\":\"s1\"}\n\n"
                )
        )
        val onDone = AtomicInteger(0)
        val onError = AtomicInteger(0)
        var reply: String? = null
        var error: AiChatError? = null
        val latch = CountDownLatch(1)
        val sse = newClient()
        sse.onDone = { d -> onDone.incrementAndGet(); reply = d.reply; latch.countDown() }
        sse.onError = { e -> onError.incrementAndGet(); error = e; latch.countDown() }
        sse.start(AiChatRequest(emptyList()))

        assertTrue(awaitLatch(latch))
        assertEquals(1, onDone.get())
        assertEquals(0, onError.get())
        assertEquals("Hi", reply)
        assertNull(error)
    }

    @Test
    fun error_then_closed_invokesOnlyOnError() {
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

    @Test
    fun parseErrorBody_parsesCodeAndMessage() {
        // HTTP error body (e.g. 503 {"code":"local_ai_not_ready",...}) → onError with that code.
        // Web parity: postChatStream reads the error body on !res.ok.
        val sse = newClient()
        val error = sse.parseErrorBody("{\"code\":\"local_ai_not_ready\",\"message\":\"Local AI not ready\"}")
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
    fun cancel_invokesNoHandler() {
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

    @Test
    fun cancel_afterDone_invokesNoFurtherHandler() {
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
        sse.onError = { onError.incrementAndGet() }
        sse.start(AiChatRequest(emptyList()))
        assertTrue(awaitLatch(latch))
        sse.cancel()
        Thread.sleep(200)
        // Exactly one terminal call; cancel after done adds nothing.
        assertEquals(1, onDone.get())
        assertEquals(0, onError.get())
    }
}
