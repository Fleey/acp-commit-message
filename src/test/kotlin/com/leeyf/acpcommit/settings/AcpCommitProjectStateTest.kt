package com.leeyf.acpcommit.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AcpCommitProjectStateTest {
    @Test
    fun `remembered agent is valid only for the same configured agent set`() {
        val state = AcpCommitProjectState()
        state.rememberAgent("Alpha", "fingerprint-1")

        assertEquals("Alpha", state.validAgent(setOf("Alpha", "Beta"), "fingerprint-1"))
        assertNull(state.validAgent(setOf("Alpha", "Beta"), "fingerprint-2"))
        assertNull(state.validAgent(setOf("Beta"), "fingerprint-1"))
    }

    @Test
    fun `forgetting an agent clears all project selection state`() {
        val state = AcpCommitProjectState()
        state.rememberAgent("Alpha", "fingerprint")
        state.forgetAgent()

        assertNull(state.state.agentName)
        assertNull(state.state.agentSetFingerprint)
    }
}
