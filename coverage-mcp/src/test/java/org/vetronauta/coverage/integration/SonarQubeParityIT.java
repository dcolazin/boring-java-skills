package org.vetronauta.coverage.integration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.vetronauta.coverage.jacoco.CoverageCalculator;
import org.vetronauta.coverage.jacoco.JacocoReportParser;
import org.vetronauta.coverage.model.CoverageSummary;
import org.vetronauta.coverage.model.FileCoverage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SonarQube parity test using Testcontainers (TestNG-native lifecycle).
 *
 * Spins up SonarQube 10.6 Community + PostgreSQL 15, builds and analyzes the
 * fixture project (generating a real JaCoCo XML report), then compares the
 * SonarQube coverage metrics with the ones computed by this module's
 * {@link JacocoReportParser} + {@link CoverageCalculator} on the very same
 * report file.
 *
 * Expected parity (per PLAN.md §5):
 *   - lines_to_cover       == our total executable lines
 *   - uncovered_lines      == our missed lines
 *   - conditions_to_cover  == our total branches
 *   - uncovered_conditions == our missed branches
 *   - line/branch coverage % match within 1% (SQ rounds to 1 decimal)
 *
 * Run with: mvn verify -pl coverage-mcp
 * (surefire excludes this group; failsafe includes it)
 */
public class SonarQubeParityIT {

    public static final String GROUP = "integration";

    private static final String SONAR_IMAGE = "sonarqube:10.6-community";
    private static final String PROJECT_KEY = "coverage-mcp-parity";
    private static final String NEW_ADMIN_PASSWORD = "CoverageParity#2026";
    private static final Path FIXTURE_SOURCE = Paths.get("src/test/resources/fixture-repo");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static PostgreSQLContainer postgres;
    private static GenericContainer<?> sonarqube;
    private static Network network;
    private static HttpClient http;
    private static String sonarUrl;
    private static String token;
    private static Path fixtureDir;
    private static Path generatedReport;

    @BeforeClass(alwaysRun = true)
    public void startStack() throws InterruptedException {
        network = Network.newNetwork();

        // 1) PostgreSQL (must be up before we can wire SonarQube's JDBC URL)
        postgres = new PostgreSQLContainer("postgres:15-alpine")
                .withDatabaseName("sonar")
                .withUsername("sonar")
                .withPassword("sonar")
                .withNetwork(network)
                .withNetworkAliases("sonardb")
                .withStartupTimeout(Duration.ofMinutes(2));
        postgres.start();

        // 2) SonarQube, wired to the started PostgreSQL container over the
        //    shared docker network (host-perspective URLs are NOT reachable
        //    from inside sibling containers)
        sonarqube = new GenericContainer<>(SONAR_IMAGE)
                .withNetwork(network)
                .withEnv("SONAR_JDBC_URL", "jdbc:postgresql://sonardb:5432/sonar")
                .withEnv("SONAR_JDBC_USERNAME", postgres.getUsername())
                .withEnv("SONAR_JDBC_PASSWORD", postgres.getPassword())
                // keep memory modest for dev machines
                .withEnv("SONAR_ES_JAVA_OPTS", "-Xms512m -Xmx1024m")
                .withEnv("SONAR_WEB_JAVA_OPTS", "-Xmx512m")
                .withEnv("SONAR_CE_JAVA_OPTS", "-Xmx512m")
                .withEnv("SONAR_ES_BOOTSTRAP_CHECK", "true")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/api/system/status").forPort(9000).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(8));
        sonarqube.start();

        sonarUrl = "http://" + sonarqube.getHost() + ":" + sonarqube.getMappedPort(9000);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        awaitSonarUp();
        token = resolveAuthToken();
        System.out.println("[parity] SonarQube ready at " + sonarUrl);
    }

