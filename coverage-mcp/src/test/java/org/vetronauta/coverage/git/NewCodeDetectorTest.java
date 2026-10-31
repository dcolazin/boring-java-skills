package org.vetronauta.coverage.git;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.vetronauta.coverage.TestFixtures;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class NewCodeDetectorTest {

    private static Path fixtureRepo;
    private static NewCodeDetector detector;

    @BeforeClass
    public static void setUp() throws Exception {
        fixtureRepo = TestFixtures.prepareFixtureRepo();
        detector = new NewCodeDetector(fixtureRepo);
    }

    @Test
    public void resolvesValidRefs() throws IOException {
        assertThat(detector.resolveBaseline("baseline-v1")).isNotNull();
        assertThat(detector.resolveBaseline("HEAD")).isNotNull();
        // a full SHA also resolves
        String sha = detector.getHead().getName();
        assertThat(detector.resolveBaseline(sha)).isNotNull();
    }

    @Test
    public void unknownRefThrowsIllegalArgument() {
        assertThatThrownBy(() -> detector.resolveBaseline("no-such-ref"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-ref");
    }

    @Test
    public void repositoryConstructorWorks() throws Exception {
        try (var git = org.eclipse.jgit.api.Git.open(fixtureRepo.toFile());
             NewCodeDetector fromRepo = new NewCodeDetector(git.getRepository())) {
            assertThat(fromRepo.getHead()).isNotNull();
            assertThat(fromRepo.getChangedFiles("baseline-v1")).isNotEmpty();
        }
    }

    @Test
    public void headAndTreeResolution() throws IOException {
        var head = detector.getHead();
        assertThat(head).isNotNull();
        var tree = detector.getTreeId(head);
        assertThat(tree).isNotNull();
        assertThat(tree).isNotEqualTo(head); // tree id differs from commit id
    }

    @Test
    public void diffEntriesMatchChangedFileInfo() throws Exception {
        var entries = detector.getDiffEntries("baseline-v1");
        var infos = detector.getChangedFiles("baseline-v1");

        assertThat(entries).hasSameSizeAs(infos);
        for (int i = 0; i < entries.size(); i++) {
            assertThat(infos.get(i).getNewPath()).isEqualTo(entries.get(i).getNewPath());
            assertThat(infos.get(i).getChangeType()).isEqualTo(entries.get(i).getChangeType().name());
            assertThat(infos.get(i).getOldPath()).isEqualTo(entries.get(i).getOldPath());
        }
        assertThat(infos).extracting(NewCodeDetector.ChangedFileInfo::getNewPath)
                .contains("src/main/java/com/example/Statistics.java");
    }

    @Test
    public void computeDiffBetweenBaselines() throws Exception {
        // diff of the baseline tree against itself is empty
        var id = detector.resolveBaseline("baseline-v1");
        assertThat(detector.computeDiff(detector.getTreeId(id), detector.getTreeId(id))).isEmpty();
    }

    @Test
    public void closeIsIdempotentEnoughForRepeatUse() throws IOException {
        // dedicated instance: closing must not affect other tests' shared detector
        try (NewCodeDetector own = new NewCodeDetector(fixtureRepo)) {
            own.close();
            own.close();
        }
    }

    @Test
    public void wrappedRepositoryIsNotClosedByDetector() throws Exception {
        try (var git = org.eclipse.jgit.api.Git.open(fixtureRepo.toFile())) {
            var repo = git.getRepository();
            int useCountBefore = useCount(repo);
            try (NewCodeDetector detector = new NewCodeDetector(repo)) {
                assertThat(detector.getHead()).isNotNull();
            }
            // close() must not decrement an externally-owned repository's use
            // counter (Git.close() is a no-op for wrapped repositories)
            assertThat(useCount(repo)).isEqualTo(useCountBefore);
            // and the repository stays usable for its owner
            assertThat(repo.resolve("HEAD")).isNotNull();
        }
    }

    /**
     * Reads JGit's {@code Repository.useCnt} field to pin the ownership
     * contract (verified against JGit 7.3.0).
     */
    private static int useCount(org.eclipse.jgit.lib.Repository repo) throws Exception {
        var field = org.eclipse.jgit.lib.Repository.class.getDeclaredField("useCnt");
        field.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicInteger) field.get(repo)).get();
    }
}
