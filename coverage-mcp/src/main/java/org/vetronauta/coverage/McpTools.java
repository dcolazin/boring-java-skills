package org.vetronauta.coverage;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.vetronauta.coverage.git.ChangedLinesComputer;
import org.vetronauta.coverage.git.NewCodeCalculator;
import org.vetronauta.coverage.git.NewCodeDetector;
import org.vetronauta.coverage.git.SourceFileLocator;
import org.vetronauta.coverage.jacoco.CoverageCalculator;
import org.vetronauta.coverage.jacoco.CoverageMerger;
import org.vetronauta.coverage.jacoco.JacocoReportParser;
import org.vetronauta.coverage.jacoco.ReportLocator;
import org.vetronauta.coverage.model.ChangedFile;
import org.vetronauta.coverage.model.ChangedFilesResponse;
import org.vetronauta.coverage.model.CoverageResponse;
import org.vetronauta.coverage.model.CoverageSummary;
import org.vetronauta.coverage.model.FileCoverage;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP tool definitions and handlers.
 */
public class McpTools {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Typed conversion target for JSON Schema objects (no raw {@code Map.class}). */
    private static final TypeReference<Map<String, Object>> SCHEMA_TYPE = new TypeReference<>() {
    };

    /**
     * Per-file detail ordering: worst-covered first (most missed lines, then
     * lower line ratio, then path), so {@code maxFilesInDetail} truncation
     * keeps the files worth fixing and the ordering is deterministic.
     */
    private static final Comparator<FileCoverage> WORST_FIRST =
            Comparator.comparingInt(FileCoverage::getLinesMissed).reversed()
                    .thenComparingDouble(FileCoverage::getLineCoverageRatio)
                    .thenComparing(FileCoverage::getFilePath, Comparator.nullsLast(Comparator.naturalOrder()));

    // ------------------------------------------------------------ arg helpers

    private static String str(Map<String, Object> args, String name) {
        Object value = args.get(name);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static List<String> stringList(Map<String, Object> args, String name, List<String> defaults) {
        Object value = args.get(name);
        if (value instanceof List<?> list) {
            List<String> result = list.stream().map(String::valueOf).toList();
            return result.isEmpty() ? defaults : result;
        }
        return defaults;
    }

    private static int intArg(Map<String, Object> args, String name, int defaultValue) {
        Object value = args.get(name);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static McpSchema.CallToolResult errorResult(Exception e) {
        ObjectNode error = MAPPER.createObjectNode();
        String message = e.getMessage() != null ? e.getMessage() : e.toString();
        error.put("error", message);
        error.put("type", e.getClass().getSimpleName());
        return McpSchema.CallToolResult.builder()
                .addTextContent(error.toPrettyString())
                .isError(true)
                .build();
    }

    private static McpSchema.CallToolResult missingParam(String name) {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("error", "missing required parameter '" + name + "'");
        error.put("type", "IllegalArgumentException");
        return McpSchema.CallToolResult.builder()
                .addTextContent(error.toPrettyString())
                .isError(true)
                .build();
    }

    // -------------------------------------------------------------- schemas

    private static Map<String, Object> analyzeCoverageSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();

        ObjectNode repoPath = MAPPER.createObjectNode();
        repoPath.put("type", "string");
        repoPath.put("description", "Git repository root path (absolute or relative to server CWD)");
        properties.set("repoPath", repoPath);

        ObjectNode reportPath = MAPPER.createObjectNode();
        reportPath.put("type", "string");
        reportPath.put("description",
                "Path or glob to jacoco.xml (supports **/jacoco.xml, comma-separated; relative paths resolve against repoPath)");
        properties.set("reportPath", reportPath);

        ObjectNode baseline = MAPPER.createObjectNode();
        baseline.put("type", "string");
        baseline.put("description",
                "Optional baseline ref (SHA, branch, tag; resolved as refs/heads/<x>, then origin/<x>, upstream/<x>, then raw). Present = new-code mode");
        properties.set("baseline", baseline);

        ObjectNode sourceRoots = MAPPER.createObjectNode();
        sourceRoots.put("type", "array");
        ObjectNode items = MAPPER.createObjectNode();
        items.put("type", "string");
        sourceRoots.set("items", items);
        sourceRoots.put("description", "Source root directories for package-to-file mapping (default: [\"src/main/java\", \"src/test/java\"])");
        properties.set("sourceRoots", sourceRoots);

        ObjectNode maxFilesInDetail = MAPPER.createObjectNode();
        maxFilesInDetail.put("type", "integer");
        maxFilesInDetail.put("description",
                "Max files to include in per-file detail, listed worst-covered first (default: 20)");
        properties.set("maxFilesInDetail", maxFilesInDetail);

        schema.set("properties", properties);

        ArrayNode required = MAPPER.createArrayNode();
        required.add("repoPath");
        required.add("reportPath");
        schema.set("required", required);

        return MAPPER.convertValue(schema, SCHEMA_TYPE);
    }

    private static Map<String, Object> listChangedFilesSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();

        ObjectNode repoPath = MAPPER.createObjectNode();
        repoPath.put("type", "string");
        repoPath.put("description", "Git repository root path");
        properties.set("repoPath", repoPath);

        ObjectNode baseline = MAPPER.createObjectNode();
        baseline.put("type", "string");
        baseline.put("description", "Baseline ref (SHA, branch, tag) for diff");
        properties.set("baseline", baseline);

        schema.set("properties", properties);

        ArrayNode required = MAPPER.createArrayNode();
        required.add("repoPath");
        required.add("baseline");
        schema.set("required", required);

        return MAPPER.convertValue(schema, SCHEMA_TYPE);
    }

