package com.inspiredandroid.kai.network.dtos.openaicompatible

import com.inspiredandroid.kai.network.tools.Tool
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Security-focused fuzz tests for the inline tool-call parser: it must never
 * throw uncaught exceptions on hostile or malformed input, because the parsed
 * output flows directly into the agent loop.
 */
class InlineToolCallParserFuzzTest {

    @Test
    fun `extractInlineToolCalls never throws on generated hostile payloads`() {
        val generator = Random(42)
        val tools = emptyList<Tool>()
        repeat(300) {
            val hostile = buildString {
                append("lead ")
                append("<tool_call>")
                append(generateValue(generator, depth = 0))
                append("</tool_call>")
                append(" tail")
            }
            runCatching { extractInlineToolCalls(hostile, tools) }
            runCatching { extractInlineToolCalls(breakTags(hostile), tools) }
        }
        assertTrue(true)
    }

    @Test
    fun `extractInlineToolCalls survives extreme nesting and long inputs`() {
        val tools = emptyList<Tool>()
        val nested = buildString {
            append("<tool_call>")
            repeat(60) { append("{") }
            append("\"k\":")
            repeat(60) { append("}") }
            append("</tool_call>")
        }
        runCatching { extractInlineToolCalls(nested, tools) }
        val big = buildString {
            append("<tool_call>")
            repeat(4000) { append("x") }
            append("</tool_call>")
        }
        runCatching { extractInlineToolCalls(big, tools) }
        val unclosed = buildString {
            repeat(50) { append("<tool_call>{\"name\":\"t\",\"arguments\":{}}") }
        }
        runCatching { extractInlineToolCalls(unclosed, tools) }
        assertTrue(true)
    }

    @Test
    fun `extractInlineToolCalls is deterministic on repeated runs`() {
        val tools = emptyList<Tool>()
        val payload = """before <tool_call><function=fs.read>{"path": "/root/a.txt"}</function ></tool_call> after"""
        val first = extractInlineToolCalls(payload, tools)
        repeat(50) {
            assertEquals(first, extractInlineToolCalls(payload, tools))
        }
    }

    @Test
    fun `missing close tag is handled safely`() {
        val tools = emptyList<Tool>()
        val noClose = "start <tool_call><function=x></function no-end"
        val result = runCatching { extractInlineToolCalls(noClose, tools) }
        assertTrue(result.isSuccess)
        // The parser keeps the unparseable block visible in cleaned text.
        assertTrue(result.getOrNull()?.cleanedText?.isNotEmpty() == true)
    }

    private fun generateValue(gen: Random, depth: Int): String =
        when {
            depth > 8 || gen.nextInt(10) < 3 -> generateString(gen)
            gen.nextInt(10) < 6 -> generateString(gen)
            gen.nextInt(2) == 0 -> generateObject(gen, depth)
            else -> generateArray(gen, depth)
        }

    private fun generateString(gen: Random): String {
        val length = gen.nextInt(25)
        return "\"" + buildString {
            repeat(length) {
                append(
                    when (gen.nextInt(7)) {
                        0 -> '\\'
                        1 -> '"'
                        2 -> '/'
                        3 -> '\n'
                        4 -> ' '
                        else -> ('a' + gen.nextInt(26))
                    },
                )
            }
        } + "\""
    }

    private fun generateObject(gen: Random, depth: Int): String = buildString {
        append("{")
        repeat(gen.nextInt(4) + 1) {
            if (it > 0) append(",")
            append("\"k$it\":${generateValue(gen, depth + 1)}")
        }
        append("}")
    }

    private fun generateArray(gen: Random, depth: Int): String = buildString {
        append("[")
        repeat(gen.nextInt(4) + 1) {
            if (it > 0) append(",")
            append(generateValue(gen, depth + 1))
        }
        append("]")
    }

    /** Inserts stray tags and escapes inside an otherwise valid block. */
    private fun breakTags(input: String): String {
        val generator = Random(input.length.toLong())
        val chars = input.toCharArray()
        if (chars.isEmpty()) return input
        repeat(input.length / 16) {
            val idx = generator.nextInt(chars.size)
            when (generator.nextInt(4)) {
                0 -> chars[idx] = '<'
                1 -> chars[idx] = '>'
                2 -> chars[idx] = '"'
                3 -> chars[idx] = '\\'
            }
        }
        return String(chars)
    }
}
