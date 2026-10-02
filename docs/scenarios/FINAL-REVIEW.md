# Final fixture review

The human approved the designs, verification and final handoff of all three revision-2 demos on October 2, 2026. All three runs and their final gates are now `SUCCEEDED`. This decision covers local assessment fixture handoff; it does not authorize deployment, publication or a merge into the original checkout.

| Scenario | Concrete result | Actual fixed verification |
|---|---|---|
| Greenfield | Implemented fixed destinations, optional expiry, permanent code reservation, HEAD exclusion, retained total and UTC daily counts | 3 tests passed |
| Brownfield | Changed the expiry comparison so equality is expired before counting; preserved other behavior | 3 tests passed |
| Ambiguous | Applied the human clarification; the existing fixture already complied, so no code change was needed | 3 tests passed |

Supplied POMs and acceptance tests remain unchanged. Independent review and verifier tasks bind the same candidate hashes, and the join and handoff completed for every scenario. Saved reports retain older failed attempts, conservative recovery, approval records and hash-validated evidence. Reviewer remarks about sandbox dependency failures concern the implementation-side attempts; the subsequent fixed verifier ran successfully against each exact candidate. Privacy-redacted verification and reviewer outputs are included with the exported candidates, resolving the handoff question about access to detailed evidence.

These are intentionally small in-memory domain fixtures. They do not establish production durability or full-service performance. The delivered REST/H2 application is separate: its final full verification passed all 87 tests and packaged the latest source, including both clarification-pause regression cases. H2 remains single-process and analytics remain best effort. Anonymous access/ownership remains a parked decision before wider release.

See [submission index](reports/submission-index.json) for complete run/revision/version/hash bindings and [scenario reports](SCENARIOS.md) for evidence links. Final human approval was explicitly granted and recorded against each current revision, version and final evidence hash.

Final independent rechecks passed all nine candidate tests again in disposable copies; original candidates and pinned files remained unchanged. The deliberately unrepaired brownfield baseline reproduced exactly the expected expiry-equality failure. See [fresh verification evidence](../validation/scenario-reverification.json).
