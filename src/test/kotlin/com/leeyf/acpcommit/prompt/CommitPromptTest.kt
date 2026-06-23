package com.leeyf.acpcommit.prompt

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommitPromptTest {
    @Test
    fun `builds a conventional Chinese prompt with branch and custom rules`() {
        val prompt = CommitPromptBuilder.build(
            branch = "feature/acp",
            diff = "diff --git a/a b/a",
            options = CommitPromptOptions("简体中文", true, "subject 不超过 50 字", model = "sonnet"),
        )
        assertContains(prompt, "Conventional Commits")
        assertContains(prompt, "Preferred ACP model: sonnet")
        assertContains(prompt, "feature/acp")
        assertContains(prompt, "subject 不超过 50 字")
        assertContains(prompt, "diff --git")
    }

    @Test
    fun `strips a single markdown fence`() {
        assertEquals("feat(core): add ACP support", CommitResponseSanitizer.sanitize("```text\nfeat(core): add ACP support\n```"))
    }

    @Test
    fun `rejects an empty response`() {
        assertFailsWith<IllegalArgumentException> { CommitResponseSanitizer.sanitize("  ") }
    }
}
