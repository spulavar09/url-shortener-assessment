# URL shortener and engineering workflow

A REST service for creating short links, redirecting requests, and recording total and UTC daily counts. It also includes a persistent engineering workflow with versioned skills, independent verification, and human approval gates.

**Stack:** Java 21, Spring Boot 4.1.1, Maven, Spring JDBC, file-backed H2, Log4j2, OpenAPI, JUnit, and Mockito.

**Verification:** 87 application tests and 9 scenario tests passed. All three demonstration workflows completed final human approval. See [validation results](docs/VALIDATION.md).

## Build and run

Install Java 21. The Maven wrapper downloads its pinned Maven version on first use. Docker and a separate database server are unnecessary. Stop a running application before rebuilding its JAR.

```bash
./mvnw -Dmaven.repo.local=.maven-repository verify
bash scripts/run-local.sh
```

Open [Swagger UI](http://localhost:8080/docs) or [OpenAPI JSON](http://localhost:8080/v3/api-docs). The service listens on loopback port 8080. Links, analytics, and workflow state persist under `.local-data/` across restarts.

Set `PORT` and `PUBLIC_BASE_URL` together when changing the port or public origin.

## Try the APIs

```bash
# Create a link
curl -i -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: example-create-001' \
  -d '{"destinationUrl":"https://example.com/a?x=1","customAlias":"example"}'

# Redirect, resolve HEAD without counting, and read metadata/counts
curl -i http://localhost:8080/r/example
curl -I http://localhost:8080/r/example
curl http://localhost:8080/api/v1/links/example
```

The six product operations create, inspect, disable, delete, redirect, and resolve HEAD. Creation supports generated codes, custom aliases, and optional expiry. Destinations stay fixed; disabling is irreversible, and deleted codes remain reserved. The details response contains `link` and `analytics`. Swagger documents request fields, management calls, and errors.

## Run the engineering workflow

Live workflow execution additionally requires:

- Codex CLI on `PATH`, signed in with `codex login`.
- Maven on `PATH` as `mvn`, or `MAVEN_COMMAND` set to a Maven executable's absolute path. The application build wrapper does not automatically provide the runtime verifier executable.
- A prepared dependency cache: run the build above first. The launcher supplies `.maven-repository` to the offline verifier; additional fixture dependencies must be cached before execution.

The launcher creates a private token file at `.local-data/operator-token`. Workflow APIs require `Authorization: Bearer <token>`; set `OPERATOR_TOKEN` to supply your own. Keep the token local.

The five workflow operations create, inspect, approve, clarify, and cancel runs. Skills define role procedures; Java persists task dependencies, attempts, evidence, and approval gates. Candidate changes stay in owned workspace copies.

Follow [workflow instructions](docs/WORKFLOW.md) for authenticated examples. Saved [scenario reports](docs/scenarios/SCENARIOS.md) cover greenfield implementation, brownfield repair, and clarification of an ambiguous request on small domain fixtures.

## Scope and limits

- Embedded H2 supports one application process. Multiple instances require a shared database and migration testing.
- Analytics are asynchronous and best effort; overload or crashes can lose counts. Redirects use HTTP 302, and HEAD does not count.
- Product APIs are anonymous, including disable/delete and analytics. Ownership and access controls remain a checkpoint before wider release.
- Generated projects are trusted local assessment inputs; untrusted execution needs stronger isolation.

See [design and implementation details](DESIGN.md), [submission guide](SUBMISSION.md), and [privacy notes](PRIVACY.md).
