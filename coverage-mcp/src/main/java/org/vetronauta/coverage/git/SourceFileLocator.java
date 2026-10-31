package org.vetronauta.coverage.git;

import org.vetronauta.coverage.util.PathNormalizer;

import java.util.List;

/**
 * Maps between git diff paths and class-relative paths via the configured
 * source roots (e.g. {@code src/main/java}, {@code src/test/java}). The
 * class-relative remainder is the stable key shared by JaCoCo reports and
 * git diff paths regardless of which root a file lives under.
 */
public class SourceFileLocator {

    public static final List<String> DEFAULT_SOURCE_ROOTS = List.of("src/main/java", "src/test/java");

    private final List<String> sourceRoots;

    public SourceFileLocator(List<String> sourceRoots) {
        this.sourceRoots = sourceRoots != null && !sourceRoots.isEmpty()
                ? List.copyOf(sourceRoots)
                : DEFAULT_SOURCE_ROOTS;
    }

    /**
     * Strips a configured source root prefix from a git path.
     * Example: {@code src/test/java/com/example/Foo.java} →
     * {@code com/example/Foo.java} (with {@code src/test/java} configured).
     *
     * @return the package-relative remainder, or {@code null} when the path is
     *         not under any configured source root
     */
    public String stripSourceRoot(String gitPath) {
        if (gitPath == null) {
            return null;
        }
        String normalized = PathNormalizer.normalize(gitPath);
        for (String root : sourceRoots) {
            String prefix = PathNormalizer.normalize(root) + "/";
            if (normalized.startsWith(prefix)) {
                return normalized.substring(prefix.length());
            }
        }
        return null;
    }

    public List<String> getSourceRoots() {
        return sourceRoots;
    }
}
