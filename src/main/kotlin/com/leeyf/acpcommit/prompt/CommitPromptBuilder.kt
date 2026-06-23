package com.leeyf.acpcommit.prompt

data class CommitPromptOptions(
    val language: String,
    val conventionalCommits: Boolean,
    val customPrompt: String,
    val model: String? = null,
)

object CommitPromptBuilder {
    fun build(branch: String?, diff: String, options: CommitPromptOptions): String = buildString {
        appendLine("Generate a Git commit message for the selected changes below.")
        appendLine("Return only the commit message. Do not add explanations, labels, or Markdown fences.")
        appendLine("Write the message in ${options.language}.")
        if (options.conventionalCommits) {
            appendLine("Use Conventional Commits: type(optional-scope): concise subject.")
            appendLine("Use a short body only when it adds useful detail.")
        } else {
            appendLine("Use a concise subject and an optional short body.")
        }
        options.model?.trim()?.takeIf { it.isNotEmpty() }?.let {
            appendLine("Preferred ACP model: $it")
        }
        branch?.takeIf { it.isNotBlank() }?.let { appendLine("Current branch: $it") }
        options.customPrompt.trim().takeIf { it.isNotEmpty() }?.let {
            appendLine("Additional instructions:")
            appendLine(it)
        }
        appendLine()
        appendLine("Selected changes (unified diff):")
        append(diff)
    }
}
