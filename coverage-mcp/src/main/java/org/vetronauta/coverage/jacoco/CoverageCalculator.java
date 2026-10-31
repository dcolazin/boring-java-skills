package org.vetronauta.coverage.jacoco;

import org.vetronauta.coverage.model.CoverageSummary;
import org.vetronauta.coverage.model.FileCoverage;

import java.util.List;

/**
 * Calculates aggregated coverage metrics from parsed file coverage data.
 */
public class CoverageCalculator {

    /**
     * Calculates overall coverage summary from a list of file coverages.
     *
     * @param fileCoverages list of file coverage data
     * @return aggregated coverage summary
     */
    public CoverageSummary calculate(List<FileCoverage> fileCoverages) {
        CoverageSummary summary = new CoverageSummary();
        summary.addAll(fileCoverages);
        return summary;
    }

}