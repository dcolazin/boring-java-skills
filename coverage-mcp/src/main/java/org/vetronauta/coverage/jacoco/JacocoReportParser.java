package org.vetronauta.coverage.jacoco;

import org.vetronauta.coverage.model.FileCoverage;
import org.vetronauta.coverage.util.PathNormalizer;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * StAX-based parser for JaCoCo XML reports.
 * Parses line and branch coverage data per file.
 */
public class JacocoReportParser {

    private static final XMLInputFactory XML_FACTORY = XMLInputFactory.newFactory();

    static {
        // Secure XML parsing
        XML_FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XML_FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    /**
     * Parses a JaCoCo XML report from a file path.
     *
     * @param reportPath  path to the JaCoCo XML report
     * @param sourceRoots list of source root directories for package-to-file mapping
     * @return list of FileCoverage objects
     * @throws XMLStreamException if parsing fails
     */
    public List<FileCoverage> parse(Path reportPath, List<String> sourceRoots) throws XMLStreamException {
        return parse(reportPath, sourceRoots, null);
    }

    /**
     * Parses a JaCoCo XML report from a file path, using the repository root
     * (when known) to pick the source root that actually contains each file —
     * so test classes map to {@code src/test/java/...} rather than the first
     * configured root.
     *
     * @param reportPath  path to the JaCoCo XML report
     * @param sourceRoots list of source root directories for package-to-file mapping
     * @param repoRoot    repository root for file-existence checks (may be null)
     * @return list of FileCoverage objects
     * @throws XMLStreamException if parsing fails
     */
    public List<FileCoverage> parse(Path reportPath, List<String> sourceRoots, Path repoRoot)
            throws XMLStreamException {
        try (var inputStream = Files.newInputStream(reportPath)) {
            return parse(inputStream, sourceRoots, repoRoot);
        } catch (java.io.IOException e) {
            throw new XMLStreamException("Failed to read report: " + reportPath, e);
        }
    }

    /**
     * Parses a JaCoCo XML report from an input stream.
     *
     * @param inputStream the JaCoCo XML report input stream
     * @param sourceRoots list of source root directories for package-to-file mapping
     * @return list of FileCoverage objects
     * @throws XMLStreamException if parsing fails
     */
    public List<FileCoverage> parse(InputStream inputStream, List<String> sourceRoots) throws XMLStreamException {
        return parse(inputStream, sourceRoots, null);
    }

    /**
     * Parses a JaCoCo XML report from an input stream with optional repo root.
     */
    public List<FileCoverage> parse(InputStream inputStream, List<String> sourceRoots, Path repoRoot)
            throws XMLStreamException {
        XMLStreamReader reader = XML_FACTORY.createXMLStreamReader(inputStream);
        try {
            return parseReport(reader, sourceRoots, repoRoot);
        } finally {
            reader.close();
        }
    }

    private List<FileCoverage> parseReport(XMLStreamReader reader, List<String> sourceRoots, Path repoRoot)
            throws XMLStreamException {
        Map<String, FileCoverage> fileMap = new HashMap<>();

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamReader.START_ELEMENT) {
                String localName = reader.getLocalName();
                if ("package".equals(localName)) {
                    parsePackage(reader, fileMap, sourceRoots, repoRoot);
                }
            }
        }

