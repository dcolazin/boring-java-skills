package org.vetronauta.coverage.model;

import java.util.List;

/**
 * Aggregated coverage metrics across all files.
 */
public class CoverageSummary {
    private int totalLinesCovered = 0;
    private int totalLinesMissed = 0;
    private int totalBranchesCovered = 0;
    private int totalBranchesMissed = 0;
    private int totalFiles = 0;
    private int filesWithCoverage = 0;

    // New code metrics
    private int newLinesCovered = 0;
    private int newLinesMissed = 0;
    private int newBranchesCovered = 0;
    private int newBranchesMissed = 0;
    private int newFilesWithCoverage = 0;

    public void add(FileCoverage file) {
        totalFiles++;
        if (file.getTotalLines() > 0) {
            filesWithCoverage++;
            totalLinesCovered += file.getLinesCovered();
            totalLinesMissed += file.getLinesMissed();
            totalBranchesCovered += file.getBranchesCovered();
            totalBranchesMissed += file.getBranchesMissed();
        }

        if (file.getNewTotalLines() > 0) {
            newFilesWithCoverage++;
            newLinesCovered += file.getNewLinesCovered();
            newLinesMissed += file.getNewLinesMissed();
            newBranchesCovered += file.getNewBranchesCovered();
            newBranchesMissed += file.getNewBranchesMissed();
        }
    }

    public void addAll(List<FileCoverage> files) {
        for (FileCoverage file : files) {
            add(file);
        }
    }

    // Overall metrics
    public int getTotalLinesCovered() {
        return totalLinesCovered;
    }

    public int getTotalLinesMissed() {
        return totalLinesMissed;
    }

    public int getTotalLines() {
        return totalLinesCovered + totalLinesMissed;
    }

    public double getLineCoverageRatio() {
        int total = getTotalLines();
        return total > 0 ? (double) totalLinesCovered / total : 0.0;
    }

    public int getTotalBranchesCovered() {
        return totalBranchesCovered;
    }

    public int getTotalBranchesMissed() {
        return totalBranchesMissed;
    }

    public int getTotalBranches() {
        return totalBranchesCovered + totalBranchesMissed;
    }

    public double getBranchCoverageRatio() {
        int total = getTotalBranches();
        return total > 0 ? (double) totalBranchesCovered / total : 0.0;
    }

    public int getTotalFiles() {
        return totalFiles;
    }

    public int getFilesWithCoverage() {
        return filesWithCoverage;
    }

    // New code metrics
    public int getNewLinesCovered() {
        return newLinesCovered;
    }

    public int getNewLinesMissed() {
        return newLinesMissed;
    }

    public int getNewTotalLines() {
        return newLinesCovered + newLinesMissed;
    }

    public double getNewLineCoverageRatio() {
        int total = getNewTotalLines();
        return total > 0 ? (double) newLinesCovered / total : 0.0;
    }

    public int getNewBranchesCovered() {
        return newBranchesCovered;
    }

    public int getNewBranchesMissed() {
        return newBranchesMissed;
    }

    public int getNewTotalBranches() {
        return newBranchesCovered + newBranchesMissed;
    }

    public double getNewBranchCoverageRatio() {
        int total = getNewTotalBranches();
        return total > 0 ? (double) newBranchesCovered / total : 0.0;
    }

    public int getNewFilesWithCoverage() {
        return newFilesWithCoverage;
    }

    @Override
    public String toString() {
        return String.format(
                "CoverageSummary{files=%d, lines=%d/%d (%.1f%%), branches=%d/%d (%.1f%%), newLines=%d/%d (%.1f%%), newBranches=%d/%d (%.1f%%)}",
                totalFiles,
                totalLinesCovered, getTotalLines(), getLineCoverageRatio() * 100,
                totalBranchesCovered, getTotalBranches(), getBranchCoverageRatio() * 100,
                newLinesCovered, getNewTotalLines(), getNewLineCoverageRatio() * 100,
                newBranchesCovered, getNewTotalBranches(), getNewBranchCoverageRatio() * 100
        );
    }
}