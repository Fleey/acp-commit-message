package com.leeyf.acpcommit.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.leeyf.acpcommit.acp.AcpCommitClient
import com.leeyf.acpcommit.config.AcpAgentDetector
import com.leeyf.acpcommit.config.AcpAgentDefinition
import com.leeyf.acpcommit.config.AcpConfigLoader
import com.leeyf.acpcommit.config.AcpConfigResult
import com.leeyf.acpcommit.config.AcpConfigWriter
import com.leeyf.acpcommit.notification.AcpCommitNotifications
import kotlinx.coroutines.runBlocking
import java.awt.event.ActionListener
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JPanel

class AcpCommitConfigurable(private val project: Project) : Configurable {
    private val agentCombo = JComboBox<String>()
    private val modelCombo = JComboBox<String>().apply { isEditable = true }
    private val languageCombo = JComboBox(arrayOf(AcpCommitSettings.LANGUAGE_ZH_CN, AcpCommitSettings.LANGUAGE_EN))
    private val conventionalCheckBox = JBCheckBox("Use Conventional Commits")
    private val customPromptArea = JBTextArea(6, 48).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val maxDiffSpinner = JBIntSpinner(512, 64, 4096, 64)
    private val startupTimeoutSpinner = JBIntSpinner(
        AcpCommitClient.DEFAULT_STARTUP_TIMEOUT_SECONDS,
        AcpCommitClient.MIN_STARTUP_TIMEOUT_SECONDS,
        AcpCommitClient.MAX_STARTUP_TIMEOUT_SECONDS,
        15,
    )
    private val timeoutSpinner = JBIntSpinner(120, 15, 600, 15)
    private val refreshModelsButton = JButton("Refresh models from ACP")
    private val detectCliButton = JButton("Detect Claude/Codex CLI")
    private val openConfigButton = JButton("Open ~/.jetbrains/acp.json")
    private var panel: JPanel? = null
    private var currentAgentFingerprint: String? = null
    private var currentAgents: Map<String, AcpAgentDefinition> = emptyMap()

    override fun getDisplayName(): String = "ACP Commit Message"

    override fun createComponent(): JComponent {
        if (panel == null) {
            openConfigButton.addActionListener(openConfigListener())
            refreshModelsButton.addActionListener(refreshModelsListener())
            detectCliButton.addActionListener(detectCliListener())
            agentCombo.addActionListener { updateModelCombo() }
            panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("ACP agent:", agentCombo)
                .addLabeledComponent("ACP model:", modelCombo)
                .addComponent(refreshModelsButton)
                .addComponent(detectCliButton)
                .addLabeledComponent("Language:", languageCombo)
                .addComponent(conventionalCheckBox)
                .addLabeledComponent("Maximum diff (KiB):", maxDiffSpinner)
                .addLabeledComponent("Agent startup timeout (seconds):", startupTimeoutSpinner)
                .addLabeledComponent("Generation timeout (seconds):", timeoutSpinner)
                .addLabeledComponent("Additional prompt:", JBScrollPane(customPromptArea))
                .addComponent(openConfigButton)
                .addComponentFillVertically(JPanel(), 0)
                .panel
        }
        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val settingsService = AcpCommitSettings.getInstance()
        val settings = settingsService.getState()
        val projectState = project.getService(AcpCommitProjectState::class.java).getState()
        return languageCombo.selectedItem != settings.language ||
            conventionalCheckBox.isSelected != settings.conventionalCommits ||
            customPromptArea.text != settings.customPrompt ||
            maxDiffSpinner.number != settings.maxDiffKiB ||
            startupTimeoutSpinner.number != settings.startupTimeoutSeconds ||
            timeoutSpinner.number != settings.generationTimeoutSeconds ||
            agentCombo.selectedItem != projectState.agentName ||
            modelSelectionFromUi() != selectedAgentName()?.let { agentName ->
                currentAgents[agentName]?.let { settingsService.modelSelectionForAgent(agentName, it) }
            }.orEmpty()
    }