    // ------------------------------------------------------ shared pipeline

    /**
     * Parses all reports matched by the reportPath spec (comma-separated
     * paths/globs) and merges them: duplicate classes are combined with
     * best-per-line-coverage semantics (a line covered in any report counts
     * as covered — SonarQube's multi-report behavior).
     */
    private static ParsedReports parseReports(Path repoPath, String reportPathSpec, List<String> sourceRoots)
            throws Exception {
        List<Path> reportPaths = ReportLocator.resolveReports(repoPath, reportPathSpec);
        JacocoReportParser parser = new JacocoReportParser();

        List<List<FileCoverage>> reports = new ArrayList<>(reportPaths.size());
        for (Path report : reportPaths) {
            reports.add(parser.parse(report, sourceRoots, repoPath));
        }

        List<String> notes = new ArrayList<>();
        if (reportPaths.size() > 1) {
            notes.add("merged " + reportPaths.size() + " reports ("
                    + reportPaths.stream().map(Path::toString).collect(Collectors.joining(", ")) + ")");
        }

        CoverageMerger.MergeResult merged = CoverageMerger.merge(reports);
        if (!merged.mergedKeys().isEmpty()) {
            List<String> keys = merged.mergedKeys();
            notes.add("merged duplicate coverage for " + keys.size() + " class(es) (best per-line coverage kept): "
                    + keys.stream().limit(5).collect(Collectors.joining(", "))
                    + (keys.size() > 5 ? ", …" : ""));
        }
        return new ParsedReports(merged.files(), notes);
    }

    private record ParsedReports(List<FileCoverage> files, List<String> notes) {
    }

    // ---------------------------------------------------------- tool 1

