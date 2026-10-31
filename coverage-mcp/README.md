# coverage-mcp

Local JaCoCo Coverage MCP Server - Analyzes JaCoCo coverage reports with optional "new code" metrics similar to SonarQube.

## Features

- **Line & Branch Coverage** - Parses JaCoCo XML reports and calculates coverage metrics
- **New Code Coverage** - Compares against a Git baseline (branch, tag, or commit SHA) to report coverage only on changed lines
- **Per-file Details** - Returns uncovered lines, branch coverage, and metrics for each file
- **MCP Protocol** - Exposes tools via Model Context Protocol (stdio transport)

## Tools

### `analyze_coverage`
Primary tool for coverage analysis.

**Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `repoPath` | string | yes | Git repository root path |
| `reportPath` | string | yes | Path or glob to jacoco.xml (supports `**/jacoco.xml`, comma-separated; multiple reports merge, see below) |
| `baseline` | string | no | Baseline ref (SHA, branch, tag) for new-code mode |
| `sourceRoots` | string[] | no | Source root directories for package-to-file mapping (default: `["src/main/java", "src/test/java"]`) |
| `maxFilesInDetail` | integer | no | Max files to include in per-file detail, listed worst-covered first (default: 20) |

**Response:**
```json
{
  "overall": {
    "totalFiles": 10,
    "filesWithCoverage": 10,
    "totalLines": 500,
    "totalLinesCovered": 450,
    "totalLinesMissed": 50,
    "lineCoverageRatio": 0.9,
    "totalBranches": 100,
    "totalBranchesCovered": 85,
    "totalBranchesMissed": 15,
    "branchCoverageRatio": 0.85,
    "newLinesCovered": 30,
    "newLinesMissed": 5,
    "newTotalLines": 35,
    "newLineCoverageRatio": 0.857,
    "newBranchesCovered": 10,
    "newBranchesMissed": 2,
    "newTotalBranches": 12,
    "newBranchCoverageRatio": 0.833,
    "newFilesWithCoverage": 3
  },
  "files": [
    {
      "filePath": "src/main/java/com/example/Calculator.java",
      "className": "com.example.Calculator",
      "linesCovered": 45,
      "linesMissed": 5,
      "totalLines": 50,
      "lineCoverageRatio": 0.9,
      "uncoveredLines": [15, 23, 42, 48, 49],
      "branchesCovered": 8,
      "branchesMissed": 2,
      "totalBranches": 10,
      "branchCoverageRatio": 0.8,
      "newLinesCovered": 3,
      "newLinesMissed": 1,
      "newTotalLines": 4,
      "newLineCoverageRatio": 0.75,
      "newBranchesCovered": 1,
      "newBranchesMissed": 1,
      "newTotalBranches": 2,
      "newBranchCoverageRatio": 0.5
    }
  ],
  "truncated": false,
  "totalFiles": 10,
  "notes": []
}
```

`overall` and per-file `new*` fields are zero when no `baseline` is given.
Duplicate classes across multiple reports merge with **best-per-line-coverage**
semantics (a line covered in any report counts as covered — SonarQube's
multi-report behavior; aggregate-only duplicates keep the higher-ratio report).
The `files` array is ordered worst-covered first (most missed lines, then
lowest line ratio, then path), so with `maxFilesInDetail` truncation the files
most worth fixing are kept.
`notes` surfaces caveats: reports without line-level data (whole-file fallback
used), multiple merged reports and merged duplicate classes, and truncated
per-file detail.

### `list_changed_files`
Helper tool to list files changed since a baseline.

**Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `repoPath` | string | yes | Git repository root path |
| `baseline` | string | yes | Baseline ref (SHA, branch, tag) for diff |

**Response:**
```json
{
  "baseline": "main",
  "files": [
    {
      "filePath": "src/main/java/com/example/Calculator.java",
      "status": "MODIFY",
      "addedLines": 12,
      "deletedLines": 3,
      "changedLineNumbers": [15, 16, 17, 23, 42],
      "oldPath": null
    }
  ],
  "totalFiles": 3,
  "totalAddedLines": 25,
  "totalDeletedLines": 8
}
```

`status` uses JGit's change-type names (`ADD`, `MODIFY`, `DELETE`, `RENAME`);
renamed files carry `oldPath` and report only their edited lines in
`changedLineNumbers`/`addedLines`.

## Quick Start

### Build
```bash
mvn package -pl coverage-mcp
```

### Run Server
```bash
java -jar coverage-mcp/target/coverage-mcp-1.0.0-SNAPSHOT.jar
```

The server communicates via stdio (MCP protocol).

## Client Configuration

### VS Code (with MCP extension)
Add to your MCP settings:
```json
{
  "mcp": {
    "servers": {
      "coverage-mcp": {
        "command": "java",
        "args": ["-jar", "/path/to/coverage-mcp-1.0.0-SNAPSHOT.jar"]
      }
    }
  }
}
```

### Claude Desktop
Add to `claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "coverage-mcp": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/coverage-mcp-1.0.0-SNAPSHOT.jar"]
    }
  }
}
```

