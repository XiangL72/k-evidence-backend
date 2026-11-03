# Third-party notices

## java-diff-utils 4.17

- Coordinates: `io.github.java-diff-utils:java-diff-utils:4.17`, pinned in pom.xml.
- Purpose: upstream Myers sequence diff over STRICT line comparison keys. Application-owned DiffEngine adapts deltas to ChangeHunk and slices original source text. This project does not claim to implement Myers.
- Distribution source: https://repo.maven.apache.org/maven2/io/github/java-diff-utils/java-diff-utils/4.17/
- Source repository/tag: https://github.com/java-diff-utils/java-diff-utils/tree/java-diff-utils-parent-4.17
- License: Apache License 2.0. Version-specific license: https://github.com/java-diff-utils/java-diff-utils/blob/java-diff-utils-parent-4.17/LICENSE
- Actual verification, 2026-10-06: Maven downloaded the 4.17 JAR/module POM/parent POM from Central; embedded pom.properties confirms 4.17; parent POM declares Apache-2.0 and the tag above. javap confirmed the used DiffUtils/List/MyersDiff and delta chunk APIs. The exact tag archive was inspected without copying upstream implementation code into this repository.
- NOTICE check: no NOTICE-named file in the downloaded tag archive; its only LICENSE/NOTICE-named file is root LICENSE (Apache-2.0). Binary JAR contains no LICENSE/NOTICE file. This states what was inspected, not a general claim about other releases.
- Binary JAR SHA-256: `3e3b6e149bf5d091422689ad6fcc22baad503a52b1139a9baf250fb6de633024`.
- Integration uses the Maven dependency; no upstream source was copied/modified. Runtime API data uses only application-owned records.

This file records the java-diff-utils integration; it is not a complete license audit of the Spring/React dependency trees.

## Persistence and testing dependencies

The backend uses Spring Boot and PostgreSQL. Flyway (`flyway-core` and `flyway-database-postgresql`) manages schema migrations. Test-scoped Testcontainers (`junit-jupiter` and `postgresql`) starts disposable PostgreSQL instances. Exact dependency versions are defined in `pom.xml` and its Spring Boot dependency management. The test image is `postgres:17.6-alpine`.

No upstream source was copied into the project. This entry identifies integrations and is not a complete transitive license audit.
