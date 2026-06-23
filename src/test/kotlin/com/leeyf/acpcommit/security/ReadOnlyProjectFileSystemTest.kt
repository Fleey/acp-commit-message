package com.leeyf.acpcommit.security

import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReadOnlyProjectFileSystemTest {
    private val root = createTempDirectory("acp-project")
    private val outside = createTempDirectory("acp-outside")

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
        outside.toFile().deleteRecursively()
    }

    @Test
    fun `reads relative files with ACP line ranges`() {
        root.resolve("src").createDirectory()
        root.resolve("src/main.txt").writeText("one\ntwo\nthree")
        val fileSystem = ReadOnlyProjectFileSystem(listOf(root))
        assertEquals("two\nthree", fileSystem.readTextFile("src/main.txt", 2u, 2u))
    }

    @Test
    fun `rejects traversal binary and oversized files`() {
        outside.resolve("secret.txt").writeText("secret")
        root.resolve("binary.dat").writeBytes(byteArrayOf(1, 0, 2))
        root.resolve("large.txt").writeText("12345")
        val fileSystem = ReadOnlyProjectFileSystem(listOf(root), maxFileBytes = 4)

        assertFailsWith<IllegalArgumentException> { fileSystem.readTextFile(outside.resolve("secret.txt").toString()) }
        assertFailsWith<IllegalArgumentException> { fileSystem.readTextFile("binary.dat") }
        assertFailsWith<IllegalArgumentException> { fileSystem.readTextFile("large.txt") }
    }

    @Test
    fun `rejects a symlink that escapes the project`() {
        outside.resolve("secret.txt").writeText("secret")
        val link = root.resolve("link.txt")
        runCatching { Files.createSymbolicLink(link, outside.resolve("secret.txt")) }.getOrElse { return }
        val fileSystem = ReadOnlyProjectFileSystem(listOf(root))
        assertFailsWith<IllegalArgumentException> { fileSystem.readTextFile("link.txt") }
    }
}
