package org.vetronauta.coverage.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.testng.annotations.Test;
import org.vetronauta.coverage.TestFixtures;
import org.vetronauta.coverage.jacoco.JacocoReportParser;
import org.vetronauta.coverage.model.FileCoverage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rename detection parity: a moved file must count only its actually-edited
 * lines as new code, not the whole file (SonarQube semantics). Without
 * {@code setDetectRenames(true)} a rename arrives as DELETE+ADD and the moved
 * file looks 100% new code.
 */
public class RenameDetectionTest {

    private static final List<String> ROOTS = List.of("src/main/java", "src/test/java");

    @Test
    public void renamedFileCountsOnlyEditedLinesAsNewCode() throws Exception {
        Path repo = TestFixtures.prepareFixtureRepo();

        // 1) Move User.java from src/main/java to src/test/java (same package →
        //    same class-relative key, so coverage matching still works) and edit
        //    exactly one executable line (14: getId) without changing line count
        Path userSrc = repo.resolve("src/main/java/com/example/User.java");
        Path userDst = repo.resolve("src/test/java/com/example/User.java");
        String content = Files.readString(userSrc);
        String edited = content.replace("public int getId() { return id; }",
                "public int getId() { return this.id; }");
        assertThat(edited).isNotEqualTo(content); // guard: the edit applied
        Files.createDirectories(userDst.getParent());
        Files.move(userSrc, userDst);
        Files.writeString(userDst, edited);

        // 2) Commit the rename
        try (Git git = Git.open(repo.toFile())) {
            git.rm().setCached(true).addFilepattern("src/main/java/com/example/User.java").call();
            git.add().addFilepattern("src/test/java/com/example/User.java").call();
            git.commit()
                    .setMessage("Rename User to src/test/java")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
        }

        try (NewCodeDetector detector = new NewCodeDetector(repo)) {
            List<DiffEntry> diffs = detector.getDiffEntries("baseline-v1");

            // the rename is one RENAME entry — not DELETE + ADD
            DiffEntry rename = diffs.stream()
                    .filter(e -> e.getNewPath().endsWith("User.java"))
                    .findFirst().orElseThrow();
            assertThat(rename.getChangeType()).isEqualTo(DiffEntry.ChangeType.RENAME);
            assertThat(rename.getOldPath()).isEqualTo("src/main/java/com/example/User.java");

            ChangedLinesComputer computer = new ChangedLinesComputer(detector.getRepository());
            var renamedLines = computer.computeChangedLines(rename);
            // only the edited line counts — not the whole file
            assertThat(renamedLines.getChangedLineNumbers()).containsExactly(14);
            assertThat(renamedLines.getAddedLines()).isEqualTo(1);
            assertThat(renamedLines.getDeletedLines()).isEqualTo(1);

            // 4) New-code metrics for the renamed file are restricted to the
            //    edited line (line 14 is executable + covered in the report)
            List<FileCoverage> files = new JacocoReportParser().parse(repo.resolve("jacoco.xml"), ROOTS, repo);
            NewCodeCalculator calc = new NewCodeCalculator(detector, computer, new SourceFileLocator(ROOTS));
            List<String> notes = calc.calculateNewCodeCoverage(files, "baseline-v1");

            FileCoverage user = files.stream()
                    .filter(f -> f.getFilePath().endsWith("User.java"))
                    .findFirst().orElseThrow();
            assertThat(user.getNewTotalLines()).isEqualTo(1);
            assertThat(user.getNewLinesCovered()).isEqualTo(1);
            assertThat(user.getNewLinesMissed()).isZero();
            // report has line data → no fallback notes
            assertThat(notes).isEmpty();
        }
    }
}
