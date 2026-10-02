# URL shortener and agentic engineering workflow: approved design

Status: design version 3 approved and frozen on October 1, 2026. The user confirmed the final approval boundaries after reviewing the design. Engineering procedures are skill-led, with a small executable control layer and embedded file-backed H2. No implementation was created before this confirmation.

The contracts and defaults below form the approved overnight implementation baseline. Material scope changes require review; routine coding, testing, and up to two repair cycles may proceed automatically within this baseline.

## 1. Requirements and open decisions

Confirmed user requirements: Java, Maven, Spring Boot, REST APIs, Log4j2, a recommended database, JUnit and Mockito; complete the design before coding; establish checkpoints; ask questions; work within one evening. The workspace assessment also requires an agentic SDLC orchestrator, three demonstration scenarios, a runnable prototype, validation, governance, and defensible engineering decisions.

Primary decisions recorded from the user on October 1, 2026:

| Decision                  | User decision                                                                                                     | Design consequence                                                                                                           |
|---------------------------|-------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| System scope              | Full assessment                                                                                                   | Design and deliver the shortener and persistent agentic SDLC orchestration                                                   |
| Available time            | Full night until early morning October 2, America/Chicago                                                         | Plan a complete overnight prototype after the design is settled; exact cutoff remains unspecified                            |
| Engineering assistance    | Codex and subagents can write the code                                                                            | Real implementation assistance is available; recommend a Codex CLI adapter for agent execution inside the delivered workflow |
| Orchestration approach    | Include skills and a small executable control layer in the assessment                                             | Skills guide engineering work; executable controls persist state, validate evidence, and enforce action gates                |
| Product access            | Anyone can create and manage links for now                                                                        | Anonymous link API; park authentication/ownership and reconsider them at the final checkpoint                                |
| Database deployment limit | Embedded database belongs to one application process; future multi-instance deployment needs a reviewed migration | Explicitly document and accept the local H2 deployment boundary; retain future database tests as migration work              |

Further product choices confirmed by the user:

- Demo interface: REST APIs, API documentation, and saved workflow reports. No separate browser dashboard.
- Link features: generated codes, optional custom names, optional expiry, disable/delete, fixed destinations after creation.
- Analytics: fast redirects with best-effort asynchronous total and daily counts; crash-related loss is accepted. This choice does not authorize a synchronous durable recording requirement.
- Runtime agent proposal: use the installed Codex CLI and existing ChatGPT sign-in. Verified CLI 0.149.1 and sign-in status; no inference request has been executed for this design. Use relevant project inputs only; do not automatically pass the internal assessment PDF to agent tasks.
- Database proposal revised following the user's simplicity requirement: embedded file-backed H2 for the runnable local demo, product data, and durable workflow state. Maven supplies its Java dependency; no separate database installation or Docker is required. PostgreSQL is an optional future deployment choice, not a prerequisite for tonight.
- Current delivery boundary: local runnable artifacts, setup instructions, API documentation, scenario reports, and a release-readiness report. A hosted deployment is outside the current local-demo request and remains a separate decision.

Approval boundaries confirmed by the user: a human approves requirements/design before implementation; coding, testing, and up to two repair cycles proceed automatically within approved scope; a human reviews final changes and validation evidence before handoff. Material scope changes and high-impact external actions require separate approval. Exact morning cutoff remains unspecified and does not block implementation.

The workflow control plane is distinct from anonymous product access. Proposed operator approval and execution controls remain protected so the demonstration can distinguish a human approval from a model action. Product accounts and link ownership are deliberately parked until checkpoint I6; do not introduce account registration or creator-only management before then.

## 2. Scope and architecture

Use one Maven project and one Spring Boot application with modules by responsibility. Keep the agent workflow outside the redirect request path. Engineering procedures live in versioned skills and their references. Recommend hosting the small executable control layer inside the existing Spring Boot application with its embedded file-backed H2 database, so we do not need another service, database server, or programming-language runtime. This is a bounded assessment implementation, not a general-purpose workflow platform.

| Module | Responsibilities | Boundary |
|---|---|---|
| Links | Create, validate, resolve, inspect, disable, expire links | Owns link rules and persistence |
| Analytics | Record redirect events and provide total/daily counts | Cannot prevent an otherwise valid redirect when its own recording fails |
| Workflow control | Versioned plans, ready-task dispatch, durable state, gates, recovery, evidence | Cannot modify the running application or the user's checkout; no engineering role procedures hardcoded here |
| Engineering skills | Clarification, architecture, change generation, verification guidance, review/handoff | Versioned instructions used by Codex; cannot self-approve actions or replace deterministic checks |
| Governance | Identity, approval validation, action policy, audit evidence | Server-side enforcement; model output cannot bypass it |
| Operations | Health, metrics, structured logs, configuration | Protected administrative surface |

Flow: clients reach link REST endpoints; link services use embedded H2; successful redirect resolutions enqueue bounded analytics work. Operators reach workflow REST endpoints; the control layer reads durable graph state from the same database, dispatches Codex with the task's versioned skill and declared inputs, collects immutable evidence, and waits at required human gates. Codex/subagents supply reasoning and engineering work using existing execution capabilities. Workers operate in an isolated, orchestrator-owned project copy. Their generated artifacts do not replace the currently running application or access its live database files.

Controllers translate HTTP requests and responses. Application services coordinate use cases. Domain rules decide validity and state transitions. Repositories and agent adapters handle external dependencies. Inject a clock, code generator, and agent adapter so tests can control time, collisions, and failures.

Proposed prototype boundaries: one service instance, one database, two concurrent workflow workers, anonymous link users, and one workflow operator principal. Horizontal scale, a user-account product, distributed execution, and advanced analytics are evolution work. No Redis, Kafka, Kubernetes, or separate browser dashboard is needed.

## 3. Technology decisions

