package org.vetronauta.coverage.model;

import java.util.List;

/**
 * Response for the list_changed_files tool. Totals are computed getters so
 * the JSON contract stays
 * {@code baseline / files / totalFiles / totalAddedLines / totalDeletedLines}.
 *
 * @param baseline the baseline ref the diff was computed against
 * @param files    changed files with per-file diff detail
 */
public record ChangedFilesResponse(String baseline, List<ChangedFile> files) {

    public int getTotalFiles() {
        return files.size();
    }

    public int getTotalAddedLines() {
        return files.stream().mapToInt(ChangedFile::addedLines).sum();
    }

    public int getTotalDeletedLines() {
        return files.stream().mapToInt(ChangedFile::deletedLines).sum();
    }
}
