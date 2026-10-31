package org.vetronauta.coverage.model;

import java.util.List;

/**
 * Complete response for the analyze_coverage tool.
 *
 * @param overall    aggregated metrics across all files
 * @param files      per-file detail (possibly truncated by maxFilesInDetail)
 * @param truncated  whether the per-file detail was truncated
 * @param totalFiles total number of files in the aggregates
 * @param notes      human-readable caveats (fallbacks, truncation, merged reports)
 */
public record CoverageResponse(
        CoverageSummary overall,
        List<FileCoverageDetail> files,
        boolean truncated,
        int totalFiles,
        List<String> notes) {

    public CoverageResponse {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public CoverageResponse(CoverageSummary overall, List<FileCoverageDetail> files, boolean truncated,
                            int totalFiles) {
        this(overall, files, truncated, totalFiles, List.of());
    }

    /**
     * Per-file coverage detail for JSON serialization.
     *
     * @param filePath repo-relative path
     * @param className fully qualified class name
     * @param linesCovered covered executable lines
     * @param linesMissed uncovered executable lines
     * @param totalLines covered + missed executable lines
     * @param lineCoverageRatio covered / total lines
     * @param uncoveredLines 1-based line numbers without coverage
     * @param branchesCovered covered conditions
     * @param branchesMissed uncovered conditions
     * @param totalBranches covered + missed conditions
     * @param branchCoverageRatio covered / total conditions
     * @param newLinesCovered covered lines among changed lines
     * @param newLinesMissed uncovered lines among changed lines
     * @param newTotalLines changed executable lines with coverage data
     * @param newLineCoverageRatio new covered / new total lines
     * @param newBranchesCovered covered conditions on changed lines
     * @param newBranchesMissed uncovered conditions on changed lines
     * @param newTotalBranches conditions on changed lines
     * @param newBranchCoverageRatio new covered / new total conditions
     */
    public record FileCoverageDetail(
            String filePath,
            String className,
            int linesCovered,
            int linesMissed,
            int totalLines,
            double lineCoverageRatio,
            List<Integer> uncoveredLines,
            int branchesCovered,
            int branchesMissed,
            int totalBranches,
            double branchCoverageRatio,
            int newLinesCovered,
            int newLinesMissed,
            int newTotalLines,
            double newLineCoverageRatio,
            int newBranchesCovered,
            int newBranchesMissed,
            int newTotalBranches,
            double newBranchCoverageRatio) {

        public FileCoverageDetail(FileCoverage file) {
            this(file.getFilePath(),
                    file.getClassName(),
                    file.getLinesCovered(),
                    file.getLinesMissed(),
                    file.getTotalLines(),
                    file.getLineCoverageRatio(),
                    file.getUncoveredLines(),
                    file.getBranchesCovered(),
                    file.getBranchesMissed(),
                    file.getTotalBranches(),
                    file.getBranchCoverageRatio(),
                    file.getNewLinesCovered(),
                    file.getNewLinesMissed(),
                    file.getNewTotalLines(),
                    file.getNewLineCoverageRatio(),
                    file.getNewBranchesCovered(),
                    file.getNewBranchesMissed(),
                    file.getNewTotalBranches(),
                    file.getNewBranchCoverageRatio());
        }
    }
}
