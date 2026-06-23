package com.leeyf.acpcommit.vcs

import com.intellij.openapi.diff.impl.patch.BinaryFilePatch
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.TextFilePatch
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import java.io.StringWriter
import java.nio.file.Path

object CommitDiffBuilder {
    fun build(
        project: Project,
        changes: Collection<Change>,
        unversionedFiles: Collection<VirtualFile>,
        maxBytes: Int,
    ): String {
        val basePath = project.basePath?.let(Path::of)
            ?: throw IllegalStateException("The project has no base directory")
        val output = StringBuilder()

        if (changes.isNotEmpty()) {
            val patches = IdeaTextPatchBuilder.buildPatch(project, changes, basePath, false)
            val textPatches = patches.filterIsInstance<TextFilePatch>()
            if (textPatches.isNotEmpty()) {
                val writer = StringWriter()
                UnifiedDiffWriter.write(project, basePath, textPatches, writer, "\n", null, emptyList())
                appendChecked(output, writer.toString(), maxBytes)
            }
            patches.filterIsInstance<BinaryFilePatch>().forEach { patch ->
                val path = patch.afterName ?: patch.beforeName ?: "unknown"
                appendChecked(output, "\nBinary file changed: $path\n", maxBytes)
            }
        }

        unversionedFiles.sortedBy { it.path }.forEach { file ->
            val relativePath = runCatching { basePath.relativize(Path.of(file.path)).toString() }
                .getOrDefault(file.path)
            if (file.fileType.isBinary) {
                appendChecked(output, "\nBinary file added: $relativePath (${file.length} bytes)\n", maxBytes)
            } else {
                if (file.length > maxBytes) throw DiffTooLargeException(maxBytes)
                val bytes = file.contentsToByteArray()
                val contents = bytes.toString(file.charset).replace("\r\n", "\n")
                val patch = renderAddedTextPatch(relativePath, contents)
                appendChecked(output, patch, maxBytes)
            }
        }

        if (output.isEmpty()) throw IllegalArgumentException("No selected text changes were found")
        return output.toString()
    }

    internal fun renderAddedTextPatch(relativePath: String, contents: String): String {
        val lines = when {
            contents.isEmpty() -> emptyList()
            contents.endsWith('\n') -> contents.dropLast(1).split('\n')
            else -> contents.split('\n')
        }
        return buildString {
            appendLine("diff --git a/$relativePath b/$relativePath")
            appendLine("new file mode 100644")
            appendLine("--- /dev/null")
            appendLine("+++ b/$relativePath")
            appendLine("@@ -0,0 +1,${lines.size} @@")
            lines.forEach { append('+').appendLine(it) }
        }
    }

    private fun appendChecked(target: StringBuilder, value: String, maxBytes: Int) {
        val totalBytes = target.toString().toByteArray(Charsets.UTF_8).size + value.toByteArray(Charsets.UTF_8).size
        if (totalBytes > maxBytes) throw DiffTooLargeException(maxBytes)
        target.append(value)
    }
}

class DiffTooLargeException(maxBytes: Int) : Exception(
    "The selected diff exceeds ${maxBytes / 1024} KiB. Select fewer changes and try again."
)
