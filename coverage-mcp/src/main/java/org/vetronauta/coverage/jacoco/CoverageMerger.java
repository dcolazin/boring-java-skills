package org.vetronauta.coverage.jacoco;

import org.vetronauta.coverage.model.FileCoverage;
import org.vetronauta.coverage.model.LineCoverage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Merges {@link FileCoverage} lists from multiple JaCoCo reports with
 * SonarQube-like semantics: when several reports cover the same class, the
 * <em>best per-line coverage wins</em> — a line covered in any report counts
 * as covered; among equal-coveredness entries the one with more covered
 * branches wins. Reports with line-level data always beat aggregate-only
 * reports; among aggregate-only duplicates the higher-ratio report wins.
 *
 * <p>This replaces the previous first-report-wins behavior, which silently
 * dropped duplicate classes and undercounted coverage in UT+IT setups.
 * Surviving reports are copied, so mutating a source report after a merge
 * cannot leak into the merged result.
 */
public final class CoverageMerger {

    /**
     * Merged coverage plus the class keys that appeared in more than one
     * report (so callers can surface them in {@code notes}).
     *
     * @param files      merged coverage, first-seen order preserved
     * @param mergedKeys class-relative keys that had duplicates
     */
    public record MergeResult(List<FileCoverage> files, List<String> mergedKeys) {
    }

    /**
     * Merges coverage from several reports, keyed by class-relative path.
     */
    public static MergeResult merge(List<List<FileCoverage>> reports) {
        Map<String, FileCoverage> merged = new LinkedHashMap<>();
        Map<String, Integer> keyCount = new LinkedHashMap<>();
        for (List<FileCoverage> report : reports) {
            for (FileCoverage file : report) {
                String key = file.classKey();
                keyCount.merge(key, 1, Integer::sum);
                merged.merge(key, file, CoverageMerger::mergePair);
            }
        }
        List<String> duplicates = keyCount.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        return new MergeResult(new ArrayList<>(merged.values()), duplicates);
    }

    /**
     * Merges two coverage records for the same class.
     */
    static FileCoverage mergePair(FileCoverage a, FileCoverage b) {
        boolean aHasLines = a.hasLineData();
        boolean bHasLines = b.hasLineData();
        if (aHasLines && !bHasLines) {
            return a.copy(); // line data enables new-code restriction; never trade it away
        }
        if (!aHasLines && bHasLines) {
            return b.copy();
        }
        if (!aHasLines) {
            // both aggregate-only: keep the better-ratio report wholesale
            return (b.getLineCoverageRatio() > a.getLineCoverageRatio() ? b : a).copy();
        }

        // both have per-line data: union with best-coverage-wins per line
        Map<Integer, LineCoverage> best = new TreeMap<>();
        for (LineCoverage line : a.getLineCoverages()) {
            best.put(line.lineNumber(), line);
        }
        for (LineCoverage line : b.getLineCoverages()) {
            best.merge(line.lineNumber(), line, CoverageMerger::bestLine);
        }
        FileCoverage result = new FileCoverage(a.getFilePath(), a.getClassName(), a.getClassRelativePath());
        for (LineCoverage line : best.values()) {
            result.addLineCoverage(line.lineNumber(), line.isCovered(), line.missedBranches(), line.coveredBranches());
        }
        return result;
    }

    /**
     * Picks the better of two coverage records for the same line: covered
     * beats uncovered, then more covered branches, then fewer missed branches.
     */
    static LineCoverage bestLine(LineCoverage a, LineCoverage b) {
        if (a.isCovered() != b.isCovered()) {
            return a.isCovered() ? a : b;
        }
        if (a.coveredBranches() != b.coveredBranches()) {
            return a.coveredBranches() > b.coveredBranches() ? a : b;
        }
        return a.missedBranches() <= b.missedBranches() ? a : b;
    }

    private CoverageMerger() {
    }
}
