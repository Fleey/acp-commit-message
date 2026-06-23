package com.leeyf.acpcommit.acp

import com.leeyf.acpcommit.config.AcpAgentDefinition
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class AcpCommitClientIntegrationTest {
    @Test
    fun `streams a commit message and serves project files read only`() = runBlocking {
        val root = createTempDirectory("acp-integration")
        try {
            root.resolve("sample.txt").writeText("project context")
            val javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val classpath = System.getProperty("java.class.path")
                .split(java.io.File.pathSeparator)
                .joinToString(java.io.File.pathSeparator) { java.nio.file.Path.of(it).toAbsolutePath().toString() }
            val agent = AcpAgentDefinition(
                command = javaExecutable,
                args = listOf("-cp", classpath, FakeAcpAgentMain::class.java.name),
            )
            val result = AcpCommitClient().generate(agent, root, listOf(root), "prompt", 15)
            assertEquals("feat(test): project context", result)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sets the preferred ACP model when the agent supports models`() = runBlocking {
        val root = createTempDirectory("acp-integration")
        try {
            root.resolve("sample.txt").writeText("project context")
            val javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val classpath = System.getProperty("java.class.path")
                .split(java.io.File.pathSeparator)
                .joinToString(java.io.File.pathSeparator) { java.nio.file.Path.of(it).toAbsolutePath().toString() }
            val agent = AcpAgentDefinition(
                command = javaExecutable,
                args = listOf("-cp", classpath, FakeAcpAgentMain::class.java.name, "--models"),
            )
            val result = AcpCommitClient().generate(
                agent = agent,
                projectRoot = root,
                readableRoots = listOf(root),
                prompt = "prompt",
                generationTimeoutSeconds = 15,
                preferredModel = "target-model",
            )
            assertEquals("feat(test): target-model project context", result)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loads dynamic model options from the ACP session`() = runBlocking {
        val root = createTempDirectory("acp-integration")
        try {
            val javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val classpath = System.getProperty("java.class.path")
                .split(java.io.File.pathSeparator)
                .joinToString(java.io.File.pathSeparator) { java.nio.file.Path.of(it).toAbsolutePath().toString() }
            val agent = AcpAgentDefinition(
                command = javaExecutable,
                args = listOf("-cp", classpath, FakeAcpAgentMain::class.java.name, "--models"),
            )
            val models = AcpCommitClient().loadModelOptions(
                agent = agent,
                projectRoot = root,
                readableRoots = listOf(root),
                timeoutSeconds = 15,
            )
            assertEquals(listOf("default-model", "target-model"), models)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
