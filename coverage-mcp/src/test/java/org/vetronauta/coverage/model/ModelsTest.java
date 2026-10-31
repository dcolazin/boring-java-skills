package org.vetronauta.coverage.model;

import org.testng.annotations.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/** Coverage of the response/DTO model classes (getters, derived metrics, toString). */
public class ModelsTest {

    // ------------------------------------------------------------- FileCoverage

    @Test
    public void fileCoverageLineBasedApiTracksCoveredAndUncovered() {
        FileCoverage f = new FileCoverage("src/A.java", "A");
        f.addLineCoverage(1, true);
        f.addLineCoverage(2, false);
        f.addLineCoverage(3, true);

        assertThat(f.getTotalLines()).isEqualTo(3);
        assertThat(f.getLinesCovered()).isEqualTo(2);
        assertThat(f.getLinesMissed()).isEqualTo(1);
        assertThat(f.getLineCoverageRatio()).isCloseTo(2.0 / 3.0, offset(1e-9));
        assertThat(f.getUncoveredLines()).containsExactly(2);
        assertThat(f.getLineCoverages()).hasSize(3);
        assertThat(f.getLineCoverages().get(0).isCovered()).isTrue();
        assertThat(f.getLineCoverages().get(1).lineNumber()).isEqualTo(2);
        assertThat(f.toString()).contains("A").contains("2/3");
    }

    @Test
    public void fileCoverageZeroTotalsGiveZeroRatios() {
        FileCoverage f = new FileCoverage("src/Empty.java", "Empty");
        assertThat(f.getTotalLines()).isZero();
        assertThat(f.getLineCoverageRatio()).isZero();
        assertThat(f.getTotalBranches()).isZero();
        assertThat(f.getBranchCoverageRatio()).isZero();
        assertThat(f.getNewLineCoverageRatio()).isZero();
        assertThat(f.getNewBranchCoverageRatio()).isZero();
        assertThat(f.getUncoveredLines()).isEmpty();
    }

    @Test
    public void fileCoverageBranchAndNewCodeAccumulators() {
        FileCoverage f = new FileCoverage("src/B.java", "B");
        f.addBranchCoverage(2, 6);
        f.addBranchCoverage(1, 1);
        assertThat(f.getTotalBranches()).isEqualTo(10);
        assertThat(f.getBranchesCovered()).isEqualTo(7);
        assertThat(f.getBranchesMissed()).isEqualTo(3);
        assertThat(f.getBranchCoverageRatio()).isCloseTo(0.7, offset(1e-9));

        f.setNewLineMetrics(20, 5);
        f.setNewBranchMetrics(2, 2);
        assertThat(f.getNewTotalLines()).isEqualTo(25);
        assertThat(f.getNewLinesCovered()).isEqualTo(20);
        assertThat(f.getNewLinesMissed()).isEqualTo(5);
        assertThat(f.getNewLineCoverageRatio()).isCloseTo(0.8, offset(1e-9));
        assertThat(f.getNewTotalBranches()).isEqualTo(4);
        assertThat(f.getNewBranchCoverageRatio()).isCloseTo(0.5, offset(1e-9));
    }

    // ------------------------------------------------------------ CoverageSummary

    @Test
    public void coverageSummaryAggregatesOverallAndNewCode() {
        FileCoverage f1 = new FileCoverage("src/A.java", "A");
        f1.addLineCoverage(1, 9);
        f1.addBranchCoverage(2, 2);
        f1.setNewLineMetrics(1, 1);
        f1.setNewBranchMetrics(2, 0);

        FileCoverage f2 = new FileCoverage("src/B.java", "B"); // no lines at all
        f2.setNewLineMetrics(1, 3); // but new-code data present

        CoverageSummary s = new CoverageSummary();
        s.addAll(List.of(f1, f2));

        assertThat(s.getTotalFiles()).isEqualTo(2);
        assertThat(s.getFilesWithCoverage()).isEqualTo(1);
        assertThat(s.getTotalLines()).isEqualTo(10);
        assertThat(s.getTotalLinesCovered()).isEqualTo(9);
        assertThat(s.getLineCoverageRatio()).isCloseTo(0.9, offset(1e-9));
        assertThat(s.getTotalBranches()).isEqualTo(4);
        assertThat(s.getBranchCoverageRatio()).isCloseTo(0.5, offset(1e-9));

        assertThat(s.getNewTotalLines()).isEqualTo(6);
        assertThat(s.getNewLinesCovered()).isEqualTo(2);
        assertThat(s.getNewLinesMissed()).isEqualTo(4);
        assertThat(s.getNewLineCoverageRatio()).isCloseTo(2.0 / 6.0, offset(1e-9));
        assertThat(s.getNewTotalBranches()).isEqualTo(2);
        assertThat(s.getNewBranchCoverageRatio()).isEqualTo(1.0);
        assertThat(s.getNewFilesWithCoverage()).isEqualTo(2);

        assertThat(s.toString()).contains("files=2").contains("lines=9/10");
    }

    @Test
    public void coverageSummaryZeroRatiosWhenEmpty() {
        CoverageSummary s = new CoverageSummary();
        assertThat(s.getLineCoverageRatio()).isZero();
        assertThat(s.getBranchCoverageRatio()).isZero();
        assertThat(s.getNewLineCoverageRatio()).isZero();
        assertThat(s.getNewBranchCoverageRatio()).isZero();
        assertThat(s.getTotalLines()).isZero();
        assertThat(s.toString()).contains("lines=0/0");
    }

