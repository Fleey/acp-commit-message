package com.leeyf.acpcommit.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

data class AcpConfigInstallResult(
    val path: Path,
    val added: List<String>,
    val skipped: List<String>,
)

object AcpConfigWriter {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        explicitNulls = false
    }

    fun installDetectedAgents(
        detectedAgents: List<DetectedAcpAgent>,
        path: Path = AcpConfigLoader.defaultPath(),
    ): AcpConfigInstallResult {
        val root = readRoot(path)
        val existingServers = (root["agent_servers"] as? JsonObject)?.toMutableMap() ?: linkedMapOf()

        val added = ArrayList<String>()
        val skipped = ArrayList<String>()
        for (detected in detectedAgents) {
            if (isAlreadyConfigured(existingServers, detected)) {
                skipped.add(detected.name)
                continue
            }
            val name = uniqueName(detected.name, existingServers.keys)
            existingServers[name] = definitionToJson(detected.definition)
            added.add(name)
        }

        if (added.isEmpty()) {
            return AcpConfigInstallResult(path, added, skipped)
        }

        val updated = buildJsonObject {
            root.forEach { (key, value) ->
                if (key != "agent_servers") put(key, value)
            }
            put("agent_servers", JsonObject(existingServers))
        }

        Files.createDirectories(path.parent)
        Files.writeString(path, json.encodeToString(JsonElement.serializer(), updated))
        return AcpConfigInstallResult(path, added, skipped)
    }

    private fun readRoot(path: Path): JsonObject {
        if (!Files.exists(path)) return buildJsonObject {}
        val text = Files.readString(path)
        if (text.isBlank()) return buildJsonObject {}
        return json.parseToJsonElement(text).jsonObject
    }

    private fun isAlreadyConfigured(
        existingServers: Map<String, JsonElement>,
        detected: DetectedAcpAgent,
    ): Boolean {
        if (detected.name in existingServers) return true
        return existingServers.values.any { value ->
            val definition = runCatching { json.decodeFromJsonElement(AcpAgentDefinition.serializer(), value) }.getOrNull()
            definition?.command == detected.definition.command && definition.args == detected.definition.args
        }
    }

    private fun uniqueName(base: String, existingNames: Set<String>): String {
        if (base !in existingNames) return base
        var index = 2
        while (true) {
            val candidate = "$base $index"
            if (candidate !in existingNames) return candidate
            index++
        }
    }

    private fun definitionToJson(definition: AcpAgentDefinition): JsonObject = buildJsonObject {
        put("command", definition.command)
        if (definition.args.isNotEmpty()) {
            put("args", JsonArray(definition.args.map(::JsonPrimitive)))
        }
        if (definition.env.isNotEmpty()) {
            put("env", JsonObject(definition.env.mapValues { (_, value) -> JsonPrimitive(value) }))
        }
        definition.preferredModelHint()?.let { put("model", it) }
    }
}
