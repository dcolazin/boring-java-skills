package org.vetronauta.coverage.jacoco;

import org.testng.annotations.Test;
import org.vetronauta.coverage.model.FileCoverage;
import org.vetronauta.coverage.model.LineCoverage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-report merge semantics: duplicate classes combine with
 * best-per-line-coverage (SonarQube-like), line data beats aggregate-only,
 * aggregate-only duplicates keep the higher-ratio report.
 */
public class CoverageMergerTest {

    private static FileCoverage lines(String key, LineCoverage... lineData) {
        String className = key.replace('/', '.').replaceAll("\\.java$", "");
        FileCoverage f = new FileCoverage("src/main/java/" + key, className, key);
        for (LineCoverage line : lineData) {
            f.addLineCoverage(line.lineNumber(), line.isCovered(), line.missedBranches(), line.coveredBranches());
        }
        return f;
    }

    private static FileCoverage aggregate(String key, int missed, int covered) {
        FileCoverage f = new FileCoverage("src/main/java/" + key, key, key);
        f.addLineCoverage(missed, covered);
        return f;
    }

    @Test
    public void duplicateClassMergesBestPerLineCoverage() {
        FileCoverage ut = lines("com/example/Foo.java",
                new LineCoverage(1, false, 2, 0),
                new LineCoverage(2, true, 1, 1));
        FileCoverage it = lines("com/example/Foo.java",
                new LineCoverage(1, true, 0, 2),
                new LineCoverage(2, false, 2, 0));

        FileCoverage merged = CoverageMerger.mergePair(ut, it);

        // line 1: covered (IT) beats missed (UT) → IT's entry (0 mb, 2 cb)
        // line 2: covered (UT) beats missed (IT) → UT's entry (1 mb, 1 cb)
        assertThat(merged.getTotalLines()).isEqualTo(2);
        assertThat(merged.getLinesCovered()).isEqualTo(2);
        assertThat(merged.getBranchesCovered()).isEqualTo(3);
        assertThat(merged.getBranchesMissed()).isEqualTo(1);
        // merge result is independent of both source reports
        assertThat(merged).isNotSameAs(ut).isNotSameAs(it);
        // line detail stays ascending after the merge
        assertThat(merged.getLineCoverages())
                .extracting(LineCoverage::lineNumber)
                .containsExactly(1, 2);
    }

    @Test
    public void lineDataBeatsAggregateOnly() {
        FileCoverage aggregateOnly = aggregate("com/example/Foo.java", 3, 1);
        FileCoverage withLines = lines("com/example/Foo.java", new LineCoverage(5, true, 0, 0));

        // copies, not shared instances — but metrically the line-data report
        FileCoverage merged = CoverageMerger.mergePair(aggregateOnly, withLines);
        assertThat(merged).isNotSameAs(withLines);
        assertThat(merged.getTotalLines()).isEqualTo(withLines.getTotalLines());
        assertThat(merged.getLinesCovered()).isEqualTo(withLines.getLinesCovered());

        FileCoverage mergedReverse = CoverageMerger.mergePair(withLines, aggregateOnly);
        assertThat(mergedReverse).isNotSameAs(withLines);
        assertThat(mergedReverse.getTotalLines()).isEqualTo(withLines.getTotalLines());
    }

    @Test
    public void bothAggregateOnlyKeepsHigherRatioReport() {
        FileCoverage worse = aggregate("com/example/Foo.java", 3, 1);  // 25%
        FileCoverage better = aggregate("com/example/Foo.java", 1, 3); // 75%

        FileCoverage merged = CoverageMerger.mergePair(worse, better);
        assertThat(merged).isNotSameAs(better);
        assertThat(merged.getTotalLines()).isEqualTo(4);
        assertThat(merged.getLinesCovered()).isEqualTo(3);

        FileCoverage mergedReverse = CoverageMerger.mergePair(better, worse);
        assertThat(mergedReverse).isNotSameAs(better);
        assertThat(mergedReverse.getLinesCovered()).isEqualTo(3);

        // exact tie → first report's numbers win (stable, order-independent)
        FileCoverage tie = aggregate("com/example/Foo.java", 1, 3);
        FileCoverage mergedTie = CoverageMerger.mergePair(better, tie);
        assertThat(mergedTie).isNotSameAs(better);
        assertThat(mergedTie.getLinesCovered()).isEqualTo(3);
    }

    @Test
    public void mergeReportsDuplicateKeysAndUnionsDisjointLines() {
        FileCoverage foo = lines("com/example/Foo.java", new LineCoverage(1, true, 0, 0));
        FileCoverage bar = lines("com/example/Bar.java", new LineCoverage(1, false, 0, 0));
        FileCoverage fooAgain = lines("com/example/Foo.java", new LineCoverage(2, true, 0, 0));

        var result = CoverageMerger.merge(List.of(List.of(foo, bar), List.of(fooAgain)));

        assertThat(result.files()).hasSize(2);
        assertThat(result.mergedKeys()).containsExactly("com/example/Foo.java");

        FileCoverage mergedFoo = result.files().stream()
                .filter(f -> f.classKey().equals("com/example/Foo.java"))
                .findFirst().orElseThrow();
        // union: line 1 from report 1, line 2 from report 2
        assertThat(mergedFoo.getTotalLines()).isEqualTo(2);
        assertThat(mergedFoo.getLinesCovered()).isEqualTo(2);
    }

    @Test
    public void mergeResultIsDefensivelyCopiedFromItsSources() {
        FileCoverage worse = aggregate("com/example/Foo.java", 3, 1);  // 25%
        FileCoverage better = aggregate("com/example/Foo.java", 1, 3); // 75%

        var result = CoverageMerger.merge(List.of(List.of(worse), List.of(better)));
        FileCoverage merged = result.files().get(0);
        assertThat(merged.getLinesCovered()).isEqualTo(3);

        // mutating a source report after the merge must not leak into the result
        better.addLineCoverage(10, 0);
        assertThat(merged.getLinesCovered()).isEqualTo(3);
        assertThat(merged.getTotalLines()).isEqualTo(4);
    }

    @Test
    public void disjointReportsReportNoDuplicates() {
        var result = CoverageMerger.merge(List.of(
                List.of(lines("com/example/A.java", new LineCoverage(1, true, 0, 0))),
                List.of(lines("com/example/B.java", new LineCoverage(1, true, 0, 0)))));

        assertThat(result.files()).hasSize(2);
        assertThat(result.mergedKeys()).isEmpty();
    }
}
