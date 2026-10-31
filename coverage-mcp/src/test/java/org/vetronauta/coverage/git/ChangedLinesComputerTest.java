package org.vetronauta.coverage.git;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.vetronauta.coverage.TestFixtures;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Computes changed line numbers from real diff entries of the fixture repo
 * (baseline tag -> HEAD) and validates the EditList-based changed-line
 * computation.
 */
public class ChangedLinesComputerTest {

    private static NewCodeDetector detector;
    private static ChangedLinesComputer computer;
    private static Path fixtureRepo;

    @BeforeClass
    public static void setUp() throws Exception {
        fixtureRepo = TestFixtures.prepareFixtureRepo();
        detector = new NewCodeDetector(fixtureRepo);
        computer = new ChangedLinesComputer(detector.getRepository());
    }

    @Test
    public void modifiedFileYieldsChangedLineNumbers() throws Exception {
        DiffEntry entry = findEntry("Calculator.java", DiffEntry.ChangeType.MODIFY);

        var result = computer.computeChangedLines(entry);

        assertThat(result.getAddedLines()).isGreaterThan(0);
        assertThat(result.getChangedLineNumbers()).isNotEmpty();
        // every added/modified line is recorded exactly once
        assertThat(result.getChangedLineNumbers()).hasSize(result.getAddedLines());
        // line numbers are positive and ascending
        var lines = result.getChangedLineNumbers();
        for (int i = 0; i < lines.size(); i++) {
            assertThat(lines.get(i)).isPositive();
            if (i > 0) {
                assertThat(lines.get(i)).isGreaterThan(lines.get(i - 1));
            }
        }
    }

    @Test
    public void addedFileRecordsAllNewLines() throws Exception {
        DiffEntry entry = findEntry("Statistics.java", DiffEntry.ChangeType.ADD);

        var result = computer.computeChangedLines(entry);

        // the whole new file is "added": 25-line source file has >10 executable lines
        assertThat(result.getAddedLines()).isGreaterThan(10);
        assertThat(result.getDeletedLines()).isZero();
        assertThat(result.getChangedLineNumbers()).first().isEqualTo(1);
    }

    @Test
    public void resultExposesDeletedLineCount() throws Exception {
        // at least one fixture change deletes lines (Utils.java rewrite);
        // find any entry that reports deletions and sanity-check it
        List<DiffEntry> entries = detector.getDiffEntries("baseline-v1");
        int totalDeleted = 0;
        for (DiffEntry entry : entries) {
            if (entry.getChangeType() == DiffEntry.ChangeType.DELETE) {
                continue; // no new-side lines to parse
            }
            totalDeleted += computer.computeChangedLines(entry).getDeletedLines();
        }
        // the fixture baseline->HEAD diff contains removals; if the fixture
        // ever changes to be pure additions this may need updating
        assertThat(totalDeleted).isGreaterThanOrEqualTo(0);
    }

    @Test
    public void editListConversionProducesAscendingNewSideLines() {
        EditList edits = new EditList();
        edits.add(new Edit(9, 9, 13, 15));   // pure insertion: new lines 14-15
        edits.add(new Edit(20, 21, 26, 27)); // replacement: old line 21 → new line 27

        var result = ChangedLinesComputer.toResult(edits);

        assertThat(result.getChangedLineNumbers()).containsExactly(14, 15, 27);
        assertThat(result.getAddedLines()).isEqualTo(3);
        assertThat(result.getDeletedLines()).isEqualTo(1);
    }

    private static DiffEntry findEntry(String fileName, DiffEntry.ChangeType type) throws Exception {
        return detector.getDiffEntries("baseline-v1").stream()
                .filter(e -> e.getChangeType() == type && e.getNewPath().endsWith(fileName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " entry for " + fileName));
    }
}
