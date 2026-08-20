package com.inspiredandroid.kai.linux

import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared real-PTY bridge for any on-device PRoot caller that needs a TTY.
 *
 * The normal command executor intentionally uses pipes because they preserve
 * stdout/stderr and exit sentinels. TUI/AI CLIs need different semantics:
 * raw bytes, a real TTY size, SIGWINCH, and byte-oriented stdin. This class is
 * that single PTY implementation for the generic sandbox path; Kai Build's
 * terminal uses the same bridge design.
 */
class PtyProotExecutor(
    private val launcher: ProotLauncher,
    private val tmpPath: String,
    initialColumns: Int = 80,
    initialRows: Int = 24,
) {
    private val sessionKey = UUID.randomUUID().toString().replace("-", "").take(12)
    private val winsizeFileName = "kai-winsize-$sessionKey"
    private val pidFileName = "kai-pid-$sessionKey"
    private val winsizePath = "/tmp/$winsizeFileName"

    @Volatile
    private var columns: Int = initialColumns.coerceAtLeast(1)

    @Volatile
    private var rows: Int = initialRows.coerceAtLeast(1)

    /** Start a raw interactive command under a real guest PTY. */
    fun executeStreaming(
        command: String,
        workingDir: String,
        onOutput: (ByteArray, Int) -> Unit,
    ): ProotHandle {
        val pidFile = File(tmpPath, pidFileName)
        runCatching { pidFile.delete() }
        writeWinsizeFile(rows, columns)
        return launcher.startStreaming(
            command = wrapWithPty(command),
            workingDir = workingDir,
            guestPidFile = pidFile,
        ) { process, cancelled ->
            listOf(
                CompletableFuture.runAsync { streamBytes(process.inputStream, cancelled, onOutput) },
                CompletableFuture.runAsync { streamBytes(process.errorStream, cancelled, onOutput) },
            )
        }
    }

    /** Resize the live PTY. The guest bridge polls the bind-mounted file. */
    fun resize(columns: Int, rows: Int) {
        val safeColumns = columns.coerceAtLeast(1)
        val safeRows = rows.coerceAtLeast(1)
        this.columns = safeColumns
        this.rows = safeRows
        writeWinsizeFile(safeRows, safeColumns)
    }

    private fun writeWinsizeFile(rows: Int, columns: Int) {
        val file = File(tmpPath, winsizeFileName)
        file.parentFile?.mkdirs()
        runCatching { file.writeText("$rows $columns\n") }
    }

    /**
     * python3 is part of the Debian production base. pty.fork() creates a real
     * controlling terminal; TIOCSWINSZ + SIGWINCH make TUIs render correctly on
     * phone-sized viewports and after rotation/keyboard changes.
     */
    private fun wrapWithPty(command: String): String {
        val cmdB64 = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
        val initialRows = rows
        val initialColumns = columns
        val script = """
            |import base64, errno, fcntl, os, pty, select, signal, struct, termios
            |ROWS, COLS = $initialRows, $initialColumns
            |cmd = base64.b64decode('$cmdB64').decode()
            |
            |def set_winsize(fd, r, c):
            |    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack('HHHH', r, c, 0, 0))
            |
            |def get_winsize(fd):
            |    packed = fcntl.ioctl(fd, termios.TIOCGWINSZ, struct.pack('HHHH', 0, 0, 0, 0))
            |    r, c, _, _ = struct.unpack('HHHH', packed)
            |    return r, c
            |
            |pid, master = pty.fork()
            |if pid == 0:
            |    os.environ['TERM'] = 'xterm-256color'
            |    os.environ['COLORTERM'] = 'truecolor'
            |    os.environ['COLUMNS'] = str(COLS)
            |    os.environ['LINES'] = str(ROWS)
            |    os.environ.pop('LD_LIBRARY_PATH', None)
            |    os.execvp('/bin/bash', ['bash', '-lc', cmd])
            |    os._exit(127)
            |
            |try:
            |    set_winsize(master, ROWS, COLS)
            |    os.kill(pid, signal.SIGWINCH)
            |except OSError:
            |    pass
            |
            |WS_PATH = '$winsizePath'
            |
            |def poll_winsize():
            |    try:
            |        with open(WS_PATH, 'r') as f:
            |            parts = f.read().split()
            |        if len(parts) < 2:
            |            return
            |        nr, nc = int(parts[0]), int(parts[1])
            |        if nr < 1 or nc < 1 or get_winsize(master) == (nr, nc):
            |            return
            |        set_winsize(master, nr, nc)
            |        try:
            |            os.kill(pid, signal.SIGWINCH)
            |        except OSError:
            |            pass
            |    except (OSError, ValueError, struct.error):
            |        pass
            |
            |stdin_open = True
            |try:
            |    while True:
            |        rfds = [master]
            |        if stdin_open:
            |            rfds.append(0)
            |        try:
            |            ready, _, _ = select.select(rfds, [], [], 0.05)
            |        except (InterruptedError, select.error):
            |            poll_winsize()
            |            continue
            |        poll_winsize()
            |        if master in ready:
            |            try:
            |                data = os.read(master, 8192)
            |            except OSError as e:
            |                if e.errno == errno.EIO:
            |                    break
            |                raise
            |            if not data:
            |                break
            |            os.write(1, data)
            |        if stdin_open and 0 in ready:
            |            data = os.read(0, 8192)
            |            if not data:
            |                stdin_open = False
            |            else:
            |                os.write(master, data)
            |except OSError:
            |    pass
            |finally:
            |    try:
            |        os.close(master)
            |    except OSError:
            |        pass
            |
            |_, status = os.waitpid(pid, 0)
            |if hasattr(os, 'waitstatus_to_exitcode'):
            |    raise SystemExit(os.waitstatus_to_exitcode(status))
            |if os.WIFEXITED(status):
            |    raise SystemExit(os.WEXITSTATUS(status))
            |raise SystemExit(1)
            """.trimMargin()
        val scriptB64 = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        val dollar = "${'$'}${'$'}"
        return """echo $dollar > /tmp/$pidFileName; exec python3 -c "import base64;exec(base64.b64decode('$scriptB64'))""""
    }

    private fun streamBytes(
        stream: java.io.InputStream,
        cancelled: AtomicBoolean,
        onOutput: (ByteArray, Int) -> Unit,
    ) {
        val buf = ByteArray(4096)
        try {
            while (!cancelled.get()) {
                val n = try {
                    stream.read(buf)
                } catch (e: IOException) {
                    if (cancelled.get()) break
                    throw e
                }
                if (n < 0) break
                if (n > 0) onOutput(buf, n)
            }
        } finally {
            runCatching { stream.close() }
        }
    }
}
