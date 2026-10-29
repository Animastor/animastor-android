package com.example.animastor.network

import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** SSE consumers must not go through the BODY logging interceptor (it buffers the body until EOF). */
class RetrofitClientStreamTest {

    @Test
    fun streamHttpClient_hasNoLoggingInterceptor_butSharesJarAndTimeouts() {
        val base = RetrofitClient.httpClient
        val stream = RetrofitClient.streamHttpClient

        assertTrue(base.interceptors.any { it is HttpLoggingInterceptor })
        assertTrue(stream.interceptors.none { it is HttpLoggingInterceptor })
        assertSame(base.cookieJar, stream.cookieJar)
        assertSame(base.connectionPool, stream.connectionPool)
        assertEquals(base.connectTimeoutMillis, stream.connectTimeoutMillis)
        assertEquals(base.readTimeoutMillis, stream.readTimeoutMillis)
        assertEquals(base.writeTimeoutMillis, stream.writeTimeoutMillis)
        assertEquals(base.callTimeoutMillis, stream.callTimeoutMillis)
    }
}
