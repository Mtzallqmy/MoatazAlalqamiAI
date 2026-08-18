package com.inspiredandroid.kai.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task classification guards for both English and Arabic prompts.
 *
 * These keep the routing layer honest: a coding request must route toward a
 * coding-capable candidate and a vision payload must never silently become a
 * plain chat — so we pin the expected TaskType for representative messages.
 */
class TaskClassifierTest {

    // English prompts

    @Test
    fun `english coding request`() {
        assertEquals(TaskType.Coding, TaskClassifier.classify("Fix the bug in the login flow; the stack trace shows a null pointer in auth.kt"))
    }

    @Test
    fun `english refactor request`() {
        assertEquals(TaskType.Coding, TaskClassifier.classify("Refactor this function and add a unit test for the edge case"))
    }

    @Test
    fun `english reasoning request`() {
        assertEquals(TaskType.Reasoning, TaskClassifier.classify("Think step by step and prove that the sum of two odd numbers is even"))
    }

    @Test
    fun `english research request`() {
        assertEquals(TaskType.Research, TaskClassifier.classify("Research the latest comparison of open-source LLM gateways"))
    }

    @Test
    fun `english vision request`() {
        assertEquals(TaskType.Vision, TaskClassifier.classify("Describe the image: what do you see in this screenshot?"))
    }

    @Test
    fun `english summarization request`() {
        assertEquals(TaskType.Summarization, TaskClassifier.classify("Summarize this article and give me the key points in short"))
    }

    @Test
    fun `english planning request`() {
        assertEquals(TaskType.Planning, TaskClassifier.classify("Create a roadmap and break down the migration plan into a checklist"))
    }

    @Test
    fun `short english question is a fast answer`() {
        assertEquals(TaskType.FastAnswer, TaskClassifier.classify("What time is it?"))
    }

    // Arabic prompts

    @Test
    fun `arabic coding request`() {
        assertEquals(TaskType.Coding, TaskClassifier.classify("اكتب دالة بلغة كوتلن لتصحيح خطأ في المشروع"))
    }

    @Test
    fun `arabic debug request`() {
        assertEquals(TaskType.Coding, TaskClassifier.classify("تصحيح خطأ في سكريبت بايثون وسكربت آخر"))
    }

    @Test
    fun `arabic reasoning request`() {
        assertEquals(TaskType.Reasoning, TaskClassifier.classify("حلل المسألة خطوة بخطوة واشرح لماذا"))
    }

    @Test
    fun `arabic research request`() {
        assertEquals(TaskType.Research, TaskClassifier.classify("ابحث عن أحدث الأخبار وقارن بين الخيارات"))
    }

    @Test
    fun `arabic vision request`() {
        assertEquals(TaskType.Vision, TaskClassifier.classify("ماذا ترى في هذه الصورة؟ صف الصورة المرفقة"))
    }

    @Test
    fun `arabic summarization request`() {
        assertEquals(TaskType.Summarization, TaskClassifier.classify("لخص هذا المقال باختصار وأعطني أهم النقاط"))
    }

    @Test
    fun `arabic planning request`() {
        assertEquals(TaskType.Planning, TaskClassifier.classify("خطة العمل ومراحل الجدول وقائمة مهام"))
    }

    @Test
    fun `short arabic question is a fast answer`() {
        assertEquals(TaskType.FastAnswer, TaskClassifier.classify("كم الساعة؟"))
    }

    // Fallbacks and edge cases

    @Test
    fun `unknown message falls back to chat`() {
        assertEquals(TaskType.Chat, TaskClassifier.classify("xyzzy plugh 12345 nothing recognizable here"))
    }

    @Test
    fun `empty message falls back to chat`() {
        assertEquals(TaskType.Chat, TaskClassifier.classify(""))
    }

    @Test
    fun `vision hint detects image references`() {
        assertTrue(TaskClassifier.hasVisionHint("حلل هذه الصورة"))
        assertTrue(TaskClassifier.hasVisionHint("analyze the attached image"))
    }

    @Test
    fun `long statement is not a fast answer`() {
        assertEquals(
            TaskType.Chat,
            TaskClassifier.classify("هذه جملة طويلة جداً تحتوي على الكثير من الكلمات وتنتهي بعلامة استفهام في نهايتها بالتأكيد؟"),
        )
    }
}
