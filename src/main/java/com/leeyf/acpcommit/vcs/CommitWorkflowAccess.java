package com.leeyf.acpcommit.vcs;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.vcs.commit.CommitWorkflowUi;

import java.util.List;

/** Java bridge for workflow accessors marked internal in Kotlin metadata. */
public final class CommitWorkflowAccess {
    private CommitWorkflowAccess() {
    }

    public static List<Change> includedChanges(CommitWorkflowUi workflow) {
        return workflow.getIncludedChanges();
    }

    public static List<FilePath> includedUnversionedFiles(CommitWorkflowUi workflow) {
        return workflow.getIncludedUnversionedFiles();
    }
}