| Technology | Proposed use and rationale |
|---|---|
| Java 21 | Installed LTS baseline; adequate for the whole service |
| Maven | Dependency management, repeatable build, unit/integration verification; wrapper added only after design approval |
| Spring Boot 4.1.1 | Stable release documented at design time; MVC REST endpoints, dependency injection, configuration, security, persistence, health; compatible with Java 21 |
| Dependency management | Use the Spring Boot parent/BOM for compatible Spring, logging, testing, and persistence versions. Pin external libraries; no snapshot dependencies or independently mixed framework versions |
| H2, embedded file-backed | Boot-managed Java dependency; local persistence, transactions, unique constraints, relational lineage, counters, and workflow state without a separate server |
| Spring Data JPA | Link persistence; focused transactional database operations for scheduler claims and analytics writes |
| Flyway | Ordered, reviewed schema evolution; no automatic destructive schema updates |
| Log4j2 | Structured operational logs via Spring Boot's Log4j2 starter; replace the default logging backend |
| Spring Security and Bean Validation | Protect workflow execution/approval and operations; leave link APIs anonymous; body/field limits and stable request validation |
| Actuator and Micrometer | Readiness, health, request and workflow metrics; protected detailed endpoints |
| OpenAPI and springdoc-openapi 3.1.1 | Reviewable REST contract and Swagger UI compatible with the Spring Boot 4 generation; no separate product frontend |
| JUnit Jupiter and Mockito | Boot-managed testing dependencies; domain and application behavior tests; mock external boundaries |
| Database integration tests | Real H2 using isolated databases; temporary file-backed H2 for restart/recovery tests; no Testcontainers/Docker requirement |

Use file-backed H2 for the runnable demo so links, approvals, and workflow progress survive application restart. In-memory H2 is suitable for disposable tests but cannot demonstrate persisted restart recovery. The database opens within the service JVM; one application process owns it, with multiple connections for concurrent requests/workers. Codex processes interact through task inputs/results rather than opening the live database. No Docker engine or database server is required. H2 and other dependencies are downloaded by Maven during setup.

This change intentionally trades a shared server database for simpler single-process operation. H2 verification proves H2 behavior; PostgreSQL compatibility mode does not prove PostgreSQL behavior. Future migration requires reviewed schema/data conversion and tests against the real target database, including concurrency and transaction semantics. Testcontainers becomes relevant only if that migration is chosen.

User accepted this single-process/future-migration trade-off on October 1, 2026 and requested that it be documented. Include it in the final setup instructions and assessment limitations as well as this design.

Official references consulted: https://docs.spring.io/spring-boot/system-requirements.html ; https://docs.spring.io/spring-boot/reference/testing/index.html ; https://docs.spring.io/spring-boot/how-to/logging.html ; https://h2database.com/html/features.html ; https://h2database.com/html/commands.html#set_write_delay ; https://docs.spring.io/spring-boot/reference/actuator/metrics.html ; https://springdoc.org/ . Dependency resolution and compatibility are verified at setup; a failed prerequisite is reported rather than silently changing the selected stack.

## 4. Proposed shortener contract

Use an explicit redirect prefix so custom aliases cannot collide with administrative routes. The configured public base URL supplies generated links; do not derive it from an untrusted Host or forwarded header.

| Operation | Access | Success | Important failures |
|---|---|---|---|
| POST /api/v1/links | Public | 201; code, short URL, destination, creation and expiration times | 400 validation; 409 alias/idempotency conflict; 429 quota; 503 database or code-allocation failure |
| GET /r/{code} | Public | 302 with Location and Cache-Control: no-store | 404 unknown; 410 expired/disabled/deleted; 429 rate limit; 503 resolution unavailable |
| HEAD /r/{code} | Public | Same resolution headers, no body | Same resolution failures; does not increment analytics |
| GET /api/v1/links/{code} | Public | 200 with metadata, including lifecycle state | 404 unknown |
| GET /api/v1/links | Public | 200, paginated results ordered by creation time and ID | 400 invalid pagination; 503 dependency unavailable |
| PATCH /api/v1/links/{code} | Public | 200; disable link | 400 unsupported field/state; 404 unknown; 409 stale resource version; 410 deleted |
| DELETE /api/v1/links/{code} | Public | 204; idempotent tombstone, including already deleted/unknown codes | Subsequent reads identify an existing tombstone; unknown codes remain unknown |
| GET /api/v1/links/{code}/analytics | Public | 200; durable recorded total and daily UTC counts, measurement semantics | 400 invalid date range; 404 unknown; 503 analytics unavailable |

There is no link authentication or ownership enforcement in this first version. Anyone can inspect, disable, or delete any known link; paginated listing is also public in the proposed contract. Document this behavior as the explicitly chosen initial access model. At I6, reconsider whether public listing/analytics and cross-user management should remain allowed. Workflow/operations authentication failures are 401; a recognized principal without the required role gets 403. Unsupported methods return 405. Malformed JSON and semantic input errors return 400; unsupported media types return 415; oversized bodies return 413. Errors use a consistent problem response with status, stable error code, field errors where appropriate, and correlation ID, without internal stack traces.

Proposed create fields: destination URL, optional custom alias, optional expires-at timestamp. Creation time and IDs are server-controlled. Require a future expiration instant when supplied. Store all instants in UTC. A link is expired when the server clock is equal to or later than expires-at; cleanup is not required for expiration to take effect.

Creation returns a Location header for the metadata resource. Missing/null expiration means no scheduled expiry. Unknown request fields are rejected rather than silently ignored. Proposed limits: 16 KiB JSON body, 128-character idempotency key, page size default 20 and maximum 100. Metadata response fields are ID, code, short URL, destination URL, created-at, expires-at, effective state, and resource version. Pagination is stable by created-at then ID with a cursor; clients do not supply internal database ordering expressions.

Disable requests contain disabled=true and the expected resource version; other mutation fields and disabled=false are unsupported in version 1. Active and expired links can be disabled. A repeated disable with the current version returns the current disabled resource without resurrecting it. A stale version returns 409; disabling a tombstone returns 410. Delete is an idempotent tombstone operation and succeeds for unknown/already deleted codes. Deletes do not reuse codes or erase retained aggregates. Effective metadata state precedence is deleted, then disabled, then expired, then active. A disabled/deleted link cannot be enabled, retargeted, or given a new expiry in this version.

### URL and code rules

