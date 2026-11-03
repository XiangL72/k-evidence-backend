# K-Evidence Backend

[Frontend repository](https://github.com/XiangL72/k-evidence-frontend)

**Source-linked contract comparison with immutable analysis history.**

This backend saves two contract documents, compares them in a user-selected order and relationship, and returns findings that can be traced to exact source spans. It primarily supports limited rules for Chinese goods-sale contracts.

**Java 21 · Spring Boot 4.1.2 · PostgreSQL · Flyway · java-diff-utils**

[Run locally](#run-locally) · [Architecture](#architecture) · [API](#api-overview) · [Verification](#verification)

## What it does

- **Preserves original documents.** Strict UTF-8 decoding, stored raw bytes and decoded text, and separate content hashes.
- **Makes comparison semantics explicit.** A full revision receives text diff; a supplement is not treated as a replacement of the entire original contract.
- **Links findings to evidence.** Amounts, payment terms, delivery changes, and clause text retain their source version and UTF-16 offsets.
- **Keeps uncertainty visible.** Missing or ambiguous targets remain unresolved rather than being guessed into definite changes.
- **Organizes supplement content.** An 11-category composition view keeps B's content in the appropriate category, including notes needing review.
- **Saves immutable snapshots.** Reopening an analysis reads the recorded result instead of rerunning newer rules.
- **Bounds expensive work.** Input, diff-work, concurrency, and snapshot-size limits constrain analysis; saved revision comparisons can fall back to coarse source blocks.

The React frontend is maintained separately. Its standalone English demo uses fictional documents and fixed results and runs without this backend. Live English analysis is not implemented here.

## Run locally

Requirements: Java 21 or a compatible newer JDK, the included Maven Wrapper, and a dedicated local PostgreSQL database named `kcomparison`. Commands below run from this backend repository.

Create the database and an application account permitted to create its schema, then configure the backend process:

| Environment variable | Value |
|---|---|
| `COMPARISON_DB_USERNAME` | Your dedicated database account; required |
| `COMPARISON_DB_PASSWORD` | The account's password, according to your local database authentication |
| `COMPARISON_DB_PORT` | Optional; defaults to `5432` |
| `CORS_ALLOWED_ORIGINS` | Optional; defaults to `http://127.0.0.1:5174` |

[`.env.example`](.env.example) documents the configuration names. Spring Boot does not automatically load that file. Set credentials in your local process environment.

Flyway applies migrations and Hibernate validates the schema. For first setup, use an empty dedicated database rather than a database belonging to another application.

```sh
./mvnw -B -DskipTests package
java -jar target/k-review-0.0.1-SNAPSHOT.jar
```

The API binds to `127.0.0.1:8081`. The existing Java package and JAR names are retained. `-DskipTests` packages the application without running tests; verification commands are below.

Start the separate frontend development server at `http://127.0.0.1:5174` to use its upload, analysis, evidence, and history views. The full application is intended for local single-user use, not an authenticated public upload service.

## Architecture

```text
Two UTF-8 documents + selected relationship
                    ↓
Strict decoding and immutable document storage
                    ↓
Bounded text diff + limited Chinese field/reference rules
                    ↓
Source-linked findings and contract matrix
                    ↓
Immutable JSONB snapshot → retrieve without recomputation
```

| Layer | Responsibility |
|---|---|
| [`document`](src/main/java/com/kreview/kreview/document) | Decoding, source spans, document versions, and storage |
| [`comparison`](src/main/java/com/kreview/kreview/comparison) | Diff adaptation, work budgets, and character evidence |
| [`extraction`](src/main/java/com/kreview/kreview/extraction) / [`matching`](src/main/java/com/kreview/kreview/matching) | Limited Chinese field extraction and explicit-reference matching |
| [`matrix`](src/main/java/com/kreview/kreview/matrix) | Category and contract-composition projections |
| [`analysis`](src/main/java/com/kreview/kreview/analysis) | Analysis orchestration and snapshot persistence |
| [`controller`](src/main/java/com/kreview/kreview/controller) | Input validation and HTTP endpoints |

The [database migration](src/main/resources/db/migration/V1__create_document_storage.sql) defines three tables: `document_set`, `document_version`, and `analysis_run`. Database constraints enforce document roles, order, and same-group version relationships. Each new analysis creates a new snapshot record.

New supplement analyses use schema 4; full revisions use schema 3. Historical schemas 1–4 remain readable. Evidence uses UTF-16 half-open intervals into the unchanged decoded text. Character diff works over Unicode code points and maps its results back to source offsets.

The dependency baseline uses Spring Framework 7.0.10 and embedded Tomcat 11.0.27. The Boot-managed Jackson 2 compatibility module preserves the existing JSON API and saved-snapshot format during this upgrade; a later Jackson 3 migration must verify those contracts again.

The Myers diff algorithm is supplied by **java-diff-utils**. This project adds resource bounds, source mapping, domain rules, matrix presentation, and persistence. It does not claim to implement Myers.

## API overview

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/api/document-sets` | Save two documents and their relationship |
| GET | `/api/document-sets` | List saved document groups |
| GET | `/api/document-sets/{id}` | Read group metadata |
| GET | `/api/document-sets/{id}/versions/{versionId}/text` | Read source text |
| POST | `/api/document-sets/{id}/analyses` | Create a new analysis snapshot |
| GET | `/api/document-sets/{id}/analyses` | List analysis history |
| GET | `/api/analyses/{analysisId}` | Retrieve a saved snapshot |
| POST | `/api/comparisons/preview` | Compare two text strings without saving |

Document uploads accept exactly two UTF-8 TXT files, up to 1 MiB, 100,000 UTF-16 units, and 5,000 lines per document. The caller selects `REVISION` or `SUPPLEMENT`; dates in the text do not reorder the documents.

## Verification

Unit and rule tests require neither a database nor Docker:

```sh
./mvnw -B test
```

Full verification requires Docker. Testcontainers creates disposable PostgreSQL instances and supplies their credentials independently of the development database:

```sh
./mvnw -B verify
```

The [backend CI configuration](.github/workflows/backend-ci.yml) runs full verification. It does not include the separate frontend's browser tests. Test fixtures are synthetic development examples, not an independent contract-accuracy benchmark.

[Local dependency and test verification](docs/VERIFICATION.md) records the patched runtime versions, advisory-check scope and PostgreSQL integration results.

## Scope and roadmap

Current rules cover a limited set of Chinese goods-sale expressions. Missing evidence or an ambiguous reference stays unresolved. Unmentioned original clauses are not treated as deleted. Responsibility clauses are presented as text; the application does not assess liability severity, legal effect, actual performance, or a complete current contract.

**Planned:** English clause segmentation, classification, reference matching, change rules, and independent evaluation. The preset English frontend demonstration is separate from that work.

Multi-file analysis, PDF/OCR, LLM integration, and a public upload service are outside the current implementation. General contract-analysis accuracy and production latency have not been established.

## Dependencies and license

Versions are defined in [`pom.xml`](pom.xml) and its Spring Boot dependency management. See [third-party notices](THIRD_PARTY_NOTICES.md). This project is licensed under the [MIT License](LICENSE). Third-party components retain their respective licenses.