### Custom Script
```bash
#!/bin/bash
# coverage-mcp-client.sh
java -jar /path/to/coverage-mcp-1.0.0-SNAPSHOT.jar
```

## Usage Examples

### Basic Coverage Analysis
```json
{
  "method": "tools/call",
  "params": {
    "name": "analyze_coverage",
    "arguments": {
      "repoPath": "/home/user/my-project",
      "reportPath": "target/site/jacoco/jacoco.xml"
    }
  }
}
```

### New Code Coverage (since main branch)
```json
{
  "method": "tools/call",
  "params": {
    "name": "analyze_coverage",
    "arguments": {
      "repoPath": "/home/user/my-project",
      "reportPath": "target/site/jacoco/jacoco.xml",
      "baseline": "main"
    }
  }
}
```

### New Code Coverage (since specific commit)
```json
{
  "method": "tools/call",
  "params": {
    "name": "analyze_coverage",
    "arguments": {
      "repoPath": "/home/user/my-project",
      "reportPath": "target/site/jacoco/jacoco.xml",
      "baseline": "abc1234"
    }
  }
}
```

### List Changed Files
```json
{
  "method": "tools/call",
  "params": {
    "name": "list_changed_files",
    "arguments": {
      "repoPath": "/home/user/my-project",
      "baseline": "main"
    }
  }
}
```

## New Code Algorithm

The "new code" coverage follows SonarQube reference-branch semantics:

1. **Baseline Resolution** - Resolves the baseline ref in SonarQube's order:
   `refs/heads/<x>` → `refs/remotes/origin/<x>` → `refs/remotes/upstream/<x>` →
   raw ref (full/abbreviated SHA, tag, `HEAD~1`, …)
2. **Merge-Base** - Computes the best common ancestor of HEAD and the baseline
   (fails with a clear error on unrelated histories)
3. **Diff Computation** - Diffs the merge-base tree against the HEAD tree
   (committed changes; whitespace-insensitive, like SonarQube's
   `WS_IGNORE_ALL` comparator). Renames are detected (>=60% similarity), so a
   moved file counts only its edited lines as new code — not the whole file
4. **Line Intersection** - For each changed file with coverage data:
   `newLines = jaCoCoLines ∩ gitChangedLines`, with JaCoCo `mb`/`cb`
   conditions attributed to their source lines (so new-code branch metrics are
   also line-restricted). Brand-new files (ADD) have every report line new.
5. **Path Matching** - Coverage ↔ diff paths match by package-relative path
   (`com/example/Foo.java`) across all configured source roots, so test
   classes map correctly to `src/test/java`

A line is considered "new" if it was added or modified in the diff. Deleted lines are not counted in new-code coverage (they don't exist in the current version).

## Limitations

- **Committed changes only**: the diff is merge-base → HEAD. Uncommitted
  working-tree changes are not part of new-code metrics (unlike SonarQube PR
  analysis, which diffs against the working tree).
- **Reports without line data**: aggregated-only reports (no JaCoCo
  `<line>` elements) fall back to whole-file metrics for changed files —
  always surfaced in the response `notes`.
- **Changed files absent from the report** (e.g. brand-new, untested classes)
  contribute no new-code metrics; only report lines can be intersected.
- **`core.autocrlf` is not forced** (SonarQube forces it during diffing);
  line-ending-heavy changes could shift changed-line detection.

## Requirements

- Java 17+
- Git repository (for new-code mode)
- JaCoCo XML report (generated by `mvn jacoco:report` or Gradle equivalent)

## Building from Source

```bash
git clone <repo>
cd boring-java-skills
mvn package -pl coverage-mcp
```

## Testing

### Unit & end-to-end tests (no Docker required)

```bash
mvn test -pl coverage-mcp
```

`mvn test` also generates a JaCoCo XML report at `target/site/jacoco/jacoco.xml`
(jacoco-maven-plugin: prepare-agent + report), which is exactly the input
`analyze_coverage` consumes — the server can dogfood its own coverage.

Runs the JaCoCo parser, coverage calculator, and end-to-end tests against the
checked-in fixture project (TestNG + AssertJ).

### SonarQube parity integration test (Docker required)

```bash
mvn verify -pl coverage-mcp
```

`SonarQubeParityIT` (failsafe, group `integration`) uses **Testcontainers 2** to
spin up PostgreSQL 15 + SonarQube 10.6 Community, builds and analyzes the
fixture project with a *real* generated JaCoCo XML report, and asserts that
this module's coverage metrics match SonarQube's exactly:

- `lines_to_cover`, `uncovered_lines`, `conditions_to_cover`, `uncovered_conditions` — exact match
- line/branch coverage % — within 1% (SonarQube rounds to one decimal)

Requirements: a running Docker daemon (images are pulled automatically on
first run) and ~3 GB of free RAM for the SonarQube container. First run takes
a few minutes; the SonarQube boot dominates. Surefire excludes the
`integration` group, so plain `mvn test` never touches Docker.

## License

Apache 2.0