- Accept absolute HTTP and HTTPS URLs with a host, up to 4,096 characters. Reject credentials in URLs, control characters, malformed ports, and unsupported schemes. Initial acceptance is valid ASCII hostnames, valid IP literals, and valid encoded paths. Internationalized domains must be supplied as valid ASCII punycode; Unicode host input is rejected with a field error. This avoids silently altering the destination during validation.
- Preserve path, query order, query encoding, and fragment. Do not normalize distinct destinations into one link. A fragment is stored in the destination but never used for server-side analytics.
- Reject destinations targeting this service's configured redirect route to prevent direct self-links. Arbitrary external redirect chains cannot be fully detected without fetching destinations; the prototype will not fetch URLs, previews, or target availability.
- The service cannot certify that an external destination is safe or reachable. For a public launch, add abuse reporting, takedown, quotas, and a reviewed destination policy. Private-address restrictions and domain allowlists remain a product decision; syntax validation alone cannot enforce them against DNS changes.
- Generate ten-character, case-sensitive Base62 codes using a cryptographically secure generator. This is uniqueness-oriented, not an authorization secret.
- Proposed custom alias: 4-32 ASCII alphanumeric, hyphen, or underscore characters; case-sensitive. Validate against the route namespace. Do not reuse expired, disabled, or deleted codes.
- Missing/null custom alias selects a generated code; an empty or whitespace-only alias is invalid. Reject leading/trailing whitespace in destination URLs rather than silently changing them. Validate redirect path length/characters before database lookup; a syntactically invalid code yields 404 without touching persistence.
- Use a database unique constraint for final collision arbitration. A pre-insert existence check alone is unsafe under concurrency. Retry generated-code collisions at most five times, each in a fresh valid transaction or an explicit conflict-safe database operation. A custom-alias collision returns 409 immediately.
- Same destination submitted twice produces two links unless the caller reuses the same idempotency key. Avoid implicit global destination deduplication because it changes ownership, expiration, and analytics semantics.

### Creation idempotency and lifecycle races

Support an optional Idempotency-Key for anonymous creation. Scope it to the public API operation and the key, limit its length, and retain completed request records for a proposed 24 hours. Recommend client-generated UUID keys. Do not use IP addresses as ownership or idempotency identity: addresses change and can be shared. Same-key collisions from unrelated anonymous clients are possible and follow the same payload-conflict rules. If identity is added at I6, new keys become principal-scoped with an explicit transition policy for old anonymous records. Store a hash of the canonical validated create request.

Canonicalization applies to request structure only: resolve omitted optional fields consistently, preserve the validated destination string, and represent expiration as a UTC instant. Do not reorder destination query parameters or reinterpret percent-encoding. Proposed key limit is 128 characters. Missing/empty keys are distinct: missing means ordinary creation; empty is invalid. Only successful committed creations are replayable; failed validation or rolled-back creation does not permanently consume the key. A bounded wait timeout returns 503 with Retry-After and the same key remains safe to retry.

Commit the idempotency record and created link together. Same key and same request replay the original success and resource; same key with different fields returns 409. Concurrent identical requests serialize through the unique key; use a bounded lock wait and return a retryable response if it cannot finish. A lost response after commit can be recovered by replay. A key replay after a link is later disabled or deleted still identifies the original created resource; it does not recreate it. After retention ends, a reused key is a new creation attempt.

Order matters: enforce body/structural limits and quotas, parse a stable request representation, and check an existing idempotency record before applying current-time creation rules. An exact replay returns the original success even when its expiration is now in the past. Apply future-expiration validation only for a new creation. Concurrent first requests use the transactional key arbitration described above before creating a second mapping. If authentication is later added, authenticate before looking up principal-scoped records.

Use an optimistic resource version for management changes. Concurrent disable/delete operations cannot restore or overwrite newer state accidentally. Destination edits and expiration extensions are outside the proposed initial contract. If selected, they need explicit versioned update rules and additional tests.

A redirect may resolve just before a disable/delete commits and still be issued. Guarantee that new database resolutions after the committed change observe it; do not claim instantaneous revocation of already resolved requests or previously delivered browser responses.

## 5. Data design

All tables have explicit primary keys, required fields, timestamps, and appropriate foreign keys. Use separate logical namespaces for product and workflow data. Transactions do not span a network or agent execution.

Store the demo database at an explicit configurable local path; keep its files out of source control, agent baseline copies, and generated artifacts. Use file-backed H2 in regular mode with case-sensitive code columns. Use standard relational types and portable JPA operations rather than PostgreSQL-specific JSON types or conflict syntax. Keep Flyway for schema versions; do not use drop-and-recreate startup behavior.

Configure zero database log write delay for the demo and restart tests, rather than relying on H2's default delayed flushing. Verify persisted committed state with an actual process restart; do not claim protection from every disk/power failure. Bound lock/query waits using database settings and JDBC mechanisms; do not interrupt a thread performing embedded H2 file I/O as a cancellation technique. Shutdown closes the pool/database cleanly where possible. An unavailable/corrupt/unwritable configured file fails startup/readiness with diagnostics; do not silently create a replacement empty database at a different path. Back up only through supported database facilities or after closing the database, not by copying an actively changing file.

| Entity | Essential fields | Constraints and indexes |
|---|---|---|
| Link | ID, code, destination, state, created-at, expires-at, deleted-at, version; nullable owner reference reserved for I6 | Unique code; creation/ID pagination index; valid lifecycle values; no invented anonymous owner |
| Create request | Scope (anonymous operation initially), key, payload hash, link ID, response metadata, retention deadline | Unique scope/key; retention index; identity scope added only after I6 decision |
| Redirect event | Event UUID, link ID, occurrence instant | Unique UUID; occurrence/retention index; no IP or user-agent stored |
| Link total | Link ID, counted events | One row per link; atomic increment |
| Daily count | Link ID, UTC date, counted events | Unique link/date; atomic upsert |
| Workflow run | ID, scenario, status, baseline, current plan revision, deadline/budget, version | Index on active status; immutable baseline reference |
| Plan and task | Plan revision, task ID, role, declared inputs/outputs, status, entry/exit gates | Versioned task identity; active-state lookup |
| Dependency | Plan revision, predecessor, successor | Unique edge; graph validated before activation |
| Attempt | Task identity, attempt number, claim/fencing token, lease, start/end, outcome | Unique task/attempt; active lease index |
| Artifact | ID, immutable version, content hash, location, producer attempt, input lineage | Immutable evidence reference; content verified before approval/use |
| Decision and approval | Actor, action, rationale, graph revision, artifact/input hashes, timestamp | Approval uniqueness for the exact reviewed evidence |
| Audit event | Run ID, ordered sequence, actor, transition/action, evidence references, timestamp | Unique run/sequence; append-only application privileges |

