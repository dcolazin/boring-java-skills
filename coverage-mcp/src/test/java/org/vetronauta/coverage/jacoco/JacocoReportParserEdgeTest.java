package org.vetronauta.coverage.jacoco;

import org.testng.annotations.Test;
import org.vetronauta.coverage.model.FileCoverage;

import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Edge cases of the StAX parser: fallbacks, malformed values, error paths. */
public class JacocoReportParserEdgeTest {

    private static List<FileCoverage> parse(String xml) throws XMLStreamException {
        return new JacocoReportParser()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                        List.of("src/main/java"));
    }

    @Test
    public void missingReportFileThrowsXmlStreamException() {
        var parser = new JacocoReportParser();
        assertThatThrownBy(() -> parser.parse(Paths.get("no/such/jacoco.xml"), List.of("src/main/java")))
                .isInstanceOf(XMLStreamException.class)
                .hasMessageContaining("no/such/jacoco.xml");
    }

    @Test
    public void counterOnlySourceFileUsesPendingCountersAsFallback() throws Exception {
        List<FileCoverage> files = parse("""
                <?xml version="1.0"?>
                <report name="t">
                  <package name="com/example">
                    <sourcefile name="Only.java">
                      <counter type="LINE" missed="2" covered="3"/>
                      <counter type="BRANCH" missed="1" covered="1"/>
                    </sourcefile>
                  </package>
                </report>
                """);

        assertThat(files).hasSize(1);
        FileCoverage f = files.get(0);
        assertThat(f.getTotalLines()).isEqualTo(5);
        assertThat(f.getLinesMissed()).isEqualTo(2);
        assertThat(f.getLinesCovered()).isEqualTo(3);
        assertThat(f.getTotalBranches()).isEqualTo(2);
        assertThat(f.getBranchesMissed()).isEqualTo(1);
    }

    @Test
    public void legacyClassMethodCounterFormatIsAggregated() throws Exception {
        List<FileCoverage> files = parse("""
                <?xml version="1.0"?>
                <report name="t">
                  <package name="com/example">
                    <sourcefile name="Legacy.java">
                      <class name="com.example.Legacy">
                        <method name="a" desc="()V" line="3">
                          <counter type="LINE" missed="1" covered="4"/>
                          <counter type="BRANCH" missed="2" covered="2"/>
                        </method>
                        <method name="b" desc="()V" line="9">
                          <counter type="LINE" missed="0" covered="2"/>
                        </method>
                      </class>
                    </sourcefile>
                  </package>
                </report>
                """);

        assertThat(files).hasSize(1);
        FileCoverage f = files.get(0);
        assertThat(f.getTotalLines()).isEqualTo(7);
        assertThat(f.getLinesCovered()).isEqualTo(6);
        assertThat(f.getTotalBranches()).isEqualTo(4);
        assertThat(f.getBranchesCovered()).isEqualTo(2);
        // legacy counters do not populate per-line detail
        assertThat(f.getUncoveredLines()).isEmpty();
    }

    @Test
    public void realLineElementsFollowSonarSemantics() throws Exception {
        List<FileCoverage> files = parse("""
                <?xml version="1.0"?>
                <report name="t">
                  <package name="com/example">
                    <sourcefile name="Real.java">
                      <line nr="10" mi="0" ci="3" mb="0" cb="0"/>
                      <line nr="11" mi="5" ci="0" mb="1" cb="1"/>
                      <line nr="12" mi="2" ci="1" mb="2" cb="0"/>
                    </sourcefile>
                  </package>
                </report>
                """);

        assertThat(files).hasSize(1);
        FileCoverage f = files.get(0);
        // ci > 0 counts as covered (lines 10, 12); ci == 0 missed (line 11)
        assertThat(f.getTotalLines()).isEqualTo(3);
        assertThat(f.getLinesCovered()).isEqualTo(2);
        assertThat(f.getLinesMissed()).isEqualTo(1);
        assertThat(f.getUncoveredLines()).containsExactly(11);
        // branches: mb/cb summed across lines with branches
        assertThat(f.getTotalBranches()).isEqualTo(4);
        assertThat(f.getBranchesCovered()).isEqualTo(1);
        assertThat(f.getBranchesMissed()).isEqualTo(3);
    }

    @Test
    public void malformedAttributesAreTreatedAsZeroOrSkipped() throws Exception {
        List<FileCoverage> files = parse("""
                <?xml version="1.0"?>
                <report name="t">
                  <package name="com/example">
                    <sourcefile name="Bad.java">
                      <line mi="1" ci="2"/>
                      <line nr="20" ci="not-a-number" mb="x" cb="y"/>
                      <counter type="LINE" missed="one" covered="two"/>
                    </sourcefile>
                  </package>
                </report>
                """);

        assertThat(files).hasSize(1);
        FileCoverage f = files.get(0);
        // line without nr skipped; line 20 with unparseable ci -> treated as 0 -> missed
        assertThat(f.getTotalLines()).isEqualTo(1);
        assertThat(f.getLinesMissed()).isEqualTo(1);
        assertThat(f.getUncoveredLines()).containsExactly(20);
        // invalid counter numbers were skipped entirely
        assertThat(f.getBranchesCovered() + f.getBranchesMissed()).isEqualTo(0);
    }

    @Test
    public void packageWithoutNameAndCountersWithoutAttributes() throws Exception {
        List<FileCoverage> files = parse("""
                <?xml version="1.0"?>
                <report name="t">
                  <package>
                    <sourcefile name="Default.java">
                      <counter missed="3" covered="0" type="LINE"/>
                    </sourcefile>
                    <sourcefile/>
                  </package>
                </report>
                """);

        // sourcefile without a name is skipped; the default-package file parses
        assertThat(files).hasSize(1);
        FileCoverage f = files.get(0);
        assertThat(f.getClassName()).isEqualTo("Default");
        assertThat(f.getFilePath()).isEqualTo("src/main/java/Default.java");
        assertThat(f.getTotalLines()).isEqualTo(3);
        assertThat(f.getLinesCovered()).isZero();
    }

    @Test
    public void emptyReportYieldsNoFiles() throws Exception {
        assertThat(parse("<?xml version=\"1.0\"?><report name=\"t\"></report>")).isEmpty();
    }

    @Test
    public void slashesInPackageNameAreNormalizedToDots() throws Exception {
        List<FileCoverage> files = parse("""
                <?xml version="1.0"?>
                <report name="t">
                  <package name="com/example/deep">
                    <sourcefile name="Deep.java">
                      <counter type="LINE" missed="0" covered="1"/>
                    </sourcefile>
                  </package>
                </report>
                """);
        assertThat(files.get(0).getClassName()).isEqualTo("com.example.deep.Deep");
        assertThat(files.get(0).getFilePath()).isEqualTo("src/main/java/com/example/deep/Deep.java");
    }
}
