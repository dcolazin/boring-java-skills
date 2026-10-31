package org.vetronauta.coverage.git;

import org.eclipse.jgit.diff.DiffEntry;
import org.vetronauta.coverage.model.FileCoverage;
import org.vetronauta.coverage.model.LineCoverage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jgit.api.errors.GitAPIException;

/**
 * Calculates new-code coverage metrics by correlating Git diff changed lines
 * with JaCoCo line coverage (SonarQube "new code" semantics):
 *
 * <pre>newLines(file) = jaCoCoLines(file) ∩ gitChangedLines(file)</pre>
 *
 * Brand-new files (ADD diff entries) have every report line changed, so the
 * intersection is the full per-line coverage.
 */
public class NewCodeCalculator implements AutoCloseable {

    private final NewCodeDetector detector;
    private final ChangedLinesComputer linesComputer;
    /** Null when only changed-file listing is needed (two-arg constructor). */
    private final SourceFileLocator locator;

    public NewCodeCalculator(NewCodeDetector detector, ChangedLinesComputer linesComputer, SourceFileLocator locator) {
        this.detector = detector;
        this.linesComputer = linesComputer;
        this.locator = locator;
    }

    /**
     * Variant without a {@link SourceFileLocator}: sufficient for changed-file
     * listing ({@link #getChangedFilesWithLines}), which never matches diff
     * paths against coverage. {@link #calculateNewCodeCoverage} requires a
     * locator and fails fast when constructed this way.
     */
    public NewCodeCalculator(NewCodeDetector detector, ChangedLinesComputer linesComputer) {
        this(detector, linesComputer, null);
    }

    /**
     * Computes new-code coverage for all files: for each diff entry whose
     * file has coverage data, intersects the changed line numbers with the
     * per-line coverage and stores the restricted metrics on the
     * {@link FileCoverage} (mutating it; re-aggregate afterwards).
     *
     * @param fileCoverages parsed JaCoCo coverage (mutated with new-code metrics)
     * @param baseline      ref (SHA, branch, tag) to diff against
     * @return human-readable notes about any degradation (e.g. files whose
     *         report had no line-level data and fell back to whole-file metrics)
     */
    public List<String> calculateNewCodeCoverage(List<FileCoverage> fileCoverages, String baseline)
            throws IOException, GitAPIException {
        Objects.requireNonNull(locator, "sourceFileLocator required for new-code coverage");
        List<String> notes = new ArrayList<>();
        List<DiffEntry> diffs = detector.getDiffEntries(baseline);
        Map<String, FileCoverage> coverageByClassKey = indexByClassKey(fileCoverages);

        for (DiffEntry diff : diffs) {
            if (diff.getChangeType() == DiffEntry.ChangeType.DELETE) {
                continue; // no new-side lines exist
            }
            FileCoverage coverage = matchCoverage(locator, diff.getNewPath(), coverageByClassKey);
            if (coverage == null) {
                continue; // changed file has no coverage data (e.g. not in report)
            }
            coverage.setFilePath(diff.getNewPath());

            ChangedLinesComputer.ChangedLinesResult result = linesComputer.computeChangedLines(diff);
            Set<Integer> changedLines = new HashSet<>(result.getChangedLineNumbers());

            if (coverage.getLineCoverages().isEmpty()) {
                // No per-line data (legacy report format): fall back to the
                // whole-file aggregate and say so.
                coverage.setNewLineMetrics(coverage.getLinesCovered(), coverage.getLinesMissed());
                coverage.setNewBranchMetrics(coverage.getBranchesCovered(), coverage.getBranchesMissed());
                notes.add(diff.getNewPath()
                        + ": report has no line-level data; whole-file coverage counted as new code");
                continue;
            }

            int newCovered = 0;
            int newMissed = 0;
            int newBranchesCovered = 0;
            int newBranchesMissed = 0;
            for (LineCoverage line : coverage.getLineCoverages()) {
                if (!changedLines.contains(line.lineNumber())) {
                    continue;
                }
                if (line.isCovered()) {
                    newCovered++;
                } else {
                    newMissed++;
                }
                newBranchesCovered += line.coveredBranches();
                newBranchesMissed += line.missedBranches();
            }
            coverage.setNewLineMetrics(newCovered, newMissed);
            coverage.setNewBranchMetrics(newBranchesCovered, newBranchesMissed);
        }
        return notes;
    }

    /**
     * Gets the list of changed files with per-file added/deleted line counts
     * and the changed (added/modified) line numbers in the new version
     * (used by the {@code list_changed_files} tool).
     */
    public List<ChangedFileDetail> getChangedFilesWithLines(String baseline) throws IOException, GitAPIException {
        List<DiffEntry> diffs = detector.getDiffEntries(baseline);
        List<ChangedFileDetail> result = new ArrayList<>(diffs.size());
        for (DiffEntry diff : diffs) {
            ChangedLinesComputer.ChangedLinesResult lines = linesComputer.computeChangedLines(diff);
            result.add(new ChangedFileDetail(
                    diff.getNewPath(),
                    diff.getChangeType().name(),
                    lines.getAddedLines(),
                    lines.getDeletedLines(),
                    List.copyOf(lines.getChangedLineNumbers()),
                    diff.getOldPath()
            ));
        }
        return result;
    }

    /**
     * Indexes coverage by class-relative path (package dirs + file name), the
     * stable key shared by JaCoCo reports and git diff paths.
     */
    private static Map<String, FileCoverage> indexByClassKey(List<FileCoverage> fileCoverages) {
        Map<String, FileCoverage> map = new HashMap<>();
        for (FileCoverage file : fileCoverages) {
            map.putIfAbsent(file.classKey(), file);
        }
        return map;
    }

    /**
     * Matches a git diff path to coverage: direct source-root-stripped lookup,
     * then a deterministic longest-suffix fallback (most specific package
     * match wins — never HashMap iteration order) for files under unknown roots.
     */
    static FileCoverage matchCoverage(SourceFileLocator locator, String gitPath,
                                      Map<String, FileCoverage> coverageByClassKey) {
        String stripped = locator.stripSourceRoot(gitPath);
        if (stripped != null) {
            FileCoverage coverage = coverageByClassKey.get(stripped);
            if (coverage != null) {
                return coverage;
            }
        }
        FileCoverage best = null;
        int bestKeyLength = -1;
        for (Map.Entry<String, FileCoverage> entry : coverageByClassKey.entrySet()) {
            String key = entry.getKey();
            if (key.length() > bestKeyLength && gitPath.endsWith("/" + key)) {
                best = entry.getValue();
                bestKeyLength = key.length();
            }
        }
        return best;
    }

    @Override
    public void close() {
        detector.close();
    }

    /**
     * Detail for changed files response.
     */
    public static class ChangedFileDetail {
        private final String filePath;
        private final String status;
        private final int addedLines;
        private final int deletedLines;
        private final List<Integer> changedLineNumbers;
        private final String oldPath;

        public ChangedFileDetail(String filePath, String status, int addedLines, int deletedLines,
                                List<Integer> changedLineNumbers, String oldPath) {
            this.filePath = filePath;
            this.status = status;
            this.addedLines = addedLines;
            this.deletedLines = deletedLines;
            this.changedLineNumbers = changedLineNumbers;
            this.oldPath = oldPath;
        }

        public String getFilePath() { return filePath; }
        public String getStatus() { return status; }
        public int getAddedLines() { return addedLines; }
        public int getDeletedLines() { return deletedLines; }
        public List<Integer> getChangedLineNumbers() { return changedLineNumbers; }
        public String getOldPath() { return oldPath; }
    }
}