Keep link tombstones so old short URLs never silently acquire a new destination. Proposed analytics event retention is seven days; daily breakdown retention is 90 days; lifetime totals survive event pruning. Deleting a link disables redirection and does not imply erasing engineering audit records. Personal-data deletion and retention requirements need a separate decision if personal information is added.

For counters, create each link's lifetime aggregate with the link transaction. Insert-or-update daily rows with unique constraints and bounded transaction retry; do not rely on a PostgreSQL-specific upsert statement. Ready-task claiming uses an atomic status/version conditional update inside the service's single database, with a lease/fencing token; two scheduler threads cannot successfully claim the same task version. Verify the selected H2 concurrency behavior directly. External scale-out and sharing this embedded database file across service JVMs are outside the selected deployment model.

## 6. Analytics semantics and reliability

Analytics measure resolved GET requests for which the application attempts to issue a redirect. They do not prove that a browser received the response or visited the destination. HEAD requests, failed resolutions, and throttled requests do not count. Bots and repeated GET requests count; unique visitors, geography, and referrers are outside the initial scope.

After resolving the destination, enqueue a minimal event UUID, link ID, and UTC occurrence time. Use a bounded in-memory queue, proposed capacity 1,000, and a dedicated worker. Queue saturation drops recording and increments an observable dropped-events metric; it does not block the redirect. Recording is asynchronous and best effort, with no promise of zero event loss after a crash.

The worker atomically inserts the unique event and increments total and daily aggregates in the same transaction. A duplicate event does not increment again. This permits a bounded retry, including when commit acknowledgment was lost. Limit recording retries to two, then report/drop the event. Pending events lost on process termination may be uncountable; do not present the dropped-events metric as a complete loss count.

Return eventual-consistency semantics in documentation. Do not provide a precise freshness guarantee until measured. Resolve links using the embedded database initially. A database access failure prevents resolution and returns 503; analytics-only failures leave successful resolutions available. Separate executors and tightly bounded recording database waits reduce analytics contention, but a shared database/pool and local disk remain shared failure domains.

The analytics response distinguishes lifetime recorded total, requested-range recorded total, daily buckets, requested date range, and earliest retained UTC date. Read aggregates using one consistent database snapshot. The default range is the last seven UTC dates including today; permit a maximum of 90 dates within retained history and reject out-of-retention or future date ranges with 400. Fill retained dates with zero when there are no recorded events. Document that lifetime totals can exceed the sum of available history and that queued/lost events are excluded. In the selected initial access model, anyone can read analytics and metadata for disabled, expired, or tombstoned links; resolution returns 410. No unique-visitor claim is made.

Lossless analytics is outside the confirmed initial scope. If requirements change, replace this queue with a durable intake/outbox and explicitly choose whether redirect latency/availability can depend on its successful write. Adding a queue without a durable acceptance boundary does not make events lossless.

## 7. Agent workflow design

### Skills and executable control responsibilities

| Responsibility | Skills / Codex | Small executable control layer |
|---|---|---|
| Understand requirements and ambiguity | Interpret intent, ask questions, propose acceptance criteria | Store answers and normalized requirement versions; block unresolved mandatory questions |
| Architecture and decomposition | Propose design, task dependencies, risks, and affected modules | Validate and persist an explicit graph and its declared inputs/gates |
| Implementation and brownfield repair | Generate candidate changes inside the task's own workspace | Enforce workspace ownership and bounds; publish approved-path candidates |
| Testing and review | Propose tests, diagnose failures, review evidence and changes | Execute/verify actual deterministic checks against exact candidate versions; retain results |
| Re-planning | Propose a revised graph after upstream changes | Activate validated revisions; invalidate affected work and stale approvals |
| Approval, retries, and recovery | Explain proposed actions and recovery choices | Check identity and evidence hashes; persist limits; stop/reconcile uncertain execution |
| Handoff | Produce rationale, trade-offs, setup guidance, final report narrative | Supply authoritative run history, metrics, artifact lineage, and validation evidence |

Planned role skills: requirement clarification, architecture/decomposition, implementation/repair, verification/review, and handoff. Include only project-specific guidance; use supporting references for detailed contracts and policies. No skill scaffolding or executable helpers are created before design freeze. Review these role boundaries before deciding whether separate skill files or one skill with role references is simpler.

Each task records the selected skill name, version/content hash, required reference hashes, and input artifact hashes. A material skill/reference change triggers impact review and invalidates affected task evidence and approvals, just as a requirement change does. Copy the exact reviewed skill version into the attempt's approved inputs; do not let implicit discovery silently choose a changed revision.

Skills describe how to work, but executable tools determine whether consequential operations are allowed and whether evidence satisfies a gate. Existing Codex sandbox and process capabilities are reused. Add only the durable graph state, version checks, limits, evidence capture, and recovery controls the assessment requires. Supporting scripts are allowed where they supply a deterministic check; there is no separate custom agent reasoning engine. Skills are not a replacement for the explicit dependency graph or persisted execution evidence.

### Graph, gates, and non-linear execution

Persist an explicit dependency graph for each plan revision. Before activation, reject cycles, missing dependency targets, duplicate task IDs, missing required gates, and undeclared artifact inputs. Changes create a new immutable revision; do not mutate the historical graph.

The normal path is requirement normalization, ambiguity resolution when needed, requirement approval, architecture/task decomposition/risk analysis, design approval, implementation tasks, synchronized validation, engineering review, release-readiness report, and final artifact approval.

