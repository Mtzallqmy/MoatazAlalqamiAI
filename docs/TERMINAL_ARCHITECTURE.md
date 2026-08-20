# Moataz Terminal Architecture

## Chat terminal

The chat path and Moataz Code share the Debian installation and `/workspace`, but each conversation owns a persistent shell session and transcript. `execute_shell_command` starts at `/workspace`; its command, stdout, and stderr stream into the inline `TerminalPanel`, which automatically expands while a shell tool is running and remains available afterward. This pipe-backed chat shell is optimized for observable agent commands; the Moataz Code screen remains the true-PTY surface for fullscreen and raw-mode applications.

The existing PTY and VT implementation is retained. `BuildProotExecutor` creates
a real guest PTY, forwards raw UTF-8 bytes, applies `TIOCSWINSZ`, and signals
`SIGWINCH`. `TerminalScreen`/`VtParser` already handle cursor control, alternate
screen entry, mouse reporting, OSC links, resizing, and multiple sessions.

New stable contracts live under `terminal/`:

- `core/TerminalCore.kt`: session and engine boundary plus compatibility aliases
- `pty/TerminalSize.kt`: PTY session/geometry boundary
- `input/KeyEncoder.kt`: delegates to the proven terminal key encoder
- `config/TerminalProfile.kt`: built-in and versioned custom profile model

`BuildEnvironmentManager` remains a compatibility facade while session/process
ownership moves behind `TerminalEngine`. Rendering stays in Compose UI and does
not enter the runtime or CLI registry.

Known work that must not be advertised as complete until tested includes a
persistent searchable scrollback model and full 256-color/truecolor cell storage.
