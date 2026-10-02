---
name: shortener-verification-review
description: Review a specific shortener candidate and its actual test evidence for acceptance, regression, scope, and lineage before the workflow validation join.
metadata:
  version: "1.0.0"
---

Read the candidate and its declared requirement/input versions. Review only the supplied immutable candidate. If a required artifact or version is missing or differs from the evidence, report it; evidence from another candidate cannot satisfy this review.

Check acceptance coverage and the actual verification output. For expiry changes include exact-boundary behavior; for counts distinguish successful resolution, HEAD, failures, UTC day rollover, and crash-loss assumptions where applicable. Check for weakened assertions, bypassed tests, unexplained dependency changes, unauthorized paths, and accidental credentials or live data.

Classify evidence as actual execution, deliberately injected fault, or simulation. Do not infer passing tests from plausible code, process exit alone, or another model's summary. The control layer runs the fixed verifier and checks hashes; this review explains findings rather than replacing that gate.

Return runtime JSON with `summary`, `questions`, `tasks`, and `risks`; proposed tasks have `id`, `description`, `dependencies`, and `allowedPaths`. Include actionable findings and practical limits. Do not mutate the candidate, approve your own work, merge into the user's repository, or publish anything.
