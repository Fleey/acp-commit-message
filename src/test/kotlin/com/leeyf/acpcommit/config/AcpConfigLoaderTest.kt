package com.leeyf.acpcommit.config

import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class AcpConfigLoaderTest {
    @Test
    fun `loads and sorts local agents without exposing secrets`() {
        val file = createTempFile(suffix = ".json")
        try {
            file.writeText(
                """
                {
                  "default_mcp_settings": {"use_idea_mcp": true},
                  "agent_servers": {
                    "Zed": {"command": "/bin/zed", "env": {"API_KEY": "top-secret"}},
                    "Alpha": {"command": "/bin/alpha", "args": ["acp"]}
                  }
                }
                """.trimIndent()
            )

            val result = assertIs<AcpConfigResult.Success>(AcpConfigLoader.load(file))
            assertEquals(listOf("Alpha", "Zed"), result.snapshot.agents.keys.toList())
            assertFalse(result.snapshot.agents.getValue("Zed").toString().contains("top-secret"))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `rejects malformed and empty configurations`() {
        val malformed = createTempFile(suffix = ".json")
        val empty = createTempFile(suffix = ".json")
        try {
            malformed.writeText("{")
            empty.writeText("""{"agent_servers": {}}""")
            assertIs<AcpConfigResult.Failure>(AcpConfigLoader.load(malformed))
            assertIs<AcpConfigResult.Failure>(AcpConfigLoader.load(empty))
        } finally {
            malformed.deleteIfExists()
            empty.deleteIfExists()
        }
    }

    @Test
    fun `loads model hints from agent configuration`() {
        val file = createTempFile(suffix = ".json")
        try {
            file.writeText(
                """
                {
                  "agent_servers": {
                    "Claude": {
                      "command": "claude-acp",
                      "default_model": "sonnet",
                      "models": [
                        {"id": "opus", "name": "Claude Opus"},
                        "haiku"
                      ]
                    }
                  }
                }
                """.trimIndent()
            )

            val result = assertIs<AcpConfigResult.Success>(AcpConfigLoader.load(file))
            val agent = result.snapshot.agents.getValue("Claude")
            assertEquals("sonnet", agent.preferredModelHint())
            assertEquals(listOf("sonnet", "opus", "haiku"), agent.modelHints())
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `fingerprint changes with the visible agent set`() {
        val first = mapOf("Agent" to AcpAgentDefinition("agent", listOf("acp")))
        val second = mapOf("Agent 2" to AcpAgentDefinition("agent", listOf("acp")))
        assertNotEquals(AcpConfigLoader.fingerprint(first), AcpConfigLoader.fingerprint(second))
    }
}
