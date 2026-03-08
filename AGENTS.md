# Repository Guidelines

## Project Structure & Module Organization
This repository is a single-module Spring Boot API project.

- Main source: `src/main/java/com/rami/artstudio`
- API controllers: `src/main/java/com/rami/artstudio/system`
- Configuration: `src/main/resources/application.properties`
- Tests: `src/test/java/com/rami/artstudio`
- Build files: `build.gradle`, `settings.gradle`, `gradlew`, `gradle/wrapper/*`

Keep new features under `com.rami.artstudio.<domain>` (for example, `com.rami.artstudio.artwork`).

## Build, Test, and Development Commands
Use the Gradle wrapper so local Gradle installation is optional.

- `./gradlew bootRun`: Start the API locally.
- `./gradlew build`: Compile, run tests, and produce build artifacts.
- `./gradlew test`: Run unit/integration tests only.
- `./gradlew clean`: Remove build outputs for a clean rebuild.

Run commands from the repository root.

## Coding Style & Naming Conventions
Follow standard Java/Spring conventions:

- Indentation: 4 spaces, no tabs in new code.
- Class names: `PascalCase` (`SystemController`).
- Methods/fields/variables: `camelCase`.
- Packages: lowercase dot notation (`com.rami.artstudio.system`).
- REST endpoints: lowercase, resource-oriented paths (e.g., `/api/v1/system/health`).

Prefer constructor injection, small focused classes, and explicit request/response models when endpoints grow.

### Code Rule: Determinism and Encapsulation
- Design services around clear interfaces with deterministic behavior (same input -> same output/exception contract).
- Hide implementation details behind interfaces; depend on abstractions, not concrete classes.
- Expose only required methods and DTOs from each module/package; keep internal policies private.
- Define interface contracts first for domain/application services, then implement adapters (DB, external API, auth).

## Testing Guidelines
Testing uses Spring Boot Test with JUnit 5 (`useJUnitPlatform()` in Gradle).

- Place tests under mirrored package paths in `src/test/java`.
- Test class naming: `<ClassName>Tests` (current pattern) or `<ClassName>Test`.
- Add controller/service tests for new behavior and edge cases.
- Do not use in-memory databases (H2/HSQL/Derby) for integration tests.
- Run integration tests against Supabase PostgreSQL.
- Use baseline test data derived from live page content on `https://www.ramiartstudio.com/`.
  Capture source page path and collection date in each test log for traceability.

Before opening a PR, run:

```bash
./gradlew clean test
```

## Commit & Pull Request Guidelines
Recent commit style is short, imperative, and descriptive (for example, `Migrate build tool from Maven to Gradle`).

- Commit messages: one-line summary in imperative mood.
- Keep commits focused by concern (build, API, refactor, tests).
- PRs should include: purpose, key changes, test results, and related issue/ticket.
- If API behavior changes, include sample request/response updates in `README.md`.
