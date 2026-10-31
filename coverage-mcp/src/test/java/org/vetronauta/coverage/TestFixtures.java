package org.vetronauta.coverage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Shared test fixture: copies fixture-repo to a temp dir and renames git -> .git. */
public final class TestFixtures {

    public static Path prepareFixtureRepo() throws IOException {
        Path source = Paths.get("src/test/resources/fixture-repo");
        Path target = Files.createTempDirectory("cov-mcp-test-");
        copyDir(source, target);
        Path git = target.resolve("git");
        if (Files.isDirectory(git)) {
            Files.move(git, target.resolve(".git"));
        }
        return target;
    }

    private static void copyDir(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            walk.forEach(p -> {
                try {
                    Path dest = target.resolve(source.relativize(p));
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(p, dest);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("copy failed for " + p, e);
                }
            });
        }
    }

    private TestFixtures() {
    }
}
