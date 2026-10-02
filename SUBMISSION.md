# Submission guide

This submission contains a runnable URL shortener and a skill-led engineering workflow. The service uses Java 21, Spring Boot, Maven, Spring JDBC, file-backed H2, Log4j2, OpenAPI, JUnit and Mockito. No Docker or database server is required.

## Start here

1. Read [README.md](README.md) for setup, API examples and deployment limits.
2. Read [DESIGN.md](DESIGN.md) for the approved design, scenarios and checkpoints.
3. Read [docs/WORKFLOW.md](docs/WORKFLOW.md) for skills, agent execution, human gates and recovery.
4. Read [docs/scenarios/SCENARIOS.md](docs/scenarios/SCENARIOS.md) and the scenario reports for actual assessment evidence.
5. Read [docs/VALIDATION.md](docs/VALIDATION.md) for verification results and their limits.

## Explain the implementation

Controllers translate HTTP requests into service calls. Services enforce business rules and transaction boundaries. Three concrete repositories own SQL and row mapping. DTO annotations validate request fields, Apache Commons Validator handles generic URL syntax, and named error constants preserve API responses. Spring binds the immutable workflow configuration record and handles standard MVC exceptions; our code supplies application defaults/bounds and problem-response fields.

The six product operations create a link, read metadata/counts, disable, delete, redirect, and resolve HEAD without counting. Destinations are fixed; expiry is optional; deleted codes stay reserved. Analytics use a bounded asynchronous queue, so redirects stay fast and counts are explicitly best effort.

The five workflow operations create, inspect, approve, clarify and cancel. Skills describe each engineering role; the Java controller persists task dependencies, exact evidence hashes, attempts and approval gates. The runtime uses real Codex tasks in owned fixtures, with independent fixture Maven verification and candidate review. Model output never grants human approval.

## Verification status

Final Maven verification passed all 87 application tests with zero failures, errors or skips and packaged the current source. All three existing scenario candidates are independently reverified; consult the scenario index and validation report for exact outcomes. Final human approval was explicitly granted and recorded for all three candidates; all three runs are `SUCCEEDED`.

## Scope and limits

The three runtime demos are small domain fixtures, while the delivered application is the REST/H2 service. Historical failed attempts and recovery evidence are retained. Fault-injected tests are identified as such. H2 is single-process, recorded analytics may lose events, and anonymous link management is the accepted local-demo access model; ownership/access must be reconsidered before wider release. No deployment or merge into the original checkout is performed.

The submission excludes operator credentials, live database files, runtime caches and private local configuration. This repository includes privacy-redacted reports and evidence exports. Build the runnable application using the README instructions; generated JARs are excluded from Git.