        return new ArrayList<>(fileMap.values());
    }

    private void parsePackage(XMLStreamReader reader, Map<String, FileCoverage> fileMap,
                              List<String> sourceRoots, Path repoRoot) throws XMLStreamException {
        String packageName = reader.getAttributeValue(null, "name");
        if (packageName == null) {
            packageName = "";
        }

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamReader.START_ELEMENT) {
                if ("sourcefile".equals(reader.getLocalName())) {
                    parseSourceFile(reader, packageName, fileMap, sourceRoots, repoRoot);
                }
            } else if (event == XMLStreamReader.END_ELEMENT && "package".equals(reader.getLocalName())) {
                break;
            }
        }
    }

    private void parseSourceFile(XMLStreamReader reader, String packageName, Map<String, FileCoverage> fileMap,
                                 List<String> sourceRoots, Path repoRoot) throws XMLStreamException {
        String fileName = reader.getAttributeValue(null, "name");
        if (fileName == null) {
            return;
        }

        // Build the full class name (real JaCoCo reports use '/'-separated packages)
        String normalizedPackage = packageName.replace('/', '.');
        String className = normalizedPackage.isEmpty() ? fileName : normalizedPackage + "." + fileName;
        // Remove .java extension
        if (className.endsWith(".java")) {
            className = className.substring(0, className.length() - 5);
        }

        // Class-relative path (package dirs + file name): the stable key used
        // to match coverage against git diff paths regardless of source root.
        String classRelativePath = normalizedPackage.isEmpty()
                ? fileName
                : normalizedPackage.replace('.', '/') + "/" + fileName;

        // Find the actual file path using source roots
        String filePath = locateFile(classRelativePath, sourceRoots, repoRoot);

        FileCoverage fileCoverage = new FileCoverage(filePath, className, classRelativePath);
        fileMap.put(classRelativePath, fileCoverage);

        // Real JaCoCo XML: <sourcefile> contains <line nr mi ci mb cb/> elements
        // plus trailing aggregate <counter> elements (applied only as fallback,
        // never together with line elements, to avoid double counting).
        // Legacy hand-crafted reports nest <class> (with method counters) inside
        // <sourcefile>; kept for backward compatibility with existing fixtures.
        boolean sawLines = false;
        boolean sawLegacyClasses = false;
        int pendingLineMissed = 0;
        int pendingLineCovered = 0;
        int pendingBranchMissed = 0;
        int pendingBranchCovered = 0;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamReader.START_ELEMENT) {
                String localName = reader.getLocalName();
                if ("line".equals(localName)) {
                    parseLineElement(reader, fileCoverage);
                    sawLines = true;
                } else if ("class".equals(localName)) {
                    parseClass(reader, fileCoverage);
                    sawLegacyClasses = true;
                } else if ("counter".equals(localName)) {
                    String type = reader.getAttributeValue(null, "type");
                    int missed = intAttr(reader, "missed");
                    int covered = intAttr(reader, "covered");
                    if ("LINE".equalsIgnoreCase(type == null ? "" : type.trim())) {
                        pendingLineMissed = missed;
                        pendingLineCovered = covered;
                    } else if ("BRANCH".equalsIgnoreCase(type == null ? "" : type.trim())) {
                        pendingBranchMissed = missed;
                        pendingBranchCovered = covered;
                    }
                }
            } else if (event == XMLStreamReader.END_ELEMENT && "sourcefile".equals(reader.getLocalName())) {
                break;
            }
        }

        if (!sawLines && !sawLegacyClasses) {
            fileCoverage.addLineCoverage(pendingLineMissed, pendingLineCovered);
            fileCoverage.addBranchCoverage(pendingBranchMissed, pendingBranchCovered);
        }
    }

    /**
     * Parses a {@code <line nr=".." mi=".." ci=".." mb=".." cb=".."/>} element
     * (the real JaCoCo report format) using SonarQube-equivalent semantics:
     * a line counts as covered when it has covered instructions (ci &gt; 0),
     * and its mb/cb conditions are attributed to that line (enabling
     * line-level new-code branch metrics).
     */
    private void parseLineElement(XMLStreamReader reader, FileCoverage fileCoverage) {
        Integer lineNumber = intAttrOrNull(reader, "nr");
        if (lineNumber == null) {
            return;
        }
        int coveredInstructions = intAttr(reader, "ci");
        int missedBranches = intAttr(reader, "mb");
        int coveredBranches = intAttr(reader, "cb");

        fileCoverage.addLineCoverage(lineNumber, coveredInstructions > 0, missedBranches, coveredBranches);
    }

    private static int intAttr(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Integer intAttrOrNull(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void parseClass(XMLStreamReader reader, FileCoverage fileCoverage) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamReader.START_ELEMENT) {
                if ("method".equals(reader.getLocalName())) {
                    parseMethod(reader, fileCoverage);
                }
            } else if (event == XMLStreamReader.END_ELEMENT && "class".equals(reader.getLocalName())) {
                break;
            }
        }
    }

    private void parseMethod(XMLStreamReader reader, FileCoverage fileCoverage) throws XMLStreamException {
        // Read method attributes (name, desc, line) but we don't need them for coverage aggregation
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamReader.START_ELEMENT) {
                if ("counter".equals(reader.getLocalName())) {
                    parseCounter(reader, fileCoverage);
                }
            } else if (event == XMLStreamReader.END_ELEMENT && "method".equals(reader.getLocalName())) {
                break;
            }
        }
    }

    private void parseCounter(XMLStreamReader reader, FileCoverage fileCoverage) throws XMLStreamException {
        String type = reader.getAttributeValue(null, "type");
        String missedStr = reader.getAttributeValue(null, "missed");
        String coveredStr = reader.getAttributeValue(null, "covered");

        if (type == null || missedStr == null || coveredStr == null) {
            return;
        }

        // Normalize type (trim and uppercase for robustness)
        String normalizedType = type.trim().toUpperCase();

        try {
            int missed = Integer.parseInt(missedStr.trim());
            int covered = Integer.parseInt(coveredStr.trim());

            if ("LINE".equals(normalizedType)) {
                fileCoverage.addLineCoverage(missed, covered);
            } else if ("BRANCH".equals(normalizedType)) {
                fileCoverage.addBranchCoverage(missed, covered);
            }
        } catch (NumberFormatException e) {
            // Skip invalid counters
        }
    }

    /**
     * Locates the repo-relative path for a class-relative path
     * (e.g. {@code com/example/Foo.java}) using the source roots. When the
     * repository root is known, the first root that actually contains the
     * file wins; otherwise the first configured root is used as candidate.
     */
    private String locateFile(String classRelativePath, List<String> sourceRoots, Path repoRoot) {
        String firstCandidate = null;
        for (String root : sourceRoots) {
            String candidate = normalizeRoot(root) + "/" + classRelativePath;
            if (firstCandidate == null) {
                firstCandidate = candidate;
            }
            if (repoRoot != null && Files.exists(repoRoot.resolve(candidate))) {
                return candidate;
            }
        }
        return firstCandidate != null ? firstCandidate : classRelativePath;
    }

    private static String normalizeRoot(String root) {
        return PathNormalizer.normalize(root);
    }
}