    override fun apply() {
        val settingsService = AcpCommitSettings.getInstance()
        val settings = settingsService.getState()
        settings.language = languageCombo.selectedItem as? String ?: AcpCommitSettings.LANGUAGE_ZH_CN
        settings.conventionalCommits = conventionalCheckBox.isSelected
        settings.customPrompt = customPromptArea.text.trim()
        settings.maxDiffKiB = maxDiffSpinner.number
        settings.startupTimeoutSeconds = startupTimeoutSpinner.number
        settings.generationTimeoutSeconds = timeoutSpinner.number

        val projectState = project.getService(AcpCommitProjectState::class.java)
        val selectedAgent = agentCombo.selectedItem as? String
        val fingerprint = currentAgentFingerprint
        if (selectedAgent != null && fingerprint != null && agentCombo.isEnabled) {
            projectState.rememberAgent(selectedAgent, fingerprint)
            settingsService.rememberAgentModelSelection(selectedAgent, modelSelectionFromUi())
        } else {
            projectState.forgetAgent()
        }
    }

    override fun reset() {
        val settings = AcpCommitSettings.getInstance().getState()
        languageCombo.selectedItem = settings.language
        conventionalCheckBox.isSelected = settings.conventionalCommits
        customPromptArea.text = settings.customPrompt
        maxDiffSpinner.number = settings.maxDiffKiB
        startupTimeoutSpinner.number = settings.startupTimeoutSeconds
        timeoutSpinner.number = settings.generationTimeoutSeconds

        val projectState = project.getService(AcpCommitProjectState::class.java).getState()
        when (val config = AcpConfigLoader.load()) {
            is AcpConfigResult.Success -> {
                currentAgents = config.snapshot.agents
                val names = config.snapshot.agents.keys.toTypedArray()
                agentCombo.model = DefaultComboBoxModel(names)
                agentCombo.isEnabled = true
                currentAgentFingerprint = config.snapshot.fingerprint
                projectState.agentName?.takeIf { it in config.snapshot.agents }?.let {
                    agentCombo.selectedItem = it
                }
                updateModelCombo()
            }
            is AcpConfigResult.Failure -> {
                currentAgents = emptyMap()
                agentCombo.model = DefaultComboBoxModel(arrayOf("No configured agents"))
                agentCombo.isEnabled = false
                modelCombo.model = DefaultComboBoxModel(arrayOf(NO_AGENT_MODEL_LABEL))
                modelCombo.isEnabled = false
                refreshModelsButton.isEnabled = false
                currentAgentFingerprint = null
            }
        }
        openConfigButton.isEnabled = java.nio.file.Files.isRegularFile(AcpConfigLoader.defaultPath())
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun openConfigListener() = ActionListener {
        VfsUtil.findFile(AcpConfigLoader.defaultPath(), true)?.let { file ->
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    private fun detectCliListener() = ActionListener {
        val root = project.basePath?.let(Path::of)
        if (root == null) {
            AcpCommitNotifications.error(project, "The project has no base directory")
            return@ActionListener
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Detecting Claude/Codex CLI", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val detection = AcpAgentDetector.detect(root)
                    if (detection.agents.isEmpty()) {
                        val suffix = if (detection.missingAdapterRuntimeFor.isNotEmpty()) {
                            " Detected ${detection.missingAdapterRuntimeFor.joinToString(", ")} CLI, but npx was not found."
                        } else {
                            ""
                        }
                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed) {
                                AcpCommitNotifications.info(project, "No supported local Claude/Codex CLI was detected.$suffix")
                            }
                        }
                        return
                    }

                    val result = AcpConfigWriter.installDetectedAgents(detection.agents)
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        reset()
                        val added = result.added.joinToString(", ")
                        val skipped = result.skipped.joinToString(", ")
                        val message = buildString {
                            if (result.added.isNotEmpty()) append("Added ACP agent(s): $added.")
                            if (result.skipped.isNotEmpty()) {
                                if (isNotEmpty()) append(" ")
                                append("Already configured: $skipped.")
                            }
                            append(" Config: ${result.path}")
                        }
                        AcpCommitNotifications.info(project, message)
                    }
                } catch (error: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            AcpCommitNotifications.error(project, error.message ?: "Failed to detect Claude/Codex CLI")
                        }
                    }
                }
            }
        })
    }

    private fun refreshModelsListener() = ActionListener {
        val agentName = selectedAgentName() ?: return@ActionListener
        val agent = currentAgents[agentName] ?: return@ActionListener
        val root = project.basePath?.let(Path::of)
        if (root == null) {
            AcpCommitNotifications.error(project, "The project has no base directory")
            return@ActionListener
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading ACP models from $agentName", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val startupTimeoutSeconds = startupTimeoutSpinner.number.coerceIn(
                        AcpCommitClient.MIN_STARTUP_TIMEOUT_SECONDS,
                        AcpCommitClient.MAX_STARTUP_TIMEOUT_SECONDS,
                    )
                    val models = runBlocking {
                        AcpCommitClient(startupTimeoutSeconds = startupTimeoutSeconds).loadModelOptions(
                            agent = agent,
                            projectRoot = root,
                            readableRoots = readableRoots(root),
                            timeoutSeconds = 30,
                        )
                    }
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        AcpCommitSettings.getInstance().rememberAgentModelOptions(agentName, models)
                        updateModelCombo()
                        if (models.isEmpty()) {
                            AcpCommitNotifications.info(project, "ACP agent '$agentName' did not report selectable models.")
                        } else {
                            AcpCommitNotifications.info(project, "Loaded ${models.size} ACP model(s) for '$agentName'.")
                        }
                    }
                } catch (error: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            AcpCommitNotifications.error(project, error.message ?: "Failed to load ACP models")
                        }
                    }
                }
            }
        })
    }

    private fun updateModelCombo() {
        val agentName = selectedAgentName()
        val agent = agentName?.let { currentAgents[it] }
        if (agentName == null || agent == null || !agentCombo.isEnabled) {
            modelCombo.model = DefaultComboBoxModel(arrayOf(NO_AGENT_MODEL_LABEL))
            modelCombo.isEnabled = false
            refreshModelsButton.isEnabled = false
            return
        }

        val settings = AcpCommitSettings.getInstance()
        val selection = settings.modelSelectionForAgent(agentName, agent)
        val candidates = linkedSetOf(USE_AGENT_DEFAULT_LABEL)
        candidates.addAll(settings.modelOptionsForAgent(agentName, agent))
        selection.takeIf { it.isNotBlank() && it != AcpCommitSettings.AGENT_DEFAULT_MODEL }?.let(candidates::add)

        modelCombo.model = DefaultComboBoxModel(candidates.toTypedArray())
        modelCombo.isEnabled = true
        modelCombo.isEditable = true
        refreshModelsButton.isEnabled = true
        modelCombo.selectedItem = if (selection == AcpCommitSettings.AGENT_DEFAULT_MODEL || selection.isBlank()) {
            USE_AGENT_DEFAULT_LABEL
        } else {
            selection
        }
    }

    private fun selectedAgentName(): String? =
        (agentCombo.selectedItem as? String)?.takeIf { agentCombo.isEnabled && it in currentAgents }

    private fun modelSelectionFromUi(): String {
        val selected = (modelCombo.selectedItem as? String)?.trim().orEmpty()
        return if (selected == USE_AGENT_DEFAULT_LABEL || selected == NO_AGENT_MODEL_LABEL) {
            AcpCommitSettings.AGENT_DEFAULT_MODEL
        } else {
            selected
        }
    }

    private fun readableRoots(basePath: Path): List<Path> {
        val roots = ProjectRootManager.getInstance(project).contentRoots
            .map(VirtualFile::getPath)
            .map(Path::of)
        return (listOf(basePath) + roots).distinct()
    }

    private companion object {
        const val USE_AGENT_DEFAULT_LABEL = "Use agent default"
        const val NO_AGENT_MODEL_LABEL = "No model options"
    }
}
