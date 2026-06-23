package com.leeyf.acpcommit.security

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ReadOnlyProjectFileSystem(
    roots: Collection<Path>,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) {
    private val allowedRoots = roots.mapNotNull { root ->
        runCatching { root.toRealPath() }.getOrNull()
    }.distinct()

    init {
        require(allowedRoots.isNotEmpty()) { "At least one existing project root is required" }
    }

    fun readTextFile(path: String, line: UInt? = null, limit: UInt? = null): String {
        val requested = Path.of(path)
        val resolved = if (requested.isAbsolute) requested else allowedRoots.first().resolve(requested)
        val realPath = runCatching { resolved.normalize().toRealPath() }
            .getOrElse { throw IllegalArgumentException("File does not exist: $path") }

        require(allowedRoots.any { realPath.startsWith(it) }) { "File is outside the project: $path" }
        require(Files.isRegularFile(realPath)) { "Path is not a regular file: $path" }
        val size = Files.size(realPath)
        require(size <= maxFileBytes) { "File is too large to read: $path" }

        val bytes = Files.readAllBytes(realPath)
        require(bytes.none { it == 0.toByte() }) { "Binary files cannot be read: $path" }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { throw IllegalArgumentException("File is not valid UTF-8 text: $path") }

        if (line == null && limit == null) return text
        val lines = text.split('\n')
        val start = ((line ?: 1u).toLong() - 1L).coerceAtLeast(0L).coerceAtMost(lines.size.toLong()).toInt()
        val count = (limit?.toLong() ?: (lines.size - start).toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return lines.drop(start).take(count).joinToString("\n")
    }

    companion object {
        const val DEFAULT_MAX_FILE_BYTES: Long = 1024L * 1024L
    }
}
