# Assessment rehearsals

The delivered application is a complete Spring REST service with H2 persistence. These three disposable domain projects make the orchestration mechanics inspectable without repeatedly generating a second application. Reports distinguish that narrower fixture scope from product integration evidence.

The workflow selects a fixed fixture by scenario ID, snapshots it, and leaves these inputs unchanged. Each actual Codex invocation uses the selected versioned skill, declared predecessor evidence, medium reasoning, and a bounded deadline. Independent Maven verification applies to the exact published candidate.

| Scenario | Starting input | Human decision | Expected evidence |
|---|---|---|---|
| greenfield | Contract and acceptance tests, no implementation | Approve normalized requirements/design, then final candidate | Actual generated domain implementation, parallel verifier/reviewer join, passing tests |
| brownfield | Existing implementation with deliberately planted expiry-boundary defect | Approve bounded repair scope, then final candidate | Regression failure on baseline, actual repair, passing unchanged acceptance tests |
| ambiguous | Existing domain model plus “make links permanent and improve analytics” | Clarify permanence/count semantics before approving a revised design | Waiting state, new revision, old approval rejection, clarified implementation/evidence |

Infrastructure retry, unknown process outcome on restart, compensation, stale completion, and exhausted repair are injected in integration tests. Label those outcomes as injected control tests, not independent model reliability statistics. No report may claim live execution until the corresponding persisted attempts and immutable artifacts exist.

Final access checkpoint remains open for any wider release: anonymous analytics access and disable/delete, ownership transition, and abuse controls need a product decision.

## Current submission evidence

All three revised designs were explicitly approved during design review. Actual runtime implementation, fixed Maven verification, candidate review and handoff have completed. Final human approval was explicitly granted and recorded; all three runs are SUCCEEDED. Final full application verification passed all 87 tests. Independent final candidate rechecks are recorded in docs/validation/scenario-reverification.json.

| Scenario | Revision | Fixture tests | Final gate | Report |
|---|---|---|---|---|
| greenfield | 2 | 3 passed | SUCCEEDED | [Saved report](reports/greenfield.json) |
| brownfield | 2 | 3 passed | SUCCEEDED | [Saved report](reports/brownfield.json) |
| ambiguous | 2 | 3 passed | SUCCEEDED | [Saved report](reports/ambiguous.json) |

[Submission index](reports/submission-index.json) records run IDs, exact candidate hashes, owned candidate paths, exported paths and final gate bindings. The archive includes reviewable candidates under `scenarios/<scenario>/`. Each report retains actual attempts and prior failure/recovery history. Earlier reviewer statements about unavailable verification concern the implementation-side sandbox attempts; subsequent independent fixed-verifier passes are recorded separately. These domain fixture results do not establish production durability or full-service load capacity.
