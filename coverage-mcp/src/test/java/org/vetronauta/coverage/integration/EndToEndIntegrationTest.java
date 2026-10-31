package org.vetronauta.coverage.integration;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.vetronauta.coverage.jacoco.JacocoReportParser;
import org.vetronauta.coverage.git.NewCodeDetector;
import org.vetronauta.coverage.git.NewCodeCalculator;
import org.vetronauta.coverage.git.SourceFileLocator;
import org.vetronauta.coverage.model.FileCoverage;
import org.vetronauta.coverage.model.CoverageSummary;
import org.vetronauta.coverage.jacoco.CoverageCalculator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.xml.stream.XMLStreamException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

public class EndToEndIntegrationTest {

    private static Path FIXTURE_REPO;
    private static Path JACOCO_REPORT;
    private static String BASELINE_TAG = "baseline-v1";

    @BeforeClass
    public static void setUpFixtureRepo() throws Exception {
        // Source fixture (without .git folder - it has "git" folder instead)
        Path sourceFixture = Paths.get("src/test/resources/fixture-repo");
        
        // Create a temporary directory for the test fixture
        FIXTURE_REPO = Files.createTempDirectory("coverage-mcp-fixture-");
        JACOCO_REPORT = FIXTURE_REPO.resolve("jacoco.xml");

        // Copy the entire fixture to temp directory
        copyDirectory(sourceFixture, FIXTURE_REPO);
        
        // Rename "git" folder to ".git"
        Path gitFolder = FIXTURE_REPO.resolve("git");
        Path dotGitFolder = FIXTURE_REPO.resolve(".git");
        Files.move(gitFolder, dotGitFolder);

        // Verify the repo is valid by opening it
        org.eclipse.jgit.api.Git.open(FIXTURE_REPO.toFile()).close();
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source)
            .forEach(sourcePath -> {
                try {
                    Path targetPath = target.resolve(source.relativize(sourcePath));
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.copy(sourcePath, targetPath);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to copy " + sourcePath, e);
                }
            });
    }

    @Test
    public void testFullAnalyzeCoverageWithoutBaseline() throws IOException, XMLStreamException {
        JacocoReportParser parser = new JacocoReportParser();
        List<FileCoverage> files = parser.parse(JACOCO_REPORT, List.of("src/main/java", "src/test/java"));

        // Statistics.java is a new file not in the jacoco.xml, so only 4 files
        assertThat(files).hasSize(4);

        CoverageCalculator calc = new CoverageCalculator();
        CoverageSummary summary = calc.calculate(files);

        assertThat(summary.getTotalFiles()).isEqualTo(4);
        assertThat(summary.getFilesWithCoverage()).isEqualTo(4);
        assertThat(summary.getTotalLines()).isGreaterThan(0);
        assertThat(summary.getLineCoverageRatio()).isBetween(0.0, 1.0);
        assertThat(summary.getTotalBranches()).isGreaterThanOrEqualTo(0);
    }

    @Test
    public void testAnalyzeCoverageWithBaseline() throws Exception {
        JacocoReportParser parser = new JacocoReportParser();
        List<FileCoverage> files = parser.parse(JACOCO_REPORT, List.of("src/main/java", "src/test/java"));

        CoverageCalculator calc = new CoverageCalculator();
        CoverageSummary overallBefore = calc.calculate(files);

        try (NewCodeDetector detector = new NewCodeDetector(FIXTURE_REPO);
             NewCodeCalculator newCodeCalc = new NewCodeCalculator(
                     detector,
                     new org.vetronauta.coverage.git.ChangedLinesComputer(detector.getRepository()),
                     new SourceFileLocator(List.of("src/main/java", "src/test/java")))) {

            newCodeCalc.calculateNewCodeCoverage(files, BASELINE_TAG);
            CoverageSummary overallAfter = calc.calculate(files);

            // line-level new code: Calculator (9 lines, 7 covered) + Utils (7 lines, 5 covered)
            assertThat(overallAfter.getNewTotalLines()).isEqualTo(16);
            assertThat(overallAfter.getNewLinesCovered()).isEqualTo(12);
            assertThat(overallAfter.getNewLinesMissed()).isEqualTo(4);
            // new branches: Calculator 5 (2 cov) + Utils 3 (1 cov)
            assertThat(overallAfter.getNewTotalBranches()).isEqualTo(8);
            assertThat(overallAfter.getNewFilesWithCoverage()).isEqualTo(2);
        }
    }

    @Test
    public void testListChangedFiles() throws Exception {
        try (NewCodeDetector detector = new NewCodeDetector(FIXTURE_REPO);
             NewCodeCalculator newCodeCalc = new NewCodeCalculator(
                     detector,
                     new org.vetronauta.coverage.git.ChangedLinesComputer(detector.getRepository()),
                     new SourceFileLocator(List.of("src/main/java", "src/test/java")))) {

            List<NewCodeCalculator.ChangedFileDetail> changedFiles = newCodeCalc.getChangedFilesWithLines(BASELINE_TAG);

            assertThat(changedFiles).hasSizeGreaterThanOrEqualTo(3);

            boolean hasNewFile = changedFiles.stream()
                    .anyMatch(f -> f.getFilePath().contains("Statistics.java"));
            assertThat(hasNewFile).isTrue();

            boolean hasCalculator = changedFiles.stream()
                    .anyMatch(f -> f.getFilePath().contains("Calculator.java"));
            assertThat(hasCalculator).isTrue();

            boolean hasUtils = changedFiles.stream()
                    .anyMatch(f -> f.getFilePath().contains("Utils.java"));
            assertThat(hasUtils).isTrue();
        }
    }

    @Test
    public void testSpecificFileCoverage() throws IOException, XMLStreamException {
        JacocoReportParser parser = new JacocoReportParser();
        List<FileCoverage> files = parser.parse(JACOCO_REPORT, List.of("src/main/java", "src/test/java"));

        FileCoverage calc = files.stream()
                .filter(f -> f.getFilePath().contains("Calculator.java"))
                .findFirst()
                .orElseThrow();

        assertThat(calc.getClassName()).isEqualTo("com.example.Calculator");
        assertThat(calc.getTotalLines()).isEqualTo(15);
        assertThat(calc.getLinesCovered()).isEqualTo(12);
        assertThat(calc.getLinesMissed()).isEqualTo(3);
        assertThat(calc.getLineCoverageRatio()).isCloseTo(0.8, offset(0.001));

        assertThat(calc.getTotalBranches()).isEqualTo(7);
        assertThat(calc.getBranchesCovered()).isEqualTo(3);
        assertThat(calc.getBranchCoverageRatio()).isCloseTo(3.0 / 7.0, offset(0.001));
    }

    @Test
    public void testUserServiceCoverage() throws IOException, XMLStreamException {
        JacocoReportParser parser = new JacocoReportParser();
        List<FileCoverage> files = parser.parse(JACOCO_REPORT, List.of("src/main/java", "src/test/java"));

        FileCoverage service = files.stream()
                .filter(f -> f.getFilePath().contains("UserService.java"))
                .findFirst()
                .orElseThrow();

        assertThat(service.getClassName()).isEqualTo("com.example.service.UserService");
        assertThat(service.getTotalLines()).isEqualTo(10);
        assertThat(service.getLinesCovered()).isEqualTo(8);
        assertThat(service.getLinesMissed()).isEqualTo(2);
    }
}