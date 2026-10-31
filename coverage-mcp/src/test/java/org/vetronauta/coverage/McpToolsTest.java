package org.vetronauta.coverage;

import io.modelcontextprotocol.spec.McpSchema;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the MCP tool handlers in-process (no server/stdio needed) by
 * invoking the {@code callHandler()} bi-function with real requests against
 * the fixture repo and fixture JaCoCo report.
 */
public class McpToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Path fixtureRepo;

    @BeforeClass
    public static void setUp() throws Exception {
        fixtureRepo = TestFixtures.prepareFixtureRepo();
    }

    private static McpSchema.CallToolResult call(McpServerFeaturesHolder spec, Map<String, Object> args) {
        return spec.spec().callHandler().apply(null,
                new McpSchema.CallToolRequest(spec.spec().tool().name(), args));
    }

    /** Tiny indirection so the import stays in one place. */
    record McpServerFeaturesHolder(io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification spec) {
    }

    private static String textOf(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    // ------------------------------------------------------------- schemas

    @Test
    public void analyzeCoverageSchemaDeclaresRequiredParams() {
        var tool = McpTools.ANALYZE_COVERAGE.tool();
        assertThat(tool.name()).isEqualTo("analyze_coverage");
        assertThat(tool.description()).contains("JaCoCo");

        Map<String, Object> schema = tool.inputSchema();
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) schema.get("required");
        assertThat(required).containsExactlyInAnyOrder("repoPath", "reportPath");

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKeys("repoPath", "reportPath", "baseline", "sourceRoots", "maxFilesInDetail");
    }

    @Test
    public void listChangedFilesSchemaDeclaresRequiredParams() {
        var tool = McpTools.LIST_CHANGED_FILES.tool();
        assertThat(tool.name()).isEqualTo("list_changed_files");

        Map<String, Object> schema = tool.inputSchema();
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) schema.get("required");
        assertThat(required).containsExactlyInAnyOrder("repoPath", "baseline");
    }

    // ------------------------------------------------------ analyze_coverage

    @Test
    public void analyzeCoverageHappyPath() {
        Map<String, Object> args = new HashMap<>();
        args.put("repoPath", fixtureRepo.toString());
        args.put("reportPath", fixtureRepo.resolve("jacoco.xml").toString());

        McpSchema.CallToolResult result = call(new McpServerFeaturesHolder(McpTools.ANALYZE_COVERAGE), args);

        assertThat(result.isError()).isFalse();
        JsonNode json = JSON.readTree(textOf(result));
        assertThat(json.path("overall").path("totalFiles").asInt()).isEqualTo(4);
        assertThat(json.path("overall").path("totalLines").asInt()).isGreaterThan(0);
        assertThat(json.path("truncated").asBoolean()).isFalse();

        var calculator = findFile(json, "Calculator.java");
        assertThat(calculator.path("className").asString()).isEqualTo("com.example.Calculator");
        assertThat(calculator.path("totalLines").asInt()).isEqualTo(15);
        assertThat(calculator.path("linesCovered").asInt()).isEqualTo(12);
        assertThat(calculator.path("linesMissed").asInt()).isEqualTo(3);
        assertThat(calculator.path("uncoveredLines").isArray()).isTrue();
        // aggregates: 43 executable lines across 4 files, 33 covered
        assertThat(json.path("overall").path("totalLines").asInt()).isEqualTo(43);
        assertThat(json.path("overall").path("totalLinesCovered").asInt()).isEqualTo(33);
        assertThat(json.path("notes").isArray()).isTrue();
    }

    @Test
    public void analyzeCoverageAcceptsGlobReportPath() {
        Map<String, Object> args = new HashMap<>();
        args.put("repoPath", fixtureRepo.toString());
        args.put("reportPath", "**/jacoco.xml");

        McpSchema.CallToolResult result = call(new McpServerFeaturesHolder(McpTools.ANALYZE_COVERAGE), args);

        assertThat(result.isError()).isFalse();
        JsonNode json = JSON.readTree(textOf(result));
        assertThat(json.path("overall").path("totalFiles").asInt()).isEqualTo(4);
    }

    @Test
    public void analyzeCoverageMergesDuplicateClassesAcrossReports() throws Exception {
        // second report covering line 26 of Calculator (missed in the fixture
        // report): best-per-line merge must lift Calculator to 13/15 covered
        Path extraReport = Files.createTempFile("jacoco-it", ".xml");
        Files.writeString(extraReport, """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <report name="it">
                  <package name="com/example">
                    <sourcefile name="Calculator.java">
                      <line nr="26" mi="0" ci="1" mb="0" cb="0"/>
                    </sourcefile>
                  </package>
                </report>
                """);

        Map<String, Object> args = new HashMap<>();
        args.put("repoPath", fixtureRepo.toString());
        args.put("reportPath", fixtureRepo.resolve("jacoco.xml") + "," + extraReport);

        McpSchema.CallToolResult result = call(new McpServerFeaturesHolder(McpTools.ANALYZE_COVERAGE), args);

        assertThat(result.isError()).isFalse();
        JsonNode json = JSON.readTree(textOf(result));
        // duplicate class merged, not duplicated: still 4 files
        assertThat(json.path("overall").path("totalFiles").asInt()).isEqualTo(4);
        assertThat(json.path("overall").path("totalLines").asInt()).isEqualTo(43);
        // 33 + line 26 now covered
        assertThat(json.path("overall").path("totalLinesCovered").asInt()).isEqualTo(34);

        var calculator = findFile(json, "Calculator.java");
        assertThat(calculator.path("linesCovered").asInt()).isEqualTo(13);
        assertThat(calculator.path("linesMissed").asInt()).isEqualTo(2);

        // both merge notes present: multi-report + duplicate-merge detail
        assertThat(json.path("notes").size()).isEqualTo(2);
        assertThat(json.path("notes").get(0).asString()).contains("merged 2 reports");
        assertThat(json.path("notes").get(1).asString())
                .contains("best per-line coverage")
                .contains("Calculator.java");
    }

    @Test
    public void analyzeCoverageMissingRequiredParamReturnsErrorResult() {
        Map<String, Object> args = new HashMap<>();
        args.put("repoPath", fixtureRepo.toString());
        // reportPath absent

        McpSchema.CallToolResult result = call(new McpServerFeaturesHolder(McpTools.ANALYZE_COVERAGE), args);

        assertThat(result.isError()).isTrue();
        JsonNode error = JSON.readTree(textOf(result));
        assertThat(error.path("error").asString()).contains("reportPath");
        assertThat(error.path("type").asString()).isEqualTo("IllegalArgumentException");
    }

    @Test
    public void analyzeCoverageWithMaxFilesInDetailTruncatesKeepingWorstFiles() {
        Map<String, Object> args = new HashMap<>();
        args.put("repoPath", fixtureRepo.toString());
        args.put("reportPath", fixtureRepo.resolve("jacoco.xml").toString());
        args.put("maxFilesInDetail", 2);

        McpSchema.CallToolResult result = call(new McpServerFeaturesHolder(McpTools.ANALYZE_COVERAGE), args);

        assertThat(result.isError()).isFalse();
        JsonNode json = JSON.readTree(textOf(result));
        assertThat(json.path("files")).hasSize(2);
        assertThat(json.path("truncated").asBoolean()).isTrue();
        assertThat(json.path("totalFiles").asInt()).isEqualTo(4); // aggregate unaffected
        // the two 3-missed files survive truncation (Utils first: lower ratio)
        assertThat(json.path("files").get(0).path("filePath").asString()).endsWith("Utils.java");
        assertThat(json.path("files").get(1).path("filePath").asString()).endsWith("Calculator.java");
    }

    @Test
    public void analyzeCoverageDetailListsWorstCoveredFilesFirst() {
        Map<String, Object> args = new HashMap<>();
        args.put("repoPath", fixtureRepo.toString());
        args.put("reportPath", fixtureRepo.resolve("jacoco.xml").toString());

        McpSchema.CallToolResult result = call(new McpServerFeaturesHolder(McpTools.ANALYZE_COVERAGE), args);

        assertThat(result.isError()).isFalse();
        JsonNode json = JSON.readTree(textOf(result));
        assertThat(json.path("files")).hasSize(4);
        // missed lines non-increasing: Utils 3, Calculator 3, User 2, UserService 2
        List<Integer> missed = new ArrayList<>();
        for (JsonNode file : json.path("files")) {
            missed.add(file.path("linesMissed").asInt());
        }
        assertThat(missed).isSortedAccordingTo(Comparator.reverseOrder());
        // equal-missed tie broken by ratio: Utils (8/11) before Calculator (12/15)
        assertThat(json.path("files").get(0).path("filePath").asString()).endsWith("Utils.java");
        assertThat(json.path("files").get(1).path("filePath").asString()).endsWith("Calculator.java");
    }

    @Test
    public void analyzeCoverageWithBaselineProducesNewCodeMetrics() {
        Map<String, Object> args = new HashMap<>();
        args.put("repoPath", fixtureRepo.toString());
        args.put("reportPath", fixtureRepo.resolve("jacoco.xml").toString());
        args.put("baseline", "baseline-v1");

        McpSchema.CallToolResult result = call(new McpServerFeaturesHolder(McpTools.ANALYZE_COVERAGE), args);

        assertThat(result.isError()).isFalse();
        JsonNode json = JSON.readTree(textOf(result));
        JsonNode overall = json.path("overall");
        // line-level new code: Calculator 9 lines (7 cov) + Utils 7 lines (5 cov)
        assertThat(overall.path("newTotalLines").asInt()).isEqualTo(16);
        assertThat(overall.path("newLinesCovered").asInt()).isEqualTo(12);
        assertThat(overall.path("newLinesMissed").asInt()).isEqualTo(4);
        // new branches: Calculator 5 (2 cov) + Utils 3 (1 cov) = 8
        assertThat(overall.path("newTotalBranches").asInt()).isEqualTo(8);
        assertThat(overall.path("newFilesWithCoverage").asInt()).isEqualTo(2);

        // real line format → no fallback notes
        assertThat(json.path("notes").isArray()).isTrue();
        assertThat(json.path("notes").size()).isZero();

        // per-file detail carries the restriction: Calculator has 9 new lines
        assertThat(findFile(json, "Calculator.java").path("newTotalLines").asInt()).isEqualTo(9);
    }

    @Test
    public void analyzeCoverageMissingReportReturnsErrorResult() {
        Map<String, Object> args = new HashMap<>();
        args.put("repoPath", fixtureRepo.toString());
        args.put("reportPath", fixtureRepo.resolve("no-such-report.xml").toString());

        McpSchema.CallToolResult result = call(new McpServerFeaturesHolder(McpTools.ANALYZE_COVERAGE), args);

        assertThat(result.isError()).isTrue();
        JsonNode error = JSON.readTree(textOf(result));
        assertThat(error.path("type").asString()).isEqualTo("XMLStreamException");
        assertThat(error.path("error").asString()).contains("no-such-report.xml");
    }

    @Test
    public void analyzeCoverageInvalidBaselineReturnsErrorResult() {
        Map<String, Object> args = new HashMap<>();
        args.put("repoPath", fixtureRepo.toString());
        args.put("reportPath", fixtureRepo.resolve("jacoco.xml").toString());
        args.put("baseline", "no-such-ref-anywhere");

        McpSchema.CallToolResult result = call(new McpServerFeaturesHolder(McpTools.ANALYZE_COVERAGE), args);

        assertThat(result.isError()).isTrue();
        JsonNode error = JSON.readTree(textOf(result));
        assertThat(error.path("type").asString()).isEqualTo("IllegalArgumentException");
        assertThat(error.path("error").asString()).contains("no-such-ref-anywhere");
    }

    // ----------------------------------------------------- list_changed_files

    @Test
    public void listChangedFilesHappyPath() {
        Map<String, Object> args = new HashMap<>();
        args.put("repoPath", fixtureRepo.toString());
        args.put("baseline", "baseline-v1");

        McpSchema.CallToolResult result = call(new McpServerFeaturesHolder(McpTools.LIST_CHANGED_FILES), args);

        assertThat(result.isError()).isFalse();
        JsonNode json = JSON.readTree(textOf(result));
        assertThat(json.path("baseline").asString()).isEqualTo("baseline-v1");
        assertThat(json.path("totalFiles").asInt()).isGreaterThanOrEqualTo(3);

        var statistics = findChangedFile(json, "Statistics.java");
        assertThat(statistics.path("status").asString()).isEqualTo("ADD");
        // JGit reports /dev/null as the old path for added files
        assertThat(statistics.path("oldPath").asString()).isEqualTo("/dev/null");
        assertThat(statistics.path("filePath").asString()).contains("Statistics.java");
        // real numbers now: a brand-new file has all lines changed from line 1
        assertThat(statistics.path("addedLines").asInt()).isGreaterThan(10);
        assertThat(statistics.path("changedLineNumbers").isArray()).isTrue();
        assertThat(statistics.path("changedLineNumbers").get(0).asInt()).isEqualTo(1);

        // computed getter on the record DTO stays in the JSON contract
        assertThat(statistics.path("totalChangedLines").asInt())
                .isEqualTo(statistics.path("addedLines").asInt());

        var calculator = findChangedFile(json, "Calculator.java");
        assertThat(calculator.path("status").asString()).isEqualTo("MODIFY");
        assertThat(calculator.path("addedLines").asInt()).isEqualTo(20);
        assertThat(calculator.path("deletedLines").asInt()).isZero();
        // changed line numbers fall inside the diff hunk range 22..41
        assertThat(calculator.path("changedLineNumbers").size()).isEqualTo(20);
        for (JsonNode line : calculator.path("changedLineNumbers")) {
            assertThat(line.asInt()).isBetween(22, 41);
        }

        // pure-addition diff: 20 + 26 + 17 added lines, none deleted
        assertThat(json.path("totalAddedLines").asInt()).isEqualTo(63);
        assertThat(json.path("totalDeletedLines").asInt()).isZero();
    }

    @Test
    public void listChangedFilesInvalidBaselineReturnsErrorResult() {
        Map<String, Object> args = new HashMap<>();
        args.put("repoPath", fixtureRepo.toString());
        args.put("baseline", "refs/heads/does-not-exist");

        McpSchema.CallToolResult result = call(new McpServerFeaturesHolder(McpTools.LIST_CHANGED_FILES), args);

        assertThat(result.isError()).isTrue();
        JsonNode error = JSON.readTree(textOf(result));
        assertThat(error.path("error").asString()).contains("does-not-exist");
    }

    private static JsonNode findFile(JsonNode response, String fileName) {
        for (JsonNode file : response.path("files")) {
            if (file.path("filePath").asString().endsWith(fileName)) {
                return file;
            }
        }
        throw new AssertionError("file not found in response: " + fileName);
    }

    private static JsonNode findChangedFile(JsonNode response, String fileName) {
        for (JsonNode file : response.path("files")) {
            if (file.path("filePath").asString().endsWith(fileName)) {
                return file;
            }
        }
        throw new AssertionError("changed file not found in response: " + fileName);
    }
}
