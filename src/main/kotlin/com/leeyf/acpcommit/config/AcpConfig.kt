package com.leeyf.acpcommit.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Serializable
data class AcpAgentDefinition(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val model: String? = null,
    @SerialName("default_model")
    val defaultModel: String? = null,
    @SerialName("defaultModel")
    val defaultModelCamel: String? = null,
    val models: JsonElement? = null,
) {
    override fun toString(): String = "AcpAgentDefinition(command='$command', args=$args, env=<redacted>)"

    fun preferredModelHint(): String? =
        listOf(model, defaultModel, defaultModelCamel).firstNonBlank() ?: modelHints().firstOrNull()

    fun modelHints(): List<String> =
        (listOf(model, defaultModel, defaultModelCamel) + models.extractModelHints()).firstNonBlankDistinct()
}

@Serializable
private data class AcpConfiguration(
    @SerialName("agent_servers")
    val agentServers: Map<String, AcpAgentDefinition> = emptyMap(),
)

data class AcpConfigSnapshot(
    val agents: Map<String, AcpAgentDefinition>,
    val fingerprint: String,
)

sealed class AcpConfigResult {
    data class Success(val snapshot: AcpConfigSnapshot) : AcpConfigResult()
    data class Failure(val message: String, val cause: Throwable? = null) : AcpConfigResult()
}

object AcpConfigLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun defaultPath(): Path = Path.of(System.getProperty("user.home"), ".jetbrains", "acp.json")

    fun load(path: Path = defaultPath()): AcpConfigResult {
        if (!Files.isRegularFile(path)) {
            return AcpConfigResult.Failure("ACP configuration was not found at $path")
        }

        return try {
            val parsed = json.decodeFromString<AcpConfiguration>(Files.readString(path))
            val agents = parsed.agentServers
                .filterKeys { it.isNotBlank() }
                .filterValues { it.command.isNotBlank() }
                .toSortedMap()
            if (agents.isEmpty()) {
                AcpConfigResult.Failure("No local agents are configured in $path")
            } else {
                AcpConfigResult.Success(AcpConfigSnapshot(agents, fingerprint(agents)))
            }
        } catch (error: Exception) {
            AcpConfigResult.Failure("ACP configuration is invalid: ${error.message}", error)
        }
    }

    internal fun fingerprint(agents: Map<String, AcpAgentDefinition>): String {
        val stableAgentSet = agents.entries.joinToString("\u0000") { (name, definition) ->
            listOf(name, definition.command, definition.args.joinToString("\u0001")).joinToString("\u0002")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(stableAgentSet.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

private fun List<String?>.firstNonBlank(): String? =
    asSequence()
        .mapNotNull { it?.trim() }
        .firstOrNull { it.isNotEmpty() }

private fun List<String?>.firstNonBlankDistinct(): List<String> {
    val seen = LinkedHashSet<String>()
    for (value in this) {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        seen.add(normalized)
    }
    return seen.toList()
}

private fun JsonElement?.extractModelHints(): List<String?> {
    if (this == null) return emptyList()
    return when (this) {
        is JsonArray -> flatMap { it.extractModelHints() }
        is JsonPrimitive -> listOf(contentOrNull)
        is JsonObject -> {
            val direct = listOf(
                this["id"],
                this["model"],
                this["model_id"],
                this["modelId"],
                this["value"],
                this["name"],
            ).map { (it as? JsonPrimitive)?.contentOrNull }.firstNonBlank()
            listOf(direct) + this["models"].extractModelHints()
        }
    }
}
