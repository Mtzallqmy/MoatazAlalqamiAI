package com.inspiredandroid.kai.terminal.core

typealias TerminalBuffer = com.inspiredandroid.kai.build.terminal.TerminalScreen
typealias TerminalCell = com.inspiredandroid.kai.build.terminal.TerminalCell
typealias TerminalSnapshot = com.inspiredandroid.kai.build.terminal.TerminalSnapshot

data class TerminalSession(
    val id: String,
    val profileId: String,
    val cwd: String,
    val columns: Int,
    val rows: Int,
    val running: Boolean,
)

interface TerminalEngine {
    fun sessions(): List<TerminalSession>
    fun start(profileId: String, cwd: String): TerminalSession
    fun write(sessionId: String, bytes: ByteArray)
    fun resize(sessionId: String, columns: Int, rows: Int)
    fun cancel(sessionId: String)
    fun close(sessionId: String)
}
