package com.example.animastor.ui

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GAP RSN-1 on Android (item 16) — web features/ai/reasoning.test.tsx +
 * reasoningProvider.test.tsx: the two provider settings and the
 * reasoning_budget_exhausted text that follows the provider (owner, step 6).
 */
class ReasoningSettingsTest {
    private val system = "✗ Ошибка: The model exhausted its token limit on reasoning (finish_reason=length, empty content, max_tokens=4096). " +
        "Provider: system. Ask the administrator to disable thinking (disable_thinking) or lower reasoning_effort of the system AI provider."
    private val personal = "The model exhausted its token limit on reasoning (finish_reason=length, empty content, max_tokens=1). " +
        "Provider: personal. Disable thinking in your AI provider settings (disable_thinking) or lower reasoning_effort."

    @Test fun `meta to form to body - null effort is sent as null (do not send)`() {
        assertEquals(ReasoningSettings.Value(true, "low"), ReasoningSettings.fromMeta(true, "low"))
        assertEquals(ReasoningSettings.Value(false, null), ReasoningSettings.fromMeta(null, "turbo"))
        val body = ReasoningSettings.body(ReasoningSettings.Value(true, null))
        assertEquals("""{"disable_thinking":true,"reasoning_effort":null}""", GsonBuilder().serializeNulls().create().toJson(body))
    }

    @Test fun `spinner positions - 0 is do not send, then the five efforts`() {
        assertNull(ReasoningSettings.effortAt(0))
        assertEquals("none", ReasoningSettings.effortAt(1))
        assertEquals("high", ReasoningSettings.effortAt(5))
        assertEquals(0, ReasoningSettings.positionOf(null))
        assertEquals(3, ReasoningSettings.positionOf("low"))
        assertEquals(0, ReasoningSettings.positionOf("turbo"))
    }

    @Test fun `the error by code or by the fixed backend text`() {
        assertTrue(ReasoningSettings.isReasoningBudgetError("reasoning_budget_exhausted"))
        assertTrue(ReasoningSettings.isReasoningBudgetError(system))
        assertFalse(ReasoningSettings.isReasoningBudgetError("timeout"))
        assertFalse(ReasoningSettings.isReasoningBudgetError(null))
    }

    @Test fun `who can change the provider - field first, then the mark, system has no settings link`() {
        assertEquals("system", ReasoningSettings.budgetProvider(system))
        assertEquals("personal", ReasoningSettings.budgetProvider(personal))
        assertEquals("system", ReasoningSettings.budgetProvider(personal, providerSource = "system"))
        assertNull(ReasoningSettings.budgetProvider("The model exhausted its token limit on reasoning. Disable thinking …"))
        assertEquals("reasoning_budget_text_system", ReasoningSettings.budgetTextKey("system"))
        assertEquals("reasoning_budget_text", ReasoningSettings.budgetTextKey("personal"))
        assertEquals("reasoning_budget_text", ReasoningSettings.budgetTextKey(null))
        assertFalse(ReasoningSettings.linksToSettings("system"))
        assertTrue(ReasoningSettings.linksToSettings("personal"))
        assertTrue(ReasoningSettings.linksToSettings(null))
    }

    @Test fun `the settings screen sends both fields with nulls kept, in PUT and in the test`() {
        val src = File("src/main/java/com/example/animastor/ui/AiProviderSettingsFragment.kt").readText()
        assertEquals(2, Regex("body\\.putAll\\(ReasoningSettings\\.body\\(currentReasoning\\(\\)\\)\\)").findAll(src).count())
        assertTrue(src.contains("RetrofitClient.api.putAiProviderRaw(jsonBody(body))"))
        assertTrue(src.contains("RetrofitClient.api.testAiProviderRaw(jsonBody(body))"))
        assertTrue(src.contains("HeroRepository.heroGson"))
    }

    @Test fun `the strings exist in both languages with the web keys`() {
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-ru/strings.xml")) {
            val xml = File(path).readText()
            for (key in listOf("reasoning_disable_thinking", "reasoning_disable_thinking_hint", "reasoning_effort", "reasoning_effort_default",
                "reasoning_effort_hint", "reasoning_budget_text", "reasoning_budget_text_system", "reasoning_budget_link")) {
                assertTrue("$path $key", xml.contains("name=\"$key\""))
            }
        }
        assertTrue(File("src/main/res/values-ru/strings.xml").readText().contains("обратитесь к администратору"))
    }
}
