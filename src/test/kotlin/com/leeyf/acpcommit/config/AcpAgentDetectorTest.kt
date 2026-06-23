package com.leeyf.acpcommit.config

import kotlin.test.Test
import kotlin.test.assertEquals

class AcpAgentDetectorTest {
    @Test
    fun `detects Claude and Codex ACP adapters when cli commands and npx exist`() {
        val available = setOf("claude", "codex", "npx")

        val result = AcpAgentDetector.detect(
            resolveCommand = { command -> command.takeIf { it in available } },
            pathForAgent = "/bin:/usr/bin",
        )

        assertEquals(listOf("Claude", "Codex"), result.agents.map { it.name })
        assertEquals("/bin:/usr/bin", result.agents.first { it.name == "Claude" }.definition.env["PATH"])
        assertEquals(
            listOf("-y", "@agentclientprotocol/claude-agent-acp"),
            result.agents.first { it.name == "Claude" }.definition.args,
        )
        assertEquals(
            listOf("-y", "@agentclientprotocol/codex-acp"),
            result.agents.first { it.name == "Codex" }.definition.args,
        )
    }

    @Test
    fun `reports cli commands that cannot be added without npx`() {
        val result = AcpAgentDetector.detect(resolveCommand = { command -> command.takeIf { it == "claude" } })

        assertEquals(emptyList(), result.agents)
        assertEquals(listOf("Claude"), result.missingAdapterRuntimeFor)
    }
}
