package com.inspiredandroid.kai.terminal.input

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class KeyEncoderTest {
    @Test fun `ctrl c encodes ETX`() = assertContentEquals(byteArrayOf(3), KeyEncoder.control('c'))
    @Test fun `arrows use ANSI CSI`() = assertEquals("\u001B[A", KeyEncoder.encode(TerminalKey.Up).decodeToString())
    @Test fun `bracketed paste wraps utf8 payload`() = assertEquals("\u001B[200~مرحبا\u001B[201~", KeyEncoder.bracketedPaste("مرحبا").decodeToString())
}
