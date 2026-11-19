package com.example.animastor.ui

// ======================================================
// Reasoning settings of an AI provider (GAP RSN-1)
// ======================================================
// Web twin: frontends/app/src/features/ai/reasoning.tsx (VBOOK_FORMAT_V4.md
// §12б, EXPERIMENTAL_BETA_PERSONAL_AI_PROVIDER.md §18a). A provider carries
// two optional settings the backend applies to EVERY /chat/completions body:
//   disable_thinking  boolean — chat_template_kwargs {enable_thinking:false}
//   reasoning_effort  null (not sent) | none | minimal | low | medium | high
// Android has the personal provider only (the admin panel is web-only,
// owner's decision 2026-10-29).
//
// The analysis error reasoning_budget_exhausted names who can change the
// provider (owner, step 6): «Provider: system.» → ask the administrator;
// «Provider: personal.» → the provider settings; no mark (older backend) →
// the provider settings as before.

object ReasoningSettings {
    val REASONING_EFFORTS = listOf("none", "minimal", "low", "medium", "high")

    data class Value(val disableThinking: Boolean, val effort: String?)

    val DEFAULT = Value(disableThinking = false, effort = null)

    /** The provider meta → the form; an unknown effort is «do not send». */
    fun fromMeta(disableThinking: Boolean?, reasoningEffort: String?): Value =
        Value(disableThinking == true, reasoningEffort?.takeIf { it in REASONING_EFFORTS })

    /** The form → the PUT / test body fields (null effort IS sent: «do not send» is a value). */
    fun body(v: Value): Map<String, Any?> = linkedMapOf("disable_thinking" to v.disableThinking, "reasoning_effort" to v.effort)

    /** Spinner position 0 = «do not send», then REASONING_EFFORTS. */
    fun effortAt(position: Int): String? = if (position <= 0) null else REASONING_EFFORTS.getOrNull(position - 1)

    fun positionOf(effort: String?): Int = effort?.let { REASONING_EFFORTS.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0

    private val BUDGET_TEXT = Regex("reasoning_budget_exhausted|exhausted its token limit on reasoning", RegexOption.IGNORE_CASE)
    private val PROVIDER_MARK = Regex("Provider: (system|personal)\\.")

    /** The code, or the fixed backend text the analysis progress carries. */
    fun isReasoningBudgetError(text: String?): Boolean = text != null && BUDGET_TEXT.containsMatchIn(text)

    /** "system" | "personal" | null: the structured field wins, else the mark of the text. */
    fun budgetProvider(text: String?, providerSource: String? = null): String? {
        if (providerSource == "system" || providerSource == "personal") return providerSource
        return text?.let { PROVIDER_MARK.find(it)?.groupValues?.get(1) }
    }

    /** The string key of the plain-words text (the same keys as web i18n). */
    fun budgetTextKey(provider: String?): String = if (provider == "system") "reasoning_budget_text_system" else "reasoning_budget_text"

    /** Can THIS user fix it in the app? personal / unknown → yes (provider settings); system → no. */
    fun linksToSettings(provider: String?): Boolean = provider != "system"
}