Within this path, independent tasks can run concurrently. For example, an approved API contract enables documentation and test specification while schema and service work proceed. Validation branches include unit/integration tests, API documentation verification, and security/change-policy checks. A join gate requires every mandatory branch to pass against the same artifact and input versions. Documentation generation alone cannot stand in for verification of the implemented API.

Non-linear behavior is explicit:

- A failed test opens a bounded repair task, then repeats affected verification.
- A changed requirement creates a new plan revision, invalidates affected descendants and approvals, and preserves reusable unaffected evidence only after checking its declared inputs and policy.
- An ambiguous request waits for a human answer; it cannot silently turn an assumption into an approved requirement.
- A policy failure cannot be bypassed by marking a downstream task complete.
- Final output is a validated artifact/report, not a claim that deployment occurred.

For a change whose impact cannot be determined confidently, invalidate a broader dependent portion of the graph. Explicit inputs and traceable dependency reasoning are prerequisites for selective reuse.

### Run and task states

Run states: pending, running, awaiting human input/approval, recovering, succeeded, failed, safely stopped, cancelled. Task states: pending, ready, running, awaiting approval, succeeded, failed, blocked, invalidated, cancelled. Completion means mandatory gates passed and final artifact approval matches the current revision.

Claim ready tasks transactionally using a lease and fencing token; commit the claim before doing external work. Execute outside the transaction. Heartbeat long tasks. Accept results only when their plan revision, task state, input hashes, and fencing token still match. A late completion from an invalidated plan cannot release the join gate or overwrite newer artifacts.

Initial bounds: two workers, a 30-second renewable lease, a 5-second heartbeat, a 10-minute agent invocation limit, a 5-minute deterministic verification limit, and a 60-minute active-execution budget per run. Human-approval waiting is reported separately and does not use active-execution time; its maximum wait is 12 hours, after which the run safely stops rather than treating silence as approval. Enforce at most three transport attempts per task and two repair cycles. Persist consumption and remaining limits so a restart cannot reset them. Report provider token usage where available; no hard monetary/token-generation cap is claimed unless the CLI actually enforces one.

### Agent adapters and trust boundary

The small control layer owns ready-task dispatch and governance; skills own engineering procedures. An adapter receives a reviewed skill version and declared inputs and returns structured proposals/artifacts/evidence. A language model does not decide whether its own output is approved, whether tests passed, or whether a tool action is permitted.

Recommended real adapter: the installed Codex CLI, invoked by a Java process adapter from a fixed configured executable path with separate argument values and task input supplied through stdin. Reuse the existing local ChatGPT sign-in through Codex's supported authentication path; do not read, copy, expose, or store login credentials in application tables. CLI presence and sign-in are verified; model entitlement, network operation, and execution success still require a bounded invocation after design freeze.

Read-only analysis/review tasks use a read-only sandbox. Approved generation tasks use workspace-write in their attempt-specific workspace, with explicit permissions and no approval/sandbox bypass flags. The Java engine stores the Codex thread/invocation identifiers, consumes bounded JSONL execution events, and validates structured final output. A successful process exit or model message alone cannot satisfy a test gate: run deterministic verification independently and associate it with the exact candidate hash. Record the CLI version, effective role/model if available, exit/turn status, invocation timestamps, and reported token usage. Do not invent monetary cost from token counts; report cost as unavailable for subscription execution when it is not supplied.

Use the user's configured model rather than inventing a model override. Proposed per-attempt event/output limit is 20 MiB, with a 1 MiB per-event limit; exceedance safely stops capture/execution and preserves the evidence already stored. Login expiry, quota exhaustion, unsupported permissions, or a required approval that cannot be represented through the adapter cause a clear waiting/safe-stop outcome. The engine never enables sandbox bypass to make a task pass.

Keep engineering assistance' assistance in building this service distinct from runtime evidence: the delivered scheduler must actually dispatch and track its own agent tasks to demonstrate orchestration. Deterministic adapters remain useful for tests and fault injection, with simulated results labeled as such. Do not automatically expose the assessment PDF or unrelated local files to tasks. Official references: https://learn.chatgpt.com/docs/non-interactive-mode and https://learn.chatgpt.com/docs/auth .

Workers can read/write only the orchestrator-owned copy through permitted operations. Build/test execution uses fixed argument templates, bounded runtime, filtered environment, and recorded exit status/output hashes. Avoid arbitrary shell text generated by an agent. Treat source files, assessment text, and external model output as untrusted data rather than instructions that override policy.

Every attempt gets its own workspace initialized from an immutable, recorded input snapshot. Parallel workers never edit the same mutable tree. A worker proposes a candidate patch/artifact against that exact baseline; the Java engine verifies permitted paths and changes and publishes the immutable candidate. A separate merge/review step combines compatible outputs and reports conflicts for re-planning. All final validation branches run against the same combined candidate. Compensation affects only the failed attempt's workspace. A stale or cancelled attempt can continue producing files only in its abandoned workspace, and its fenced result cannot enter the active plan. Validate relative paths and prevent symlink escapes when importing generated files.

An application-level allowlist is not complete OS isolation: Maven plugins and tests can execute code. Codex workspace-write constrains writes but does not by itself prove complete read/network isolation. For this local assessment, operate only on approved project copies, keep credentials and live database files out of build/test environments, and record the effective sandbox limits. A stronger deployment needs OS/container isolation for untrusted code. That is separate from tonight's embedded database choice; Docker is not a local-demo prerequisite.

### Approvals and change control

Proposed mandatory gates: normalized requirements and design before code generation, high-impact proposed actions, and final validated artifacts. A single requirements/design approval can bind both reviewed artifacts and their plan revision; routine approved implementation/testing tasks then run without per-file approval. Clarifications that materially change that approved scope require a new gate. Approval includes identity, time, rationale, graph revision, relevant artifact hashes, hashes of declared inputs, and the relevant skill/reference versions. Any material change invalidates the relevant approval. An approval to proceed once is not a permanent approval for all future graph revisions.

Protected API operations create/read runs, provide clarifications, approve/reject exact evidence, cancel execution, and inspect reports. Proposed paths are /api/v1/workflow-runs and their /clarifications, /approvals, /cancel, /events, and /report resources. Approval state changes use a resource version to reject stale concurrent decisions. These are control-plane endpoints, not a public agent-command API.

