package org.vetronauta.coverage.git;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.vetronauta.coverage.TestFixtures;
import org.vetronauta.coverage.jacoco.JacocoReportParser;
import org.vetronauta.coverage.model.FileCoverage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Line-level new-code coverage: changed git lines ∩ per-line JaCoCo coverage.
 *
 * Fixture diff (baseline-v1 → master):
 * - Calculator.java MODIFY: new lines 22-41 (modulo + power methods)
 * - Utils.java     MODIFY: new lines 14-30 (reverse + isPalindrome)
 * - Statistics.java ADD:   not in the coverage report (no metrics)
 * - User/UserService: unchanged (no new-code metrics)
 */
public class NewCodeCalculatorTest {

    private static final List<String> ROOTS = List.of("src/main/java", "src/test/java");

    private static Path fixtureRepo;

    @BeforeClass
    public static void setUp() throws Exception {
        fixtureRepo = TestFixtures.prepareFixtureRepo();
    }

    private static FileCoverage file(List<FileCoverage> files, String name) {
        return files.stream()
                .filter(f -> f.getFilePath().endsWith(name))
                .findFirst().orElseThrow();
    }

    @Test
    public void calculatorNewCodeIsRestrictedToChangedLines() throws Exception {
        List<FileCoverage> files = new JacocoReportParser().parse(fixtureRepo.resolve("jacoco.xml"), ROOTS);

        try (NewCodeDetector detector = new NewCodeDetector(fixtureRepo);
             NewCodeCalculator calc = new NewCodeCalculator(detector,
                     new ChangedLinesComputer(detector.getRepository()),
                     new SourceFileLocator(ROOTS))) {

            List<String> notes = calc.calculateNewCodeCoverage(files, "baseline-v1");

            FileCoverage calculator = file(files, "Calculator.java");
            // overall file: 15 executable lines, 12 covered
            assertThat(calculator.getTotalLines()).isEqualTo(15);
            assertThat(calculator.getLinesCovered()).isEqualTo(12);
            // new code (lines 22-41): 25,26,28,33,34,36,37,38,40 → 9 lines, 7 covered, 2 missed
            assertThat(calculator.getNewTotalLines()).isEqualTo(9);
            assertThat(calculator.getNewLinesCovered()).isEqualTo(7);
            assertThat(calculator.getNewLinesMissed()).isEqualTo(2);
            // new branches on changed lines: 25(1+1), 33(1+0), 37(1+1) → 5 total, 2 covered, 3 missed
            assertThat(calculator.getNewTotalBranches()).isEqualTo(5);
            assertThat(calculator.getNewBranchesCovered()).isEqualTo(2);
            assertThat(calculator.getNewBranchesMissed()).isEqualTo(3);

            // real line format → no degradation notes
            assertThat(notes).isEmpty();
        }
    }

    @Test
    public void utilsNewCodeAndUnchangedFilesHaveNoNewMetrics() throws Exception {
        List<FileCoverage> files = new JacocoReportParser().parse(fixtureRepo.resolve("jacoco.xml"), ROOTS);

        try (NewCodeDetector detector = new NewCodeDetector(fixtureRepo);
             NewCodeCalculator calc = new NewCodeCalculator(detector,
                     new ChangedLinesComputer(detector.getRepository()),
                     new SourceFileLocator(ROOTS))) {

            calc.calculateNewCodeCoverage(files, "baseline-v1");

            FileCoverage utils = file(files, "Utils.java");
            // new lines 14-30: 17,18,20,25,26,28,29 → 7 lines, 5 covered, 2 missed
            assertThat(utils.getNewTotalLines()).isEqualTo(7);
            assertThat(utils.getNewLinesCovered()).isEqualTo(5);
            assertThat(utils.getNewLinesMissed()).isEqualTo(2);
            assertThat(utils.getNewTotalBranches()).isEqualTo(3); // 17(1+1) + 25(1+0)

            // unchanged files get no new-code metrics at all
            assertThat(file(files, "User.java").getNewTotalLines()).isZero();
            assertThat(file(files, "UserService.java").getNewTotalLines()).isZero();

            // coverage matched by class-relative path → filePath corrected to git path
            assertThat(utils.getFilePath()).isEqualTo("src/main/java/com/example/Utils.java");
        }
    }

