# Plan: `coverage-mcp` — Local JaCoCo Coverage MCP Server

> Status: **IMPLEMENTED** (M0–M5; SonarQube parity IT Docker-gated) · Java 17 · Maven · stdio
>
> **Implementation deltas vs. this plan** (README is the living tool contract):
> - New-code diff is **merge-base → HEAD** (committed changes); the planned
>   working-tree diff was descoped and is documented as a limitation.
> - Line-level intersection, per-line branch attribution, SQ ref-resolution
>   order, and merge-base are implemented as planned (§5–§6).
> - Response shape follows the flattened `new*` fields + `notes` (see README
>   example, verified by tests) rather than the nested §4 sketch.
> - Reports without line data fall back to whole-file metrics, always noted.
> - `core.autocrlf` is not forced; the `WS_IGNORE_ALL` comparator is used.
> - `org.jacoco.report` dependency was dropped (hand-rolled StAX parser only).
> - SDK 2.0.1 has no builder `onClose` and its stdio transport does not exit
>   the JVM on stdin EOF; the server wraps stdin to observe EOF directly and
>   shuts down gracefully, with a JVM shutdown hook as backup (SIGTERM).

## 1. Goal

A local MCP server that takes:

- a **git repository path**, and
- a **JaCoCo XML report** (`jacoco.xml`),

and returns:

- **line coverage %** and **condition/branch coverage %**,
- the **non-covered lines** and **non-covered conditions**, per file.

In **new-code mode** (when a baseline git ref is given), it returns the same metrics
restricted to lines changed since the baseline — **exactly how a remote SonarQube
instance computes "New Code" coverage** (reference-branch semantics), but fully
local, with no SQ server or scanner run.

### Non-goals (v1)

- SonarQube "previous version" / "X days" new-code periods (only reference commit).
- Actually running tests / generating JaCoCo reports (input is an existing report).
- Web UI; SQ rule/issue analysis; non-JVM languages beyond configurable source roots.

## 2. Key architectural finding — "reusing the Sonar libraries"

Researched SonarQube's actual new-code pipeline:

- `sonar-plugin-api` (`org.sonar.api.batch.scm.ScmProvider`) only defines the
  **interfaces** (`branchChangedFiles`, `branchChangedLines`). The real Git
  implementation lives in **`sonar-scm-git`** (`GitScmProvider`), and it is
  implemented **directly on JGit** in ~200 lines:
  1. resolve target ref (`refs/heads/<x>` → `refs/remotes/origin/<x>` → `refs/remotes/upstream/<x>`),
  2. find **merge-base** of HEAD and target (`RevFilter.MERGE_BASE`),
  3. diff merge-base tree **vs working tree** (`FileTreeIterator`) via
     `DiffFormatter` with `RawTextComparator.WS_IGNORE_ALL` and `core.autocrlf`
     forced to `true`,
  4. parse unified-diff hunks to collect changed line numbers
     (`ChangedLinesComputer`, ~60 lines).
- Embedding `sonar-plugin-api` standalone would drag in the whole SonarQube
  scanner runtime (picocontainer injection, `SensorContext`, server protocol) —
  there is no standalone entry point. **Not viable for a local tool.**

**Decision:** depend on **JGit** — the exact library SonarQube's engine uses — and
port the merge-base/diff/hunk algorithm. This gives SonarQube-identical results
(same diff comparator, same autocrlf handling, same hunk math) without the SQ
runtime. `sonar-plugin-api` is used as the *semantic reference* for behavior.

## 3. Dependencies (versions verified on Maven Central / GitHub releases)

| Dependency | Version | Purpose |
|---|---|---|
| `io.modelcontextprotocol.sdk:mcp` | `2.0.1` | MCP server, stdio transport, tool registration (requires Java 17) |
| `org.eclipse.jgit:org.eclipse.jgit` | `7.3.0.202506031305-r` | ref resolution, merge-base, diff (same lib SonarQube uses) |
| Jackson (transitive via SDK) | — | JSON serialization of tool results |
| `org.jacoco:org.jacoco.report` | `0.8.13` | *optional*: `XMLReportParser`; default is a hand-rolled StAX parser (report XML schema is tiny and stable) |
| JUnit 5, AssertJ, SDK `McpClient` | latest | unit + protocol tests |

