package com.leeyf.acpcommit.acp

import java.io.File
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AcpCommandResolverTest {
    @Test
    fun `resolves bare command from configured path and exports effective path`() {
        val root = createTempDirectory("acp-command-root")
        val bin = createTempDirectory("acp-command-bin")
        try {
            val executable = bin.resolve("fake-agent")
            executable.writeText("#!/bin/sh\nexit 0\n")
            assertTrue(executable.toFile().setExecutable(true))

            val resolution = AcpCommandResolver.resolve(
                command = "fake-agent",
                environment = mapOf("PATH" to bin.toString()),
                projectRoot = root,
                includeUserShellPath = false,
            )

            assertEquals(executable.toAbsolutePath().normalize().toString(), resolution.executable)
            assertEquals(bin.toString(), resolution.pathForProcess?.split(File.pathSeparatorChar)?.first())
        } finally {
            root.toFile().deleteRecursively()
            bin.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolves project relative executable paths`() {
        val root = createTempDirectory("acp-command-root")
        try {
            val executable = root.resolve("tools").createDirectories().resolve("agent")
            executable.writeText("#!/bin/sh\nexit 0\n")
            assertTrue(executable.toFile().setExecutable(true))

            val resolution = AcpCommandResolver.resolve(
                command = "tools/agent",
                environment = emptyMap(),
                projectRoot = root,
                includeUserShellPath = false,
            )

            assertEquals(executable.toAbsolutePath().normalize().toString(), resolution.executable)
            assertEquals(null, resolution.pathForProcess)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reports missing bare command with absolute path hint`() {
        val root = createTempDirectory("acp-command-root")
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                AcpCommandResolver.resolve(
                    command = "missing-acp-command-for-test",
                    environment = mapOf("PATH" to root.toString()),
                    projectRoot = root,
                    includeUserShellPath = false,
                )
            }

            assertTrue(error.message.orEmpty().contains("absolute path"))
            assertTrue(error.message.orEmpty().contains("missing-acp-command-for-test"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
