package org.vetronauta.coverage.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whitespace-only changes are invisible under the WS_IGNORE_ALL comparator:
 * the tree diff still reports the file as modified (blob ids differ) but the
 * changed-line computation yields no new-side lines — so whitespace-only
 * edits never count as new code.
 */
public class WhitespaceIgnoreTest {

    @Test
    public void whitespaceOnlyChangeHasNoChangedLines() throws Exception {
        Path dir = Files.createTempDirectory("ws-repo-");
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            Path file = dir.resolve("src/Foo.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "package p;\nclass Foo {\n  int x() { return 1; }\n}\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("v1")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();

            // reindent + trailing spaces + widened separators: no content
            // change once whitespace is ignored
            Files.writeString(file, "package  p ;\nclass Foo {\n    int x() { return 1; }   \n}\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("v2 whitespace only")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();

            try (NewCodeDetector detector = new NewCodeDetector(dir)) {
                List<DiffEntry> diffs = detector.getDiffEntries("HEAD~1");

                // blob changed → MODIFY entry exists…
                assertThat(diffs).hasSize(1);
                assertThat(diffs.get(0).getChangeType()).isEqualTo(DiffEntry.ChangeType.MODIFY);

                // …but contributes no changed lines
                var result = new ChangedLinesComputer(detector.getRepository())
                        .computeChangedLines(diffs.get(0));
                assertThat(result.getChangedLineNumbers()).isEmpty();
                assertThat(result.getAddedLines()).isZero();
                assertThat(result.getDeletedLines()).isZero();
            }
        }
    }
}
