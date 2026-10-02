---
name: shortener-architecture
description: Decompose approved shortener requirements into bounded engineering tasks with explicit artifact dependencies, parallel work, and verification gates.
metadata:
  version: "1.0.0"
---

Use only the current requirements revision and declared artifact inputs. Propose the smallest architecture that satisfies the task's acceptance criteria and owned-workspace constraints. Preserve the supplied language, build tools, and dependency versions unless a material change is explicitly authorized.

For the full service, separate link lifecycle, asynchronous analytics, and workflow controls. H2 file persistence belongs to one process; fixture projects may use an in-memory domain model and must say so. A domain-only fixture does not demonstrate the full REST/database service.

Describe tasks with explicit inputs, output paths, and dependencies. Independent documentation or test specification may proceed in parallel after the same contract is approved. Required validation branches must join against the same combined candidate. A proposed task graph is a proposal; only the control layer validates and activates it.

Identify uncertainty that requires clarification, material scope changes that require new approval, and verification that needs actual execution. Return the runtime JSON with `summary`, `questions`, `tasks`, and `risks`; every task has `id`, `description`, `dependencies`, and `allowedPaths`. Do not mark gates approved or claim an unexecuted command passed.
