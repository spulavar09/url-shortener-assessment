package com.example.shortener.workflow;

import com.example.shortener.common.ErrorCodes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.io.IOException;

import com.example.shortener.common.ApiException;

import static com.example.shortener.workflow.WorkflowModels.*;

@Service
public class WorkflowService {
    private static final Logger log = LogManager.getLogger(WorkflowService.class);
    private final WorkflowRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final WorkspaceStore files;
    private final WorkflowProperties config;

    public WorkflowService(WorkflowRepository repository, org.springframework.transaction.PlatformTransactionManager manager, ObjectMapper json, Clock clock, WorkspaceStore files, WorkflowProperties config) {
        this.repository = repository;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(5);
        this.json = json;
        this.clock = clock;
        this.files = files;
        this.config = config;
    }

    private Instant now() {
        return clock.instant();
    }

    String encode(Object o) {
        return json.writeValueAsString(o);
    }

    Task task(Map<String, Object> row) {
        return json.readValue((String) row.get("definition"), Task.class);
    }

    private Map<String, Object> run(String id, boolean lock) {
        var r = lock ? repository.findRunForUpdate(id) : repository.findRun(id);
        if (r.isEmpty()) throw ApiException.notFound(ErrorCodes.WORKFLOW_NOT_FOUND, "Unknown workflow run");
        return r.getFirst();
    }

    private int rev(Map<String, Object> r) {
        return ((Number) r.get("revision")).intValue();
    }

    private long version(Map<String, Object> r) {
        return ((Number) r.get("version")).longValue();
    }

    private boolean terminal(String s) {
        return Set.of("SUCCEEDED", "FAILED", "SAFELY_STOPPED", "CANCELLED").contains(s);
    }

    private void event(String id, int revision, String task, String type, String detail) {
        repository.appendEvent(id, revision, task, type, detail, now());
        // Operational logs identify decisions; sensitive details stay in protected evidence.
        switch (type) {
            case "CREATED", "CLARIFIED", "KNOWN_QUESTION_STOP_RECOVERED", "CANCELLED", "CLARIFICATION_REQUIRED" ->
                    log.info("Workflow transition runId={} revision={} taskId={} event={}", id, revision, task, type);
            case "HUMAN_APPROVED", "HUMAN_REJECTED" ->
                    log.info("Workflow human decision runId={} revision={} taskId={} event={}", id, revision, task, type);
            case "SAFE_STOP", "RECONCILED_UNKNOWN", "LEASE_EXPIRED_UNKNOWN", "BOUNDED_RETRY", "REPAIR_REVISION", "FAILURE_DEFERRED_FOR_CLARIFICATION" ->
                    log.warn("Workflow failure or recovery runId={} revision={} taskId={} event={}", id, revision, task, type);
            case "FENCED_RESULT" ->
                    log.debug("Workflow result discarded runId={} revision={} taskId={} event={}", id, revision, task, type);
            default -> { }
        }
    }

    private void state(String id, String state, String reason) {
        repository.changeRunState(state, reason, now(), id);
    }

    private void addTasks(String id, int revision, List<Task> tasks) {
        WorkflowGraph.validate(tasks);
        for (Task t : tasks) {
            String skillHash = null;
            try {
                if (t.skill() != null) skillHash = files.skillHash(t.skill());
            } catch (IOException e) {
                throw ApiException.unavailable(ErrorCodes.SKILL_UNAVAILABLE, "Exact reviewed skill input is unavailable");
            }
            repository.insertTask(id, revision, t.id(), encode(t), "PENDING", skillHash);
        }
    }

    public synchronized Map<String, Object> create(CreateRun request) {
        if (!Set.of("greenfield", "brownfield", "ambiguous").contains(request.scenario()))
            throw ApiException.badRequest(ErrorCodes.INVALID_SCENARIO, "Use greenfield, brownfield or ambiguous");
        String id = UUID.randomUUID().toString();
        try {
            Path baseline = files.snapshot(id, request.scenario());
            String hash = files.treeHash(baseline);
            return tx.execute(s -> {
                        long recent = repository.countRecentRuns(now().minusSeconds(3600));
                        if (recent >= 4)
                            throw new ApiException(429, ErrorCodes.WORKFLOW_RATE_LIMIT, "Four workflow creations per hour are allowed");
                        repository.insertRun(id, request.scenario(), request.requirement(), request.ambiguous() || request.scenario().equals("ambiguous") ? "AWAITING_INPUT" : "RUNNING", 1, 1, now(), now(), baseline.toString(), hash);
                        addTasks(id, 1, WorkflowGraph.initial(request.requirement()));
                        event(id, 1, null, "CREATED", "Actual CLI execution; unresolved ambiguity blocks dispatch; baseline=" + hash);
                        return get(id);
                    }
            );
        } catch (IOException e) {
            log.warn("Workflow creation failed runId={} outcome={}", id, ErrorCodes.WORKSPACE_UNAVAILABLE);
            throw ApiException.unavailable(ErrorCodes.WORKSPACE_UNAVAILABLE, "Cannot create owned immutable input snapshot");
        }
    }

