package com.inspiredandroid.kai.terminal.pty

data class TerminalSize(val columns: Int, val rows: Int) {
    init {
        require(columns > 0)
        require(rows > 0)
    }
}

interface PtySession {
    val id: String
    val size: TerminalSize
    fun write(bytes: ByteArray)
    fun resize(size: TerminalSize)
    fun cancel()
}
