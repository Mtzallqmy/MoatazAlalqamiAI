package com.inspiredandroid.kai.gateway

/**
 * Task type inferred by the [TaskClassifier]. Routing decisions are driven by
 * task type + required capabilities — never by a raw string in the UI layer.
 *
 * Kept in its own file so the classifier can be exercised and evolved
 * independently of the router's scoring logic.
 */
enum class TaskType {
    Chat,
    Coding,
    Reasoning,
    Research,
    Vision,
    Summarization,
    Planning,
    FastAnswer,
}

/**
 * Heuristic-only task classifier. No LLM call is ever needed: keywords and
 * simple structure rules decide the task type for both Arabic and English
 * prompts. Local and cheap by design; an optional small router model can
 * refine this later if the user explicitly configures one.
 *
 * Classification is intentionally permissive: the router treats the result as
 * a preference, not a commitment — an unknown pattern falls back to [TaskType.Chat].
 */
object TaskClassifier {

    private val codingKeywords = listOf(
        // English
        "fix bug", "fix the bug", "bug in", "refactor", "implement ", "implement a",
        "write code", "write a function", "write a test", "unit test",
        "compile", "build ", "lint", "error in", "exception", "crash", "stack trace",
        "pull request", "git ", "branch", "dependency", "package.json",
        "gradle", "kotlin", "python", "npm", "ci/cd",
        "code review", "add endpoint", "function ", "class ", "debug",
        // Arabic
        "اكتب", "برمجة", "برمج", "دالة", "كود", "مشروع",
        "خطأ في", "تصحيح", "معالجة", "سكريبت", "سكربت", "استعلام",
        "اصنع", "أنشئ ", "بناء ", "تنفيذ كود", "تطبيق ",
    )

    private val reasoningKeywords = listOf(
        // English
        "think step by step", "step-by-step", "reason", "proof", "prove",
        "solve ", "calculate", "math", "equation", "logic puzzle",
        "explain the reasoning", "why does", "deep think", "analyze ",
        // Arabic
        "حلل", "تحليل", "خطوة بخطوة", "حل المسألة", "حل هذه", "اشرح",
        "لماذا", "كيف تعمل", "استنتاج", "منطق", "برهان", "احسب",
        "مسألة", "معضلة", "تفكير", "فكر",
    )

    private val researchKeywords = listOf(
        // English
        "research ", "find information", "search the web", "browse", "look up",
        "overview of", "who is", "compare ", "comparison", "latest news",
        "current events", "read this page", "fetch", "download",
        // Arabic
        "ابحث عن", "ابحث", "أبحث", "بحث", "تصفح", "معلومات",
        "أحدث", "آخر الأخبار", "قارن", "مقارنة", "من هو", "ما هي", "ما هو",
        "اقرأ", "اجلب", "حمّل", "احصل على",
    )

    private val visionKeywords = listOf(
        // English
        "screenshot", "image", "photo", "picture", "analyze this image",
        "describe the image", "vision", "ocr", "chart", "graph", "diagram",
        "what do you see", "scan", "upload an image", "attached image",
        // Arabic
        "الصورة", "هذه الصورة", "في الصورة", "ماذا ترى", "صف الصورة",
        "ما هذه", "لقطة", "تصوير", "قراءة الصورة",
        "صورة ", "مرفق",
    )

    private val summarizationKeywords = listOf(
        // English
        "summarize", "summary", "tl;dr", "tldr", "brief", "condense",
        "key points", "main points", "in short", "abstract",
        // Arabic
        "لخص", "تلخيص", "ملخص", "اختصر", "أهم النقاط",
        "خلاصة", "باختصار", "نقاط", "الزبدة",
    )

    private val planningKeywords = listOf(
        // English
        "plan ", "planning", "roadmap", "schedule", "break down", "decompose", "checklist",
        // Arabic
        "خطة", "خطط", "جدول", "تخطيط", "قائمة مهام", "مراحل", "خطوات العمل",
        "نظم", "أولويات",
    )

    fun classify(message: String): TaskType {
        val lower = message.lowercase()
        val scores = mapOf(
            TaskType.Coding to countMatches(lower, codingKeywords),
            TaskType.Reasoning to countMatches(lower, reasoningKeywords),
            TaskType.Research to countMatches(lower, researchKeywords),
            TaskType.Vision to countMatches(lower, visionKeywords),
            TaskType.Summarization to countMatches(lower, summarizationKeywords),
            TaskType.Planning to countMatches(lower, planningKeywords),
            TaskType.FastAnswer to if (isLikelyFastAnswer(lower)) 1 else 0,
        )
        val best = scores.maxByOrNull { it.value }
        return if (best == null || best.value == 0) TaskType.Chat else best.key
    }

    /**
     * Whether the payload textually implies a vision task. The router should
     * AND this with the presence of actual image attachments before marking
     * the request as vision-required.
     */
    fun hasVisionHint(message: String): Boolean =
        visionKeywords.any { message.lowercase().contains(it) }

    private fun countMatches(lower: String, keywords: List<String>): Int {
        var hits = 0
        for (kw in keywords) if (lower.contains(kw)) hits++
        return hits
    }

    /** A very short direct question is treated as a fast answer. */
    private fun isLikelyFastAnswer(lower: String): Boolean =
        lower.length < 60 && (lower.endsWith("?") || lower.endsWith("؟"))
}