    private Map<String, Object> summary(String id) {
        Map<String, Object> r = new LinkedHashMap<>(run(id, false));
        r.remove("baseline");
        r.put("pendingQuestions", questionEvents(id, rev(r), "CLARIFICATION_REQUIRED"));
        r.put("finalReviewItems", questionEvents(id, rev(r), "HANDOFF_REVIEW_ITEMS"));
        r.put("tasks", repository.findTaskSummaries(id, rev(r)));
        return r;
    }

    private List<String> questionEvents(String id, int revision, String type) {
        List<String> questions = new ArrayList<>();
        for (var event : repository.findQuestionEvents(id, revision, type)) {
            var list = json.readTree((String) event.get("detail"));
            for (var question : list) questions.add(question.asString());
        }
        return questions;
    }

    private boolean recoverableLegacyQuestionStop(String id, Map<String, Object> run) {
        String prefix = "Attempt failed; bounded recovery unavailable: Unresolved agent questions require reviewed clarification: ";
        if (!"SAFELY_STOPPED".equals(run.get("state")) || !Objects.toString(run.get("reason"), "").startsWith(prefix))
            return false;
        if (repository.countUnresolvedAttempts(id) != 0)
            return false;
        var attempts = repository.findRevisionAttemptsNewestFirst(id, rev(run));
        if (attempts.isEmpty()) return false;
        var attempt = attempts.getFirst();
        if (!"FAILED".equals(attempt.get("state")) || !Integer.valueOf(0).equals(attempt.get("exit_code")) || !"handoff".equals(attempt.get("task_id")) || !Objects.toString(attempt.get("detail"), "").startsWith("Unresolved agent questions require reviewed clarification: "))
            return false;
        try {
            var evidence = json.readTree(files.readEvidence((String) attempt.get("evidence_path"), (String) attempt.get("evidence_hash")));
            if (!evidence.path("failure").asString().startsWith("Unresolved agent questions require reviewed clarification: ") || evidence.path("exitCode").asInt() != 0)
                return false;
            boolean structuredQuestions = false;
            for (String line : evidence.path("transcript").asString().lines().toList()) {
                if (!line.startsWith("{")) continue;
                var event = json.readTree(line);
                if ("item.completed".equals(event.path("type").asString()) && "agent_message".equals(event.path("item").path("type").asString())) {
                    var result = json.readTree(event.path("item").path("text").asString());
                    if (result.path("summary").isString() && result.path("questions").isArray() && !result.path("questions").isEmpty())
                        structuredQuestions = true;
                }
            }
            if (!structuredQuestions) return false;
            var completed = repository.findSucceededRevisionTasks(id, rev(run));
            if (!completed.stream().map(t -> t.get("task_id")).toList().containsAll(List.of("design-approval", "implementation", "verify", "review", "join")))
                return false;
            for (var task : completed) {
                files.readEvidence((String) task.get("evidence_path"), (String) task.get("evidence_hash"));
                files.assertHash((String) task.get("candidate_path"), (String) task.get("candidate_hash"));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<Map<String, Object>> history(String id) {
        run(id, false);
        return repository.findEventHistory(id);
    }

    public Map<String, Object> get(String id) {
        Map<String, Object> r = summary(id);
        List<Map<String, Object>> attempts = repository.findAttemptHistory(id);
        r.put("attemptHistory", attempts);
        r.put("reviewEvidence", reviewEvidence(id));
        r.put("approvals", repository.findApprovalHistory(id));
        r.put("events", history(id));
        r.put("graphRevisions", repository.findGraphHistory(id));
        List<Map<String, Object>> history = history(id);
        Set<Integer> repairRevisions = new HashSet<>();
        history.stream().filter(e -> e.get("event_type").equals("REPAIR_REVISION")).forEach(e -> repairRevisions.add(((Number) e.get("revision")).intValue()));
        long retry = attempts.stream().filter(a -> ((Number) a.get("fence")).longValue() > 1 || repairRevisions.contains(((Number) a.get("revision")).intValue())).count();
        long terminalRuns = repository.countTerminalRuns(), successRuns = repository.countSuccessfulRuns(), cancelledRuns = repository.countCancelledRuns(), mutatedRuns = repository.countImplementationRuns(), compensatedRuns = repository.countCompensatedRuns();
        Instant created = ((OffsetDateTime) r.get("created_at")).toInstant(), ended = terminal((String) r.get("state")) ? ((OffsetDateTime) r.get("updated_at")).toInstant() : now();
        long humanWait = 0;
        Instant waiting = "ambiguous".equals(r.get("scenario")) ? created : null;
        Instant recovery = null;
        List<Long> recoveries = new ArrayList<>();
        for (var e : history) {
            String type = (String) e.get("event_type");
            Instant time = ((OffsetDateTime) e.get("occurred_at")).toInstant();
            if (type.equals("GATE_READY") || type.equals("CLARIFICATION_REQUIRED")) waiting = time;
            if ((type.equals("HUMAN_APPROVED") || type.equals("HUMAN_REJECTED") || type.equals("CLARIFIED")) && waiting != null) {
                humanWait += Math.max(0, Duration.between(waiting, time).toMillis());
                waiting = null;
            }
            if (type.equals("REPAIR_REVISION")) recovery = time;
            if (type.equals("TASK_SUCCEEDED") && recovery != null) {
                recoveries.add(Math.max(0, Duration.between(recovery, time).toMillis()));
                recovery = null;
            }
        }
        if (waiting != null) humanWait += Math.max(0, Duration.between(waiting, ended).toMillis());
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("attempts", attempts.size());
        metrics.put("retryFrequency", attempts.isEmpty() ? 0.0 : (double) retry / attempts.size());
        metrics.put("successRateAcrossObservedTerminalRuns", terminalRuns == 0 ? "unavailable" : (double) successRuns / terminalRuns);
        metrics.put("cancelledRunsReportedSeparately", cancelledRuns);
        metrics.put("compensationFrequency", mutatedRuns == 0 ? "unavailable" : (double) compensatedRuns / mutatedRuns);
        metrics.put("endToEndLatencyMs", Math.max(0, Duration.between(created, ended).toMillis()));
        metrics.put("humanWaitMs", humanWait);
        metrics.put("activeExecutionMs", r.get("active_ms"));
        metrics.put("activeExecutionSemantics", "sum of worker attempt elapsed time; concurrent attempts consume separate budget reservations");
        metrics.put("repairCycles", r.get("repair_count"));
        Map<String, Object> tokens = new LinkedHashMap<>();
        for (String key : List.of("input_tokens", "cached_input_tokens", "output_tokens")) {
            long known = attempts.stream().filter(a -> a.get(key) != null).count();
            tokens.put(key, known == 0 ? "unavailable" : attempts.stream().filter(a -> a.get(key) != null).mapToLong(a -> ((Number) a.get(key)).longValue()).sum());
            tokens.put(key + "_attempts_with_reported_usage", known);
        }
        tokens.put("attempts_with_unknown_usage", attempts.stream().filter(a -> a.get("input_tokens") == null).count());
        metrics.put("tokenUsage", tokens);
        metrics.put("monetaryCost", "unavailable: subscription CLI");
        metrics.put("completedRecoveryTimesMs", recoveries.isEmpty() ? "unavailable" : recoveries);
        r.put("metrics", metrics);
        r.put("executionClassification", "actual; test adapters and injected faults are identified in attempt details");
        r.put("limitations", List.of("Single process embedded H2", "Application allowlist and Codex sandbox do not prove read/network isolation", "Candidate workspaces never merged into user checkout", "Local events are not tamper-proof", "No deployment performed"));
        return r;
    }

    private List<Map<String, Object>> reviewEvidence(String id) {
        List<Map<String, Object>> published = repository.findPublishedTaskEvidence(id);
        published.addAll(repository.findPublishedAttemptEvidence(id));
        List<Map<String, Object>> reviews = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, tools.jackson.databind.JsonNode> validatedFiles = new HashMap<>();
        for (var artifact : published) {
            String path = (String) artifact.get("evidence_path"), hash = (String) artifact.get("evidence_hash");
            String reference = artifact.get("revision") + "/" + artifact.get("task_id") + "/" + hash;
            if (!seen.add(reference)) continue;
            try {
                // Read/hash the immutable original; return only the compact review projection.
                String fileIdentity = path + "/" + hash;
                var evidence = validatedFiles.get(fileIdentity);
                if (evidence == null) {
                    evidence = json.readTree(files.readEvidence(path, hash));
                    validatedFiles.put(fileIdentity, evidence);
                }
                Map<String, Object> review = new LinkedHashMap<>();
                for (String field : List.of("revision", "task_id", "state", "input_hash", "candidate_hash"))
                    if (artifact.get(field) != null) review.put(field, artifact.get(field));
                review.put("evidenceHash", hash);
                review.put("hashValidated", true);
                review.put("fullEvidenceRetained", true);
                for (String field : List.of("execution", "exitCode", "sandbox", "failure", "tokenUsage", "monetaryCost"))
                    if (evidence.has(field)) review.put(field, evidence.get(field));
                if (evidence.has("candidateHash")) review.put("candidate_hash", evidence.get("candidateHash").asString());
                var result = structuredResult(evidence);
                if (result != null) review.put("structuredResult", result);
                String transcript = evidence.path("transcript").asString("");
                if (!transcript.isEmpty()) review.put("fullOutputHash", WorkspaceStore.hash(transcript.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                if ("verify".equals(artifact.get("task_id"))) {
                    review.put("verifierOutput", transcript.substring(Math.max(0, transcript.length() - 16000)));
                    review.put("verifierOutputTruncated", transcript.length() > 16000);
                    if (evidence.has("result")) review.put("verificationResult", evidence.get("result"));
                }
                if (evidence.has("dependencies")) {
                    List<Map<String, Object>> dependencies = new ArrayList<>();
                    for (var dependency : evidence.path("dependencies")) {
                        Map<String, Object> lineage = new LinkedHashMap<>();
                        for (String field : List.of("task_id", "state", "evidence_hash", "candidate_hash", "input_hash"))
                            if (dependency.has(field)) lineage.put(field, dependency.get(field));
                        dependencies.add(lineage);
                    }
                    review.put("dependencies", dependencies);
                }
                reviews.add(review);
            } catch (IOException e) {
                throw ApiException.unavailable(ErrorCodes.EVIDENCE_UNAVAILABLE, "Published workflow review evidence cannot be read");
            }
        }
        return reviews;
    }

    private tools.jackson.databind.JsonNode structuredResult(tools.jackson.databind.JsonNode evidence) {
        var result = evidence.path("result");
        if (result.isString()) {
            try { result = json.readTree(result.asString()); }
            catch (Exception ignored) { result = null; }
        }
        if (result != null && result.isObject() && result.path("summary").isString()) return result;
        // Earlier failed question attempts retained the final JSON in their immutable JSONL.
        tools.jackson.databind.JsonNode legacy = null;
        for (String line : evidence.path("transcript").asString("").lines().toList()) {
            if (!line.startsWith("{")) continue;
            try {
                var event = json.readTree(line);
                if ("item.completed".equals(event.path("type").asString()) && "agent_message".equals(event.path("item").path("type").asString())) {
                    var candidate = json.readTree(event.path("item").path("text").asString());
                    if (candidate.isObject() && candidate.path("summary").isString()) legacy = candidate;
                }
            } catch (Exception ignored) { }
        }
        return legacy;
    }

    public synchronized Map<String, Object> clarify(String id, Clarification answer) {
        return tx.execute(s -> {
                    var r = run(id, true);
                    expect(r, answer.version());
                    boolean recoveringLegacyQuestionStop = terminal((String) r.get("state"));
                    if (recoveringLegacyQuestionStop && !recoverableLegacyQuestionStop(id, r))
                        conflict("Terminal run cannot be clarified; only a reconciled known question stop can be recovered");
                    int revision = rev(r) + 1;
                    repository.invalidateRevisionTasks(id, rev(r));
                    String requirement = r.get("requirement") + "\nHuman clarification: " + answer.answer();
                    repository.activateClarifiedRevision(requirement, revision, now(), id);
                    addTasks(id, revision, WorkflowGraph.initial(requirement));
                    event(id, revision, null, recoveringLegacyQuestionStop ? "KNOWN_QUESTION_STOP_RECOVERED" : "CLARIFIED", "Human clarification created a new immutable revision; all evidence and approvals invalidated; attempt/time/repair consumption retained");
                    return get(id);
                }
        );
    }

    private void expect(Map<String, Object> r, long v) {
        if (version(r) != v) conflict("Stale workflow version");
    }

    private void conflict(String detail) {
        throw ApiException.conflict(ErrorCodes.STALE_WORKFLOW_STATE, detail);
    }

    public synchronized Map<String, Object> approve(String id, Approval a, String principal) {
        return tx.execute(s -> {
                    var r = run(id, true);
                    expect(r, a.version());
                    if (rev(r) != a.revision() || terminal((String) r.get("state"))) conflict("Approval revision is stale");
                    var tasks = repository.findTask(id, a.revision(), a.taskId());
                    if (tasks.isEmpty()) conflict("Unknown approval task");
                    var t = tasks.getFirst();
                    if (!"AWAITING_APPROVAL".equals(t.get("state")) || !"APPROVAL".equals(task(t).type()) || !Objects.equals(t.get("evidence_hash"), a.evidenceHash()))
                        conflict("Approval does not bind current evidence");
                    try {
                        files.readEvidence((String) t.get("evidence_path"), a.evidenceHash());
                        validateDependencies(id, a.revision(), task(t));
                    } catch (IOException e) {
                        conflict("Approval evidence is unavailable");
                    }
                    repository.insertApproval(UUID.randomUUID().toString(), id, a.revision(), a.taskId(), a.version(), a.evidenceHash(), a.approved(), principal, a.rationale(), now());
                    repository.recordGateDecision(a.approved() ? "SUCCEEDED" : "BLOCKED", id, a.revision(), a.taskId());
                    state(id, a.approved() ? (a.taskId().equals("final-approval") ? "SUCCEEDED" : "RUNNING") : "SAFELY_STOPPED", a.approved() ? null : "Human rejected gate");
                    event(id, a.revision(), a.taskId(), a.approved() ? "HUMAN_APPROVED" : "HUMAN_REJECTED", a.rationale());
                    return get(id);
                }
        );
    }

    public synchronized Map<String, Object> cancel(String id) {
        return tx.execute(s -> {
                    var r = run(id, true);
                    if (!terminal((String) r.get("state"))) {
                        state(id, "CANCELLED", "Operator cancellation");
                        repository.cancelUnfinishedRevisionTasks(id, rev(r));
                        event(id, rev(r), null, "CANCELLED", "New claims blocked; process identity reconciliation required");
                    }
                    return get(id);
                }
        );
    }

    private void validateDependencies(String id, int revision, Task t) throws IOException {
        for (String d : t.dependencies()) {
            var row = repository.findTask(id, revision, d).getFirst();
            if (!"SUCCEEDED".equals(row.get("state"))) conflict("Dependency is not complete");
            if (row.get("evidence_path") != null)
                files.readEvidence((String) row.get("evidence_path"), (String) row.get("evidence_hash"));
            if (row.get("candidate_path") != null)
                files.assertHash((String) row.get("candidate_path"), (String) row.get("candidate_hash"));
        }
    }

    public synchronized Optional<Claim> claim() {
        return tx.execute(s -> {
                    long running = repository.countRunningAttempts();
                    if (running >= config.concurrency()) return Optional.empty();
                    for (var r : repository.findDispatchableRuns()) {
                        String id = (String) r.get("id");
                        run(id, true);
                        int revision = rev(r);
                        if (((Number) r.get("active_ms")).longValue() >= 3600000) {
                            stop(id, revision, "Active execution budget exhausted");
                            continue;
                        }
                        var taskRows = repository.findOrderedRevisionTasks(id, revision);
                        Map<String, Map<String, Object>> byId = new HashMap<>();
                        taskRows.forEach(t -> byId.put((String) t.get("task_id"), t));
                        for (var t : taskRows) {
                            if (!"PENDING".equals(t.get("state"))) continue;
                            if (t.get("retry_after") instanceof OffsetDateTime retryAfter && retryAfter.toInstant().isAfter(now()))
                                continue;
                            Task def = task(t);
                            if (!def.dependencies().stream().allMatch(d -> "SUCCEEDED".equals(byId.get(d).get("state"))))
                                continue;
                            try {
                                validateDependencies(id, revision, def);
                                String baseline = (String) r.get("baseline"), candidateHash = (String) r.get("baseline_hash");
                                if (def.skill() != null && !files.skillHash(def.skill()).equals(t.get("skill_hash"))) {
                                    stop(id, revision, "Material skill/reference change requires reviewed new revision");
                                    return Optional.empty();
                                }
                                for (String d : def.dependencies()) {
                                    var dep = byId.get(d);
                                    if (dep.get("candidate_path") != null) {
                                        baseline = (String) dep.get("candidate_path");
                                        candidateHash = (String) dep.get("candidate_hash");
                                    }
                                }
                                if (def.id().equals("verify") || def.id().equals("review")) {
                                    var implementation = byId.get("implementation");
                                    baseline = (String) implementation.get("candidate_path");
                                    candidateHash = (String) implementation.get("candidate_hash");
                                }
                                files.assertHash(baseline, candidateHash);
                                String input = WorkspaceStore.hash(encode(Map.of("revision", revision, "task", def, "requirement", r.get("requirement"), "baseline", candidateHash, "dependencies", def.dependencies().stream().map(d -> Map.of("id", d, "hash", Objects.toString(byId.get(d).get("evidence_hash"), ""))).toList())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                if (def.type().equals("APPROVAL") || def.type().equals("JOIN")) {
                                    if (def.type().equals("JOIN")) {
                                        String v = (String) byId.get("verify").get("input_hash"), review = (String) byId.get("review").get("input_hash");
                                        String vc = (String) byId.get("verify").get("candidate_hash"), rc = (String) byId.get("review").get("candidate_hash");
                                        if (vc == null || !vc.equals(rc)) {
                                            stop(id, revision, "Verification branches do not bind the same candidate");
                                            continue;
                                        }
                                    }
                                    String evidence = encode(Map.of("task", def, "revision", revision, "inputHash", input, "candidateHash", candidateHash, "dependencies", def.dependencies().stream().map(byId::get).toList()));
                                    Path p = files.evidence(id, UUID.randomUUID().toString(), evidence);
                                    repository.publishGateEvidence(def.type().equals("APPROVAL") ? "AWAITING_APPROVAL" : "SUCCEEDED", p.toString(), WorkspaceStore.hash(evidence.getBytes(java.nio.charset.StandardCharsets.UTF_8)), baseline, candidateHash, input, id, revision, def.id());
                                    state(id, def.type().equals("APPROVAL") ? "AWAITING_APPROVAL" : "RUNNING", null);
                                    event(id, revision, def.id(), def.type().equals("APPROVAL") ? "GATE_READY" : "JOIN_PASSED", "Exact input and candidate evidence published");
                                    return Optional.empty();
                                }
                                long reserved = repository.findRunningTimeReservations(id).stream().mapToLong(a -> Duration.between(((OffsetDateTime) a.get("started_at")).toInstant(), ((OffsetDateTime) a.get("deadline")).toInstant()).toMillis()).sum();
                                long requested = (def.type().equals("VERIFY") ? config.verificationTimeoutSeconds() : config.agentTimeoutSeconds()) * 1000L;
                                if (((Number) r.get("active_ms")).longValue() + reserved + requested > 3600000) {
                                    stop(id, revision, "Insufficient remaining active time for bounded attempt reservation");
                                    return Optional.empty();
                                }
                                String attemptId = UUID.randomUUID().toString(), invocation = UUID.randomUUID().toString();
                                Path workspace = files.attempt(id, attemptId, baseline);
                                if (def.skill() != null) {
                                    String skillHash = files.skill(def.skill(), workspace);
                                    if (!skillHash.equals(t.get("skill_hash"))) {
                                        stop(id, revision, "Skill changed during approved input copy");
                                        return Optional.empty();
                                    }
                                    input = WorkspaceStore.hash((input + skillHash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                }
                                long fence = ((Number) t.get("fence")).longValue() + 1;
                                int attempts = ((Number) t.get("attempts")).intValue() + 1;
                                if (attempts > 3) {
                                    stop(id, revision, "Task transport attempt budget exhausted");
                                    continue;
                                }
                                repository.claimTask(attempts, fence, now().plusSeconds(30), input, id, revision, def.id());
                                repository.insertAttemptReservation(attemptId, id, revision, def.id(), fence, invocation, input, workspace.toString(), "RUNNING", now(), now().plusSeconds(def.type().equals("VERIFY") ? config.verificationTimeoutSeconds() : config.agentTimeoutSeconds()));
                                event(id, revision, def.id(), "CLAIMED", "attempt=" + attemptId + " invocation=" + invocation + " input=" + input);
                                return Optional.of(new Claim(id, revision, def.id(), fence, attemptId, invocation, input, workspace.toString(), baseline, (String) r.get("requirement"), (String) t.get("skill_hash"), def));
                            } catch (IOException | ApiException e) {
                                stop(id, revision, "Input/artifact policy validation failed: " + e.getMessage());
                                return Optional.empty();
                            }
                        }
                    }
                    return Optional.empty();
                }
        );
    }

    private void stop(String id, int revision, String reason) {
        state(id, "SAFELY_STOPPED", reason);
        repository.blockUnfinishedRevisionTasks(id, revision);
        event(id, revision, null, "SAFE_STOP", reason);
    }

    public String declaredInputs(Claim c) throws IOException {
        List<Object> inputs = new ArrayList<>();
        for (String d : c.task().dependencies()) {
            var r = repository.findDependencyEvidence(c.runId(), c.revision(), d).getFirst();
            if (r.get("evidence_path") != null) {
                String raw = files.readEvidence((String) r.get("evidence_path"), (String) r.get("evidence_hash"));
                var evidence = json.readTree(raw);
                if (evidence.isObject()) {
                    var object = (tools.jackson.databind.node.ObjectNode) evidence;
                    var transcript = object.remove("transcript");
                    if (transcript != null) {
                        String text = transcript.asString();
                        object.put("transcriptTail", text.substring(Math.max(0, text.length() - 16000)));
                    }
                }
                inputs.add(Map.of("task", d, "evidenceHash", r.get("evidence_hash"), "evidence", evidence));
            }
        }
        String input = encode(inputs);
        if (input.length() > 1024 * 1024) throw new IOException("Declared inputs exceed bounded 1 MiB payload");
        return input;
    }

    public synchronized void heartbeat(Claim c) {
        repository.renewTaskLease(now().plusSeconds(30), c.runId(), c.revision(), c.taskId(), c.fence());
    }

    public synchronized boolean current(Claim c) {
        var r = run(c.runId(), false);
        return rev(r) == c.revision() && !terminal((String) r.get("state")) && repository.countCurrentTaskClaim(c.runId(), c.revision(), c.taskId(), c.fence(), c.inputHash(), now()) == 1;
    }

    public synchronized void processStarted(Claim c, Process p) {
        repository.recordProcessIdentity(p.pid(), p.info().startInstant().orElseThrow(), c.attemptId());
    }

    public synchronized void complete(Claim c, Outcome out) {
        tx.executeWithoutResult(s -> {
                    var r = run(c.runId(), true);
                    var a = repository.findAttempt(c.attemptId()).getFirst();
                    if (!"RUNNING".equals(a.get("state"))) {
                        event(c.runId(), c.revision(), c.taskId(), "FENCED_RESULT", "Attempt already reconciled; late result cannot replace UNKNOWN outcome");
                        return;
                    }
                    if (rev(r) == c.revision() && !terminal((String) r.get("state")) && !current(c)) {
                        repository.markAttemptUnknown(now(), "Result lease/input fencing validation failed", c.attemptId());
                        stop(c.runId(), c.revision(), "Expired lease or mismatched declared inputs; late outcome cannot advance run");
                        event(c.runId(), c.revision(), c.taskId(), "FENCED_RESULT", "Unknown result rejected before task success");
                        return;
                    }
                    Instant started = ((java.time.OffsetDateTime) a.get("started_at")).toInstant();
                    long elapsed = Math.max(0, Duration.between(started, now()).toMillis());
                    repository.recordAttemptOutcome(WorkflowProcessAdapter.blocksForQuestions(c.task(), out.questions()) ? "AWAITING_INPUT" : out.success() ? "SUCCEEDED" : out.detail() != null && out.detail().contains("termination confirmed=false") ? "UNKNOWN" : "FAILED", now(), out.exitCode(), out.usage() == null ? null : out.usage().get("input_tokens"), out.usage() == null ? null : out.usage().get("cached_input_tokens"), out.usage() == null ? null : out.usage().get("output_tokens"), out.evidencePath(), out.evidenceHash(), truncate(out.detail()), c.attemptId());
                    repository.consumeActiveTime(elapsed, c.runId());
                    if (!current(c)) {
                        event(c.runId(), c.revision(), c.taskId(), "FENCED_RESULT", "Late result retained but cannot satisfy active plan");
                        return;
                    }
                    if (out.evidencePath() != null) {
                        try {
                            files.readEvidence(out.evidencePath(), out.evidenceHash());
                        } catch (Exception e) {
                            stop(c.runId(), c.revision(), "Attempt evidence hash validation failed");
                            return;
                        }
                    }
                    if (WorkflowProcessAdapter.blocksForQuestions(c.task(), out.questions())) {
                        try {
                            validateDependencies(c.runId(), c.revision(), c.task());
                            files.readEvidence(out.evidencePath(), out.evidenceHash());
                            files.assertHash(out.candidatePath(), out.candidateHash());
                        } catch (Exception e) {
                            stop(c.runId(), c.revision(), "Clarification evidence validation failed");
                            return;
                        }
                        repository.blockTaskForClarification(out.evidencePath(), out.evidenceHash(), c.runId(), c.revision(), c.taskId(), c.fence());
                        state(c.runId(), "AWAITING_INPUT", "Explicit human clarification required");
                        event(c.runId(), c.revision(), c.taskId(), "CLARIFICATION_REQUIRED", encode(out.questions()));
                        return;
                    }
                    if (out.success()) {
                        try {
                            validateDependencies(c.runId(), c.revision(), c.task());
                            files.readEvidence(out.evidencePath(), out.evidenceHash());
                            files.assertHash(out.candidatePath(), out.candidateHash());
                        } catch (Exception e) {
                            stop(c.runId(), c.revision(), "Published evidence validation failed");
                            return;
                        }
                        repository.completeFencedTask(out.evidencePath(), out.evidenceHash(), out.candidatePath(), out.candidateHash(), c.runId(), c.revision(), c.taskId(), c.fence());
                        event(c.runId(), c.revision(), c.taskId(), "TASK_SUCCEEDED", out.detail());
                        if (!out.questions().isEmpty())
                            event(c.runId(), c.revision(), c.taskId(), "HANDOFF_REVIEW_ITEMS", encode(out.questions()));
                    } else {
                        // A parallel failure must not replace an unanswered human clarification.
                        if ("AWAITING_INPUT".equals(r.get("state"))) {
                            repository.failFencedTask(out.evidencePath(), out.evidenceHash(), c.runId(), c.revision(), c.taskId(), c.fence());
                            event(c.runId(), c.revision(), c.taskId(), "FAILURE_DEFERRED_FOR_CLARIFICATION", "Failure retained; automatic recovery waits for human clarification");
                            return;
                        }
                        if (c.task().skill() != null && c.task().skill().equals("shortener-implementation-repair"))
                            event(c.runId(), c.revision(), c.taskId(), "COMPENSATED", "Owned attempt restored or quarantined; original checkout unchanged");
                        int attempts = repository.findTaskAttemptCount(c.runId(), c.revision(), c.taskId());
                        if (out.transientFailure() && attempts < 3) {
                            repository.scheduleTaskRetry(now().plusMillis((1L << attempts) * 1000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(500)), c.runId(), c.revision(), c.taskId());
                            event(c.runId(), c.revision(), c.taskId(), "BOUNDED_RETRY", out.detail());
                        } else if (c.task().type().equals("VERIFY") && ((Number) r.get("repair_count")).intValue() < 2) {
                            repair(c, r, out.detail());
                        } else
                            stop(c.runId(), c.revision(), "Attempt failed; bounded recovery unavailable: " + truncate(out.detail()));
                    }
                }
        );
    }

    private String truncate(String s) {
        return s == null ? "" : s.substring(0, Math.min(1800, s.length()));
    }

    private void repair(Claim c, Map<String, Object> r, String failure) {
        int next = c.revision() + 1;
        var old = repository.findRevisionTasksForRepair(c.runId(), c.revision());
        List<Task> plan = old.stream().map(this::task).map(t -> t.id().equals("implementation") ? new Task(t.id(), t.type(), t.skill(), t.prompt() + "\nRepair actual verification failure: " + truncate(failure), t.dependencies(), t.allowedPaths()) : t).toList();
        addTasks(c.runId(), next, plan);
        for (var t : old)
            if (Set.of("requirements", "architecture", "design-approval").contains(t.get("task_id")))
                repository.carryApprovedTaskEvidence((String) t.get("evidence_path"), (String) t.get("evidence_hash"), (String) t.get("candidate_path"), (String) t.get("candidate_hash"), (String) t.get("input_hash"), c.runId(), next, (String) t.get("task_id"));
        var implementation = old.stream().filter(t -> t.get("task_id").equals("implementation")).findFirst().orElseThrow();
        repository.setApprovedRepairBaseline((String) implementation.get("candidate_path"), (String) implementation.get("candidate_hash"), c.runId(), next);
        repository.invalidateRepairDescendants(c.runId(), c.revision());
        repository.activateRepairRevision(next, now(), c.runId());
        event(c.runId(), next, "implementation", "REPAIR_REVISION", "Human-approved scope retained; new candidate required; failure=" + truncate(failure));
    }

    public synchronized void reconcile() {
        tx.executeWithoutResult(s -> {
                    for (var a : repository.findRunningAttempts()) {
                        String id = (String) a.get("run_id");
                        Instant reservedStart = ((OffsetDateTime) a.get("started_at")).toInstant(), reservedEnd = ((OffsetDateTime) a.get("deadline")).toInstant();
                        long reservation = Math.max(0, Duration.between(reservedStart, now().isBefore(reservedEnd) ? now() : reservedEnd).toMillis());
                        repository.consumeActiveTime(reservation, id);
                        int revision = ((Number) a.get("revision")).intValue();
                        boolean confirmed = WorkflowProcessAdapter.terminateIdentity((Number) a.get("pid"), a.get("process_start"));
                        repository.markAttemptUnknown(now(), confirmed ? "Restart interrupted execution; original process terminated; no blind replay" : "Process identity/termination uncertain; workspace quarantined; no replay", (String) a.get("id"));
                        var r = run(id, true);
                        if (!terminal((String) r.get("state")))
                            stop(id, revision, "Restart with unknown AI/process outcome; attempt reservation preserved");
                        event(id, revision, (String) a.get("task_id"), "RECONCILED_UNKNOWN", "Token/cost unknown; workspace retained; process termination confirmed=" + confirmed);
                    }
                }
        );
    }

    public synchronized void reconcileExpired() {
        tx.executeWithoutResult(s -> {
                    for (var a : repository.findExpiredLeaseAttempts(now())) {
                        String id = (String) a.get("run_id");
                        int revision = ((Number) a.get("revision")).intValue();
                        boolean stopped = WorkflowProcessAdapter.terminateIdentity((Number) a.get("pid"), a.get("process_start"));
                        Instant start = ((OffsetDateTime) a.get("started_at")).toInstant(), deadline = ((OffsetDateTime) a.get("deadline")).toInstant();
                        long elapsed = Math.max(0, Duration.between(start, now().isBefore(deadline) ? now() : deadline).toMillis());
                        repository.markAttemptUnknown(now(), "Expired lease; process termination confirmed=" + stopped + "; workspace quarantined; no replay", (String) a.get("id"));
                        repository.consumeActiveTime(elapsed, id);
                        var r = run(id, true);
                        if (!terminal((String) r.get("state")))
                            stop(id, revision, "Worker lease expired with unknown execution outcome");
                        event(id, revision, (String) a.get("task_id"), "LEASE_EXPIRED_UNKNOWN", "Reserved attempts retained; process termination confirmed=" + stopped);
                    }
                }
        );
    }

    public synchronized void expireWaiting() {
        tx.executeWithoutResult(s -> {
                    for (var r : repository.findExpiredHumanWaitRuns(now().minusSeconds(43200)))
                        stop((String) r.get("id"), rev(r), "Human wait exceeded 12 hours");
                }
        );
    }

    public List<Map<String, Object>> unfinishedProcesses() {
        return repository.findProcessesRequiringTermination();
    }
}
