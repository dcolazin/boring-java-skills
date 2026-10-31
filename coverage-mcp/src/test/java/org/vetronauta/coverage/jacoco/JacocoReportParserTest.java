package org.vetronauta.coverage.jacoco;

import org.testng.annotations.Test;
import org.vetronauta.coverage.model.FileCoverage;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

public class JacocoReportParserTest {

    @Test
    public void parseSampleReport() throws Exception {
        Path reportPath = Paths.get("src/test/resources/jacoco-sample.xml");
        JacocoReportParser parser = new JacocoReportParser();
        
        List<FileCoverage> files = parser.parse(reportPath, List.of("src/main/java", "src/test/java"));
        
        assertThat(files).hasSize(3);
        
        // Check Calculator.java
        FileCoverage calc = files.stream()
                .filter(f -> f.getFilePath().contains("Calculator.java"))
                .findFirst()
                .orElseThrow();
        
        assertThat(calc.getClassName()).isEqualTo("com.example.Calculator");
        assertThat(calc.getTotalLines()).isEqualTo(8);
        assertThat(calc.getLinesCovered()).isEqualTo(7);
        assertThat(calc.getLinesMissed()).isEqualTo(1);
        assertThat(calc.getLineCoverageRatio()).isCloseTo(0.875, offset(0.001));
        
        assertThat(calc.getTotalBranches()).isEqualTo(6); // 2 (add) + 4 (divide)
        assertThat(calc.getBranchesCovered()).isEqualTo(5); // 2 + 3
        assertThat(calc.getBranchCoverageRatio()).isCloseTo(0.833, offset(0.001));
        
        // Check Utils.java
        FileCoverage utils = files.stream()
                .filter(f -> f.getFilePath().contains("Utils.java"))
                .findFirst()
                .orElseThrow();
        
        assertThat(utils.getClassName()).isEqualTo("com.example.Utils");
        assertThat(utils.getTotalLines()).isEqualTo(2);
        assertThat(utils.getLinesCovered()).isEqualTo(2);
        assertThat(utils.getLineCoverageRatio()).isEqualTo(1.0);
        
        // Check UserService.java
        FileCoverage service = files.stream()
                .filter(f -> f.getFilePath().contains("UserService.java"))
                .findFirst()
                .orElseThrow();
        
        assertThat(service.getClassName()).isEqualTo("com.example.service.UserService");
        assertThat(service.getTotalLines()).isEqualTo(7);
        assertThat(service.getLinesCovered()).isEqualTo(5);
        assertThat(service.getLinesMissed()).isEqualTo(2);
        assertThat(service.getLineCoverageRatio()).isCloseTo(0.714, offset(0.001));
    }

    @Test
    public void parseFromInputStream() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("jacoco-sample.xml")) {
            JacocoReportParser parser = new JacocoReportParser();
            
            List<FileCoverage> files = parser.parse(is, List.of("src/main/java", "src/test/java"));
            
            assertThat(files).hasSize(3);
        }
    }
}