package org.vetronauta.coverage.jacoco;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Report path spec resolution: plain, relative, glob, comma-separated. */
public class ReportLocatorTest {

    private static Path repoWithReports() throws IOException {
        Path repo = Files.createTempDirectory("report-locator-");
        writeFile(repo, "target/site/jacoco/jacoco.xml");
        writeFile(repo, "module2/target/site/jacoco/jacoco.xml");
        writeFile(repo, "unrelated.txt");
        return repo;
    }

    private static void writeFile(Path repo, String relative) throws IOException {
        Path p = repo.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "<report/>");
    }

    @Test
    public void plainRelativePathResolvesAgainstRepo() throws IOException {
        Path repo = repoWithReports();
        List<Path> reports = ReportLocator.resolveReports(repo, "target/site/jacoco/jacoco.xml");
        assertThat(reports).containsExactly(repo.resolve("target/site/jacoco/jacoco.xml"));
    }

    @Test
    public void absolutePathIsUsedAsIs() throws IOException {
        Path repo = repoWithReports();
        Path absolute = repo.resolve("target/site/jacoco/jacoco.xml");
        assertThat(ReportLocator.resolveReports(repo, absolute.toString())).containsExactly(absolute);
    }

    @Test
    public void globFindsAllMatchingReports() throws IOException {
        Path repo = repoWithReports();
        List<Path> reports = ReportLocator.resolveReports(repo, "**/jacoco.xml");
        assertThat(reports).hasSize(2);
        assertThat(reports).allSatisfy(p -> assertThat(p.getFileName().toString()).isEqualTo("jacoco.xml"));
    }

    @Test
    public void commaSeparatedEntriesAreMergedAndDeduplicated() throws IOException {
        Path repo = repoWithReports();
        String first = repo.resolve("target/site/jacoco/jacoco.xml").toString();
        List<Path> reports = ReportLocator.resolveReports(repo,
                first + ", target/site/jacoco/jacoco.xml, module2/target/site/jacoco/jacoco.xml");
        assertThat(reports).hasSize(2);
    }

    @Test
    public void globMatchingNothingYieldsErrorWhenAlone() {
        assertThatThrownBy(() -> ReportLocator.resolveReports(Path.of("."), "**/no-such-report.xml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No JaCoCo reports found");
    }

    @Test
    public void blankSpecThrows() {
        assertThatThrownBy(() -> ReportLocator.resolveReports(Path.of("."), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reportPath is empty");
    }

    @Test
    public void missingPlainPathIsReturnedForTheParserToReport() throws IOException {
        Path repo = repoWithReports();
        // plain paths pass through even when absent (parser produces the friendly error)
        assertThat(ReportLocator.resolveReports(repo, "no/such/jacoco.xml"))
                .containsExactly(repo.resolve("no/such/jacoco.xml"));
    }

    @Test
    public void globWithFixedPrefixOnlyFindsReportsUnderThatSubtree() throws IOException {
        Path repo = repoWithReports();
        List<Path> reports = ReportLocator.resolveReports(repo, "module2/**/jacoco.xml");
        assertThat(reports).containsExactly(repo.resolve("module2/target/site/jacoco/jacoco.xml"));
    }

    @Test
    public void globPrefixPointingAtMissingDirectoryFindsNothing() {
        assertThatThrownBy(() -> ReportLocator.resolveReports(Path.of("."), "no-such-dir/**/jacoco.xml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No JaCoCo reports found");
    }

    @Test
    public void gitDirectoryIsSkippedDuringGlobWalks() throws IOException {
        Path repo = repoWithReports();
        writeFile(repo, ".git/hooks/jacoco.xml"); // a would-be match hiding in .git
        List<Path> reports = ReportLocator.resolveReports(repo, "**/jacoco.xml");
        assertThat(reports).hasSize(2);
        assertThat(reports).noneMatch(p -> p.toString().contains(".git"));
    }
}
