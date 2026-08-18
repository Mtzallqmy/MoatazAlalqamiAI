package com.inspiredandroid.kai

import org.jetbrains.compose.resources.StringResource
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.brand_app_name

/**
 * Central product-identity resource.
 *
 * All user-facing identity (app name, vendor, about text, notifications
 * channel labels) should be derived from this object instead of being
 * scattered across screens, so rebranding is a single-file change.
 * Legal attribution to the upstream project is handled separately in
 * LICENSE/THIRD_PARTY_LICENSES/LEGAL_COMPLIANCE — this object only carries
 * *identity*, not licensing metadata.
 */
object AppBrand {
    const val DISPLAY_NAME: String = "Moataz Alalqami AI"
    const val DISPLAY_NAME_SHORT: String = "MA-AI"
    const val PACKAGE_NS: String = "com.inspiredandroid.kai"
    const val GITHUB_ORG: String = "moataz-alalqami"

    // Localized app name resource (overrides `app_name` string used by Android).
    val nameRes: StringResource = Res.string.brand_app_name

    const val ABOUT_TITLE: String = "معتز العلقمي AI"
    const val ABOUT_TAGLINE: String = "وكيلك الذكي — يعمل على جهازك"

    fun aboutText(): String = buildString {
        appendLine("$DISPLAY_NAME")
        appendLine("$ABOUT_TAGLINE\n")
        appendLine("وكيل ذكاء اصطناعي متكامل على أندرويد: مزودو النماذج (OpenAI و Gemini و Anthropic و Open Router وأي API متوافق)،")
        appendLine("محرّك الوكلاء، طرفية لينكس معزولة، الذاكرة الدلالية وبناء المشاريع — في تطبيق واحد.")
    }

    fun aboutTextEn(): String = buildString {
        appendLine("$DISPLAY_NAME")
        appendLine("Your AI agent — running on your device\n")
        appendLine("An all-in-one Android AI Agent: model providers (OpenAI, Gemini, Anthropic,")
        appendLine("Open Router and any OpenAI-compatible API), an agent runtime, an isolated")
        appendLine("Linux sandbox terminal, semantic memory and project building — in one app.")
    }
}
