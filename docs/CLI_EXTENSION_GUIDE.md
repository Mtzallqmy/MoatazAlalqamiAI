# CLI Extension Guide

Developer tools are `CliDefinition` records in `cli/CliRegistry.kt`. Terminal
core has no provider-specific branches.

To add a CLI, register one definition:

```kotlin
registry.register(
    CliDefinition(
        id = "aider",
        displayName = "aider",
        executable = "aider",
        install = InstallStrategy.Pipx("aider-chat"),
        category = CliCategory.Agent,
        homepage = "https://aider.chat",
    ),
)
```

The detector requires both `command -v` and a successful version command.
Installer exit code zero is insufficient: if detection fails, status is
`Broken`, never `Ready`. HTTPS script installers are downloaded to a file with
timeout/protocol restrictions and host allowlisting, optionally hash-checked,
then executed; the UI does not run `curl | bash`.

Add a built-in terminal entry only when the tool should appear as a profile.
User-created profiles belong in the versioned `TerminalProfileStore` and do not
require an APK rebuild.
