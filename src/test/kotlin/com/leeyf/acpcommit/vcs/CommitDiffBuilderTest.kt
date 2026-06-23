package com.leeyf.acpcommit.vcs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CommitDiffBuilderTest {
    @Test
    fun `renders an untracked text file without inventing a trailing line`() {
        val patch = CommitDiffBuilder.renderAddedTextPatch("src/new.txt", "one\ntwo\n")

        assertContains(patch, "@@ -0,0 +1,2 @@")
        assertEquals(1, patch.lines().count { it == "+one" })
        assertEquals(1, patch.lines().count { it == "+two" })
    }

    @Test
    fun `renders an empty untracked file with a zero line hunk`() {
        val patch = CommitDiffBuilder.renderAddedTextPatch("empty.txt", "")

        assertContains(patch, "@@ -0,0 +1,0 @@")
        assertEquals(0, patch.lines().count { it.startsWith('+') && !it.startsWith("+++") })
    }
}
