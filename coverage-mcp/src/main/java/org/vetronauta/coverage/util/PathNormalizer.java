package org.vetronauta.coverage.util;

/**
 * Normalizes repo-relative path strings used as source roots and git diff
 * paths: forward slashes only, repeated separators collapsed, no trailing
 * separator. Shared by {@code git/SourceFileLocator} and
 * {@code jacoco/JacocoReportParser} so both build identical candidate paths.
 */
public final class PathNormalizer {

    /**
     * Normalizes a path string: backslashes become forward slashes, repeated
     * separators collapse to one, any trailing separator is dropped.
     */
    public static String normalize(String path) {
        return path.replace("\\", "/").replaceAll("/+", "/").replaceAll("/$", "");
    }

    private PathNormalizer() {
    }
}
