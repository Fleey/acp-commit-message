package com.leeyf.acpcommit.settings

import com.leeyf.acpcommit.acp.AcpCommitClient
import kotlin.test.Test
import kotlin.test.assertEquals

class AcpCommitSettingsTest {
    @Test
    fun `legacy startup timeout default is migrated to the current default`() {
        val settings = AcpCommitSettings()
        settings.loadState(
            AcpCommitSettings.State(
                settingsVersion = 1,
                startupTimeoutSeconds = 60,
            ),
        )

        assertEquals(AcpCommitClient.DEFAULT_STARTUP_TIMEOUT_SECONDS, settings.state.startupTimeoutSeconds)
    }

    @Test
    fun `current startup timeout selection is preserved`() {
        val settings = AcpCommitSettings()
        settings.loadState(
            AcpCommitSettings.State(
                settingsVersion = 2,
                startupTimeoutSeconds = 300,
            ),
        )

        assertEquals(300, settings.state.startupTimeoutSeconds)
    }
}
