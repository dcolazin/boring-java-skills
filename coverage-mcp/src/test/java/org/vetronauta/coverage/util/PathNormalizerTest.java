package org.vetronauta.coverage.util;

import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PathNormalizerTest {

    @Test
    public void normalizesSeparatorsAndTrailingSlashes() {
        assertThat(PathNormalizer.normalize("src\\main\\java")).isEqualTo("src/main/java");
        assertThat(PathNormalizer.normalize("src//main///java")).isEqualTo("src/main/java");
        assertThat(PathNormalizer.normalize("src/main/java/")).isEqualTo("src/main/java");
        assertThat(PathNormalizer.normalize("plain")).isEqualTo("plain");
        assertThat(PathNormalizer.normalize("a/\\//b")).isEqualTo("a/b");
    }
}