Model output cannot approve an action. Rejected gates preserve evidence and await a revised proposal or end the run, according to policy. Combining approved task outputs within an orchestrator-owned candidate is permitted through the controlled merge/review step. Merging into the user's repository or an external branch, publishing, deployment, secret access, destructive database changes, and sending messages to external parties remain disabled unless explicitly authorized and supported by a concrete reviewed action.

### Retry, fallback, cancellation, and rollback

- Transient provider/transport failures: at most two retries with bounded exponential delay and jitter, honoring provider retry guidance within the deadline.
- Validation failures: at most two repair cycles, each producing new versioned evidence. Never retry a failed deterministic test indefinitely without a changed candidate.
- Fallback: an alternate adapter is eligible only if preconfigured, approved for the same data, and subject to the same input/output and action policy. Otherwise stop with evidence; do not invent a fallback result.
- Deadline or an enforced attempt/time/concurrency limit, repeated policy failure, or unavailable required dependencies produce a durable safe stop with a reason and resumable evidence. Token/cost caps apply only when a future adapter provides dependable enforcement; they are not claimed for tonight's subscription-backed CLI.
- Cancellation stops new claims, signals running work, terminates owned child processes where supported, and rejects late results. If execution cannot be stopped conclusively, record the outcome as unknown and prevent downstream action.
- Store process identity with its creation time/invocation identity, not just a PID, because PIDs can be reused. Reconcile and terminate the owned process tree before reclaiming its workspace. If identity or termination is uncertain, quarantine the attempt workspace and stop; do not kill an unrelated process or dispatch another writer into it.
- Recovery after process restart checks expired leases, attempt outcomes, and workspace/artifact hashes. Retry only steps proven idempotent or reconciled safe to repeat. Reconcile potentially consequential actions before replay; unknown outcome is not equivalent to failure.
- Before invoking Codex, persist the invocation ID, attempt identity, request/input hash, workspace identity, reserved attempt slot, concurrency slot, and execution deadline. A generation request is not assumed idempotent: retrying can change its output and consume additional subscription usage. After interruption, retrieve/reconcile the original result where supported; otherwise safely stop or request a reviewed bounded retry. Preserve attempt/time consumption, report unknown token usage and monetary cost as unavailable, and do not recycle or clean a workspace until its original process is confirmed terminated. Known provider failures still follow the bounded retry policy. Monetary/token reservations belong only to an adapter that can actually measure and enforce them.
- Workspace compensation restores or discards only orchestrator-owned changes from a recorded snapshot. Never reset or overwrite the user's original checkout.
- Migration and deployment rollback are not automatically covered by workspace restoration. Destructive migrations need backup/restore planning and separate approval. The prototype produces readiness evidence and does not claim to demonstrate production deployment rollback.

DB state transitions and their audit events commit together. File artifacts are staged immutably before a database reference is finalized; validate hashes on read. A crash can leave an unreferenced staged file, which is safe to reconcile later. It must not create a database success record pointing to missing evidence.

## 8. Security, privacy, and operations

Link creation, management, and analytics are anonymous by user decision. Product login, ownership, and registration are parked until I6. Proposed workflow/operations authentication uses a configured operator credential outside source and logs; human approvals remain explicit. If product identity is selected at I6, define stable identities, ownership, treatment of existing anonymous links, and preferably an external OIDC provider rather than introducing ad hoc accounts.

Apply request body limits and bounded pagination/date ranges. Initial configurable local limits: 30 creations per minute per client address, 300 creations per minute globally, 60 management writes per minute per address, and four workflow-run creations per hour per operator. Use bounded limiter storage with idle expiry; do not persist raw client addresses as analytics data. Anonymous client-address throttling is an abuse control, not identity: document its limitations for shared proxies and changing addresses. These single-instance limits are local and must be described as such. Trust forwarded client headers only from configured proxies. Keep the prototype bound to loopback by default; broader hosting is a separate release decision.

Do not log full destinations, query strings, tokens, prompt secrets, or raw personal identifiers. Operational logs include correlation ID and, for workflows, run/task/attempt/revision IDs. Durable lineage is stored separately from logs.

The append-only event table records decisions and artifacts, and demonstrates traceability. Database administrators can still alter local storage. It is not independently tamper-proof, audit-certified, or proof of regulatory compliance. A stronger deployment would need role separation, restricted credentials, retained evidence, and independently protected audit storage.

Public health exposes minimal state. Detailed health and metrics are protected. Database unavailability fails readiness; liveness indicates whether the process itself can continue. Do not expose management endpoints or raw diagnostic output publicly.

Leave the H2 web console and TCP server disabled in the default demo profile. Database introspection, if needed, is an explicit local diagnostic step; it is not part of the public link API.

Use short dependency timeouts, bounded executor queues, and bounded database pool usage. No remote destination check occurs during creation or redirect. Serving from a local cache is deferred; adding one requires invalidation, expiry, revocation, and stale-read policies.

Proposed initial validation envelope: 100,000 links, 100 requests/second, and predominantly redirect reads. Proposed local target: redirect p95 below 150 ms without injected failures. These are design assumptions and test targets, not established capacity or availability guarantees. Record hardware, concurrency, dataset, duration, and analytics loss when testing.

## 9. Scenario and verification matrix

