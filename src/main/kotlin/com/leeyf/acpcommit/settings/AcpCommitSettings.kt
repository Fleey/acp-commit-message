package com.leeyf.acpcommit.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.leeyf.acpcommit.acp.AcpCommitClient
import com.leeyf.acpcommit.config.AcpAgentDefinition

@State(name = "AcpCommitSettings", storages = [Storage("acpCommit.xml")])
class AcpCommitSettings : PersistentStateComponent<AcpCommitSettings.State> {
    data class State(
        var settingsVersion: Int = CURRENT_SETTINGS_VERSION,
        var language: String = LANGUAGE_ZH_CN,
        var conventionalCommits: Boolean = true,
        var customPrompt: String = "",
        var maxDiffKiB: Int = 512,
        var startupTimeoutSeconds: Int = AcpCommitClient.DEFAULT_STARTUP_TIMEOUT_SECONDS,
        var generationTimeoutSeconds: Int = 120,
        var agentModels: MutableMap<String, String> = linkedMapOf(),
        var agentModelOptions: MutableMap<String, MutableList<String>> = linkedMapOf(),
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        if (
            state.settingsVersion < CURRENT_SETTINGS_VERSION &&
            state.startupTimeoutSeconds == LEGACY_DEFAULT_STARTUP_TIMEOUT_SECONDS
        ) {
            state.startupTimeoutSeconds = AcpCommitClient.DEFAULT_STARTUP_TIMEOUT_SECONDS
        }
        state.startupTimeoutSeconds = state.startupTimeoutSeconds.coerceIn(
            AcpCommitClient.MIN_STARTUP_TIMEOUT_SECONDS,
            AcpCommitClient.MAX_STARTUP_TIMEOUT_SECONDS,
        )
        state.settingsVersion = CURRENT_SETTINGS_VERSION
        this.state = state
    }

    fun modelSelectionForAgent(agentName: String, agent: AcpAgentDefinition): String =
        state.agentModels[agentName]?.trim()?.takeIf { it.isNotEmpty() }
            ?: agent.preferredModelHint().orEmpty()

    fun modelOptionsForAgent(agentName: String, agent: AcpAgentDefinition): List<String> {
        val seen = LinkedHashSet<String>()
        agent.modelHints().filterTo(seen) { it.isNotBlank() }
        state.agentModelOptions[agentName].orEmpty().filterTo(seen) { it.isNotBlank() }
        return seen.toList()
    }

    fun selectedModelForAgent(agentName: String, agent: AcpAgentDefinition): String? {
        val selection = modelSelectionForAgent(agentName, agent)
        return selection
            .takeUnless { it == AGENT_DEFAULT_MODEL }
            ?.takeIf { it.isNotBlank() }
    }

    fun rememberAgentModelSelection(agentName: String, selection: String) {
        val normalized = selection.trim()
        if (normalized.isEmpty()) {
            state.agentModels.remove(agentName)
        } else {
            state.agentModels[agentName] = normalized
        }
    }

    fun rememberAgentModelOptions(agentName: String, models: List<String>) {
        val normalized = models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalized.isEmpty()) {
            state.agentModelOptions.remove(agentName)
        } else {
            state.agentModelOptions[agentName] = normalized.toMutableList()
        }
    }

    companion object {
        const val LANGUAGE_ZH_CN = "简体中文"
        const val LANGUAGE_EN = "English"
        const val AGENT_DEFAULT_MODEL = "__agent_default__"
        private const val CURRENT_SETTINGS_VERSION = 2
        private const val LEGACY_DEFAULT_STARTUP_TIMEOUT_SECONDS = 60

        fun getInstance(): AcpCommitSettings =
            ApplicationManager.getApplication().getService(AcpCommitSettings::class.java)
    }
}
