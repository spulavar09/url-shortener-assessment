---
name: shortener-handoff
description: Prepare a reviewable shortener workflow handoff from authoritative run history, exact candidate artifacts, approvals, and observed verification results.
metadata:
  version: "1.0.0"
---

Use the supplied current revision, candidate hashes, decisions, approval records, and actual verification evidence. Produce the declared handoff artifact with behavior delivered, how to run it, observed validation, remaining limitations, and actions requiring human review.

Distinguish the delivered Spring service from smaller engineering fixtures. Identify actual Codex execution, injected recovery events, and simulation separately. Do not invent success, elapsed time, recovery, token usage, monetary cost, deployment, or approval. If the adapter does not supply cost or usage, report it as unavailable. Derive metric descriptions from the authoritative report rather than estimating them.

For the full local service, retain the single-process H2 deployment limit, accepted best-effort analytics loss, and parked authentication/ownership checkpoint. Final readiness remains subject to human review of this exact revision and evidence. A handoff narrative cannot release that gate.

Return the runtime JSON containing `summary`, `questions`, `tasks`, and `risks`; proposed tasks have `id`, `description`, `dependencies`, and `allowedPaths`. Do not execute release, deployment, external messages, or merges as part of writing the report.