    @Test
    public void legacyReportWithoutLineDataFallsBackToWholeFileWithNote() throws Exception {
        // jacoco-sample.xml uses the legacy format (no <line> elements)
        Path sample = Path.of("src/test/resources/jacoco-sample.xml");
        List<FileCoverage> files = new JacocoReportParser().parse(sample, ROOTS, fixtureRepo);

        try (NewCodeDetector detector = new NewCodeDetector(fixtureRepo);
             NewCodeCalculator calc = new NewCodeCalculator(detector,
                     new ChangedLinesComputer(detector.getRepository()),
                     new SourceFileLocator(ROOTS))) {

            List<String> notes = calc.calculateNewCodeCoverage(files, "baseline-v1");

            // Calculator/Utils/UserService are changed or unchanged; the changed
            // ones (Calculator, Utils) must fall back to whole-file metrics
            FileCoverage calculator = file(files, "Calculator.java");
            assertThat(calculator.getNewTotalLines()).isEqualTo(calculator.getTotalLines());
            assertThat(calculator.getNewLinesCovered()).isEqualTo(calculator.getLinesCovered());

            assertThat(notes).isNotEmpty();
            assertThat(notes.stream().anyMatch(n -> n.contains("Calculator.java")
                    && n.contains("no line-level data"))).isTrue();
        }
    }

    @Test
    public void changedFilesWithLinesCarriesRealNumbers() throws Exception {
        try (NewCodeDetector detector = new NewCodeDetector(fixtureRepo);
             NewCodeCalculator calc = new NewCodeCalculator(detector,
                     new ChangedLinesComputer(detector.getRepository()),
                     new SourceFileLocator(ROOTS))) {

            List<NewCodeCalculator.ChangedFileDetail> changed = calc.getChangedFilesWithLines("baseline-v1");

            assertThat(changed).hasSize(3);

            var calculator = changed.stream()
                    .filter(cf -> cf.getFilePath().endsWith("Calculator.java"))
                    .findFirst().orElseThrow();
            assertThat(calculator.getStatus()).isEqualTo("MODIFY");
            assertThat(calculator.getAddedLines()).isEqualTo(20);
            assertThat(calculator.getDeletedLines()).isZero();
            // every changed line is within the hunk range 22..41
            assertThat(calculator.getChangedLineNumbers()).isNotEmpty();
            assertThat(calculator.getChangedLineNumbers()).allSatisfy(l -> {
                assertThat(l).isBetween(22, 41);
            });

            var statistics = changed.stream()
                    .filter(cf -> cf.getFilePath().endsWith("Statistics.java"))
                    .findFirst().orElseThrow();
            assertThat(statistics.getStatus()).isEqualTo("ADD");
            assertThat(statistics.getAddedLines()).isGreaterThan(10);
            assertThat(statistics.getChangedLineNumbers()).first().isEqualTo(1);
        }
    }

    @Test
    public void matchCoveragePrefersLongestSuffixDeterministically() {
        SourceFileLocator locator = new SourceFileLocator(ROOTS);
        FileCoverage foo = new FileCoverage("src/main/java/com/example/Foo.java", "com.example.Foo",
                "com/example/Foo.java");
        FileCoverage nested = new FileCoverage("src/main/java/nested/com/example/Foo.java",
                "nested.com.example.Foo", "nested/com/example/Foo.java");

        // Map.of order is unspecified: the longest-suffix match must still pick
        // the nested (more specific) key every time
        Map<String, FileCoverage> map = Map.of(foo.classKey(), foo, nested.classKey(), nested);
        assertThat(NewCodeCalculator.matchCoverage(locator, "vendor/nested/com/example/Foo.java", map))
                .isSameAs(nested);

        // exact source-root-stripped lookup wins outright
        assertThat(NewCodeCalculator.matchCoverage(locator, "src/test/java/com/example/Foo.java", map))
                .isSameAs(foo);
    }
}