> ⚠️ **M0 verification step:** confirm `io.modelcontextprotocol.sdk:mcp` resolves
> from Maven Central (solr search was inconclusive; CHANGELOG confirms releases
> 2.0.1/1.1.4/0.18.4 exist). Fallbacks: 1.1.x line, or build from the tagged
> source (`github.com/modelcontextprotocol/java-sdk`).

## 4. MCP tool contract

### Tool 1 (primary): `analyze_coverage`

| Param | Type | Req | Default | Notes |
|---|---|---|---|---|
| `repoPath` | string | ✅ | — | git repo root (absolute, or relative to server CWD) |
| `reportPath` | string | ✅ | — | path or glob to `jacoco.xml` (supports `**/jacoco.xml`, comma-separated, like `sonar.coverage.jacocoXmlReportPaths`) |
| `baseline` | string | ❌ | — | SHA, branch, or tag (e.g. `origin/main`, `HEAD~1`, `v1.2`). Present ⇒ new-code mode |
| `sourceRoots` | string[] | ❌ | `src/main/java`, `src/test/java` | for package→file mapping (add `src/*/kotlin` etc.) |
| `maxFilesInDetail` | int | ❌ | 20 | cap per-file detail in response; aggregates always exact |

Response (JSON, pretty-printed `CallToolResult` text content):

```json
{
  "overall": {
    "lines":       { "total": 120, "covered": 90, "missed": 25, "partial": 5, "coveragePct": 75.0 },
    "conditions":  { "total": 40,  "covered": 28, "missed": 12, "coveragePct": 70.0 }
  },
  "uncovered": [
    { "file": "src/main/java/com/x/Foo.java",
      "missedLines": [12, 13],
      "partialLines": [20],
      "missedConditions": { "20": 2 } }
  ],
  "newCode": {
    "baseline": "abc1234", "mergeBase": "def5678", "changedFiles": 3,
    "lines":      { "total": 30, "covered": 18, "missed": 12, "partial": 2, "coveragePct": 60.0 },
    "conditions": { "total": 10, "covered": 4,  "missed": 6,  "coveragePct": 40.0 },
    "uncovered": [ { "file": "...", "missedLines": [...], "partialLines": [...], "missedConditions": {...} } ]
  },
  "truncated": false,
  "notes": ["3 files omitted from detail (maxFilesInDetail=20); aggregates unaffected"]
}
```

### Tool 2 (helper): `list_changed_files`

`repoPath`, `baseline` → changed files with per-file changed-line counts. Same
diff engine; aids agent debugging of new-code results.

## 5. Coverage semantics (SonarQube parity)

JaCoCo XML emits only **executable** lines: `<line nr mi ci mb cb/>`
(mi/ci = missed/covered instructions, mb/cb = missed/covered branches).