| Scenario | Expected behavior | Required evidence |
|---|---|---|
| Valid creation and redirect | Stored mapping; 201 then 302 with preserved destination | API and H2 integration test |
| Malformed URL, credentials, bad scheme, control characters, bad expiry | 400; no partial insert | Parameterized validation and API tests |
| Same destination, separate requests | Separate codes | Integration test |
| Generated collision | Bounded retry; one unique mapping per code | Controlled generator and real-database constraint test |
| Concurrent custom alias requests | Exactly one create; others conflict | Concurrent real-database test |
| Same idempotency key, same/different payload | Replay original / reject mismatch | Sequential and concurrent integration tests |
| Exact replay after original expiration passes | Replay original success without creating another link | Controlled clock and idempotency integration test |
| Commit succeeded but create response lost | Replay discovers original resource | Idempotency integration test |
| Unknown, expired, disabled, deleted link | 404 / 410; no new code reuse | API and injected-clock tests |
| Expiration at exact boundary | Expired at equality | Controlled clock test |
| HEAD or unsuccessful resolution | No count | API and analytics tests |
| Concurrent management/redirect requests | Defined lookup race; no state resurrection | Version/conflict and concurrency tests |
| Analytics duplicate or lost commit acknowledgment | One aggregate increment per event | Transaction/deduplication integration test |
| Analytics retention/deleted-link query | Lifetime/range semantics and public retained history are consistent | Retention-boundary and API tests |
| Analytics queue full or recorder failure | Redirect remains available; observable loss | Fault-injection test |
| Process dies with queued analytics | Possible documented loss; durable counts remain | Restart demonstration |
| Product database unavailable | Bounded 503; readiness fails | Dependency failure test |
| File-backed startup/restart and locked/unwritable file | Committed state persists; explicit failure without empty replacement database | Temporary file-database and process restart tests |
| Anonymous product and protected workflow calls | Link operations work without login; workflow/operations enforce 401/403 and no unauthorized mutation | Security API tests |
| Invalid graph or missing mandatory gate | Plan rejected before execution | Graph invariant tests |
| Independent graph branches | Parallel execution; join waits for all required results | Controlled-worker integration test |
| Transient agent failure | Bounded retry without resetting budgets | Controlled adapter and recorded attempt evidence |
| Failed verification | Repair then re-verification, or safe stop at bound | Run report and task history |
| Upstream change | New revision; affected artifacts and approvals invalidated | Revision and lineage test |
| Material skill/reference change | Exact versions retained; affected work and approvals invalidated after impact review | Skill lineage and revision test |
| Old worker completes after invalidation | Result cannot satisfy current gates | Fencing test |
| Stale approval or modified artifact | Rejected approval/use | Hash and version tests |
| Restart during task execution | Reconcile lease/outcome; resume safely | Restart demonstration with persistent database |
| Restart during an AI call with unknown completion/usage | Preserve attempt/time reservation and workspace; reconcile or safely stop; no invented cost | Controlled adapter and restart evidence |
| Crash between artifact staging and DB commit | No falsely successful task or missing evidence reference | Artifact reconciliation test |
| Cancel or run budget exhausted | No new execution; pending high-impact work blocked | Controlled-worker tests and safe-stop report |
| Uncertain external action outcome | Await reconciliation; no blind replay | Fault-injection evidence |
| Failed workspace mutation | Restore owned changes; original checkout unaffected | Compensation test using disposable workspace |
| Attempts to bypass policy via model output | Server denies unauthorized actions | Adversarial adapter tests |

Mock external provider boundaries with Mockito; use real H2 for uniqueness, concurrent claims, and atomicity, and temporary file-backed H2 for process restart/recovery behavior. In-memory tests alone cannot prove restart persistence. These tests validate the selected H2 deployment and do not claim PostgreSQL portability. Avoid tests that merely mirror implementation details. Add a short end-to-end scenario rehearsal and representative load check after correctness tests pass.

## 10. Assessment demonstrations

1. Greenfield: normalize a request to build a shortener with basic analytics, approve requirements/design, execute dependency-aware tasks, synchronize independent validation branches, and produce actual reviewable artifacts and passing evidence. If actual generation is unavailable, show the same workflow as an explicitly labeled simulation, not autonomous engineering.
2. Brownfield: use a disposable project revision containing an expired-link handling defect. Analyze impacted modules, add a regression test, generate a fix, verify the repaired artifact, and demonstrate bounded retry/restart recovery. Do not intentionally insert the defect into the final service or modify the user's checkout to stage the demonstration.
3. Ambiguous: receive 'make links permanent and improve analytics.' Ask whether permanence means no expiration, an immutable destination, or a permanent redirect, and what analytics means. Await clarification, create a new plan revision, invalidate affected evidence/approvals, demonstrate stale-approval rejection, and show one exhausted recovery path safely stopping.

Each report includes normalized requirements, assumptions, graph revisions, decisions, approvals, producer/input artifact lineage, test results, policy outcomes, attempt history, terminal state, observed metrics, and limitations. Reports distinguish actual executions, injected faults, and simulations.

Metric definitions: success rate is successful terminal runs divided by successful, failed, and safely stopped terminal runs; report cancellations separately. Retry frequency is retry attempts divided by all attempts. Compensation frequency is compensated runs divided by runs with workspace mutations. End-to-end latency includes approval wait, with active execution and human wait also reported separately. Recovery time runs from failure detection to restored successful progress for recovered failures only; show not available when no recovery completes. Three demonstrations provide observations, not reliable long-term reliability statistics.

## 11. Checkpoints and sequencing

Checkpoint timeboxes are sequencing guidance.

| Checkpoint | Proposed timebox | Exit evidence | Dependency |
|---|---|---|---|
| D1: scope and execution boundaries | 10 minutes | Full assessment and anonymous access confirmed; Codex assistance confirmed; proposed runtime CLI integration and local delivery boundary reviewed | User decisions recorded; runtime path discussed |
| D2: contracts and behavior | 15 minutes | User selected aliases, optional expiry, disable/delete, fixed targets, best-effort total/daily counts, APIs/docs/reports; detailed contract reviewed | D1 |
| D3: data and reliability | 15 minutes | Entities, uniqueness, idempotency, transactions, races, retention, failure behavior agreed | D2 |
| D4: workflow and governance | 20 minutes | Skill/control responsibilities, versioned graph, approvals, repair bounds, restart/reconciliation, sandbox and lineage agreed | D1 and D3 |
| D5: review and design freeze | 10 minutes | Scenario matrix reviewed; unresolved decisions closed or explicitly scoped; implementation sequence authorized | D1-D4 |
| I1: infrastructure and persistence | 30-45 minutes after D5 | Repeatable application start with embedded file-backed H2, migrations/constraints and restart persistence verified, bounded Codex invocation and sandbox behavior checked | Implementation authorization; Java/Maven and Codex setup |
| I2: product vertical slice | 90-120 minutes | Anonymous creation/management, aliases, redirect, expiry, total/daily analytics, API evidence | I1 |
| I3: orchestration vertical slice | 120-180 minutes; independent of I2 where inputs permit | Versioned skills, small control layer, persisted graph execution, real tasks, gates, parallel work, versioned artifacts and reports | I1 and executable adapter verification |
| I4: recovery and scenarios | 60-90 minutes | Three scenario reports, restart, invalidation, bounded safe stop, compensation evidence | I2/I3 |
| I5: reviewable handoff | 30-45 minutes | Setup instructions without Docker/database installation, contracts, verification results, H2 deployment limits and future database migration considerations, release-readiness report | I4 |
| I6: reconsider link access and ownership | Final checkpoint requested by user | Review anonymous creation, public listing/analytics, who can disable/delete, abuse controls, and transition of existing links; record decision before any wider release | Runnable demo and reviewable handoff; user product decision |

