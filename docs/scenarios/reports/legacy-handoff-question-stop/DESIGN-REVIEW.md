# Live scenario design review

The application and control layer pass 47 automated tests. These are actual GPT-5.6 Sol medium requirements/design outputs for isolated engineering fixtures. The service waits for human review before those agents write candidate source. No fixture changes have been applied to the original projects.

## Greenfield

Requirements are complete and unambiguous: implement an in-memory demo.LinkBook that permanently reserves codes, preserves destinations, treats expiresAt equality as expired, resolves only active links, records only successful record=true resolutions, retains counts after disable/delete, and exposes total plus UTC daily counts. No HTTP server, database, destination fetch, deployment, dependency, or POM change is in scope. This requirements-only invocation wrote no files and did not run or claim Maven verification.

The approved design is a single in-memory demo.LinkBook domain class using a permanent code registry. Each entry preserves its destination and optional expiration, tracks disabled/deleted state, and stores total plus UTC-date resolution counts. Resolution succeeds only for present, active, non-expired entries; expiresAt equality is expired, and counting occurs only for successful record=true resolutions. This architecture phase wrote no files and did not run or claim Maven verification.

Implementation scope: owned fixture source only. Existing POM and acceptance tests stay pinned. Independent Maven verification and a parallel candidate review must join on the same candidate hash. Final handoff requires a separate human review.

Approval binding: run `e0cd39a1-e027-447e-b436-8101fcf2f89f`, revision `1`, version `2`, gate hash `ab7ecea2fec69de056d13b0c361e542550dbf72c399a47f38d69793d175cc7ac`.

[Full persisted report](greenfield.json)

## Brownfield

Confirmed defect: LinkBook.resolve currently expires links only when now is after expiresAt, so equality incorrectly resolves and may increment analytics. Required boundary: a link is expired when now is equal to or after expiresAt; such resolution returns Optional.empty() and changes neither total nor UTC daily counts. Preserve behavior before expiry, null expiry, record=false, missing/disabled/deleted links, immutable destinations, permanent code reservation, retained counts, UTC buckets, the POM, and supplied acceptance tests. This requirements-only pass made no files or source changes.

The smallest compliant repair is a one-condition change in the in-memory domain model: LinkBook.resolve must reject a non-null expiry whenever now is not before expiresAt. The expiry check remains before analytics mutation, so equality and later resolutions return Optional.empty() without changing total or UTC daily counts. No API, test, dependency, POM, persistence, service, or deployment changes are needed. No files were written and no Maven verification is claimed.

Implementation scope: owned fixture source only. Existing POM and acceptance tests stay pinned. Independent Maven verification and a parallel candidate review must join on the same candidate hash. Final handoff requires a separate human review.

Approval binding: run `cf5243dd-3640-4d95-aa6d-0a83a509b7a3`, revision `1`, version `2`, gate hash `d92a21fe984540a8f3b03af9465ac5be64284f72d30593b78defe7116a1d3dcc`.

[Full persisted report](brownfield.json)

## Ambiguous example

The deliberately ambiguous request “make links permanent and improve analytics” is paused in `AWAITING_INPUT`; no engineering task has been dispatched.

Suggested clarification for this example: retain the already approved fixed destinations and optional expiration; retain total plus UTC daily recorded-resolution counts, with HEAD and failed resolutions excluded. This keeps the example aligned with the agreed product design.

The answer creates a new graph revision before its requirements/design gate is reviewed. These fixture decisions do not introduce a new deployment or external action.
