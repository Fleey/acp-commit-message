package com.leeyf.acpcommit.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object FakeAcpAgentMain {
    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun main(args: Array<String>) {
        val supportsModels = "--models" in args
        var currentModel = "default-model"
        val reader = System.`in`.bufferedReader()
        val writer = System.out.bufferedWriter()
        while (true) {
            val line = reader.readLine() ?: return
            val request = json.parseToJsonElement(line).jsonObject
            val id = request["id"] ?: continue
            when (request["method"]?.jsonPrimitive?.content) {
                "initialize" -> respond(writer, id.toString(), buildJsonObject {
                    put("protocolVersion", request.getValue("params").jsonObject.getValue("protocolVersion"))
                    put("agentCapabilities", buildJsonObject {})
                    put("authMethods", kotlinx.serialization.json.JsonArray(emptyList()))
                })
                "session/new" -> respond(writer, id.toString(), buildJsonObject {
                    put("sessionId", "test-session")
                    if (supportsModels) {
                        put("models", buildJsonObject {
                            put("currentModelId", currentModel)
                            put("availableModels", kotlinx.serialization.json.JsonArray(listOf(
                                buildJsonObject {
                                    put("modelId", "default-model")
                                    put("name", "Default Model")
                                },
                                buildJsonObject {
                                    put("modelId", "target-model")
                                    put("name", "Target Model")
                                },
                            )))
                        })
                    }
                })
                "session/set_model" -> {
                    currentModel = request.getValue("params").jsonObject.getValue("modelId").jsonPrimitive.content
                    respond(writer, id.toString(), buildJsonObject {})
                }
                "session/prompt" -> {
                    writer.write("""{"jsonrpc":"2.0","id":100,"method":"fs/read_text_file","params":{"sessionId":"test-session","path":"sample.txt"}}""")
                    writer.newLine()
                    writer.flush()
                    val fileResponse = json.parseToJsonElement(reader.readLine()).jsonObject
                    val content = fileResponse.getValue("result").jsonObject.getValue("content").jsonPrimitive.content
                    val subject = if (supportsModels) "$currentModel $content" else content
                    writer.write("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"test-session","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"feat(test): $subject"}}}}""")
                    writer.newLine()
                    writer.flush()
                    respond(writer, id.toString(), buildJsonObject { put("stopReason", "end_turn") })
                }
            }
        }
    }

    private fun respond(writer: java.io.BufferedWriter, id: String, result: JsonObject) {
        writer.write("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        writer.newLine()
        writer.flush()
    }
}
