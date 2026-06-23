package com.leeyf.acpcommit.config

import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AcpConfigWriterTest {
    @Test
    fun `adds detected agents while preserving existing acp settings`() {
        val file = createTempFile(suffix = ".json")
        try {
            file.writeText(
                """
                {
                  "default_mcp_settings": {"use_idea_mcp": true},
                  "agent_servers": {
                    "Existing": {"command": "/bin/existing"}
                  }
                }
                """.trimIndent()
            )

            val result = AcpConfigWriter.installDetectedAgents(
                listOf(
                    DetectedAcpAgent(
                        name = "Claude",
                        cliCommand = "claude",
                        definition = AcpAgentDefinition(
                            command = "npx",
                            args = listOf("-y", "@agentclientprotocol/claude-agent-acp"),
                        ),
                    )
                ),
                file,
            )

            val text = file.readText()
            assertEquals(listOf("Claude"), result.added)
            assertContains(text, "default_mcp_settings")
            assertContains(text, "\"Claude\"")
            assertContains(text, "@agentclientprotocol/claude-agent-acp")
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `skips already configured detected agents`() {
        val file = createTempFile(suffix = ".json")
        try {
            file.writeText(
                """
                {
                  "agent_servers": {
                    "Claude": {
                      "command": "npx",
                      "args": ["-y", "@agentclientprotocol/claude-agent-acp"]
                    }
                  }
                }
                """.trimIndent()
            )

            val result = AcpConfigWriter.installDetectedAgents(
                listOf(
                    DetectedAcpAgent(
                        name = "Claude",
                        cliCommand = "claude",
                        definition = AcpAgentDefinition(
                            command = "npx",
                            args = listOf("-y", "@agentclientprotocol/claude-agent-acp"),
                        ),
                    )
                ),
                file,
            )

            assertEquals(emptyList(), result.added)
            assertEquals(listOf("Claude"), result.skipped)
        } finally {
            file.deleteIfExists()
        }
    }
}
