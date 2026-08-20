package com.inspiredandroid.kai.terminal.input

import com.inspiredandroid.kai.build.terminal.TerminalKeyEncoder

typealias TerminalKey = com.inspiredandroid.kai.build.terminal.TerminalKey
typealias TerminalModifiers = com.inspiredandroid.kai.build.terminal.TerminalModifiers

/** Stable terminal API delegating to the already-proven encoder. */
object KeyEncoder {
    fun encode(
        key: TerminalKey,
        modifiers: TerminalModifiers = TerminalModifiers.None,
        applicationCursorKeys: Boolean = false,
    ): ByteArray = TerminalKeyEncoder.encode(key, modifiers, applicationCursorKeys).encodeToByteArray()

    fun control(character: Char): ByteArray =
        TerminalKeyEncoder.encodeChar(character, TerminalModifiers(ctrl = true)).encodeToByteArray()

    fun bracketedPaste(text: String): ByteArray = "\u001B[200~$text\u001B[201~".encodeToByteArray()
}
