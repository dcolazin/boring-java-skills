# Agent Instructions for boring-java-skills

## Project Conventions

### License Policy
- Prefer open source libraries with licenses compatible with the Free Software Foundation (FSF)
- Favor Apache 2.0, MIT, BSD, EPL, LGPL over GPL for libraries
- Avoid proprietary or restrictive licenses

### Testing Framework
- **Use TestNG** for all testing (not JUnit)
- Version: 7.10.2 (managed in parent POM)
- Use AssertJ for assertions

### JSON Library
- **Use Jackson 3** (`tools.jackson.core:jackson-databind:3.x`) exclusively
- Do NOT use Jackson 2 (`com.fasterxml.jackson.core:jackson-databind:2.x`)
- Jackson 2 annotations (`com.fasterxml.jackson.core:jackson-annotations`) are acceptable as a transitive dependency required by Jackson 3

### Java Version
- Target Java 17 (LTS)
- Use `--enable-preview` only if explicitly needed

### Build Tool
- Maven with multi-module structure
- Parent POM manages dependency versions via `<dependencyManagement>`
- Use `maven-shade-plugin` for fat JARs
- Use `maven-compiler-plugin` with `-parameters` flag

### Code Style
- Follow standard Java conventions
- Use `var` for local variable type inference where type is obvious
- Prefer immutable data structures
- Use records for DTOs (Java 17+)

### Git Practices
- Conventional commits
- Branch per feature/fix
- Squash merge to main

### Documentation
- Update AGENTS.md when conventions change
- Keep README.md current with setup/run instructions
- Document tool contracts in PLAN.md per module