If there are fewer than three hours for the entire exercise, do not imply that every production concern and a generalized autonomous SDLC engine can be implemented faithfully. Agree which demonstrations are real and which are simulations before building. Preserve the mandatory governance behaviors and explicitly record any reduced product scope.

Selected overnight scope: anonymous creation/management, generated codes and custom aliases, public redirects, optional expiry, fixed targets, best-effort recorded totals and daily counts, retention cleanup, OpenAPI documentation, and saved workflow reports. Workflow scope is skill-led, with a small executable control layer providing durable graph execution for real Codex tasks, exact-version human approval, parallel validation, bounded repair, invalidation/stale-result rejection, and restart/safe-stop evidence across the three assessment scenarios. No separate dashboard. Product identity is deliberately parked until I6. The complete design must be reviewed at D5. If real-execution prerequisites fail, resolve the deliverable honestly with the user rather than silently substituting simulation.

## 12. Completion and implementation boundary

Design checkpoints D1-D5 are complete. User scope answers, contracts, verification scenarios, execution boundaries, and final approval gates are recorded. The user's confirmation freezes this baseline and lifts the no-code boundary. Runtime invocation, dependency compatibility, and persistence remain implementation prerequisites to verify at I1.

Freeze evidence: assessment read in full; full overnight/full-assessment scope, anonymous access, selected link features, best-effort total/daily counts, API/documentation/report interface, skills with a small orchestration control layer, H2 single-process trade-off, and workflow approval boundaries confirmed. Java 21, Maven, Codex CLI, and ChatGPT sign-in were verified. No source tree existed at approval. Implementation follows checkpoints I1-I5; I6 retains the requested final access/ownership review before any wider release.


## 13. Recorded implementation refinements

The frozen baseline above remains the approved design. The implementation uses focused Spring JDBC transactions for both product and workflow persistence instead of adding JPA. Explicit SQL makes H2 uniqueness, conditional claims, version fences, and analytics atomicity visible; the delivered contracts and selected database stay the same.

Each revision supports one candidate-writing implementation task, followed by parallel read-only verification and review. Unsupported extra writing branches are rejected. The human approved the narrower isolated domain fixtures for the three live assessment scenarios; the delivered REST/H2 application has separate product and HTTP integration evidence.

App subagents use GPT-6.1 medium. The installed CLI rejected that model, so the human authorized supported GPT-5.6 Sol medium for actual runtime demonstrations. Handoff questions are retained for the final human gate; other unresolved agent questions pause for clarification. A recovered revision preserves its failed history and requires fresh design approval.


## 14. Approved API simplification — October 2, 2026

The user approved removing six business HTTP operations while retaining the explicit HEAD handler. This supersedes earlier endpoint and pagination proposals: there is no list-all-links API or cursor pagination. `GET /api/v1/links/{code}` returns nested `link` metadata and `analytics`; optional `from`/`to` query parameters retain daily range selection. Create/disable/delete/redirect behavior stays as previously defined.

Workflow GET status, events, report, and evidence retrieval are consolidated into `GET /api/v1/workflow-runs/{id}`, including hash-validated review evidence. Public graph editing is removed; fixed definitions and clarification/repair revisions remain inside the engine. The other operations are create, approvals, clarifications, and cancellation. There are eleven business HTTP operations, counting explicit HEAD. `/actuator/info` is no longer exposed; health, metrics, and API documentation remain.


## 15. Approved repository extraction — October 2, 2026

The user approved concrete `LinkRepository`, `AnalyticsRepository`, and `WorkflowRepository` classes to separate storage from service decisions. Repositories own SQL, row mapping and conditional database operations. Services retain business rules and existing transaction boundaries. All repositories share the existing Spring-managed H2 datasource and transaction manager; API contracts and migration versions are preserved. Persistence remains Spring JDBC.


## 16. Approved validation simplification — October 2, 2026

The user approved delegating generic URL/hostname/IP syntax checks to Apache Commons Validator and request field constraints to Jakarta Bean Validation. A small destination validator retains application policy: ASCII HTTP(S) destinations, no credentials or empty/zero port, and no redirect back into the same origin's decoded `/r/` route. Instance-local suffix configuration allows syntactically valid private/test domains without a public-registration requirement or global library mutation. Fragments, encoded destinations and double path slashes remain supported; destinations are stored unchanged and never fetched. Generic syntax now follows the library (for example, malformed/leading-zero IPv4 literals and paths traversing above the root are rejected).

Expiry remains a service decision after idempotency replay, rather than a `@Future` request constraint: a retained original creation must replay even after its expiry timestamp has passed. Controllers and direct service calls validate the same DTO constraints. Existing link error codes are preserved; annotation failures also identify affected fields in the problem response. Migration versions and workflow approval gates are unchanged.


## 17. Approved Spring simplification — October 2, 2026

With a thirty-minute submission window, the human approved replacing workflow configuration boilerplate with a Spring-bound record and delegating standard MVC exception handling to `ResponseEntityExceptionHandler`. Configuration scanning registers the record; `@DefaultValue` preserves defaults and its compact constructor retains numeric clamping and supported reasoning values. Spring now supplies MVC exception dispatch, statuses and headers, while the application retains its error codes, correlation IDs, validation field errors and domain/storage exception responses. No dependency or migration was added. Existing test constructors were adapted and compiled; application tests remain paused.
