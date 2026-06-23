package com.leeyf.acpcommit.config

import com.leeyf.acpcommit.acp.AcpCommandResolver
import java.io.File
import java.nio.file.Path

data class DetectedAcpAgent(
    val name: String,
    val cliCommand: String,
    val definition: AcpAgentDefinition,
)

data class AcpAgentDetectionResult(
    val agents: List<DetectedAcpAgent>,
    val missingAdapterRuntimeFor: List<String>,
)

object AcpAgentDetector {
    fun detect(projectRoot: Path): AcpAgentDetectionResult =
        detect(
            resolveCommand = { command ->
                runCatching {
                    AcpCommandResolver.resolve(command, emptyMap(), projectRoot).executable
                }.getOrNull()
            },
            pathForAgent = AcpCommandResolver.effectivePathEntries(emptyMap()).joinToString(File.pathSeparator),
        )

    internal fun detect(
        resolveCommand: (String) -> String?,
        pathForAgent: String? = null,
    ): AcpAgentDetectionResult {
        val hasNpx = resolveCommand("npx") != null
        val detected = ArrayList<DetectedAcpAgent>()
        val missingRuntime = ArrayList<String>()

        fun addIfCliExists(name: String, cliCommand: String, adapterPackage: String) {
            if (resolveCommand(cliCommand) == null) return
            if (!hasNpx) {
                missingRuntime.add(name)
                return
            }
            detected.add(
                DetectedAcpAgent(
                    name = name,
                    cliCommand = cliCommand,
                    definition = AcpAgentDefinition(
                        command = "npx",
                        args = listOf("-y", adapterPackage),
                        env = pathForAgent
                            ?.takeIf { it.isNotBlank() }
                            ?.let { mapOf("PATH" to it) }
                            .orEmpty(),
                    ),
                )
            )
        }

        addIfCliExists("Claude", "claude", "@agentclientprotocol/claude-agent-acp")
        addIfCliExists("Codex", "codex", "@agentclientprotocol/codex-acp")
        return AcpAgentDetectionResult(detected, missingRuntime)
    }
}
