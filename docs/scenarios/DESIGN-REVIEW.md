# Revised scenario design review

The human approved all three revised fixture designs below on October 2, 2026 after reviewing a concise chat summary, and authorized their fixture tests. Execution and final candidate approval have completed; all three runs are now `SUCCEEDED`. The summaries come from actual GPT-5.6 Sol medium requirements and architecture attempts. Final full-application verification passed all 87 tests after the latest changes.

The first greenfield and brownfield candidates passed verification, then stopped because expected final-review questions were incorrectly classified as fatal. That controller defect is fixed and tested. Recovery retains the failed attempts, evidence, and consumed budgets, and requires fresh design approval. The ambiguous run retains the human clarification and its revision history.

## Greenfield

Confirmed contract: implement only the isolated in-memory demo.LinkBook fixture; preserve the supplied pom.xml and acceptance tests; keep destinations immutable; reserve every created code permanently, including after deletion; reject duplicate creation with IllegalArgumentException; support optional expiration with equality considered expired; resolve only existing, enabled, undeleted, unexpired links; treat record=false as HEAD and increment analytics only for successful resolutions with record=true; retain counts after disable/delete; expose total counts and UTC LocalDate daily buckets; return zero counts for unknown codes. No HTTP server, database, destination fetch, external dependency, deployment, or production-service reconstruction is in scope. Observable acceptance criteria are the behaviors above plus a successful future mvn verify run. This requirements-only invocation made no file changes and did not claim Maven verification.

Proposed architecture: implement demo.LinkBook as one process-local in-memory domain object backed by a code-to-entry map. Each retained entry stores the immutable destination, optional expiration Instant, disabled/deleted lifecycle state, cumulative recorded-resolution count, and a map of UTC LocalDate buckets. Creation rejects any already-present code, so deletion changes state but never removes the reservation. Resolution performs eligibility checks before recording: missing, disabled, deleted, or now greater than or equal to expiresAt returns Optional.empty(); otherwise it returns the original destination and, only when record=true, increments the total and the UTC date derived from now. total and daily return zero for unknown codes, while retained entries preserve analytics after disable or delete. This is deliberately a domain-only fixture: no HTTP layer, persistence, destination fetch, external dependency, deployment, or reconstruction of the full service is proposed. The supplied pom.xml and acceptance tests remain unchanged. This architecture-only invocation wrote no files, did not implement source code, and does not claim that Maven verification passed.

Scope: isolated in-memory domain fixture; preserve the supplied POM and acceptance tests. No new REST service, database, dependency, or deployment. Independent verification and review must join on the same candidate; final approval remains separate.

Approved design binding (version at approval): run `e0cd39a1-e027-447e-b436-8101fcf2f89f`, revision `2`, version `7`, design gate hash `c053d0835f1539e60280d9445a9395c5df637e1a7158f24182e8f6af41706421`.

[Full current persisted report](reports/greenfield.json) · [Versioned evidence](reports/greenfield/revision-2/)

## Brownfield

Confirmed requirement: within the isolated in-memory demo.LinkBook fixture, a link is expired when now is equal to or later than expiresAt. resolve(code, now, record) must return Optional.empty() at that boundary and must not increment total or UTC daily counts. Preserve immutable destinations, null meaning no expiration, permanent code reservation after disable/delete, record=false behavior, retained counts, unknown-code zero counts, UTC daily buckets, synchronization, the supplied POM, and all supplied acceptance tests. No live service, database, deployment, destination fetch, or production change is in scope. This requirements role made no file changes and does not claim Maven verification passed.

Proposed architecture: retain the existing synchronized, in-memory demo.LinkBook domain model and make one behavioral change in resolve: when expiresAt is non-null, reject the link unless now is strictly before expiresAt. This places equality and later instants on the expired path before total or UTC daily counters can mutate. Preserve the public API, immutable destinations, optional expiration, permanent code reservation, disable/delete behavior, record=false behavior, retained and unknown-code counts, UTC bucketing, supplied POM, dependency versions, and acceptance tests. This is a fixture-only proposal; no REST service, persistence, live system, deployment, or external action is included. No files were changed and Maven verification is not claimed.

Scope: isolated in-memory domain fixture; preserve the supplied POM and acceptance tests. No new REST service, database, dependency, or deployment. Independent verification and review must join on the same candidate; final approval remains separate.

Approved design binding (version at approval): run `cf5243dd-3640-4d95-aa6d-0a83a509b7a3`, revision `2`, version `7`, design gate hash `009c2715c22c042abd34f70015d8b84e8b05e208cd7fac8d5372a0acb85fa31a`.

[Full current persisted report](reports/brownfield.json) · [Versioned evidence](reports/brownfield/revision-2/)

## Ambiguous

Requirements are fully clarified for the supplied LinkBook fixture. “Permanent” means each code remains permanently reserved, including after deletion, and its destination never changes; links may optionally expire, with expiration beginning when now equals expiresAt. A resolution succeeds only for an existing, active, non-deleted, non-expired link. Analytics count only successful resolutions with record=true: expose a lifetime total and UTC-calendar-day counts based on the supplied resolution instant. record=false (HEAD), missing links, expired links, disabled links, and deleted links do not increment analytics. Disabling or deleting a link preserves its existing counts. Unknown codes report zero. Preserve the fixture pom.xml and supplied acceptance tests. Scope excludes an HTTP server, persistence system, destination fetch, external dependencies, deployment, and changes outside artifacts/requirements/.

The smallest proposed architecture is the existing synchronized, in-memory `demo.LinkBook` domain model. Each reserved code owns one immutable destination, optional expiry, active state, lifetime count, and UTC-date count map. Resolution performs eligibility checks before recording, with expiry defined as `now >= expiresAt`; only successful `record=true` resolutions update both counters atomically. Disable and delete retain the record so its code and analytics remain preserved. No HTTP, database, external dependency, destination fetch, or deployment component is warranted. The supplied implementation appears structurally aligned with this design, but actual Maven verification remains an execution gate.

Scope: isolated in-memory domain fixture; preserve the supplied POM and acceptance tests. No new REST service, database, dependency, or deployment. Independent verification and review must join on the same candidate; final approval remains separate.

Approved design binding (version at approval): run `fe5d1047-93ba-4e5a-82a0-a91cd4c1c873`, revision `2`, version `3`, design gate hash `e8ceb135d5168efa0820b9324e165c59cad79f9e0daa59fc9acb8b67b2f38fff`.

[Full current persisted report](reports/ambiguous.json) · [Versioned evidence](reports/ambiguous/revision-2/)

## Recorded decision

All three revised designs were approved for implementation in their owned fixtures, preserving POMs and supplied acceptance tests. Fixture verification is authorized. This decision does not approve final candidates or wider release.
