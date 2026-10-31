package org.vetronauta.coverage.jacoco;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the {@code reportPath} tool argument to actual report files,
 * mirroring {@code sonar.coverage.jacocoXmlReportPaths} semantics:
 * comma-separated entries, each either a plain path or an Ant-style glob
 * (any-depth search for a jacoco.xml file). Relative entries resolve
 * against the repository root.
 */
public final class ReportLocator {

    /**
     * Resolves a report path spec to concrete report files.
     *
     * @param repoPath repository root used to resolve relative paths/globs
     * @param spec     comma-separated plain paths and/or glob patterns
     * @return at least one path (deduplicated, order preserved); plain paths
     *         are returned even when they do not exist (the parser produces
     *         the error), glob entries that match nothing are skipped
     * @throws IllegalArgumentException when the spec is blank or resolves to nothing
     */
    public static List<Path> resolveReports(Path repoPath, String spec) {
        List<Path> result = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("reportPath is empty");
        }
        for (String entry : spec.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.indexOf('*') >= 0 || trimmed.indexOf('?') >= 0 || trimmed.indexOf('{') >= 0) {
                result.addAll(expandGlob(repoPath, trimmed));
            } else {
                result.add(plainPath(repoPath, trimmed));
            }
        }
        List<Path> unique = result.stream().distinct().toList();
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("No JaCoCo reports found for: " + spec);
        }
        return unique;
    }

    private static Path plainPath(Path repoPath, String path) {
        Path p = Paths.get(path);
        return p.isAbsolute() ? p : repoPath.resolve(path);
    }

    private static List<Path> expandGlob(Path repoPath, String pattern) {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        Path baseDir = repoPath;
        Path patternPath = Paths.get(pattern);
        if (patternPath.isAbsolute()) {
            baseDir = patternPath.getRoot();
        }
        final Path base = baseDir;
        // Wildcard-free leading directory segments bound the walk: with
        // "module-a/**/jacoco.xml" only module-a/ is scanned, while a leading
        // "**" still searches everywhere under the base.
        Path walkRoot = base;
        for (int i = 0; i < patternPath.getNameCount(); i++) {
            String segment = patternPath.getName(i).toString();
            if (isWildcardSegment(segment)) {
                break;
            }
            walkRoot = walkRoot.resolve(segment);
        }
        if (!Files.isDirectory(walkRoot)) {
            return List.of();
        }
        List<Path> matches = new ArrayList<>();
        try {
            // .git is skipped during traversal (not filtered out afterwards):
            // its object store can dwarf the working tree, and a report
            // hiding in there is never wanted.
            Files.walkFileTree(walkRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return ".git".equals(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relative = base.relativize(file);
                    if (matcher.matches(relative) || matcher.matches(file)) {
                        matches.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to expand report glob '" + pattern + "': " + e.getMessage(), e);
        }
        matches.sort(null); // deterministic order for notes/merge input
        return matches;
    }

    private static boolean isWildcardSegment(String segment) {
        return segment.indexOf('*') >= 0 || segment.indexOf('?') >= 0 || segment.indexOf('{') >= 0;
    }

    private ReportLocator() {
    }
}
