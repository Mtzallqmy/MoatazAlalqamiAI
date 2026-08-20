package com.inspiredandroid.kai.build

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildAgentsTest {
    @Test fun `OpenCode is the only runtime-required agent`() {
        assertEquals(listOf("opencode"), BuildAgents.autoInstallAgents.map { it.id })
    }
}
