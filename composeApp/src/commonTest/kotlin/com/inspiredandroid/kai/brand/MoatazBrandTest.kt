package com.inspiredandroid.kai.brand

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoatazBrandTest {
    @Test fun `public identity is Moataz`() {
        val names = listOf(
            MoatazBrand.productName, MoatazBrand.codeName, MoatazBrand.terminalName,
            MoatazBrand.runtimeName, MoatazBrand.agentsName, MoatazBrand.gatewayName,
        )
        assertTrue(names.all { it.contains("Moataz") })
        assertFalse(names.any { it.contains("Kai", ignoreCase = true) })
    }

    @Test fun `default persona identifies itself as AI`() {
        assertTrue(AssistantIdentity.Default.systemIdentity.contains("AI"))
    }
}
