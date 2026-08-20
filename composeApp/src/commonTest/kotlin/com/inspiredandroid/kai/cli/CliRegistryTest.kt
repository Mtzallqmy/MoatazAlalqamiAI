package com.inspiredandroid.kai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliRegistryTest {
    @Test fun `registry adds a CLI without terminal changes`() {
        val registry = CliRegistry(emptyList())
        registry.register(CliDefinition("aider", "aider", "aider", install = InstallStrategy.Pipx("aider-chat"), category = CliCategory.Agent))
        assertNotNull(registry.get("aider"))
    }

    @Test fun `detector requires both executable and version probe`() {
        val detector = CliDetector { command ->
            when {
                command.startsWith("command -v") -> CliCommandResult(0, "/usr/bin/git\n")
                else -> CliCommandResult(0, "git version 2.47.0\n")
            }
        }
        val status = detector.detect(defaultCliDefinitions().first { it.id == "git" })
        assertEquals("git version 2.47.0", assertIs<CliStatus.Ready>(status).version)
    }

    @Test fun `successful installer with missing binary is broken`() {
        val runner = CliCommandRunner { command ->
            if (command.startsWith("apt-get")) CliCommandResult(0) else CliCommandResult(1)
        }
        val status = CliInstaller(runner).install(defaultCliDefinitions().first { it.id == "git" })
        assertIs<CliStatus.Broken>(status)
    }

    @Test fun `apt installer preserves package argument boundaries`() {
        var installCommand = ""
        val definition = CliDefinition(
            id = "bundle",
            displayName = "Bundle",
            executable = "bundle",
            install = InstallStrategy.Apt(listOf("first", "second package")),
        )
        val runner = CliCommandRunner { command ->
            if (command.startsWith("apt-get")) {
                installCommand = command
                CliCommandResult(0)
            } else {
                CliCommandResult(1)
            }
        }

        CliInstaller(runner).install(definition)

        assertTrue("'first' 'second package'" in installCommand)
    }
}