    @AfterClass(alwaysRun = true)
    public void stopStack() {
        if (sonarqube != null) {
            sonarqube.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test(groups = GROUP)
    public void sonarQubeCoverageMetricsShouldMatch() throws Exception {
        prepareFixtureWithRealJacocoReport();
        runSonarScanner();

        SonarMetrics sonar = fetchSonarMetrics();
        OurMetrics ours = computeOurMetrics();

        compare(sonar, ours);
    }

    // ------------------------------------------------------------------ steps

    /**
     * Copies the fixture project to a temp dir (renaming {@code git} to
     * {@code .git} so it is a valid repository), then runs {@code mvn test}
     * which compiles the classes and produces a real JaCoCo XML report with
     * &lt;line&gt; elements at {@code target/site/jacoco/jacoco.xml}.
     */
    private void prepareFixtureWithRealJacocoReport() throws Exception {
        fixtureDir = Files.createTempDirectory("coverage-parity-fixture-");
        copyDirectory(FIXTURE_SOURCE, fixtureDir);

        Path gitFolder = fixtureDir.resolve("git");
        if (Files.isDirectory(gitFolder)) {
            Files.move(gitFolder, fixtureDir.resolve(".git"));
        }

        runCommand(List.of("mvn", "-B", "-ntp", "test"), fixtureDir, "fixture-build.log");

        generatedReport = fixtureDir.resolve("target/site/jacoco/jacoco.xml");
        assertThat(Files.exists(generatedReport))
                .as("fixture build must generate a JaCoCo XML report")
                .isTrue();
    }

    /** Runs sonar:sonar on the fixture, pointing at the generated report. */
    private void runSonarScanner() throws Exception {
        List<String> base = List.of(
                "mvn", "-B", "-ntp",
                "org.sonarsource.scanner.maven:sonar-maven-plugin:3.11.0.3922:sonar",
                "-Dsonar.host.url=" + sonarUrl,
                "-Dsonar.projectKey=" + PROJECT_KEY,
                "-Dsonar.projectName=" + PROJECT_KEY,
                "-Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml",
                // Isolate the scanner cache per run: the shared ~/.sonar cache
                // gets poisoned by interrupted/parallel runs (corrupt analyzer
                // jars are trusted without re-hashing -> ClassFormatError, and
                // stale checksum metadata then rejects valid downloads)
                "-Dsonar.userHome=" + fixtureDir.resolve(".sonar-home"),
                "-Dsonar.scm.disabled=true");

        List<String> withToken = new ArrayList<>(base);
        withToken.add("-Dsonar.token=" + token);
        if (!tryCommand(withToken, fixtureDir, "sonar-scanner.log")) {
            // Older plugin/server combos may not know sonar.token - fall back to sonar.login
            List<String> withLogin = new ArrayList<>(base);
            withLogin.add("-Dsonar.login=" + token);
            runCommand(withLogin, fixtureDir, "sonar-scanner-retry.log");
        }
    }

    private SonarMetrics fetchSonarMetrics() throws Exception {
        String metrics = "lines_to_cover,uncovered_lines,conditions_to_cover,uncovered_conditions,"
                + "line_coverage,branch_coverage,coverage";
        String api = sonarUrl + "/api/measures/component?component=" + PROJECT_KEY
                + "&metricKeys=" + metrics;

        // Analysis is processed asynchronously by the Compute Engine task;
        // the scanner blocks on it, but give the API a grace period anyway.
        Map<String, Double> values = Map.of();
        for (int attempt = 0; attempt < 24 && values.isEmpty(); attempt++) {
            HttpResponse<String> response = send(HttpRequest.newBuilder()
                    .uri(URI.create(api))
                    .header("Authorization", tokenAuth(token))
                    .GET());
            if (response.statusCode() == 200) {
                values = parseMeasures(response.body());
            }
            if (values.isEmpty()) {
                Thread.sleep(5000);
            }
        }
        assertThat(values).as("SonarQube measures should be available").isNotEmpty();
        return SonarMetrics.from(values);
    }

    private OurMetrics computeOurMetrics() throws Exception {
        List<FileCoverage> files = new JacocoReportParser()
                .parse(generatedReport, List.of("src/main/java", "src/test/java"));
        CoverageSummary summary = new CoverageCalculator().calculate(files);
        return new OurMetrics(
                summary.getLineCoverageRatio(),
                summary.getBranchCoverageRatio(),
                summary.getTotalLines(),
                summary.getTotalLinesMissed(),
                summary.getTotalBranches(),
                summary.getTotalBranchesMissed());
    }

    private void compare(SonarMetrics sonar, OurMetrics ours) {
        System.out.println("=== SonarQube vs coverage-mcp (same jacoco.xml) ===");
        System.out.printf("Line coverage:        SQ=%.2f%%  ours=%.2f%%%n", sonar.lineCoverage * 100, ours.lineCoverage * 100);
        System.out.printf("Branch coverage:      SQ=%.2f%%  ours=%.2f%%%n", sonar.branchCoverage * 100, ours.branchCoverage * 100);
        System.out.printf("Lines to cover:       SQ=%d      ours=%d%n", sonar.linesToCover, ours.linesToCover);
        System.out.printf("Uncovered lines:      SQ=%d      ours=%d%n", sonar.uncoveredLines, ours.uncoveredLines);
        System.out.printf("Conditions to cover:  SQ=%d      ours=%d%n", sonar.conditionsToCover, ours.conditionsToCover);
        System.out.printf("Uncovered conditions: SQ=%d      ours=%d%n", sonar.uncoveredConditions, ours.uncoveredConditions);

        assertThat(sonar.linesToCover)
                .as("lines_to_cover must match exactly")
                .isEqualTo(ours.linesToCover);
        assertThat(sonar.uncoveredLines)
                .as("uncovered_lines must match exactly")
                .isEqualTo(ours.uncoveredLines);
        assertThat(sonar.conditionsToCover)
                .as("conditions_to_cover must match exactly")
                .isEqualTo(ours.conditionsToCover);
        assertThat(sonar.uncoveredConditions)
                .as("uncovered_conditions must match exactly")
                .isEqualTo(ours.uncoveredConditions);

        double tolerance = 0.01; // SQ rounds percentages to one decimal
        assertThat(Math.abs(sonar.lineCoverage - ours.lineCoverage))
                .as("line coverage should match within 1%%")
                .isLessThanOrEqualTo(tolerance);
        assertThat(Math.abs(sonar.branchCoverage - ours.branchCoverage))
                .as("branch coverage should match within 1%%")
                .isLessThanOrEqualTo(tolerance);
    }

    // ------------------------------------------------------------- SonarQube API helpers

    /** /api/system/status returns 200 with status=STARTING|UP|DOWN. */
    private void awaitSonarUp() throws InterruptedException {
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                HttpResponse<String> response = send(HttpRequest.newBuilder()
                        .uri(URI.create(sonarUrl + "/api/system/status"))
                        .GET());
                if (response.statusCode() == 200 && response.body().contains("\"UP\"")) {
                    return;
                }
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for SonarQube", e);
            } catch (RuntimeException e) {
                // connection refused while booting - retry
            }
            Thread.sleep(5000);
        }
        throw new IllegalStateException("SonarQube did not reach status UP in time");
    }

