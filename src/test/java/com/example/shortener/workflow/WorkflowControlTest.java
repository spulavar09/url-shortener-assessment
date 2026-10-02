package com.example.shortener.workflow;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import com.example.shortener.common.ApiException;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static com.example.shortener.workflow.WorkflowModels.*;

class WorkflowControlTest {
    @TempDir
    Path temp;
    WorkflowService service;
    WorkspaceStore files;
    WorkflowProperties config;
    JdbcTemplate db;

    @BeforeEach
    void setup() throws Exception {
        config = new WorkflowProperties(false, temp.resolve("owned").toString(),
                temp.resolve("project").toString(), temp.resolve("skills").toString(),
                "codex", "mvn", "", "medium", "", true, false, 2, 600, 300);
        Files.createDirectories(temp.resolve("project/src"));
        Files.writeString(temp.resolve("project/pom.xml"), "<project/>");
        Files.writeString(temp.resolve("project/src/Main.java"), "class Main {}");
        Files.createDirectories(temp.resolve("project/.local-data"));
        Files.writeString(temp.resolve("project/.local-data/live.mv.db"), "secret");
        for (String skill : WorkflowGraph.SKILLS) {
            Path s = temp.resolve("skills").resolve(skill);
            Files.createDirectories(s);
            Files.writeString(s.resolve("SKILL.md"), "Versioned " + skill);
        }
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2__workflow.sql")).execute(ds);
        db = new JdbcTemplate(ds);
        files = new WorkspaceStore(config);
        service = new WorkflowService(new WorkflowRepository(db), new DataSourceTransactionManager(ds), new ObjectMapper(), Clock.systemUTC(), files, config);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> history(String id) {
        return (List<Map<String, Object>>) service.get(id).get("events");
    }

    private String create() {
        return (String) service.create(new CreateRun("greenfield", "Implement approved domain fixture", false)).get("id");
    }

    private Claim claim() {
        return service.claim().orElseThrow();
    }

    private void success(Claim c) throws Exception {
        String body = "{\"execution\":\"injected-test\",\"summary\":\"test result\"}";
        Path p = files.evidence(c.runId(), c.attemptId(), body);
        service.complete(c, new Outcome(true, false, "Injected deterministic adapter (not real CLI)", p.toString(), WorkspaceStore.hash(body.getBytes()), c.baseline(), files.treeHash(files.owned(c.baseline())), 0));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> task(String id, String task) {
        return ((List<Map<String, Object>>) service.get(id).get("tasks")).stream().filter(t -> t.get("task_id").equals(task)).findFirst().orElseThrow();
    }

    private void design(String id) throws Exception {
        success(claim());
        success(claim());
        assertTrue(service.claim().isEmpty());
        approve(id, "design-approval");
    }

    private void approve(String id, String task) {
        var r = service.get(id);
        var t = task(id, task);
        service.approve(id, new Approval(((Number) r.get("revision")).intValue(), ((Number) r.get("version")).longValue(), task, (String) t.get("evidence_hash"), true, "Human reviewed exact test evidence"), "operator");
    }

    @Test
    void graphRejectsCyclesMissingGatesAndWriteTraversal() {
        List<Task> plan = new ArrayList<>(WorkflowGraph.initial("req"));
        Task old = plan.getFirst();
        plan.set(0, new Task(old.id(), old.type(), old.skill(), old.prompt(), List.of("architecture"), old.allowedPaths()));
        assertThrows(ApiException.class, () -> WorkflowGraph.validate(plan));
        assertThrows(ApiException.class, () -> WorkflowGraph.validate(WorkflowGraph.initial("req").stream().filter(t -> !t.id().equals("final-approval")).toList()));
        assertThrows(ApiException.class, () -> WorkspaceStore.validateRelative("../escape"));
        assertThrows(ApiException.class, () -> WorkspaceStore.validateRelative("approved-inputs/key"));
    }

    @Test
    void humanGateBlocksCodeAndParallelValidationJoinWaitsForBoth() throws Exception {
        String id = create();
        success(claim());
        success(claim());
        service.claim();
        assertEquals("AWAITING_APPROVAL", service.get(id).get("state"));
        assertTrue(service.claim().isEmpty());
        approve(id, "design-approval");
        Claim implementation = claim();
        assertEquals("implementation", implementation.taskId());
        success(implementation);
        Claim one = claim(), two = claim();
        assertEquals(Set.of("review", "verify"), Set.of(one.taskId(), two.taskId()));
        assertTrue(service.claim().isEmpty());
        success(one);
        assertTrue(service.claim().isEmpty());
        assertEquals("PENDING", task(id, "join").get("state"));
        success(two);
        service.claim();
        assertEquals("SUCCEEDED", task(id, "join").get("state"));
        success(claim());
        service.claim();
        assertEquals("AWAITING_APPROVAL", task(id, "final-approval").get("state"));
        approve(id, "final-approval");
        assertEquals("SUCCEEDED", service.get(id).get("state"));
    }

    @Test
    void staleApprovalAndMutatedEvidenceAreRejected() throws Exception {
        String id = create();
        success(claim());
        success(claim());
        service.claim();
        var r = service.get(id);
        var t = task(id, "design-approval");
        long version = ((Number) r.get("version")).longValue();
        String hash = (String) t.get("evidence_hash");
        assertThrows(ApiException.class, () -> service.approve(id, new Approval(1, version - 1, "design-approval", hash, true, "stale"), "operator"));
        String path = db.queryForObject("SELECT evidence_path FROM workflow_tasks WHERE run_id=? AND task_id='design-approval'", String.class, id);
        Files.writeString(Path.of(path), "tampered");
        assertThrows(ApiException.class, () -> service.approve(id, new Approval(1, version, "design-approval", hash, true, "modified"), "operator"));
    }

    @Test
    void oldWorkerCannotCompleteAfterClarificationInvalidatesRevision() throws Exception {
        String id = create();
        Claim old = claim();
        long version = ((Number) service.get(id).get("version")).longValue();
        service.clarify(id, new Clarification(version, "Permanence means immutable destination"));
        success(old);
        assertEquals(2, ((Number) service.get(id).get("revision")).intValue());
        assertEquals("PENDING", task(id, "requirements").get("state"));
        assertTrue(history(id).stream().anyMatch(e -> e.get("event_type").equals("FENCED_RESULT")));
    }

    @Test
    void transientRetryPreservesAttemptsAndStopsAtThree() {
        String id = create();
        for (int i = 1; i <= 3; i++) {
            Claim c = claim();
            service.complete(c, new Outcome(false, true, "Injected transport failure", null, null, null, null, 1));
            assertEquals(i, ((Number) task(id, "requirements").get("attempts")).intValue());
            db.update("UPDATE workflow_tasks SET retry_after=NULL WHERE run_id=?", id);
        }
        assertEquals("SAFELY_STOPPED", service.get(id).get("state"));
        assertTrue(service.claim().isEmpty());
    }

    @Test
    void restartUnknownOutcomeIsNeverBlindlyReplayed() {
        String id = create();
        Claim c = claim();
        service.reconcile();
        assertEquals("SAFELY_STOPPED", service.get(id).get("state"));
        assertEquals("UNKNOWN", db.queryForObject("SELECT state FROM workflow_attempts WHERE id=?", String.class, c.attemptId()));
        assertEquals(1, ((Number) task(id, "requirements").get("attempts")).intValue());
        assertTrue(service.claim().isEmpty());
    }

    @Test
    void cancelStopsClaimsAndFencesLateResult() throws Exception {
        String id = create();
        Claim c = claim();
        service.cancel(id);
        success(c);
        assertEquals("CANCELLED", service.get(id).get("state"));
        assertTrue(service.claim().isEmpty());
        assertTrue(history(id).stream().anyMatch(e -> e.get("event_type").equals("FENCED_RESULT")));
    }

    @Test
    void compensationOnlyRestoresOwnedWorkspaceAndSnapshotExcludesLiveData() throws Exception {
        String id = create();
        Claim c = claim();
        assertFalse(Files.exists(files.owned(c.baseline()).resolve(".local-data")));
        Files.writeString(Path.of(c.workspace()).resolve("src/Main.java"), "bad generated change");
        files.compensate(c.workspace(), c.baseline());
        assertEquals("class Main {}", Files.readString(Path.of(c.workspace()).resolve("src/Main.java")));
        assertEquals("class Main {}", Files.readString(temp.resolve("project/src/Main.java")));
        assertThrows(ApiException.class, () -> files.compensate(temp.resolve("project").toString(), c.baseline()));
    }

    @Test
    void disallowedGeneratedPathCannotPublishCandidate() throws Exception {
        String id = create();
        Claim c = claim();
        Files.writeString(Path.of(c.workspace()).resolve("pom.xml"), "tampered");
        assertThrows(ApiException.class, () -> files.publish(id, c.attemptId(), Path.of(c.workspace()), c.task().allowedPaths(), c.baseline()));
        assertFalse(WorkflowProcessAdapter.terminateIdentity(ProcessHandle.current().pid(), Instant.EPOCH));
    }

    @Test
    void failedVerificationCreatesAtMostTwoRepairRevisions() throws Exception {
        String id = create();
        design(id);
        for (int cycle = 0; cycle < 3; cycle++) {
            success(claim());
            Claim a = claim(), b = claim();
            Claim verify = a.taskId().equals("verify") ? a : b, review = a.taskId().equals("review") ? a : b;
            success(review);
            service.complete(verify, new Outcome(false, false, "Injected deterministic test failure", null, null, null, null, 1));
        }
        assertEquals(2, ((Number) service.get(id).get("repair_count")).intValue());
        assertEquals("SAFELY_STOPPED", service.get(id).get("state"));
        assertTrue(service.claim().isEmpty());
    }

    @Test
    void changedSkillVersionStopsRatherThanReusingApproval() throws Exception {
        String id = create();
        Files.writeString(temp.resolve("skills/shortener-requirements/SKILL.md"), "Materially different skill");
        assertTrue(service.claim().isEmpty());
        assertEquals("SAFELY_STOPPED", service.get(id).get("state"));
    }

    @Test
    void joinRejectsBranchesThatValidateDifferentCandidateHashes() throws Exception {
        String id = create();
        design(id);
        success(claim());
        Claim one = claim(), two = claim();
        success(one);
        success(two);
        db.update("UPDATE workflow_tasks SET candidate_hash='incorrect' WHERE run_id=? AND task_id='review'", id);
        assertTrue(service.claim().isEmpty());
        assertEquals("SAFELY_STOPPED", service.get(id).get("state"));
    }

    @Test
    void fileDatabaseReopenPreservesUnknownAttemptReservation() throws Exception {
        String url = "jdbc:h2:file:" + temp.resolve("restart-store").toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
        var first = new DriverManagerDataSource(url, "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2__workflow.sql")).execute(first);
        var firstDb = new JdbcTemplate(first);
        service = new WorkflowService(new WorkflowRepository(firstDb), new DataSourceTransactionManager(first), new ObjectMapper(), Clock.systemUTC(), files, config);
        String id = create();
        Claim c = claim();
        firstDb.execute("SHUTDOWN");
        var reopened = new DriverManagerDataSource(url, "sa", "");
        db = new JdbcTemplate(reopened);
        service = new WorkflowService(new WorkflowRepository(db), new DataSourceTransactionManager(reopened), new ObjectMapper(), Clock.systemUTC(), files, config);
        service.reconcile();
        assertEquals("SAFELY_STOPPED", service.get(id).get("state"));
        assertEquals("UNKNOWN", db.queryForObject("SELECT state FROM workflow_attempts WHERE id=?", String.class, c.attemptId()));
        assertEquals(1, ((Number) task(id, "requirements").get("attempts")).intValue());
        assertTrue(Files.isDirectory(Path.of(c.workspace())));
        assertTrue(service.claim().isEmpty());
        db.execute("SHUTDOWN");
    }

    @Test
    void expiredLiveLeaseStopsAndLateCompletionCannotOverwriteUnknown() throws Exception {
        String id = create();
        Claim c = claim();
        db.update("UPDATE workflow_tasks SET lease_until=? WHERE run_id=?", Instant.now().minusSeconds(1), id);
        service.reconcileExpired();
        success(c);
        assertEquals("SAFELY_STOPPED", service.get(id).get("state"));
        assertEquals("UNKNOWN", db.queryForObject("SELECT state FROM workflow_attempts WHERE id=?", String.class, c.attemptId()));
    }

    @Test
    void runDeadlineReservationRejectsDispatchBeforeWork() {
        String id = create();
        db.update("UPDATE workflow_runs SET active_ms=3500000 WHERE id=?", id);
        assertTrue(service.claim().isEmpty());
        assertEquals("SAFELY_STOPPED", service.get(id).get("state"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM workflow_attempts WHERE run_id=?", Integer.class, id));
    }

    @Test
    void mandatoryReviewCannotUseDifferentRoleSkill() {
        List<Task> tasks = new ArrayList<>(WorkflowGraph.initial("req"));
        Task review = tasks.stream().filter(t -> t.id().equals("review")).findFirst().orElseThrow();
        tasks.set(tasks.indexOf(review), new Task(review.id(), review.type(), "shortener-requirements", review.prompt(), review.dependencies(), review.allowedPaths()));
        assertThrows(ApiException.class, () -> WorkflowGraph.validate(tasks));
    }

    @Test
    void fixturePinRejectsModifiedExistingAcceptanceTest() throws Exception {
        String id = create();
        Claim c = claim();
        Path baseline = files.owned(c.baseline());
        Files.createDirectories(baseline.resolve("src/test/java"));
        Files.writeString(baseline.resolve("src/test/java/AcceptanceTest.java"), "original");
        Path testCopy = Path.of(c.workspace()).resolve("src/test/java");
        Files.createDirectories(testCopy);
        Files.writeString(testCopy.resolve("AcceptanceTest.java"), "weakened");
        WorkflowProperties pinned = new WorkflowProperties(true, config.workspaceRoot(),
                config.projectRoot(), config.skillsRoot(), "codex", "mvn", "", "medium", "",
                true, true, 2, 600, 300);
        WorkspaceStore policy = new WorkspaceStore(pinned);
        assertThrows(ApiException.class, () -> policy.checkChanges(baseline, Path.of(c.workspace()), List.of("src/")));
    }

    @Test
    void unsupportedParallelMutationIsRejectedBeforeActivation() {
        List<Task> plan = new ArrayList<>(WorkflowGraph.initial("req"));
        plan.add(new Task("second-change", "AGENT", "shortener-implementation-repair", "parallel writer", List.of("design-approval"), List.of("src/")));
        assertThrows(ApiException.class, () -> WorkflowGraph.validate(plan));
    }

    @Test
    void suppliedUsageIsStoredWhileUnreportedUsageRemainsUnknown() throws Exception {
        String id = create();
        Claim c = claim();
        String body = "{\"execution\":\"injected-test\"}";
        Path artifact = files.evidence(id, c.attemptId(), body);
        service.complete(c, new Outcome(true, false, "Injected usage fixture", artifact.toString(), WorkspaceStore.hash(body.getBytes()), c.baseline(), files.treeHash(files.owned(c.baseline())), 0, Map.of("input_tokens", 10L, "cached_input_tokens", 2L, "output_tokens", 5L)));
        assertEquals(10L, db.queryForObject("SELECT input_tokens FROM workflow_attempts WHERE id=?", Long.class, c.attemptId()));
        Claim next = claim();
        success(next);
        assertNull(db.queryForObject("SELECT input_tokens FROM workflow_attempts WHERE id=?", Long.class, next.attemptId()));
        assertTrue(service.get(id).containsKey("metrics"));
    }

    private Outcome questionOutcome(Claim c, List<String> questions, boolean success) throws Exception {
        String result = new ObjectMapper().writeValueAsString(Map.of("summary", "Question-bearing structured result", "questions", questions, "tasks", List.of(), "risks", List.of()));
        String body = new ObjectMapper().writeValueAsString(Map.of("execution", "injected-test", "result", result));
        Path artifact = files.evidence(c.runId(), c.attemptId(), body);
        return new Outcome(success, false, "Structured questions retained", artifact.toString(), WorkspaceStore.hash(body.getBytes()), c.baseline(), files.treeHash(files.owned(c.baseline())), 0, null, questions);
    }

    @Test
    void blockingQuestionsAwaitHumanInputWithoutRetryOrTerminalStop() throws Exception {
        String id = create();
        Claim c = claim();
        List<String> questions = List.of("Which permanence semantics are required?");
        service.complete(c, questionOutcome(c, questions, false));
        assertEquals("AWAITING_INPUT", service.get(id).get("state"));
        assertEquals("BLOCKED", task(id, "requirements").get("state"));
        assertEquals(questions, service.get(id).get("pendingQuestions"));
        assertEquals("AWAITING_INPUT", db.queryForObject("SELECT state FROM workflow_attempts WHERE id=?", String.class, c.attemptId()));
        assertTrue(service.claim().isEmpty());
        assertTrue(new ObjectMapper().writeValueAsString(service.get(id).get("reviewEvidence")).contains("permanence"));
        db.update("UPDATE workflow_runs SET active_ms=750 WHERE id=?", id);
        long version = ((Number) service.get(id).get("version")).longValue();
        service.clarify(id, new Clarification(version, "Immutable destination; optional expiry remains"));
        assertEquals(2, ((Number) service.get(id).get("revision")).intValue());
        assertEquals(750L, ((Number) service.get(id).get("active_ms")).longValue());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM workflow_attempts WHERE run_id=?", Integer.class, id));
        assertEquals("PENDING", task(id, "design-approval").get("state"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parallelVerifierFailurePreservesReviewerClarification(boolean transientFailure) throws Exception {
        String id = create();
        design(id);
        success(claim());
        Claim a = claim(), b = claim();
        Claim review = a.taskId().equals("review") ? a : b;
        Claim verify = a.taskId().equals("verify") ? a : b;
        List<String> questions = List.of("Which resolution semantics should the repair preserve?");
        service.complete(review, questionOutcome(review, questions, false));
        int revision = ((Number) service.get(id).get("revision")).intValue();
        Path evidence = files.evidence(id, verify.attemptId(), "{\"execution\":\"injected-test\",\"failure\":\"parallel verifier failure\"}");
        String evidenceHash = WorkspaceStore.hash(Files.readAllBytes(evidence));
        service.complete(verify, new Outcome(false, transientFailure, "Injected verification failure", evidence.toString(), evidenceHash, null, null, 1));

        var paused = service.get(id);
        assertEquals("AWAITING_INPUT", paused.get("state"));
        assertEquals(revision, ((Number) paused.get("revision")).intValue());
        assertEquals(0, ((Number) paused.get("repair_count")).intValue());
        assertEquals(questions, paused.get("pendingQuestions"));
        assertEquals("BLOCKED", task(id, "review").get("state"));
        assertEquals("FAILED", task(id, "verify").get("state"));
        assertEquals(evidenceHash, task(id, "verify").get("evidence_hash"));
        assertEquals("FAILED", db.queryForObject("SELECT state FROM workflow_attempts WHERE id=?", String.class, verify.attemptId()));
        assertEquals(evidenceHash, db.queryForObject("SELECT evidence_hash FROM workflow_attempts WHERE id=?", String.class, verify.attemptId()));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM workflow_tasks WHERE run_id=? AND state='RUNNING'", Integer.class, id));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM workflow_events WHERE run_id=? AND event_type IN ('REPAIR_REVISION','BOUNDED_RETRY')", Integer.class, id));
        assertTrue(service.claim().isEmpty());

        service.clarify(id, new Clarification(((Number) paused.get("version")).longValue(), "Human-supplied resolution semantics"));
        assertEquals(revision + 1, ((Number) service.get(id).get("revision")).intValue());
        assertEquals("PENDING", task(id, "design-approval").get("state"));
    }

    @Test
    void handoffQuestionsAreFinalReviewItemsAndCannotApproveFinalGate() throws Exception {
        String id = create();
        design(id);
        success(claim());
        Claim a = claim(), b = claim();
        success(a);
        success(b);
        service.claim();
        Claim handoff = claim();
        assertEquals("handoff", handoff.taskId());
        List<String> questions = List.of("Will the human approve the exact candidate?", "Is the fixture the intended artifact?");
        service.complete(handoff, questionOutcome(handoff, questions, true));
        service.claim();
        assertEquals("SUCCEEDED", task(id, "handoff").get("state"));
        assertEquals("AWAITING_APPROVAL", task(id, "final-approval").get("state"));
        assertEquals("AWAITING_APPROVAL", service.get(id).get("state"));
        assertEquals(questions, service.get(id).get("finalReviewItems"));
        assertTrue(new ObjectMapper().writeValueAsString(service.get(id).get("reviewEvidence")).contains("exact candidate"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM workflow_approvals WHERE run_id=?", Integer.class, id));
    }

    @Test
    void adapterQuestionPolicyIsRoleAwareAndBounded() throws Exception {
        var adapter = new WorkflowProcessAdapter(config, files, service, new ObjectMapper());
        List<String> questions = adapter.questionList("{\"questions\":[\"Review the final evidence?\"]}");
        Task handoff = WorkflowGraph.initial("req").stream().filter(t -> t.id().equals("handoff")).findFirst().orElseThrow();
        assertFalse(WorkflowProcessAdapter.blocksForQuestions(handoff, questions));
        for (Task task : WorkflowGraph.initial("req"))
            if ("AGENT".equals(task.type()) && !"handoff".equals(task.id()))
                assertTrue(WorkflowProcessAdapter.blocksForQuestions(task, questions));
        assertThrows(java.io.IOException.class, () -> adapter.questionList(new ObjectMapper().writeValueAsString(Map.of("questions", Collections.nCopies(11, "too many")))));
        assertThrows(java.io.IOException.class, () -> adapter.questionList(new ObjectMapper().writeValueAsString(Map.of("questions", List.of("x".repeat(2049))))));
    }

    @Test
    void genericFatalUnknownAndExpiredStopsCannotBeReopenedByClarification() throws Exception {
        String fatal = create();
        Claim c = claim();
        service.complete(c, new Outcome(false, false, "Policy failure", null, null, null, null, 1));
        long version = ((Number) service.get(fatal).get("version")).longValue();
        assertThrows(ApiException.class, () -> service.clarify(fatal, new Clarification(version, "Resume")));
        String unknown = create();
        claim();
        service.reconcile();
        long unknownVersion = ((Number) service.get(unknown).get("version")).longValue();
        assertThrows(ApiException.class, () -> service.clarify(unknown, new Clarification(unknownVersion, "Resume")));
    }

    @Test
    void knownLegacyQuestionStopRecoveryRequiresExplicitHumanClarificationAndRetainsHistory() throws Exception {
        String id = create();
        design(id);
        success(claim());
        Claim a = claim(), b = claim();
        success(a);
        success(b);
        service.claim();
        Claim handoff = claim();
        String detail = "Unresolved agent questions require reviewed clarification: [\"Will human approve?\"]";
        String structured = new ObjectMapper().writeValueAsString(Map.of("summary", "Release readiness", "questions", List.of("Will human approve?"), "tasks", List.of(), "risks", List.of()));
        String transcript = new ObjectMapper().writeValueAsString(Map.of("type", "item.completed", "item", Map.of("type", "agent_message", "text", structured))) + "\n";
        String body = new ObjectMapper().writeValueAsString(Map.of("failure", detail, "exitCode", 0, "transcript", transcript));
        Path artifact = files.evidence(id, handoff.attemptId(), body);
        service.complete(handoff, new Outcome(false, false, detail, artifact.toString(), WorkspaceStore.hash(body.getBytes()), null, null, 0));
        assertEquals("SAFELY_STOPPED", service.get(id).get("state"));
        long consumed = ((Number) service.get(id).get("active_ms")).longValue();
        int attempts = db.queryForObject("SELECT COUNT(*) FROM workflow_attempts WHERE run_id=?", Integer.class, id);
        long version = ((Number) service.get(id).get("version")).longValue();
        service.clarify(id, new Clarification(version, "Fixture is intended scope; final approval remains a separate human decision"));
        assertEquals("RUNNING", service.get(id).get("state"));
        assertEquals(2, ((Number) service.get(id).get("revision")).intValue());
        assertEquals(consumed, ((Number) service.get(id).get("active_ms")).longValue());
        assertEquals(attempts, db.queryForObject("SELECT COUNT(*) FROM workflow_attempts WHERE run_id=?", Integer.class, id));
        assertEquals("FAILED", db.queryForObject("SELECT state FROM workflow_attempts WHERE id=?", String.class, handoff.attemptId()));
        assertTrue(history(id).stream().anyMatch(e -> e.get("event_type").equals("KNOWN_QUESTION_STOP_RECOVERED")));
        assertEquals("PENDING", task(id, "design-approval").get("state"));
    }
    @Test void consolidatedGetIncludesStructuredReviewAndHistoryWithoutRawTranscripts() throws Exception {
        String id = create();
        Claim c = claim();
        String structured = new ObjectMapper().writeValueAsString(Map.of("summary", "Normalized acceptance criteria", "questions", List.of(), "tasks", List.of(), "risks", List.of("Local fixture scope")));
        String transcript = "RAW_AGENT_TRANSCRIPT_SHOULD_NOT_BE_RETURNED";
        String full = new ObjectMapper().writeValueAsString(Map.of("execution", "injected-test", "result", structured, "transcript", transcript, "exitCode", 0));
        Path artifact = files.evidence(id, c.attemptId(), full);
        String hash = WorkspaceStore.hash(full.getBytes());
        service.complete(c, new Outcome(true, false, "Injected structured evidence", artifact.toString(), hash, c.baseline(), files.treeHash(files.owned(c.baseline())), 0));
        var report = service.get(id);
        for (String field : List.of("attemptHistory", "approvals", "events", "graphRevisions", "metrics", "reviewEvidence")) assertTrue(report.containsKey(field));
        @SuppressWarnings("unchecked") var review = ((List<Map<String, Object>>) report.get("reviewEvidence")).getFirst();
        assertEquals(hash, review.get("evidenceHash"));
        assertEquals(true, review.get("hashValidated"));
        assertEquals("Normalized acceptance criteria", ((tools.jackson.databind.JsonNode) review.get("structuredResult")).path("summary").asString());
        assertFalse(new ObjectMapper().writeValueAsString(report).contains(transcript));
        assertTrue(Files.readString(artifact).contains(transcript));
        Files.writeString(artifact, "tampered");
        assertThrows(ApiException.class, () -> service.get(id));
    }

    @Test void consolidatedGetIncludesBoundedActualVerifierOutputAndExactArtifactHash() throws Exception {
        String id = create(); design(id); success(claim());
        Claim one = claim(), two = claim();
        Claim verify = one.taskId().equals("verify") ? one : two;
        Claim review = one.taskId().equals("review") ? one : two;
        success(review);
        String transcript = "x".repeat(17000) + "\nTests run: 3, Failures: 0, Errors: 0\nBUILD SUCCESS";
        String full = new ObjectMapper().writeValueAsString(Map.of("execution", "injected-test", "result", "Actual Maven verify exit 0", "transcript", transcript, "exitCode", 0));
        Path artifact = files.evidence(id, verify.attemptId(), full);
        String hash = WorkspaceStore.hash(full.getBytes());
        service.complete(verify, new Outcome(true, false, "Injected verification evidence", artifact.toString(), hash, verify.baseline(), files.treeHash(files.owned(verify.baseline())), 0));
        @SuppressWarnings("unchecked") var evidence = (List<Map<String, Object>>) service.get(id).get("reviewEvidence");
        var verifier = evidence.stream().filter(e -> e.get("task_id").equals("verify")).findFirst().orElseThrow();
        assertEquals(hash, verifier.get("evidenceHash"));
        assertEquals(16000, ((String) verifier.get("verifierOutput")).length());
        assertEquals(true, verifier.get("verifierOutputTruncated"));
        assertTrue(((String) verifier.get("verifierOutput")).contains("Tests run: 3"));
        assertEquals(WorkspaceStore.hash(transcript.getBytes()), verifier.get("fullOutputHash"));
        assertEquals(transcript, new ObjectMapper().readTree(Files.readString(artifact)).path("transcript").asString());
    }

}