    // ------------------------------------------------------- CoverageResponse

    @Test
    public void coverageResponseDetailMirrorsFileCoverage() {
        FileCoverage f = new FileCoverage("src/A.java", "A");
        f.addLineCoverage(1, 4);
        f.addBranchCoverage(1, 3);
        f.addLineCoverage(10, false);
        f.setNewLineMetrics(3, 2);
        f.setNewBranchMetrics(1, 1);

        var detail = new CoverageResponse.FileCoverageDetail(f);
        CoverageResponse response = new CoverageResponse(new CoverageSummary(), List.of(detail), true, 7);

        assertThat(response.overall()).isNotNull();
        assertThat(response.files()).hasSize(1);
        assertThat(response.truncated()).isTrue();
        assertThat(response.totalFiles()).isEqualTo(7);

        assertThat(detail.filePath()).isEqualTo("src/A.java");
        assertThat(detail.className()).isEqualTo("A");
        assertThat(detail.totalLines()).isEqualTo(6);
        assertThat(detail.linesCovered()).isEqualTo(4);
        assertThat(detail.linesMissed()).isEqualTo(2);
        assertThat(detail.lineCoverageRatio()).isCloseTo(4.0 / 6.0, offset(1e-9));
        assertThat(detail.uncoveredLines()).containsExactly(10);
        assertThat(detail.totalBranches()).isEqualTo(4);
        assertThat(detail.branchCoverageRatio()).isCloseTo(0.75, offset(1e-9));
        assertThat(detail.newTotalLines()).isEqualTo(5);
        assertThat(detail.newLinesCovered()).isEqualTo(3);
        assertThat(detail.newLineCoverageRatio()).isCloseTo(0.6, offset(1e-9));
        assertThat(detail.newTotalBranches()).isEqualTo(2);
        assertThat(detail.newBranchCoverageRatio()).isCloseTo(0.5, offset(1e-9));
    }

    // -------------------------------------------------- ChangedFile / Response

    @Test
    public void changedFileFiveArgConstructorDefaultsOldPathNull() {
        ChangedFile cf = new ChangedFile("src/A.java", "MODIFY", 3, 2, List.of(4, 5, 9));
        assertThat(cf.filePath()).isEqualTo("src/A.java");
        assertThat(cf.status()).isEqualTo("MODIFY");
        assertThat(cf.addedLines()).isEqualTo(3);
        assertThat(cf.deletedLines()).isEqualTo(2);
        assertThat(cf.getTotalChangedLines()).isEqualTo(5);
        assertThat(cf.changedLineNumbers()).containsExactly(4, 5, 9);
        assertThat(cf.oldPath()).isNull();
    }

    @Test
    public void changedFileSixArgConstructorKeepsOldPathForRenames() {
        ChangedFile cf = new ChangedFile("src/New.java", "RENAME", 10, 0, List.of(1), "src/Old.java");
        assertThat(cf.oldPath()).isEqualTo("src/Old.java");
    }

    @Test
    public void changedFilesResponseComputesTotals() {
        ChangedFilesResponse r = new ChangedFilesResponse("main", List.of(
                new ChangedFile("src/A.java", "MODIFY", 5, 2, List.of(1, 2, 3, 4, 5)),
                new ChangedFile("src/B.java", "ADD", 3, 0, List.of(7, 8, 9))));

        assertThat(r.baseline()).isEqualTo("main");
        assertThat(r.getTotalFiles()).isEqualTo(2);
        assertThat(r.getTotalAddedLines()).isEqualTo(8);
        assertThat(r.getTotalDeletedLines()).isEqualTo(2);
        assertThat(r.files()).hasSize(2);
    }

    // --------------------------------------------------------------- LineCoverage

    @Test
    public void lineCoverageRecordAccessors() {
        LineCoverage covered = new LineCoverage(42, true);
        LineCoverage missed = new LineCoverage(43, false);
        assertThat(covered.isCovered()).isTrue();
        assertThat(missed.isCovered()).isFalse();
        assertThat(covered.lineNumber()).isEqualTo(42);
        // branch-carrying variant (real JaCoCo line elements)
        LineCoverage branchy = new LineCoverage(7, true, 2, 3);
        assertThat(branchy.missedBranches()).isEqualTo(2);
        assertThat(branchy.coveredBranches()).isEqualTo(3);
        assertThat(branchy.totalBranches()).isEqualTo(5);
    }

    // ------------------------------------------------------- class-relative key

    @Test
    public void fileCoverageClassKeyPrefersClassRelativePath() {
        FileCoverage f = new FileCoverage("src/main/java/com/example/Foo.java", "com.example.Foo",
                "com/example/Foo.java");
        assertThat(f.getClassRelativePath()).isEqualTo("com/example/Foo.java");
        assertThat(f.classKey()).isEqualTo("com/example/Foo.java");

        FileCoverage legacy = new FileCoverage("src/main/java/Bar.java", "Bar");
        assertThat(legacy.classKey()).isEqualTo("src/main/java/Bar.java");

        f.setFilePath("src/test/java/com/example/Foo.java");
        assertThat(f.getFilePath()).isEqualTo("src/test/java/com/example/Foo.java");
        assertThat(f.classKey()).isEqualTo("com/example/Foo.java"); // key stays stable
    }
}
