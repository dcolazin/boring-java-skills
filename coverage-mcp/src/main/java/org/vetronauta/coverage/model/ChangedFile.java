package org.vetronauta.coverage.model;

import java.util.List;

/**
 * A file changed since baseline with diff information (DTO for
 * {@code list_changed_files}).
 *
 * @param filePath           repo-relative path in the new version
 * @param status             ADD, MODIFY, DELETE, or RENAME (JGit ChangeType names)
 * @param addedLines         lines added/modified in the new version
 * @param deletedLines       lines deleted from the old version
 * @param changedLineNumbers lines added/modified in the new version (1-based)
 * @param oldPath            previous path for renamed files, else null
 */
public record ChangedFile(
        String filePath,
        String status,
        int addedLines,
        int deletedLines,
        List<Integer> changedLineNumbers,
        String oldPath) {

    public ChangedFile(String filePath, String status, int addedLines, int deletedLines,
                       List<Integer> changedLineNumbers) {
        this(filePath, status, addedLines, deletedLines, changedLineNumbers, null);
    }

    /** Added + deleted lines (computed getter kept for JSON compatibility). */
    public int getTotalChangedLines() {
        return addedLines + deletedLines;
    }
}
