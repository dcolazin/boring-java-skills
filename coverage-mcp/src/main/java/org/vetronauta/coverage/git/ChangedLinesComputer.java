package org.vetronauta.coverage.git;

import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes changed line numbers from Git diff edits.
 *
 * <p>Runs the same histogram diff algorithm with the same whitespace-insensitive
 * comparator that {@code DiffFormatter} would use, but consumes the structured
 * {@link EditList} directly instead of re-parsing rendered unified-diff text —
 * no hunk-header string handling, no dead content buffers.
 */
public class ChangedLinesComputer {

    /**
     * Histogram diff with {@code WS_IGNORE_ALL}, matching both DiffFormatter's
     * default algorithm and SonarQube's whitespace-insensitive new-code
     * comparison.
     */
    private static final DiffAlgorithm DIFF_ALGORITHM =
            DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM);

    private final Repository repository;

    public ChangedLinesComputer(Repository repository) {
        this.repository = repository;
    }

    /**
     * Computes changed line numbers for a diff entry.
     * Returns the line numbers in the NEW version of the file that were added/modified.
     */
    public ChangedLinesResult computeChangedLines(DiffEntry diffEntry) throws IOException {
        try (ObjectReader reader = repository.newObjectReader()) {
            // Get the content of old and new versions. NOTE: the ids are
            // AbbreviatedObjectId — equals(ObjectId.zeroId()) is a type-confused
            // no-op; use AnyObjectId.startsWith(AbbreviatedObjectId) instead so
            // ADD/DELETE entries don't try to open the all-zero object.
            byte[] oldBytes = readBlob(reader, diffEntry.getOldId());
            byte[] newBytes = readBlob(reader, diffEntry.getNewId());

            // Binary content has no meaningful line structure (DiffFormatter
            // would print "Binary files differ" with no hunks)
            if (RawText.isBinary(oldBytes) || RawText.isBinary(newBytes)) {
                return new ChangedLinesResult(List.of(), 0, 0);
            }

            EditList edits = DIFF_ALGORITHM.diff(RawTextComparator.WS_IGNORE_ALL,
                    new RawText(oldBytes), new RawText(newBytes));
            return toResult(edits);
        }
    }

    /**
     * Converts structured edits to new-side line numbers (0-based → 1-based).
     * Insertions and replacements contribute new-side lines; pure deletions
     * contribute none.
     */
    static ChangedLinesResult toResult(EditList edits) {
        List<Integer> changedLines = new ArrayList<>();
        int addedLines = 0;
        int deletedLines = 0;
        for (Edit edit : edits) {
            addedLines += edit.getEndB() - edit.getBeginB();
            deletedLines += edit.getEndA() - edit.getBeginA();
            for (int line = edit.getBeginB(); line < edit.getEndB(); line++) {
                changedLines.add(line + 1);
            }
        }
        return new ChangedLinesResult(changedLines, addedLines, deletedLines);
    }

    private static byte[] readBlob(ObjectReader reader, AbbreviatedObjectId id) throws IOException {
        if (id == null || ObjectId.zeroId().startsWith(id)) {
            return new byte[0];
        }
        return reader.open(id.toObjectId()).getBytes();
    }

    /**
     * Result of changed lines computation.
     */
    public static class ChangedLinesResult {
        private final List<Integer> changedLineNumbers;
        private final int addedLines;
        private final int deletedLines;

        public ChangedLinesResult(List<Integer> changedLineNumbers, int addedLines, int deletedLines) {
            this.changedLineNumbers = changedLineNumbers;
            this.addedLines = addedLines;
            this.deletedLines = deletedLines;
        }

        public List<Integer> getChangedLineNumbers() { return changedLineNumbers; }
        public int getAddedLines() { return addedLines; }
        public int getDeletedLines() { return deletedLines; }
    }
}
