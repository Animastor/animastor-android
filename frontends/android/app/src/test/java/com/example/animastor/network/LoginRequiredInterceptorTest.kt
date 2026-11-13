package com.example.animastor.network

import com.example.animastor.repository.AuthStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Web parity: frontends/app src/api/client.login-required.test.ts
class LoginRequiredInterceptorTest {

    @Test
    fun loginRequiredOnlyFor401OutsideAuth() {
        assertTrue(LoginRequiredInterceptor.isLoginRequired("/api/v1/book/import", 401))
        assertTrue(LoginRequiredInterceptor.isLoginRequired("/api/v1/book/b1/download", 401))
        assertFalse(LoginRequiredInterceptor.isLoginRequired("/api/v1/auth/login", 401))
        assertFalse(LoginRequiredInterceptor.isLoginRequired("/api/v1/book/import", 403))
        assertFalse(LoginRequiredInterceptor.isLoginRequired("/api/v1/book/import", 400))
    }

    @Test
    fun interceptorNotifiesOnceFor401AndPassesResponseThrough() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Sign in required","code":"login_required"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor(LoginRequiredInterceptor { calls++ }).build()
        val r1 = client.newCall(Request.Builder().url(server.url("/api/v1/book/import")).build()).execute()
        assertEquals(401, r1.code); r1.close()
        client.newCall(Request.Builder().url(server.url("/api/v1/auth/me")).build()).execute().close()
        client.newCall(Request.Builder().url(server.url("/api/v1/books")).build()).execute().close()
        assertEquals(1, calls)
        server.shutdown()
    }

    @Test
    fun authStoreOneClaimPerPrompt() {
        val before = AuthStore.loginPrompt.value
        AuthStore.requestLogin()
        assertEquals(before + 1, AuthStore.loginPrompt.value)
        assertTrue(AuthStore.claimLoginPrompt())
        assertFalse(AuthStore.claimLoginPrompt())
    }
}
