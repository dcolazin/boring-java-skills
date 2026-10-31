package org.vetronauta.coverage.git;

import org.testng.annotations.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SourceFileLocatorTest {

    @Test
    public void nullSourceRootsFallBackToDefaults() {
        var locator = new SourceFileLocator(null);
        assertThat(locator.getSourceRoots())
                .containsExactly("src/main/java", "src/test/java");
    }

    @Test
    public void emptySourceRootsFallBackToDefaults() {
        assertThat(new SourceFileLocator(List.of()).getSourceRoots())
                .containsExactly("src/main/java", "src/test/java");
    }

    @Test
    public void inputListIsDefensivelyCopied() {
        var roots = new java.util.ArrayList<>(List.of("src/main/java"));
        var locator = new SourceFileLocator(roots);
        roots.add("mutated/after/construction");
        assertThat(locator.getSourceRoots()).containsExactly("src/main/java");
    }

    @Test
    public void stripSourceRootRemovesConfiguredRootPrefix() {
        var locator = new SourceFileLocator(List.of("src/main/java", "src/test/java"));
        assertThat(locator.stripSourceRoot("src/main/java/com/example/Calculator.java"))
                .isEqualTo("com/example/Calculator.java");
        assertThat(locator.stripSourceRoot("src/test/java/com/example/CalculatorTest.java"))
                .isEqualTo("com/example/CalculatorTest.java");
        // not under any configured root
        assertThat(locator.stripSourceRoot("docs/readme.md")).isNull();
        assertThat(locator.stripSourceRoot(null)).isNull();
        // windows separators and doubled slashes are normalized
        assertThat(locator.stripSourceRoot("src\\main\\java\\com\\example\\Utils.java"))
                .isEqualTo("com/example/Utils.java");
    }

    @Test
    public void backslashRootsNormalizeLikeTheirPaths() {
        var windowsLocator = new SourceFileLocator(List.of("src\\main\\java", "src//test//java"));
        assertThat(windowsLocator.stripSourceRoot("src\\main\\java\\com\\example\\Calculator.java"))
                .isEqualTo("com/example/Calculator.java");
        assertThat(windowsLocator.stripSourceRoot("src/test/java/com/example/CalculatorTest.java"))
                .isEqualTo("com/example/CalculatorTest.java");
    }
}
