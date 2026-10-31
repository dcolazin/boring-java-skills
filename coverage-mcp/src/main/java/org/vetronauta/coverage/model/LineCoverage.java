package org.vetronauta.coverage.model;

/**
 * Coverage information for a single executable source line.
 *
 * @param lineNumber      1-based line number in the source file
 * @param covered         {@code true} when the line has covered instructions (JaCoCo {@code ci > 0});
 *                        partially covered lines count as covered (SonarQube line semantics)
 * @param missedBranches  conditions missed on this line (JaCoCo {@code mb})
 * @param coveredBranches conditions covered on this line (JaCoCo {@code cb})
 */
public record LineCoverage(int lineNumber, boolean covered, int missedBranches, int coveredBranches) {

    public LineCoverage(int lineNumber, boolean covered) {
        this(lineNumber, covered, 0, 0);
    }

    public boolean isCovered() {
        return covered;
    }

    public int totalBranches() {
        return missedBranches + coveredBranches;
    }
}
