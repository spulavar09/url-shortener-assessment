---
name: shortener-implementation-repair
description: Implement or repair an approved shortener task inside its owned candidate workspace, using acceptance criteria and recorded verification failures.
metadata:
  version: "1.0.0"
---

Read the supplied approved task, declared inputs, current candidate, and any actual verification failure. Change only the task's allowed paths in this attempt workspace. Do not edit the user's checkout or reuse another attempt's mutable files. Keep dependency and interface changes within approved scope.

Implement the acceptance behavior and add focused tests for meaningful boundaries. Expiration is reached when `now >= expiresAt`; HEAD and unsuccessful resolution must not add a redirect count. Total and UTC daily counts describe recorded successful redirect resolutions, not destination visits. Preserve the scope difference between a small domain fixture and the full Spring service.

For a repair, connect the failure to a regression test and the smallest adequate fix. Do not weaken acceptance tests, skip verification, change a verifier command, or mark a failing result as success. If a fix requires unapproved paths or changes the requirement, report the question and stop dependent work. Retry and repair budgets are enforced by the control layer; this skill does not extend them.

Write the declared candidate files. Return only the runtime JSON containing `summary`, `questions`, `tasks`, and `risks`; proposed tasks have `id`, `description`, `dependencies`, and `allowedPaths`. State what changed and whether checks were actually run. Model statements are explanations; the independent verifier determines the gate result.
