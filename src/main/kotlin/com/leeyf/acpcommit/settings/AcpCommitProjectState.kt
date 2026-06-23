package com.leeyf.acpcommit.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "AcpCommitProjectState", storages = [Storage("acpCommit.xml")])
class AcpCommitProjectState : PersistentStateComponent<AcpCommitProjectState.State> {
    data class State(
        var agentName: String? = null,
        var agentSetFingerprint: String? = null,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun rememberAgent(name: String, fingerprint: String) {
        state.agentName = name
        state.agentSetFingerprint = fingerprint
    }

    fun forgetAgent() {
        state.agentName = null
        state.agentSetFingerprint = null
    }

    fun validAgent(availableNames: Set<String>, fingerprint: String): String? =
        state.agentName?.takeIf { it in availableNames && state.agentSetFingerprint == fingerprint }
}