    /**
     * Tool 1: analyze_coverage
     * Primary tool that analyzes JaCoCo coverage with optional new-code mode.
     */
    public static final McpServerFeatures.SyncToolSpecification ANALYZE_COVERAGE =
            new McpServerFeatures.SyncToolSpecification(
                    McpSchema.Tool.builder("analyze_coverage")
                            .description("Analyze JaCoCo coverage from a Git repo. Returns line/condition coverage, "
                                    + "uncovered lines, and optionally new-code (changed-lines-only) coverage "
                                    + "with SonarQube reference-branch semantics.")
                            .inputSchema(analyzeCoverageSchema())
                            .build(),
                    (exchange, request) -> {
                        try {
                            Map<String, Object> args = request.arguments();
                            String repoPathStr = str(args, "repoPath");
                            String reportPathStr = str(args, "reportPath");
                            if (repoPathStr == null) {
                                return missingParam("repoPath");
                            }
                            if (reportPathStr == null) {
                                return missingParam("reportPath");
                            }
                            String baseline = str(args, "baseline");
                            List<String> sourceRoots = stringList(args, "sourceRoots", SourceFileLocator.DEFAULT_SOURCE_ROOTS);
                            int maxFilesInDetail = intArg(args, "maxFilesInDetail", 20);

                            Path repoPath = Paths.get(repoPathStr);

                            ParsedReports parsed = parseReports(repoPath, reportPathStr, sourceRoots);
                            List<FileCoverage> fileCoverages = parsed.files();
                            List<String> notes = new ArrayList<>(parsed.notes());

                            // New-code coverage restricted to changed lines (mutates fileCoverages)
                            if (baseline != null) {
                                try (NewCodeDetector detector = new NewCodeDetector(repoPath);
                                     NewCodeCalculator newCodeCalc = new NewCodeCalculator(
                                             detector,
                                             new ChangedLinesComputer(detector.getRepository()),
                                             new SourceFileLocator(sourceRoots))) {
                                    notes.addAll(newCodeCalc.calculateNewCodeCoverage(fileCoverages, baseline));
                                }
                            }

                            // Aggregates computed after new-code mutation
                            CoverageCalculator calculator = new CoverageCalculator();
                            CoverageSummary overall = calculator.calculate(fileCoverages);

                            // Detail: worst-covered files first so truncation
                            // keeps the files an agent should act on
                            List<CoverageResponse.FileCoverageDetail> fileDetails = fileCoverages.stream()
                                    .sorted(WORST_FIRST)
                                    .limit(maxFilesInDetail)
                                    .map(CoverageResponse.FileCoverageDetail::new)
                                    .collect(Collectors.toList());

                            boolean truncated = fileCoverages.size() > maxFilesInDetail;
                            if (truncated) {
                                notes.add((fileCoverages.size() - maxFilesInDetail)
                                        + " files omitted from detail (maxFilesInDetail=" + maxFilesInDetail
                                        + "); aggregates unaffected");
                            }

                            CoverageResponse response =
                                    new CoverageResponse(overall, fileDetails, truncated, fileCoverages.size(), notes);

                            ObjectNode result = MAPPER.convertValue(response, ObjectNode.class);

                            return McpSchema.CallToolResult.builder()
                                    .addTextContent(result.toPrettyString())
                                    .isError(false)
                                    .build();

                        } catch (Exception e) {
                            return errorResult(e);
                        }
                    }
            );

    // ---------------------------------------------------------- tool 2

    /**
     * Tool 2: list_changed_files
     * Helper tool that lists files changed since a baseline with per-file changed-line counts.
     */
    public static final McpServerFeatures.SyncToolSpecification LIST_CHANGED_FILES =
            new McpServerFeatures.SyncToolSpecification(
                    McpSchema.Tool.builder("list_changed_files")
                            .description("List files changed since a baseline ref with per-file added/deleted line "
                                    + "counts and changed line numbers. Uses the same diff engine as analyze_coverage.")
                            .inputSchema(listChangedFilesSchema())
                            .build(),
                    (exchange, request) -> {
                        try {
                            Map<String, Object> args = request.arguments();
                            String repoPathStr = str(args, "repoPath");
                            String baseline = str(args, "baseline");
                            if (repoPathStr == null) {
                                return missingParam("repoPath");
                            }
                            if (baseline == null) {
                                return missingParam("baseline");
                            }

                            Path repoPath = Paths.get(repoPathStr);

                            // No locator needed: changed-file listing never
                            // matches diff paths against coverage
                            try (NewCodeDetector detector = new NewCodeDetector(repoPath);
                                 NewCodeCalculator newCodeCalc = new NewCodeCalculator(
                                         detector,
                                         new ChangedLinesComputer(detector.getRepository()))) {

                                List<NewCodeCalculator.ChangedFileDetail> changedFiles =
                                        newCodeCalc.getChangedFilesWithLines(baseline);

                                ChangedFilesResponse response = new ChangedFilesResponse(baseline,
                                        changedFiles.stream()
                                                .map(cf -> new ChangedFile(
                                                        cf.getFilePath(),
                                                        cf.getStatus(),
                                                        cf.getAddedLines(),
                                                        cf.getDeletedLines(),
                                                        cf.getChangedLineNumbers(),
                                                        cf.getOldPath()
                                                ))
                                                .toList());

                                ObjectNode result = MAPPER.convertValue(response, ObjectNode.class);

                                return McpSchema.CallToolResult.builder()
                                        .addTextContent(result.toPrettyString())
                                        .isError(false)
                                        .build();
                            }

                        } catch (Exception e) {
                            return errorResult(e);
                        }
                    }
            );
}
