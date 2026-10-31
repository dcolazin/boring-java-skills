package org.vetronauta.coverage.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Coverage data for a single source file.
 */
public class FileCoverage {
    private String filePath;
    private final String className;
    /**
     * Package-relative path, e.g. {@code com/example/Calculator.java}. Stable
     * key for matching against git diff paths regardless of source root
     * (main vs test). Null when unknown.
     */
    private final String classRelativePath;

    // Line coverage
    private int linesCovered = 0;
    private int linesMissed = 0;
    private final List<LineCoverage> lineCoverages = new ArrayList<>();

    // Branch (condition) coverage
    private int branchesCovered = 0;
    private int branchesMissed = 0;

    public FileCoverage(String filePath, String className) {
        this(filePath, className, null);
    }

    public FileCoverage(String filePath, String className, String classRelativePath) {
        this.filePath = filePath;
        this.className = className;
        this.classRelativePath = classRelativePath;
    }

    public String getFilePath() {
        return filePath;
    }

    /** Overwrites the display/matching path (e.g. with the real git diff path). */
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getClassName() {
        return className;
    }

    public String getClassRelativePath() {
        return classRelativePath;
    }

    /**
     * Stable matching key: the class-relative path when known, else the file path.
     */
    public String classKey() {
        return classRelativePath != null ? classRelativePath : filePath;
    }

    // ------------------------------------------------------------- line coverage

    /** Aggregated line counters (legacy reports without per-line data). */
    public void addLineCoverage(int missed, int covered) {
        this.linesMissed += missed;
        this.linesCovered += covered;
    }

    /** Line-based coverage without branch info. */
    public void addLineCoverage(int lineNumber, boolean covered) {
        addLineCoverage(lineNumber, covered, 0, 0);
    }

    /**
     * Line-based coverage with per-line condition counters (real JaCoCo
     * {@code <line nr mi ci mb cb/>} elements). Updates both the per-line
     * detail and the aggregate line/branch counters.
     */
    public void addLineCoverage(int lineNumber, boolean covered, int missedBranches, int coveredBranches) {
        lineCoverages.add(new LineCoverage(lineNumber, covered, missedBranches, coveredBranches));
        if (covered) {
            linesCovered++;
        } else {
            linesMissed++;
        }
        branchesMissed += missedBranches;
        branchesCovered += coveredBranches;
    }

    public int getLinesCovered() {
        return linesCovered;
    }

    public int getLinesMissed() {
        return linesMissed;
    }

    public int getTotalLines() {
        return linesCovered + linesMissed;
    }

    public double getLineCoverageRatio() {
        int total = getTotalLines();
        return total > 0 ? (double) linesCovered / total : 0.0;
    }

    public List<LineCoverage> getLineCoverages() {
        return lineCoverages;
    }

    /** True when per-line detail was parsed (real JaCoCo {@code <line>} elements). */
    public boolean hasLineData() {
        return !lineCoverages.isEmpty();
    }

    public List<Integer> getUncoveredLines() {
        return lineCoverages.stream()
                .filter(lc -> !lc.isCovered())
                .map(LineCoverage::lineNumber)
                .toList();
    }

    // ----------------------------------------------------------- branch coverage

    /** Aggregated branch counters (legacy reports). */
    public void addBranchCoverage(int missed, int covered) {
        this.branchesMissed += missed;
        this.branchesCovered += covered;
    }

    public int getBranchesCovered() {
        return branchesCovered;
    }

    public int getBranchesMissed() {
        return branchesMissed;
    }

    public int getTotalBranches() {
        return branchesCovered + branchesMissed;
    }

    public double getBranchCoverageRatio() {
        int total = getTotalBranches();
        return total > 0 ? (double) branchesCovered / total : 0.0;
    }

    // ------------------------------------------------------ new-code metrics
    // Populated by NewCodeCalculator: coverage restricted to lines changed
    // since the baseline ref.

    private int newLinesCovered = 0;
    private int newLinesMissed = 0;
    private int newBranchesCovered = 0;
    private int newBranchesMissed = 0;

    public void setNewLineMetrics(int covered, int missed) {
        this.newLinesCovered = covered;
        this.newLinesMissed = missed;
    }

    public void setNewBranchMetrics(int covered, int missed) {
        this.newBranchesCovered = covered;
        this.newBranchesMissed = missed;
    }

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

    /**
     * Defensive copy for merge results: independent aggregate counters and
     * line list; the immutable {@link LineCoverage} records are shared.
     */
    public FileCoverage copy() {
        FileCoverage copy = new FileCoverage(filePath, className, classRelativePath);
        copy.linesMissed = linesMissed;
        copy.linesCovered = linesCovered;
        copy.branchesMissed = branchesMissed;
        copy.branchesCovered = branchesCovered;
        copy.lineCoverages.addAll(lineCoverages);
        copy.newLinesCovered = newLinesCovered;
        copy.newLinesMissed = newLinesMissed;
        copy.newBranchesCovered = newBranchesCovered;
        copy.newBranchesMissed = newBranchesMissed;
        return copy;
    }

    @Override
    public String toString() {
        return String.format("FileCoverage{file=%s, lines=%d/%d (%.1f%%), branches=%d/%d (%.1f%%)}",
                filePath, linesCovered, getTotalLines(), getLineCoverageRatio() * 100,
                branchesCovered, getTotalBranches(), getBranchCoverageRatio() * 100);
    }
}
