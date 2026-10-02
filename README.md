# URL shortener and skill-led engineering workflow

A Java 21, Maven, Spring Boot 4.1.1 REST service with file-backed H2, Log4j2, OpenAPI, JUnit, and Mockito. It includes a small persistent workflow controller that runs versioned engineering skills through Codex, verifies candidates independently, and waits for human approval at design and final handoff gates.

## Run locally

Java 21 is required. The Maven wrapper pins Maven 3.10.0 and downloads it on first use; an existing compatible Maven installation can also run the build. Verification used Java 21.0.12 and the pinned Maven 3.10.0 wrapper. Docker and a database server are unnecessary.

Stop a running application before rebuilding its jar; build and start are separate steps.

```bash
./mvnw -Dmaven.repo.local=.maven-repository verify
bash scripts/run-local.sh
```

Open [API documentation](http://localhost:8080/docs) or [OpenAPI JSON](http://localhost:8080/v3/api-docs). The application listens on loopback by default. The configured public origin is `http://localhost:8080`; set `PUBLIC_BASE_URL` if you change the port or origin. Runtime data lives under `.local-data/` and survives restart.

The launch script creates a local operator token in `.local-data/operator-token` with private file permissions. It never prints it. Product APIs are anonymous; workflow and detailed operations APIs require `Authorization: Bearer <token>`. Set `OPERATOR_TOKEN` to supply your own token. Starting the jar directly without one leaves operator APIs inaccessible.

For live workflow tasks install Codex CLI, sign in using `codex login`, and verify `codex login status`. No separate API key is required for the selected ChatGPT-sign-in adapter. This machine verified CLI 0.149.1 and actual GPT-5.6 Sol execution at medium reasoning. The app's coding subagents use GPT-6.1 medium; this CLI rejected GPT-6.1, and the user approved a supported CLI model for runtime demonstrations. Set `WORKFLOW_CODEX_MODEL` to another supported model when needed.

Runtime verification is offline after setup. The launcher supplies the local dependency cache automatically. A fixture with additional dependencies must be prepared through a reviewed setup step; the engine will not silently change the verifier or model when a prerequisite fails.

## Product examples

```bash
curl -i -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: example-create-001' \
  -d '{"destinationUrl":"https://example.com/a?x=1","customAlias":"example"}'

curl -i http://localhost:8080/r/example
curl -I http://localhost:8080/r/example
curl http://localhost:8080/api/v1/links/example

curl -i -X PATCH http://localhost:8080/api/v1/links/example \
  -H 'Content-Type: application/json' \
  -d '{"disabled":true,"expectedVersion":0}'
```

The details response contains `link` metadata and `analytics` with lifetime and UTC daily recorded counts. Optional `from` and `to` query parameters select 1-90 retained UTC dates; the default is the last seven dates. Creation and disable responses retain their original metadata shape.

Creation also supports an optional future `expiresAt` UTC timestamp. Generated codes have ten Base62 characters; custom codes are case-sensitive and contain 4-32 ASCII letters, digits, hyphens, or underscores. Destinations remain fixed. Disabling is irreversible in this version. Delete creates a tombstone and never makes a code reusable. Retries with the same optional idempotency key and payload replay the original creation for 24 hours, even if its link later expires or is deleted. Different payloads using that key conflict.

Redirects use 302 and `Cache-Control: no-store`. HEAD resolves without counting. Total and UTC daily counts describe recorded successful redirect resolutions, including repeated requests and bots; they do not prove arrival at the destination. Recording uses a bounded asynchronous queue and can lose counts during crashes or recorder overload. Durable lifetime totals remain; daily buckets cover 90 UTC dates and deduplication events seven days.

Unknown, invalid, or conflicting requests return problem responses with `errorCode` and `correlationId`. Creation and management quotas are local-process controls. Forwarded client headers are not trusted by default. URL syntax, hostnames and IP addresses are checked by [Apache Commons Validator](https://commons.apache.org/proper/commons-validator/apidocs/org/apache/commons/validator/routines/UrlValidator.html). Jakarta Bean Validation annotations handle request fields. The service retains application rules for ASCII destinations, credentials, port zero, self-redirects, expiry and idempotency. Local/private hosts, fragments and repeated path slashes are supported. Validation never fetches destinations and cannot certify their safety or availability.

## Workflow and evidence

See [approved design](DESIGN.md), [workflow operation](docs/WORKFLOW.md), and [scenario scope](docs/scenarios/SCENARIOS.md). Skills live in `agent-skills/`; Java enforces state, dependencies, deadlines, evidence hashes, approval identity, and recovery bounds. The engine writes to its owned copies under `.local-data/workflow/`, never merges a candidate into this checkout, and performs no deployment.

The three fixture scenarios exercise real generation, repair, and ambiguity handling on deliberately small Java domain projects. They are narrower than the delivered REST/database service. Fault injection tests exercise retries, restart reconciliation, stale evidence, cancellation, compensation, and exhausted repair separately; reports label their evidence accordingly.

## Code structure

Controllers handle HTTP, services decide behavior, and concrete repositories execute SQL and map stored rows. Persistence uses Spring JDBC with the existing H2 datasource. Stable API error codes are defined in `common/ErrorCodes.java`. `WorkflowProperties` is an immutable configuration record: Spring binds defaults, and its constructor preserves the existing numeric bounds and reasoning validation. `ApiErrorAdvice` extends Spring’s `ResponseEntityExceptionHandler`, retaining our problem-response fields while Spring supplies MVC exception dispatch, statuses and headers.

| Area | Service responsibility | Repository responsibility |
|---|---|---|
| Links | URL/code validation, expiry, idempotency decisions, lifecycle and create transactions | `LinkRepository`: link records and creation-idempotency records |
| Analytics | Queue, retries, UTC/retention rules, counters and recording/read transactions | `AnalyticsRepository`: events, lifetime totals and daily counts |
| Workflow | Dependencies, approvals, evidence checks, budgets, recovery and transaction boundaries | `WorkflowRepository`: runs, tasks, attempts, approval/event history and conditional database updates |

API controller JavaDoc describes endpoint inputs, responses and lifecycle rules. Log4j2 writes JSON operational logs with correlation IDs. Lifecycle events use INFO, failures use WARN/ERROR, and request timing and redirect/polling detail use DEBUG. Enable detail with `--logging.level.com.example.shortener=DEBUG`. Logs omit destination URLs, request bodies, workflow prompts and credentials.

Services coordinate transactions across repository calls. For example, creating a link, initializing its analytics total and reserving an idempotency key commit together. Analytics reads retain their repeatable-read snapshot; claim locks and version/fence conditions remain in the repository SQL.

## Operational limits and parked decisions

- **One application process owns embedded H2.** Multiple instances need a reviewed migration to a shared database and tests against that actual database. H2 compatibility modes do not prove PostgreSQL behavior. The user accepted this limit for the local demo.
- Stop the application before backing up the H2 database and its associated `.local-data/workflow/` evidence together. Database rows reference those immutable files. Do not open the database from agent tasks or another service instance. Migrations run through Flyway; startup does not silently replace an unavailable database.
- **Anyone can create, inspect, disable, or delete links and read analytics by code.** This is the chosen initial access model. Checkpoint I6 must reconsider ownership, analytics access, and abuse controls before any wider release.
- Codex's sandbox and application path checks provide bounded write control. They are not a complete read/network isolation environment. Generated Java tests and Maven projects are executable code and are trusted local assessment inputs; use a stronger OS sandbox before handling untrusted projects.
- Unknown process outcome on restart preserves the consumed attempt and stops for reconciliation. It does not blindly replay an AI request or promise zero analytics loss.
- Local audit events are append-only through the application, not tamper-proof against a database administrator. Token usage is reported only when supplied by the CLI; subscription monetary cost is unavailable.

See [validation and checkpoints](docs/VALIDATION.md) for the final 87 passing tests, including the latest simplifications and clarification-pause regressions, actual restart and database-lock checks, bounded redirect measurements, and current scenario approval status.
