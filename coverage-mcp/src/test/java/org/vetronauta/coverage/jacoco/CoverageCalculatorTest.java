package org.vetronauta.coverage.jacoco;

import org.testng.annotations.Test;
import org.vetronauta.coverage.model.CoverageSummary;
import org.vetronauta.coverage.model.FileCoverage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

public class CoverageCalculatorTest {

    @Test
    public void calculateOverallSummary() {
        FileCoverage f1 = new FileCoverage("src/main/java/A.java", "A");
        f1.addLineCoverage(2, 8); // 10 lines, 80%
        f1.addBranchCoverage(1, 3); // 4 branches, 75%

        FileCoverage f2 = new FileCoverage("src/main/java/B.java", "B");
        f2.addLineCoverage(5, 5); // 10 lines, 50%
        f2.addBranchCoverage(2, 2); // 4 branches, 50%

        FileCoverage f3 = new FileCoverage("src/main/java/C.java", "C");
        f3.addLineCoverage(0, 0); // no lines

        CoverageCalculator calc = new CoverageCalculator();
        CoverageSummary summary = calc.calculate(List.of(f1, f2, f3));

        assertThat(summary.getTotalFiles()).isEqualTo(3);
        assertThat(summary.getFilesWithCoverage()).isEqualTo(2);
        assertThat(summary.getTotalLinesCovered()).isEqualTo(13);
        assertThat(summary.getTotalLinesMissed()).isEqualTo(7);
        assertThat(summary.getTotalLines()).isEqualTo(20);
        assertThat(summary.getLineCoverageRatio()).isCloseTo(0.65, offset(0.001));

        assertThat(summary.getTotalBranchesCovered()).isEqualTo(5);
        assertThat(summary.getTotalBranchesMissed()).isEqualTo(3);
        assertThat(summary.getTotalBranches()).isEqualTo(8);
        assertThat(summary.getBranchCoverageRatio()).isCloseTo(0.625, offset(0.001));
    }
}