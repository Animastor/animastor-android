package com.example.animastor.ui

import com.example.animastor.repository.AuthUser
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner, VBook v4 step 6: PUT /connectors/profiles (the ONE global prompt
 * profile) is platform-admin only; /auth/me reports user.is_admin. Web
 * parity: SettingsPage worker section, reasoningProvider.test.tsx.
 */
class ProfileAdminOnlyTest {
    @Test fun `isAdmin - is_admin from the backend, role admin for an older backend`() {
        val gson = Gson()
        assertTrue(gson.fromJson("""{"username":"boss","role":"user","is_admin":true}""", AuthUser::class.java).isAdmin())
        assertTrue(gson.fromJson("""{"username":"root","role":"admin"}""", AuthUser::class.java).isAdmin())
        assertFalse(gson.fromJson("""{"username":"alice","role":"user","is_admin":false}""", AuthUser::class.java).isAdmin())
        assertFalse(AuthUser(username = "x").isAdmin())
    }

    @Test fun `the selector is read-only for a non-admin and never sends the PUT`() {
        val src = File("src/main/java/com/example/animastor/ui/WorkerSettingsFragment.kt").readText()
        assertTrue(src.contains("val canEditProfile = AuthStore.state.value.user?.isAdmin() == true"))
        assertTrue(src.contains("b.profileSpinner.isEnabled = canEditProfile"))
        val listener = src.substringAfter("b.profileSpinner.onItemSelectedListener").substringBefore("putConnectorProfile")
        assertTrue(listener.contains("if (!profileInitialized || !canEditProfile) return"))
        assertEquals(1, Regex("putConnectorProfile\\(").findAll(src).count())
    }
}
