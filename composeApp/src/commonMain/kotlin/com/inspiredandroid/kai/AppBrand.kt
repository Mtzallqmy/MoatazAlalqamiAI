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

    const val ABOUT_TITLE: String = "Moataz Alalqami AI"
    const val ABOUT_TAGLINE: String = "Your on-device AI agent workbench"

    fun aboutText(): String = buildString {
        appendLine("$DISPLAY_NAME")
        appendLine("$ABOUT_TAGLINE\n")
        appendLine("An Android AI Agent Workbench that combines model providers,")
        appendLine("an agent runtime, a coding sandbox and project memory in one app.")
    }
}