    /**
     * Fresh SonarQube instances ship with admin/admin. Depending on version and
     * first-login state we may need to rotate the password; either way we end
     * up with a generated analysis token (password auth is deprecated for
     * scanners since SonarQube 10.4).
     */
    private String resolveAuthToken() {
        String basic = basicAuth("admin", "admin");
        if (!validate(basic)) {
            if (changePassword(basic, "admin", NEW_ADMIN_PASSWORD)) {
                basic = basicAuth("admin", NEW_ADMIN_PASSWORD);
            } else {
                basic = basicAuth("admin", NEW_ADMIN_PASSWORD);
                assertThat(validate(basic))
                        .as("could not authenticate to SonarQube as admin")
                        .isTrue();
            }
        }
        return generateToken(basic);
    }

    private boolean validate(String authHeader) {
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder()
                    .uri(URI.create(sonarUrl + "/api/authentication/validate"))
                    .header("Authorization", authHeader)
                    .GET());
            return response.statusCode() == 200 && response.body().contains("\"valid\":true");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean changePassword(String authHeader, String previous, String newPassword) {
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder()
                    .uri(URI.create(sonarUrl + "/api/users/change_password"))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "login=admin&previous_password=" + previous + "&password=" + newPassword)));
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String generateToken(String authHeader) {
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder()
                    .uri(URI.create(sonarUrl + "/api/user_tokens/generate"))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("name=coverage-parity")));
            assertThat(response.statusCode()).as("token generation should succeed").isEqualTo(200);
            return JSON.readTree(response.body()).path("token").asString();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to generate SonarQube token", e);
        }
    }

    private Map<String, Double> parseMeasures(String body) {
        JsonNode measures = JSON.readTree(body).path("component").path("measures");
        Map<String, Double> values = new java.util.HashMap<>();
        for (JsonNode measure : measures) {
            String metric = measure.path("metric").asString();
            if (measure.hasNonNull("value")) {
                values.put(metric, measure.path("value").asDouble());
            } else if (measure.path("bestValue").asBoolean(false)) {
                values.put(metric, metric.startsWith("uncovered") ? 0.0 : 100.0);
            }
        }
        return values;
    }

    // ------------------------------------------------------------------ plumbing

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String basicAuth(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
    }

    /** SonarQube tokens are used as the username with an empty password. */
    private static String tokenAuth(String token) {
        return basicAuth(token, "");
    }

    private void runCommand(List<String> command, Path workingDir, String logName)
            throws IOException, InterruptedException {
        assertThat(tryCommand(command, workingDir, logName))
                .as("command failed: %s (see %s)", command.get(0), workingDir.resolve(logName))
                .isTrue();
    }

    private boolean tryCommand(List<String> command, Path workingDir, String logName)
            throws IOException, InterruptedException {
        Path log = workingDir.resolve(logName);
        Process process = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectOutput(log.toFile())
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source).forEach(sourcePath -> {
            try {
                Path targetPath = target.resolve(source.relativize(sourcePath));
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(sourcePath, targetPath);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy " + sourcePath, e);
            }
        });
    }

    // ------------------------------------------------------------------ DTOs

    record SonarMetrics(double lineCoverage, double branchCoverage, long linesToCover,
                        long uncoveredLines, long conditionsToCover, long uncoveredConditions) {
        static SonarMetrics from(Map<String, Double> values) {
            return new SonarMetrics(
                    values.getOrDefault("line_coverage", 0.0) / 100.0,
                    values.getOrDefault("branch_coverage", 0.0) / 100.0,
                    values.getOrDefault("lines_to_cover", 0.0).longValue(),
                    values.getOrDefault("uncovered_lines", 0.0).longValue(),
                    values.getOrDefault("conditions_to_cover", 0.0).longValue(),
                    values.getOrDefault("uncovered_conditions", 0.0).longValue());
        }
    }

    record OurMetrics(double lineCoverage, double branchCoverage, long linesToCover,
                      long uncoveredLines, long conditionsToCover, long uncoveredConditions) {
    }
}