| Metric | Rule (SonarQube-equivalent) |
|---|---|
| lines to cover | every `<line>` element (≈ SQ `lines_to_cover`; JaCoCo's bytecode view needs no Java parser / significant-code pass) |
| covered line | `ci > 0` — **partially covered counts as covered** (SQ line metric) |
| uncovered line | `ci == 0` (fully missed) — SQ `uncovered_lines` |
| partially covered | `mi > 0 && ci > 0` (reported separately, drives condition gaps) |
| conditions to cover | `Σ (mb + cb)` |
| covered conditions | `Σ cb`; uncovered = `Σ mb` (SQ `conditions_to_cover` / `uncovered_conditions`) |
| line coverage % | coveredLines / linesToCover × 100 |
| condition coverage % | Σcb / Σ(mb+cb) × 100 |

**New-code intersection:** newLines(file) = jaCoCoLines(file) ∩ gitChangedLines(file).
Brand-new files (ADD diff / untracked) ⇒ all report lines are new. JaCoCo line
numbers refer to the working-tree source, and the git diff is also
merge-base→working-tree, so numbering aligns by construction.

## 6. New-code algorithm (port of `GitScmProvider.branchChangedLines`)

```java
// 1) resolve baseline ref — mirror SQ's resolveTargetRef order
Ref target = resolve(repo, "refs/heads/"+b, "refs/remotes/origin/"+b,
                          "refs/remotes/upstream/"+b) ?? resolveSha(b);

// 2) merge base (SQ: RevFilter.MERGE_BASE)
RevCommit base = mergeBase(repo, HEAD, target);   // error if none (unrelated histories)

// 3) diff merge-base tree vs WORKING TREE (captures uncommitted changes, like SQ PR analysis)
repo.getConfig().setBoolean("core", null, "autocrlf", true);
try (DiffFormatter fmt = new DiffFormatter(out)) {
  fmt.setRepository(repo);
  fmt.setDiffComparator(RawTextComparator.WS_IGNORE_ALL);   // SQ parity
  fmt.setPathFilter(PathFilter.create(gitPath));
  List<DiffEntry> diffs = fmt.scan(prepareTreeParser(repo, base), new FileTreeIterator(repo));
  fmt.format(diffs); fmt.flush();                            // → hunk parser
}
// 4) ADD/MODIFY entries → changedLines; DELETE ignored
```

Hunk parser (clean-room from the unified-diff format — do **not** copy
SonarSource's LGPL class verbatim):

```java
// "@@ -a,b +c,d @@" → lineNum = c
// subsequent line ' ' → lineNum++ ; '+' → record lineNum, lineNum++ ; '-' → skip
```

## 7. Module layout

```
boring-java-skills/                 (parent pom, aggregator for future skills)
└── coverage-mcp/
    ├── PLAN.md                     (this file)
    ├── pom.xml
    └── src/
        ├── main/java/dev/boring/coverage/
        │   ├── CoverageMcpServer.java      // main(): stdio transport, register tools, logs→stderr
        │   ├── McpTools.java               // tool schemas, handlers, error mapping (JSON error results)
        │   ├── jacoco/
        │   │   ├── JacocoReportParser.java // StAX: package→sourcefile→line (mi,ci,mb,cb)
        │   │   └── FileCoverage.java       // per-file line map + derived metrics
        │   ├── git/
        │   │   ├── NewCodeDetector.java    // ref resolution, merge-base, diff orchestration
        │   │   ├── ChangedLinesComputer.java // unified-diff hunk parser (clean-room)
        │   │   └── SourceFileLocator.java  // package+name → repo-relative path (index over sourceRoots)
        │   └── core/
        │       ├── CoverageCalculator.java // overall + new-code aggregation (§5 rules)
        │       └── CoverageResult.java     // response DTOs → Jackson
        └── test/java/...                   // fixtures: jacoco XMLs, JGit-built repos, golden numbers
```

## 8. Milestones

| # | Deliverable | Acceptance | Est |
|---|---|---|---|
| M0 | Maven skeleton; stdio MCP server with dummy tool; verify SDK artifact resolves | `McpClient` (from SDK) round-trips `tools/list` + call over stdio | 0.5d |
| M1 | JaCoCo parse + overall metrics + uncovered detail | golden-XML unit tests match hand-computed numbers | 1d |
| M2 | New-code engine: ref resolve, merge-base, diff, hunk parser, source locator | JGit fixture repo (2 commits, temp dir) → expected changed lines | 1.5d |
| M3 | Calculator + response shaping, truncation, error mapping, `list_changed_files` | end-to-end tool call on fixture repo + real jacoco.xml | 0.5d |
| M4 | Parity harness vs dockerized SonarQube (`sonarqube:lts-community`, `sonar.newCode.referenceBranch`) | `new_lines_to_cover`, `new_uncovered_lines`, new coverage % match (± rounding) | 1–2d |
| M5 | Shade fat jar; README with client configs; repo README entry | `java -jar coverage-mcp-server.jar` works as goose/Claude extension | 0.5d |

Total ≈ 5–6 focused days.

## 9. Testing strategy

- **Unit**: hunk parser vs unified-diff text fixtures; coverage math vs golden JaCoCo XMLs; ref-resolution order.
- **Integration**: build fixture git repos with JGit in `@TempDir` (commit A → modify/add files → commit B + dirty worktree); run tools; assert changed lines & new-code metrics.
- **Protocol**: in-memory `McpClient` against the started stdio server; assert tool schemas + JSON contract.
- **SonarQube parity (M4)**: sample Maven project (branches, partial lines), 2 commits, `mvn test jacoco:report`; run our tool vs dockerized SQ with reference-branch new-code; compare metrics. Document any deltas (expected ≈ 0; SQ's significant-code trimming only affects non-executable lines JaCoCo doesn't emit anyway).

## 10. Risks & mitigations

| Risk | Mitigation |
|---|---|
| MCP SDK 2.x API churn (2.0.0 was a breaking release) | Pin `2.0.1`; isolate bootstrap in `CoverageMcpServer`; thin wrapper |
| Artifact resolution doubt (see §3 warning) | M0 explicitly verifies; fallbacks: 1.1.4, build-from-tag |
| LGPL contamination from `ChangedLinesComputer` | Clean-room reimplementation from the unified-diff spec (~30 lines); keep SonarSource code as *behavioral reference only* |
| Package→path ambiguity (same FQCN in 2 source roots) | Index all matches; prefer the one present in diff; flag ambiguity in `notes` |
| stdout pollution kills stdio MCP | **All** logging to stderr; assert in protocol test |
| Huge reports / context bloat | `maxFilesInDetail` truncation; aggregates always exact; per-file line lists compacted to ranges |
| Dirty worktree semantics surprise | By design (SQ PR analysis diffs vs working tree); document in tool description |

## 11. Open questions (defaults chosen, override freely)

1. One tool + helper vs. strictly one tool → **chosen: `analyze_coverage` + `list_changed_files`**.
2. Support SQ's "previous version"-based new code (pom `<version>`) → **v1: baseline ref only**.
3. Kotlin/Scala sources → **covered via `sourceRoots` param**; no language-specific logic.
4. Report globbing defaults → mirror `sonar.coverage.jacocoXmlReportPaths` semantics.

## 12. Client configuration

goose (`goose configure` → Add Extension → Command-Line Extension, or `config.yaml`):

```yaml
extensions:
  coverage-mcp:
    name: Coverage (JaCoCo)
    cmd: java
    args: ["-jar", "/path/to/coverage-mcp-server.jar"]
    enabled: true
    type: stdio
    timeout: 300
```

Claude Desktop / Cursor (standard MCP client syntax, `java -jar …` command).

## 13. Post-review fixes (2026-11)

Follow-up to a code review of the completed implementation. All 78/78 tests
green (`mvn test -pl coverage-mcp`, JDK 17).

1. **Multi-report merge (correctness)** — duplicate classes across reports
   now merge with best-per-line-coverage semantics via
   `jacoco/CoverageMerger` (a line covered in any report counts as covered;
   ties resolved by covered branches; line data beats aggregate-only;
   aggregate-only duplicates keep the higher-ratio report). Previously the
   first report silently won, undercounting UT+IT setups. Merged duplicates
   are listed in `notes`.
2. **Rename detection** — `NewCodeDetector.computeDiff` post-processes the
   tree diff with `RenameDetector` (JGit 7.3's `DiffCommand` has no rename
   switch), collapsing DELETE+ADD into RENAME so moved files count only
   edited lines as new code (SonarQube parity).
3. **EditList instead of text parsing** — `ChangedLinesComputer` runs
   histogram diff (`DiffAlgorithm.SupportedAlgorithm.HISTOGRAM` +
   `WS_IGNORE_ALL`) and converts the structured `EditList` directly; the
   unified-diff text re-parser (~100 lines) and the dead `newContent` buffer
   are gone. Binary blobs yield no changed lines.
4. **Deterministic matching** — `NewCodeCalculator.matchCoverage` fallback
   picks the longest (most specific) suffix match instead of HashMap
   iteration order.
5. **Runtime logging** — `slf4j-simple` ships in the fat jar (stderr-pinned
   via `simplelogger.properties`); startup/fatal logs are no longer
   swallowed by the NOP-logger fallback.
6. **DTO records** — `CoverageResponse`, `FileCoverageDetail`, `ChangedFile`,
   `ChangedFilesResponse` are records per project conventions; JSON contract
   unchanged (computed getters pinned by `McpToolsTest`).

Fixture fix: `fixture-repo/jacoco.xml` listed `User.java` under the
`com.example.service` package although the source declares
`package com.example` — moved to the correct package element. The rename test
was the first consumer of User's class-relative key and caught it.

Known artifact: whitespace-ignoring diff anchors can slide by one line within
an ambiguous all-changed block (observed [14..30] → [13..29] after a fixture
tweak); the executable-line intersection — and therefore every coverage
metric — was unaffected. `WhitespaceIgnoreTest` pins the semantic property
on a minimal two-commit repo instead.

Tests: 68 → 78 (merger semantics ×5, rename ×1, whitespace-ignore ×1, EditList
conversion ×1, matchCoverage determinism ×1, duplicate-merge note via the
tool handler ×1).

## 14. Review follow-up (2026-11, concerns 2–6)

1. **Worst-first per-file detail** — `analyze_coverage` now sorts `files` by
   most missed lines (then line ratio, then path) before applying
   `maxFilesInDetail`, so truncation keeps the files worth fixing and the
   ordering is deterministic. Aggregates unaffected.
2. **Hygiene sweep** — removed production-dead API:
   `CoverageCalculator.calculate(list, set)`,
   `SourceFileLocator.locate/locateFromJacocoClassName/locateInRepo/findInRepo/candidates`
   (test-only consumers, and `findInRepo` walked the whole repo per lookup),
   and the redundant `CloseableResource` marker (implementors are plain
   `AutoCloseable` now). Shared path normalization extracted to
   `util/PathNormalizer` (was duplicated in `SourceFileLocator` and
   `JacocoReportParser`).
3. **list_changed_files without a locator** — `NewCodeCalculator` gained a
   two-arg constructor; the tool no longer builds a `SourceFileLocator` it
   never uses. `calculateNewCodeCoverage` fails fast when constructed
   locator-less.
4. **Bounded glob walks** — `ReportLocator` skips `.git` during traversal
   (instead of walking it and filtering matches afterwards) and starts the
   walk at the wildcard-free leading directory prefix of the pattern
   (`module2/**/jacoco.xml` scans only `module2/`). Matches are sorted, so
   multi-report notes are deterministic.
5. **Defensive merge results** — `CoverageMerger` returns copies
   (`FileCoverage.copy()`) instead of source-report instances, so mutating a
   parsed report after a merge cannot leak into the merged output.
6. **Repository ownership** — `NewCodeDetector.close()` delegates to
   `Git.close()` alone. JGit's `closeRepo` flag (bytecode-verified for both
   constructors) closes the repository exactly when the detector opened it
   via `Git.open`; wrapped repositories stay open for their owner. This also
   eliminated the pre-existing `useCnt already zero` warning noise from
   double-closing. The contract is pinned by
   `wrappedRepositoryIsNotClosedByDetector` (reflects JGit's
   `Repository.useCnt`).

Tests: 78 → 81 (−6 removed with the dead API, +9 new: ordering ×1, locator
hygiene ×2, merger defensive copy ×1, repository ownership ×1, glob bounds
and `.git` skip ×3, PathNormalizer ×1).
