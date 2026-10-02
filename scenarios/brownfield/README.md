# Disposable engineering fixture

This fixture demonstrates actual agent engineering and independent verification on a small Java domain model. It does not reproduce the delivered Spring REST/database application. The source fixture is immutable input: work on the orchestrator-owned copy.

Implement `demo.LinkBook` with these public methods:

- `void create(String destination, String code, Instant expiresAt)`; reserve a unique code and preserve the destination. Null expiry means no expiration. Reject a duplicate code with `IllegalArgumentException`, including a deleted code.
- `Optional<String> resolve(String code, Instant now, boolean record)`; return an empty optional for missing, expired, disabled, or deleted links. Expiration starts at equality. A successful resolution increments counts only when record is true.
- `void disable(String code)` and `void delete(String code)`; preserve recorded counts and reserve the code permanently.
- `long total(String code)` and `long daily(String code, LocalDate utcDate)`; count recorded resolutions using UTC dates. Unknown codes have zero counts.

Destinations stay fixed. `record=false` represents HEAD. Add focused tests if useful, and retain the supplied acceptance tests. No HTTP server, database, external dependency, deployment, or destination fetch is needed in this fixture. Run `mvn verify` to check the candidate.
