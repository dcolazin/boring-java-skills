package org.vetronauta.coverage.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects new code changes using JGit.
 * Diffs the merge-base of HEAD and the baseline ref against HEAD
 * (SonarQube reference-branch new-code semantics).
 */
public class NewCodeDetector implements AutoCloseable {

    /**
     * Ref resolution order, mirroring SonarQube's {@code GitScmProvider}:
     * local branch, then {@code origin}, then {@code upstream} remotes, then
     * the raw ref string (SHA, tag, HEAD~1, ...).
     */
    private static final String[] REF_PREFIXES = {
            "refs/heads/",
            "refs/remotes/origin/",
            "refs/remotes/upstream/"
    };

    private final Repository repository;
    private final Git git;

    public NewCodeDetector(Path repoPath) throws IOException {
        this.git = Git.open(repoPath.toFile());
        this.repository = git.getRepository();
    }

    /**
     * Wraps an externally-owned repository; {@link #close()} will not close
     * it (the {@code Git} wrapper is created with {@code closeRepo=false}).
     */
    public NewCodeDetector(Repository repository) {
        this.repository = repository;
        this.git = new Git(repository);
    }

    /**
     * Resolves a baseline ref (branch, tag, SHA) to an ObjectId.
     */
    public ObjectId resolveBaseline(String baseline) throws IOException {
        for (String prefix : REF_PREFIXES) {
            Ref ref = repository.exactRef(prefix + baseline);
            if (ref != null && ref.getObjectId() != null) {
                return ref.getObjectId();
            }
        }
        ObjectId baselineId = repository.resolve(baseline);
        if (baselineId == null) {
            throw new IllegalArgumentException("Could not resolve baseline ref: " + baseline);
        }
        return baselineId;
    }

    /**
     * Computes the merge-base (best common ancestor) of two commits.
     *
     * @throws IllegalArgumentException when the commits share no ancestry
     */
    public ObjectId mergeBase(ObjectId a, ObjectId b) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit first = walk.parseCommit(a);
            RevCommit second = walk.parseCommit(b);
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(first);
            walk.markStart(second);
            RevCommit base = walk.next();
            if (base == null) {
                throw new IllegalArgumentException(
                        "No common ancestry between " + a.getName() + " and " + b.getName()
                                + " (unrelated histories)");
            }
            return base.getId();
        }
    }

    /**
     * Computes the diff between two tree IDs.
     */
    public List<DiffEntry> computeDiff(ObjectId oldTree, ObjectId newTree) throws IOException, GitAPIException {
        try (ObjectReader reader = repository.newObjectReader()) {
            CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
            oldTreeIter.reset(reader, oldTree);

            CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
            newTreeIter.reset(reader, newTree);

            List<DiffEntry> diffs = git.diff()
                    .setOldTree(oldTreeIter)
                    .setNewTree(newTreeIter)
                    .call();

            // Rename detection (>=60% similarity) as a post-processing pass —
            // JGit's DiffCommand has no built-in rename switch. Collapses
            // DELETE+ADD pairs into RENAME entries so a moved file only counts
            // its actually edited lines as new code (SonarQube parity; without
            // this a pure rename looks 100% new code).
            RenameDetector renameDetector = new RenameDetector(repository);
            renameDetector.addAll(diffs);
            return renameDetector.compute();
        }
    }

    /**
     * Gets the tree ID for a commit.
     */
    public ObjectId getTreeId(ObjectId commitId) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(commitId);
            return commit.getTree().getId();
        }
    }

    /**
     * Gets the HEAD commit ID.
     */
    public ObjectId getHead() throws IOException {
        return repository.resolve("HEAD");
    }

    /**
     * Gets all changed files between the merge-base of baseline and HEAD, and
     * HEAD itself. Changed files are reported with new path, change type and
     * old path.
     */
    public List<ChangedFileInfo> getChangedFiles(String baseline) throws IOException, GitAPIException {
        List<DiffEntry> diffs = getDiffEntries(baseline);
        List<ChangedFileInfo> result = new ArrayList<>(diffs.size());

        for (DiffEntry diff : diffs) {
            result.add(new ChangedFileInfo(
                    diff.getNewPath(),
                    diff.getChangeType().name(),
                    diff.getOldPath()
            ));
        }

        return result;
    }

    /**
     * Gets all diff entries (merge-base → HEAD) with full detail for precise
     * line computation.
     */
    public List<DiffEntry> getDiffEntries(String baseline) throws IOException, GitAPIException {
        ObjectId headId = getHead();
        ObjectId baselineId = resolveBaseline(baseline);
        ObjectId baseId = mergeBase(headId, baselineId);

        ObjectId oldTreeId = getTreeId(baseId);
        ObjectId newTreeId = getTreeId(headId);

        return computeDiff(oldTreeId, newTreeId);
    }

    public void close() {
        git.close();
        if (ownsRepository) {
            repository.close();
        }
    }

    public Repository getRepository() {
        return repository;
    }

    /**
     * Information about a changed file from Git diff.
     */
    public static class ChangedFileInfo {
        private final String newPath;
        private final String changeType;
        private final String oldPath;

        public ChangedFileInfo(String newPath, String changeType, String oldPath) {
            this.newPath = newPath;
            this.changeType = changeType;
            this.oldPath = oldPath;
        }

        public String getNewPath() { return newPath; }
        public String getChangeType() { return changeType; }
        public String getOldPath() { return oldPath; }
    }
}
