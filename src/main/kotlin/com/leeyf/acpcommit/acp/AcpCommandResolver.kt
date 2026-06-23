package com.leeyf.acpcommit.acp

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class AcpCommandResolution(
    val executable: String,
    val pathForProcess: String?,
)

internal object AcpCommandResolver {
    private const val PATH_BEGIN = "__ACP_COMMIT_PATH_BEGIN__"
    private const val PATH_END = "__ACP_COMMIT_PATH_END__"
    private const val SHELL_PATH_TIMEOUT_MILLIS = 1_500L

    private val cachedShellPathEntries = AtomicReference<List<String>?>(null)

    fun resolve(
        command: String,
        environment: Map<String, String>,
        projectRoot: Path,
        includeUserShellPath: Boolean = true,
    ): AcpCommandResolution {
        require(command.isNotBlank()) { "ACP agent command is empty." }

        if (command.hasPathSeparator()) {
            val configuredPath = expandHome(command)
            val resolved = if (configuredPath.isAbsolute) {
                configuredPath.normalize()
            } else {
                projectRoot.resolve(configuredPath).normalize()
            }
            require(Files.isRegularFile(resolved)) { "ACP agent executable does not exist: $command" }
            require(Files.isExecutable(resolved)) { "ACP agent command is not executable: $command" }
            return AcpCommandResolution(resolved.toString(), null)
        }

        val pathEntries = effectivePathEntries(environment, includeUserShellPath)
        val executable = pathEntries.asSequence()
            .map { Path.of(it).resolve(command) }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

        require(executable != null) {
            "ACP agent command was not found: $command. Configure an absolute path in ~/.jetbrains/acp.json " +
                "or make the command available from your terminal login PATH."
        }

        return AcpCommandResolution(
            executable = executable.toAbsolutePath().normalize().toString(),
            pathForProcess = pathEntries.joinToString(File.pathSeparator),
        )
    }

    internal fun effectivePathEntries(
        environment: Map<String, String>,
        includeUserShellPath: Boolean = true,
    ): List<String> = buildList {
        addPathEntries(environmentPath(environment))
        if (includeUserShellPath) addAll(readUserShellPathEntries())
        addPathEntries(System.getenv("PATH"))
        addAll(commonPathEntries())
    }.deduplicatePathEntries()

    private fun readUserShellPathEntries(): List<String> {
        cachedShellPathEntries.get()?.let { return it }
        val discovered = discoverUserShellPathEntries()
        cachedShellPathEntries.compareAndSet(null, discovered)
        return cachedShellPathEntries.get() ?: discovered
    }

    private fun discoverUserShellPathEntries(): List<String> {
        if (isWindows()) return emptyList()

        val command = "printf '\\n$PATH_BEGIN%s$PATH_END\\n' \"\$PATH\""
        for (shell in candidateShells()) {
            val args = shellArgs(shell, command)
            val output = runShellForPath(shell, args) ?: continue
            val path = output.substringAfter(PATH_BEGIN, missingDelimiterValue = "")
                .substringBefore(PATH_END, missingDelimiterValue = "")
            val entries = path.splitPathEntries().deduplicatePathEntries()
            if (entries.isNotEmpty()) return entries
        }
        return emptyList()
    }

    private fun runShellForPath(shell: Path, args: List<String>): String? = runCatching {
        val process = ProcessBuilder(listOf(shell.toString()) + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.outputStream.close()
        if (!process.waitFor(SHELL_PATH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun candidateShells(): List<Path> = buildList {
        val shell = System.getenv("SHELL")
        if (!shell.isNullOrBlank()) add(Path.of(shell))
        add(Path.of("/bin/zsh"))
        add(Path.of("/bin/bash"))
        add(Path.of("/bin/sh"))
    }.filter { Files.isRegularFile(it) && Files.isExecutable(it) }
        .distinctBy { it.toAbsolutePath().normalize().toString() }

    private fun shellArgs(shell: Path, command: String): List<String> {
        val name = shell.fileName.toString()
        return when (name) {
            "zsh", "bash" -> listOf("-l", "-i", "-c", command)
            else -> listOf("-c", command)
        }
    }

    private fun commonPathEntries(): List<String> = buildList {
        add("/opt/homebrew/bin")
        add("/opt/homebrew/sbin")
        add("/usr/local/bin")
        add("/usr/local/sbin")
        add("/usr/bin")
        add("/bin")
        add("/usr/sbin")
        add("/sbin")

        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return@buildList
        add("$home/.local/bin")
        add("$home/bin")
        add("$home/.cargo/bin")
        add("$home/.volta/bin")
        add("$home/.bun/bin")
        add("$home/.trae/bin")
        add("$home/.nvm/current/bin")
        addAll(nvmNodeBins(Path.of(home, ".nvm", "versions", "node")))
    }

    private fun nvmNodeBins(nodeVersions: Path): List<String> = runCatching {
        if (!Files.isDirectory(nodeVersions)) return@runCatching emptyList()
        Files.newDirectoryStream(nodeVersions).use { stream ->
            stream.asSequence()
                .filter { Files.isDirectory(it.resolve("bin")) }
                .sortedByDescending { it.fileName.toString() }
                .map { it.resolve("bin").toString() }
                .toList()
        }
    }.getOrDefault(emptyList())

    private fun MutableList<String>.addPathEntries(path: String?) {
        addAll(path.splitPathEntries())
    }

    private fun String?.splitPathEntries(): List<String> {
        if (this.isNullOrBlank()) return emptyList()
        return split(File.pathSeparatorChar)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { expandHome(it).toString() }
            .toList()
    }

    private fun List<String>.deduplicatePathEntries(): List<String> {
        val seen = LinkedHashSet<String>()
        val result = ArrayList<String>()
        for (entry in this) {
            val normalized = runCatching { Path.of(entry).toAbsolutePath().normalize().toString() }
                .getOrDefault(entry)
            if (seen.add(normalized)) result.add(entry)
        }
        return result
    }

    private fun environmentPath(environment: Map<String, String>): String? {
        if (!isWindows()) return environment["PATH"]
        return environment.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value
    }

    private fun expandHome(value: String): Path {
        if (value == "~" || value.startsWith("~/")) {
            val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
            if (home != null) return Path.of(home).resolve(value.removePrefix("~/").removePrefix("~"))
        }
        return Path.of(value)
    }

    private fun String.hasPathSeparator(): Boolean = contains('/') || contains('\\')

    private fun isWindows(): Boolean = System.getProperty("os.name").contains("Windows", ignoreCase = true)
}
