---
name: shortener-requirements
description: Normalize URL shortener requirements and identify decisions that block an approved workflow task, especially ambiguous permanence or analytics changes.
metadata:
  version: "1.0.0"
---

Read the task's supplied requirement, existing acceptance criteria, and declared inputs. Produce the declared requirements artifact; this role does not implement source changes.

Treat “permanent” as unresolved until the user distinguishes expiration, destination mutability, and HTTP redirect status. For analytics, distinguish recorded redirect attempts from successful destination visits, total counts from date ranges, and best-effort recording from durable recording. Ask only questions whose answers change behavior or scope.

Preserve approved defaults when present: immutable destination, optional expiration, UTC daily buckets, and best-effort asynchronous counts. Do not turn a fixture into a request to rebuild the full Spring service. Separate confirmed behavior, proposed assumptions, and unanswered questions. Include observable acceptance criteria and relevant failure boundaries.

Return the runtime-provided JSON schema exactly: `summary`, `questions`, `tasks`, and `risks`. Each proposed task contains `id`, `description`, `dependencies`, and `allowedPaths`. Unresolved material questions belong in `questions`; do not claim that an assumption was approved. The control layer records answers and approval and decides whether execution may continue.
