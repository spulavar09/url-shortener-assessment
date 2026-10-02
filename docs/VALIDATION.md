# Validation and checkpoint status

Final Maven verification passed all 87 application tests with zero failures, errors or skips and packaged the current source using Java 21.0.12 and the pinned Maven 3.10.0 wrapper. This includes logging/JavaDoc edits, the configuration record, Spring HTTP error handling, and both parallel-failure clarification regressions. The packaged service and all three existing exact scenario candidates were rechecked independently. No new AI generation or human approval was inferred.

| Evidence | Result | Scope |
|---|---|---|
| [Automated tests](validation/automated-tests.json) | 87 passed after all latest changes, zero failures/errors/skips | 28 product/analytics, 27 URL-validation cases, 28 workflow, four HTTP integration tests with multiple contract assertions |
| [Scenario rechecks](validation/scenario-reverification.json) | 9 passed; baseline defect reproduced as expected | Three exact candidates in disposable copies; original hashes, POMs and acceptance tests preserved |
| [API simplification](validation/api-simplification.json) | PASS | Packaged service; removed routes absent, combined responses usable, existing workflow gate bindings and hash-validated evidence preserved |
| [Repository extraction](validation/repository-refactor.json) | PASS | Existing SQL/transactions retained; cross-repository rollback verified; existing links and workflow gates remain accessible |
| [Process restart](validation/process-restart.json) | PASS on final package | Actual stop/start preserved committed link metadata, recorded counts and waiting workflow state |
| [Locked database](validation/locked-database-startup.json) | Expected startup failure | Second instance refused the already-owned H2 file |
| [Unusable database path](validation/unusable-database-path-startup.json) | Expected startup failure | Parent path was a regular file; this is not an ACL or corruption test |
| [Brownfield baseline](validation/scenario-reverification-brownfield-baseline.log) | One expected acceptance failure | Fresh independent run reproduced the planted defect; repaired candidate passed |
| [Redirect measurement](validation/redirect-smoke.json) | 1,000/1,000 returned 302; p95 19.2 ms | About 100 requests/second for ten seconds, loopback, one link, no active AI generation |
| [Redirects with active workflow agents](validation/redirect-smoke-with-active-workflows.json) | 1,000/1,000 returned 302; p95 171 ms | Earlier sample exceeded the proposed 150 ms latency target under background engineering activity |

The latest redirect measurement used the final package; the active-agent measurement is retained as historical evidence. Neither sample establishes capacity for 100,000 links, remote clients, prolonged load, or crash loss. Analytics are best effort; both observed samples recorded all 1,000 resolutions, which does not guarantee zero loss in other conditions.

All three revised scenario designs were explicitly approved by the human. Implementation, independent fixed Maven verification, review, join and handoff have now completed: each fixture passed three tests (nine passes total), and final independent rechecks passed the same nine tests. The deliberately broken brownfield baseline again produced exactly its expected expiry-equality failure. Final candidate approvals were explicitly granted and recorded on October 2, 2026; all three runs are now `SUCCEEDED`. Greenfield and brownfield first-revision candidates passed real verification, but handoff questions triggered a controller stop. That handling was fixed; the failed history remains and conservative recovery required a new design approval, which the human has now granted. Ambiguous includes the explicit human clarification. Full current and versioned reports are under [scenario reports](scenarios/reports/), with the review decision in [scenario design review](scenarios/DESIGN-REVIEW.md).

| Checkpoint | Current status |
|---|---|
| D1-D5 design | Complete; subsequent API simplification explicitly approved |
| I1 infrastructure/persistence | Complete for the selected local H2 deployment |
| I2 product | Implemented and verified after API cleanup and repository extraction |
| I3 control layer | Implemented and verified; fixed workflow definitions, consolidated reporting and repository persistence |
| I4 live scenarios/recovery | Control fault tests and actual restart complete; All three revised fixtures completed verification/review/handoff; final human approval recorded; runs SUCCEEDED |
| I5 handoff | Setup/API/evidence documentation available; Submission archive and exact candidates available; final human approval recorded; runs SUCCEEDED |
| I6 link ownership/access | Parked user decision before wider release |

The H2 deployment remains single-process. Fault-injected workflow tests demonstrate bounds and safety behavior; they are not model reliability statistics. Monetary cost is unavailable for the signed-in subscription CLI. No candidate was merged into the original checkout or deployed.

The workflow fix preserves `AWAITING_INPUT` when a parallel verifier fails after reviewer questions. Both ordinary and transient failure regression cases passed in the final full test run: failure evidence is retained, the revision and question remain unchanged, and automatic retry/repair waits for the human answer.
