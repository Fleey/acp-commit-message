package com.leeyf.acpcommit.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.commit.CommitWorkflowUi
import com.leeyf.acpcommit.acp.AcpCommitClient
import com.leeyf.acpcommit.config.AcpAgentDefinition
import com.leeyf.acpcommit.config.AcpConfigLoader
import com.leeyf.acpcommit.config.AcpConfigResult
import com.leeyf.acpcommit.config.AcpConfigSnapshot
import com.leeyf.acpcommit.notification.AcpCommitNotifications
import com.leeyf.acpcommit.prompt.CommitPromptBuilder
import com.leeyf.acpcommit.prompt.CommitPromptOptions
import com.leeyf.acpcommit.prompt.CommitResponseSanitizer
import com.leeyf.acpcommit.settings.AcpCommitProjectState
import com.leeyf.acpcommit.settings.AcpCommitSettings
import com.leeyf.acpcommit.vcs.CommitDiffBuilder
import com.leeyf.acpcommit.vcs.CommitWorkflowAccess
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class GenerateCommitMessageAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val workflow = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        val hasChanges = workflow?.let {
            CommitWorkflowAccess.includedChanges(it).isNotEmpty() ||
                CommitWorkflowAccess.includedUnversionedFiles(it).isNotEmpty()
        } == true
        val hasGit = project?.let { GitRepositoryManager.getInstance(it).repositories.isNotEmpty() } == true
        val hasAgent = AcpConfigLoader.load() is AcpConfigResult.Success
        event.presentation.isEnabledAndVisible = project != null && workflow != null && hasChanges && hasGit && hasAgent
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val workflow = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return
        when (val config = AcpConfigLoader.load()) {
            is AcpConfigResult.Failure -> AcpCommitNotifications.error(project, config.message)
            is AcpConfigResult.Success -> chooseAgentAndGenerate(event, project, workflow, config.snapshot)
        }
    }

    private fun chooseAgentAndGenerate(
        event: AnActionEvent,
        project: Project,
        workflow: CommitWorkflowUi,
        snapshot: AcpConfigSnapshot,
    ) {
        val projectState = project.getService(AcpCommitProjectState::class.java)
        val remembered = projectState.validAgent(snapshot.agents.keys, snapshot.fingerprint)
        if (remembered != null) {
            generate(project, workflow, remembered, snapshot.agents.getValue(remembered))
            return
        }

        if (snapshot.agents.size == 1) {
            val (name, agent) = snapshot.agents.entries.single()
            projectState.rememberAgent(name, snapshot.fingerprint)
            generate(project, workflow, name, agent)
            return
        }

        val names = snapshot.agents.keys.toList()
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(names)
            .setTitle("Choose ACP Agent")
            .setItemChosenCallback { name ->
                projectState.rememberAgent(name, snapshot.fingerprint)
                generate(project, workflow, name, snapshot.agents.getValue(name))
            }
            .createPopup()
            .showInBestPositionFor(event.dataContext)
    }

    private fun generate(
        project: Project,
        workflow: CommitWorkflowUi,
        agentName: String,
        agent: AcpAgentDefinition,
    ) {
        val settings = AcpCommitSettings.getInstance().state
        workflow.commitMessageUi.startLoading()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating commit message with $agentName", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val preferredModel = AcpCommitSettings.getInstance().selectedModelForAgent(agentName, agent)
                    val maxBytes = settings.maxDiffKiB.coerceIn(64, 4096) * 1024
                    val diff = CommitDiffBuilder.build(
                        project,
                        CommitWorkflowAccess.includedChanges(workflow),
                        CommitWorkflowAccess.includedUnversionedFiles(workflow).mapNotNull { it.virtualFile },
                        maxBytes,
                    )
                    val root = project.basePath?.let(Path::of)
                        ?: throw IllegalStateException("The project has no base directory")
                    val branch = currentBranch(project)
                    val prompt = CommitPromptBuilder.build(
                        branch,
                        diff,
                        CommitPromptOptions(
                            language = settings.language,
                            conventionalCommits = settings.conventionalCommits,
                            customPrompt = settings.customPrompt,
                            model = preferredModel,
                        ),
                    )
                    val roots = readableRoots(project, root)
                    val rawResponse = runBlocking {
                        val generation = async {
                            AcpCommitClient(
                                startupTimeoutSeconds = settings.startupTimeoutSeconds.coerceIn(
                                    AcpCommitClient.MIN_STARTUP_TIMEOUT_SECONDS,
                                    AcpCommitClient.MAX_STARTUP_TIMEOUT_SECONDS,
                                ),
                            ).generate(
                                agent = agent,
                                projectRoot = root,
                                readableRoots = roots,
                                prompt = prompt,
                                generationTimeoutSeconds = settings.generationTimeoutSeconds.coerceIn(15, 600),
                                preferredModel = preferredModel,
                            )
                        }
                        try {
                            while (!generation.isCompleted) {
                                indicator.checkCanceled()
                                delay(100)
                            }
                            generation.await()
                        } finally {
                            generation.cancel()
                        }
                    }
                    val message = CommitResponseSanitizer.sanitize(rawResponse)
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            workflow.commitMessageUi.setText(message)
                            workflow.commitMessageUi.focus()
                        }
                    }
                } catch (_: ProcessCanceledException) {
                    // Cancellation leaves the existing commit message untouched.
                } catch (error: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            AcpCommitNotifications.error(project, error.message ?: "Unknown ACP error")
                        }
                    }
                } finally {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) workflow.commitMessageUi.stopLoading()
                    }
                }
            }
        })
    }

    private fun currentBranch(project: Project): String? {
        val repositories = GitRepositoryManager.getInstance(project).repositories
        val branches = repositories.mapNotNull { repository ->
            repository.currentBranchName?.let { branch -> repository.root.name to branch }
        }
        return when (branches.size) {
            0 -> null
            1 -> branches.single().second
            else -> branches.joinToString(", ") { (root, branch) -> "$root: $branch" }
        }
    }

    private fun readableRoots(project: Project, basePath: Path): List<Path> {
        val roots = ProjectRootManager.getInstance(project).contentRoots
            .map(VirtualFile::getPath)
            .map(Path::of)
        return (listOf(basePath) + roots).distinct()
    }
}
