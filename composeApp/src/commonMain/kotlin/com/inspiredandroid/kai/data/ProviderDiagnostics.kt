package com.inspiredandroid.kai.data

import kotlinx.serialization.Serializable

/** Result of an explicit, user-triggered provider probe. No API key is retained. */
@Serializable
data class ProviderDiagnosticReport(
    val instanceId: String,
    val providerName: String,
    val modelId: String,
    val endpoint: String,
    val connection: DiagnosticCheck,
    val modelDiscovery: DiagnosticCheck,
    val chatCompletion: DiagnosticCheck,
    val toolCalling: DiagnosticCheck,
    val latencyMs: Long,
    val checkedAtEpochMs: Long,
) {
    val isUsableForChat: Boolean get() = connection.passed && chatCompletion.passed
    val isUsableForAgents: Boolean get() = isUsableForChat && toolCalling.passed
}

@Serializable
data class DiagnosticCheck(
    val status: DiagnosticStatus,
    val detail: String,
    val count: Int? = null,
) {
    val passed: Boolean get() = status == DiagnosticStatus.Passed

    companion object {
        fun passed(detail: String, count: Int? = null) = DiagnosticCheck(DiagnosticStatus.Passed, detail, count)
        fun failed(detail: String) = DiagnosticCheck(DiagnosticStatus.Failed, detail)
        fun unsupported(detail: String) = DiagnosticCheck(DiagnosticStatus.Unsupported, detail)
        fun skipped(detail: String) = DiagnosticCheck(DiagnosticStatus.Skipped, detail)
    }
}

@Serializable
enum class DiagnosticStatus { Passed, Failed, Unsupported, Skipped }
