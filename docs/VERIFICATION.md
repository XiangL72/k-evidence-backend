# Dependency and test verification — 2026-11-02

The backend was upgraded from Spring Boot 3.5.14 to 4.1.2. Resolved runtime dependencies include Spring Framework 7.0.10, Spring Data JPA 4.1.2, embedded Tomcat 11.0.27, Hibernate ORM 7.4.13.Final, Flyway 12.4.0, PostgreSQL JDBC 42.7.14 and Jackson 2.21.7. Versions come from the Boot dependency management rather than individual runtime overrides.

## Compatibility

Web MVC and Flyway use their Boot 4 starters. Testcontainers uses its renamed 2.x modules. HTTP integration tests use the relocated TestRestTemplate with explicit auto-configuration. The application retains Boot's Jackson 2 compatibility module so the JSON API and persisted snapshots keep their existing representation. This module is deprecated upstream and should be replaced by a separately verified Jackson 3 migration.

Hibernate's new database-error logger is disabled, preserving the existing policy against logging database row contents. A configuration assertion covers the new category. Chinese contract rules, API DTOs, source offsets and the SQL migration are unchanged.

## Executed checks

`./mvnw -B verify` passed locally: 178 unit tests and 15 integration tests, zero failures, errors or skips. PostgreSQL 17.6 ran in disposable Testcontainers containers on random ports. Checks cover HTTP validation, transaction rollback, save/reopen, application restart, source bytes and hashes, and historical analysis schemas. No development database or existing application service was used. Local runtime was JDK 23 with Java 21 compilation target; the GitHub workflow specifies JDK 21 but has not yet run in the new repository.

The final run also confirmed that the deliberately triggered database failure no longer appeared through Hibernate's renamed error logger.

## Advisory check

The resolved runtime dependency tree contained 81 unique Maven coordinates. Each exact package/version was queried against OSV's live batch API; none returned a known advisory. The previously identified Spring and Tomcat affected versions are absent. This check excludes test/build plugins, container images and OS packages, and does not prove the absence of unknown vulnerabilities or application-level issues.

The backend remains a local, unauthenticated single-user application. Dependency updates do not add authorization, a public upload service or a request-body gateway limit. Public source availability does not mean the API is ready for public hosting.
