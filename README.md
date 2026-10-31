# Boring Java Skills

A collection of boring but useful Java skills/tools implemented as MCP (Model Context Protocol) servers.

## Modules

### coverage-mcp
Local JaCoCo Coverage MCP Server - Analyzes JaCoCo coverage reports with optional "new code" metrics similar to SonarQube.

- **Tools**: `analyze_coverage`, `list_changed_files`
- **Features**: Line/branch coverage, new-code detection via Git diff, per-file details
- **Transport**: stdio (MCP protocol)
- **Docs**: [coverage-mcp/README.md](coverage-mcp/README.md)

## Quick Start

```bash
# Build all modules
mvn package

# Run coverage-mcp server
java -jar coverage-mcp/target/coverage-mcp-1.0.0-SNAPSHOT.jar
```

## Requirements

- Java 17+
- Maven 3.8+

## License

Apache 2.0