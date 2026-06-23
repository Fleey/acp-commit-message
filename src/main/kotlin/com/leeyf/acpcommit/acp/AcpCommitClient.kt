package com.leeyf.acpcommit.acp

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionCategory
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionConfigSelectOptions
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.acpFail
import com.agentclientprotocol.transport.StdioTransport
import com.leeyf.acpcommit.config.AcpAgentDefinition
import com.leeyf.acpcommit.security.ReadOnlyProjectFileSystem
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class AcpCommitClient(
    private val startupTimeoutSeconds: Int = DEFAULT_STARTUP_TIMEOUT_SECONDS,
) {
    suspend fun generate(
        agent: AcpAgentDefinition,
        projectRoot: Path,
        readableRoots: Collection<Path>,
        prompt: String,
        generationTimeoutSeconds: Int,
        preferredModel: String? = null,
    ): String = withAcpSession(agent, projectRoot, readableRoots, generationTimeoutSeconds) { session ->
        applyPreferredModel(session, preferredModel)

        val response = StringBuilder()
        var stopReason: StopReason? = null
        session.prompt(listOf(ContentBlock.Text(prompt))).collect { event ->
            when (event) {
                is Event.SessionUpdateEvent -> {
                    val update = event.update
                    val content = (update as? SessionUpdate.AgentMessageChunk)?.content
                    if (content is ContentBlock.Text) {
                        response.append(content.text)
                    }
                }
                is Event.PromptResponseEvent -> stopReason = event.response.stopReason
            }
        }

        if (stopReason != StopReason.END_TURN) {
            throw AcpCommitException("The ACP agent stopped with: ${stopReason ?: "no completion response"}")
        }
        response.toString()
    }

    suspend fun loadModelOptions(
        agent: AcpAgentDefinition,
        projectRoot: Path,
        readableRoots: Collection<Path>,
        timeoutSeconds: Int,
    ): List<String> = withAcpSession(agent, projectRoot, readableRoots, timeoutSeconds) { session ->
        session.modelOptions()
    }

    private suspend fun <T> withAcpSession(
        agent: AcpAgentDefinition,
        projectRoot: Path,
        readableRoots: Collection<Path>,
        timeoutSeconds: Int,
        block: suspend (ClientSession) -> T,
    ): T {
        val command = AcpCommandResolver.resolve(agent.command, agent.env, projectRoot)
        val commandLine = listOf(command.executable) + agent.args
        var npxCacheDirectory: String? = null
        val process = ProcessBuilder(commandLine)
            .directory(projectRoot.toFile())
            .apply {
                environment().putAll(agent.env)
                command.pathForProcess?.let { environment()["PATH"] = it }
                if (commandLine.isNpxCommand()) {
                    npxCacheDirectory = environment().configureNpxEnvironment()
                }
            }
            .start()

        val scope = CoroutineScope(Job() + Dispatchers.IO + CoroutineName("ACP commit client"))
        val stderr = StringBuilder()
        val stderrJob = scope.launch {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (stderr.length < MAX_STDERR_CHARS) stderr.appendLine(line)
                }
            }
        }
        val writer = process.outputStream.bufferedWriter()
        val writerMutex = Mutex()
        val transport = StdioTransport(
            parentScope = scope,
            ioDispatcher = Dispatchers.IO,
            input = process.inputStream.bufferedReader().asLineFlow(),
            output = { line -> writerMutex.withLock { writer.writeLineAndFlush(line) } },
            name = "ACP Commit Message",
        )

        return try {
            val protocol = Protocol(scope, transport)
            val client = Client(protocol)
            protocol.start()

            val agentInfo = withStartupTimeout("initializing the ACP agent", stderr, commandLine, npxCacheDirectory) {
                client.initialize(
                    ClientInfo(
                        capabilities = ClientCapabilities(
                            fs = FileSystemCapability(readTextFile = true, writeTextFile = false),
                            terminal = false,
                        ),
                        implementation = Implementation(
                            name = "acp-commit-message",
                            version = CLIENT_VERSION,
                            title = "ACP Commit Message",
                        ),
                    )
                )
            }

            val fileSystem = ReadOnlyProjectFileSystem(readableRoots)
            val operationsFactory = ClientOperationsFactory { _, _ -> ReadOnlyClientOperations(fileSystem) }
            val session = try {
                withStartupTimeout("creating an ACP session", stderr, commandLine, npxCacheDirectory) {
                    client.newSession(
                        SessionCreationParameters(
                            cwd = projectRoot.toString(),
                            mcpServers = emptyList(),
                        ),
                        operationsFactory,
                    )
                }
            } catch (error: AcpCommitException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val diagnosticText = listOfNotNull(error.message, stderr.toString()).joinToString("\n")
                val authHint = if (
                    agentInfo.authMethods.isNotEmpty() || AUTH_FAILURE_PATTERN.containsMatchIn(diagnosticText)
                ) {
                    " Authenticate the agent in a terminal, then try again."
                } else {
                    ""
                }
                throw AcpCommitException("The ACP agent could not create a session.$authHint", error)
            }
            withResponseTimeout(timeoutSeconds) {
                block(session)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is AcpCommitException) throw error
            val diagnosticText = listOfNotNull(error.message, stderr.toString()).joinToString("\n")
            val authenticationHint = if (AUTH_FAILURE_PATTERN.containsMatchIn(diagnosticText)) {
                " Authenticate the agent in a terminal, then try again."
            } else {
                ""
            }
            val exitDetail = if (!process.isAlive) " Agent exited with code ${process.exitValue()}." else ""
            throw AcpCommitException(
                "ACP generation failed: ${error.message ?: "unexpected agent failure"}.$exitDetail$authenticationHint",
                error,
            )
        } finally {
            runCatching { transport.close() }
            runCatching { writer.close() }
            if (process.isAlive) process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS) && process.isAlive) process.destroyForcibly()
            stderrJob.cancel()
            scope.cancel()
        }
    }

    private suspend fun <T> withStartupTimeout(
        step: String,
        stderr: StringBuilder,
        commandLine: List<String>,
        npxCacheDirectory: String?,
        block: suspend () -> T,
    ): T =
        try {
            val effectiveTimeout = startupTimeoutSeconds.coerceIn(
                MIN_STARTUP_TIMEOUT_SECONDS,
                MAX_STARTUP_TIMEOUT_SECONDS,
            )
            withTimeout(effectiveTimeout.seconds) {
                block()
            }
        } catch (error: TimeoutCancellationException) {
            val effectiveTimeout = startupTimeoutSeconds.coerceIn(
                MIN_STARTUP_TIMEOUT_SECONDS,
                MAX_STARTUP_TIMEOUT_SECONDS,
            )
            throw AcpCommitException(
                startupTimeoutMessage(step, effectiveTimeout, stderr.toString(), commandLine, npxCacheDirectory),
                error,
            )
        }

    private suspend fun <T> withResponseTimeout(timeoutSeconds: Int, block: suspend () -> T): T =
        try {
            val effectiveTimeout = timeoutSeconds.coerceIn(MIN_RESPONSE_TIMEOUT_SECONDS, MAX_RESPONSE_TIMEOUT_SECONDS)
            withTimeout(effectiveTimeout.seconds) {
                block()
            }
        } catch (error: TimeoutCancellationException) {
            val effectiveTimeout = timeoutSeconds.coerceIn(MIN_RESPONSE_TIMEOUT_SECONDS, MAX_RESPONSE_TIMEOUT_SECONDS)
            throw AcpCommitException(
                "Timed out while waiting for the ACP agent response after ${effectiveTimeout}s. " +
                    "Increase Generation timeout in Settings | Version Control | ACP Commit Message, " +
                    "or reduce the selected changes.",
                error,
            )
        }

    @OptIn(UnstableApi::class)
    private suspend fun applyPreferredModel(session: ClientSession, preferredModel: String?) {
        val model = preferredModel?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (applyDedicatedModel(session, model)) return
        applyModelConfigOption(session, model)
    }

    @OptIn(UnstableApi::class)
    private suspend fun applyDedicatedModel(session: ClientSession, model: String): Boolean {
        if (!session.modelsSupported) return false

        val available = session.availableModels
        val match = available.firstOrNull { it.modelId.value == model || it.name == model }
            ?: available.firstOrNull {
                it.modelId.value.equals(model, ignoreCase = true) || it.name.equals(model, ignoreCase = true)
            }
            ?: throw AcpCommitException(
                "Selected model '$model' is not available for this ACP agent. " +
                    availableModelsMessage(available.map { "${it.name} (${it.modelId.value})" }),
            )

        if (session.currentModel.value.value != match.modelId.value) {
            session.setModel(match.modelId)
        }
        return true
    }

    @OptIn(UnstableApi::class)
    private suspend fun applyModelConfigOption(session: ClientSession, model: String): Boolean {
        if (!session.configOptionsSupported) return false

        val option = session.configOptions.value
            .filterIsInstance<SessionConfigOption.Select>()
            .firstOrNull {
                it.category == SessionConfigOptionCategory.MODEL ||
                    it.id.value.contains("model", ignoreCase = true) ||
                    it.name.contains("model", ignoreCase = true)
            }
            ?: return false

        val options = option.modelValues()
        val match = options.firstOrNull { (value, name) -> value == model || name == model }
            ?: options.firstOrNull { (value, name) ->
                value.equals(model, ignoreCase = true) || name.equals(model, ignoreCase = true)
            }
            ?: throw AcpCommitException(
                "Selected model '$model' is not available for this ACP agent. " +
                    availableModelsMessage(options.map { (value, name) -> "$name ($value)" }),
            )

        session.setConfigOption(option.id, SessionConfigOptionValue.of(match.first))
        return true
    }

    private fun SessionConfigOption.Select.modelValues(): List<Pair<String, String>> =
        when (val selectOptions = options) {
            is SessionConfigSelectOptions.Flat -> selectOptions.options.map { it.value.value to it.name }
            is SessionConfigSelectOptions.Grouped -> selectOptions.groups.flatMap { group ->
                group.options.map { it.value.value to it.name }
            }
        }

    @OptIn(UnstableApi::class)
    private fun ClientSession.modelOptions(): List<String> {
        val result = LinkedHashSet<String>()
        if (modelsSupported) {
            val current = currentModel.value.value
            current.takeIf { it.isNotBlank() }?.let(result::add)
            availableModels.map { it.modelId.value }.filterTo(result) { it.isNotBlank() }
        }
        if (configOptionsSupported) {
            configOptions.value
                .filterIsInstance<SessionConfigOption.Select>()
                .filter {
                    it.category == SessionConfigOptionCategory.MODEL ||
                        it.id.value.contains("model", ignoreCase = true) ||
                        it.name.contains("model", ignoreCase = true)
                }
                .flatMap { it.modelValues() }
                .map { (value, _) -> value }
                .filterTo(result) { it.isNotBlank() }
        }
        return result.toList()
    }

    private fun availableModelsMessage(values: List<String>): String =
        if (values.isEmpty()) {
            "The agent did not report any available models."
        } else {
            "Available models: ${values.joinToString(", ")}"
        }

    private fun startupTimeoutMessage(
        step: String,
        timeoutSeconds: Int,
        stderrText: String,
        commandLine: List<String>,
        npxCacheDirectory: String?,
    ): String = buildString {
        append("Timed out while $step after ${timeoutSeconds}s. ")
        if (commandLine.isNpxCommand()) {
            append(
                "This ACP agent is launched through npx, so the first run can be slow while npm installs " +
                    "or refreshes the adapter package. ",
            )
        }
        if (NPM_ENOTEMPTY_PATTERN.containsMatchIn(stderrText)) {
            append(
                "npm reported ENOTEMPTY while renaming an npx package directory, which usually means the npx cache " +
                    "is corrupted or another npm/npx process touched it during install. ",
            )
            npxCacheDirectory?.takeIf { it.isNotBlank() }?.let {
                append("This plugin is using npm cache '$it'. ")
            }
        }
        append(
            "Increase Agent startup timeout in Settings | Version Control | ACP Commit Message, " +
                "or run the agent once in a terminal to finish npx installation/authentication.",
        )

        val diagnostic = stderrText.safeDiagnosticSnippet()
        if (diagnostic.isNotBlank()) {
            append("\n\nAgent stderr:\n")
            append(diagnostic)
        } else {
            append("\n\nAgent stderr was empty, so the process started but did not answer ACP initialize.")
        }
    }

    private fun List<String>.isNpxCommand(): Boolean {
        val executableName = firstOrNull()
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.lowercase()
            ?: return false
        return executableName == "npx" || executableName == "npx.cmd"
    }

    private fun MutableMap<String, String>.configureNpxEnvironment(): String? {
        putIfAbsent("NPM_CONFIG_YES", "true")
        putIfAbsent("npm_config_yes", "true")
        putIfAbsent("NPM_CONFIG_UPDATE_NOTIFIER", "false")
        putIfAbsent("NO_UPDATE_NOTIFIER", "1")

        val configuredCache = this["NPM_CONFIG_CACHE"] ?: this["npm_config_cache"]
        if (!configuredCache.isNullOrBlank()) return configuredCache

        val cacheDirectory = defaultNpxCacheDirectory()
        runCatching { Files.createDirectories(cacheDirectory) }
        val cache = cacheDirectory.toString()
        this["NPM_CONFIG_CACHE"] = cache
        this["npm_config_cache"] = cache
        return cache
    }

    private fun defaultNpxCacheDirectory(): Path {
        val userHome = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
        return if (userHome == null) {
            Path.of(System.getProperty("java.io.tmpdir"), "acp-commit-message", "npm-cache")
        } else {
            Path.of(userHome, ".cache", "acp-commit-message", "npm-cache")
        }
    }

    private fun String.safeDiagnosticSnippet(): String =
        replace(ANSI_ESCAPE_PATTERN, "")
            .replace(SECRET_ASSIGNMENT_PATTERN) { match -> "${match.groupValues[1]}=<redacted>" }
            .replace(BEARER_TOKEN_PATTERN, "Bearer <redacted>")
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(MAX_STDERR_LINES_IN_MESSAGE)
            .joinToString("\n")
            .takeLast(MAX_STDERR_MESSAGE_CHARS)

    private fun java.io.BufferedReader.asLineFlow() = flow {
        use { reader ->
            while (true) {
                val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                emit(line)
            }
        }
    }

    private suspend fun BufferedWriter.writeLineAndFlush(line: String) = withContext(Dispatchers.IO) {
        write(line)
        newLine()
        flush()
    }

    private class ReadOnlyClientOperations(
        private val fileSystem: ReadOnlyProjectFileSystem,
    ) : ClientSessionOperations {
        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: JsonElement?,
        ): RequestPermissionResponse {
            val rejection = permissions.firstOrNull {
                it.kind == PermissionOptionKind.REJECT_ONCE || it.kind == PermissionOptionKind.REJECT_ALWAYS
            }
            return if (rejection == null) {
                RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
            } else {
                RequestPermissionResponse(RequestPermissionOutcome.Selected(rejection.optionId))
            }
        }

        override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit

        override suspend fun fsReadTextFile(
            path: String,
            line: UInt?,
            limit: UInt?,
            _meta: JsonElement?,
        ): ReadTextFileResponse = try {
            ReadTextFileResponse(fileSystem.readTextFile(path, line, limit))
        } catch (error: IllegalArgumentException) {
            acpFail(error.message ?: "File read was denied")
        }
    }

    companion object {
        const val DEFAULT_STARTUP_TIMEOUT_SECONDS = 120
        const val MIN_STARTUP_TIMEOUT_SECONDS = 15
        const val MAX_STARTUP_TIMEOUT_SECONDS = 600
        private const val MIN_RESPONSE_TIMEOUT_SECONDS = 15
        private const val MAX_RESPONSE_TIMEOUT_SECONDS = 600
        private const val CLIENT_VERSION = "0.1.7"
        private const val MAX_STDERR_CHARS = 16_384
        private const val MAX_STDERR_LINES_IN_MESSAGE = 24
        private const val MAX_STDERR_MESSAGE_CHARS = 2_000
        private val AUTH_FAILURE_PATTERN = Regex(
            "authentication|unauthorized|not authenticated|login required|sign[ -]?in|\\b401\\b",
            RegexOption.IGNORE_CASE,
        )
        private val ANSI_ESCAPE_PATTERN = Regex("\\u001B\\[[;\\d]*[ -/]*[@-~]")
        private val SECRET_ASSIGNMENT_PATTERN = Regex(
            "\\b([A-Z0-9_]*(?:TOKEN|SECRET|PASSWORD|API[_-]?KEY)[A-Z0-9_]*)\\s*=\\s*[^\\s]+",
            RegexOption.IGNORE_CASE,
        )
        private val BEARER_TOKEN_PATTERN = Regex("\\bBearer\\s+[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE)
        private val NPM_ENOTEMPTY_PATTERN = Regex("\\bENOTEMPTY\\b|npm error code ENOTEMPTY", RegexOption.IGNORE_CASE)
    }
}

class AcpCommitException(message: String, cause: Throwable? = null) : Exception(message, cause)
