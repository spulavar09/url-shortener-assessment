package com.example.shortener.workflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Workflow persistence operations participate in the service-owned transactions. */
@Repository
public class WorkflowRepository {
    private final JdbcTemplate db;
    private static final RowMapper<Map<String, Object>> ROW_MAPPER = (rs, rowNumber) -> {
        Map<String, Object> row = new LinkedHashMap<>();
        var metadata = rs.getMetaData();
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            Object value = rs.getObject(column);
            if (value instanceof java.sql.Clob clob) value = clob.getSubString(1, (int) clob.length());
            row.put(metadata.getColumnLabel(column).toLowerCase(Locale.ROOT), value);
        }
        return row;
    };

    public WorkflowRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> findRun(String runId) {
        return db.query("SELECT * FROM workflow_runs WHERE id=?", ROW_MAPPER, runId);
    }

    public List<Map<String, Object>> findRunForUpdate(String runId) {
        return db.query("SELECT * FROM workflow_runs WHERE id=? FOR UPDATE", ROW_MAPPER, runId);
    }

    public void appendEvent(String runId, int revision, String taskId, String type, String detail, Instant occurredAt) {
        db.update("INSERT INTO workflow_events(run_id,revision,task_id,event_type,detail,occurred_at) VALUES(?,?,?,?,?,?)", runId, revision, taskId, type, detail, occurredAt);
    }

    public void changeRunState(String state, String reason, Instant updatedAt, String runId) {
        db.update("UPDATE workflow_runs SET state=?,reason=?,version=version+1,updated_at=? WHERE id=?", state, reason, updatedAt, runId);
    }

    public void insertTask(String runId, int revision, String taskId, String definition, String state, String skillHash) {
        db.update("INSERT INTO workflow_tasks(run_id,revision,task_id,definition,state,skill_hash) VALUES(?,?,?,?,?,?)", runId, revision, taskId, definition, state, skillHash);
    }

    public long countRecentRuns(Instant createdAfter) {
        return db.queryForObject("SELECT COUNT(*) FROM workflow_runs WHERE created_at>?", Long.class, createdAfter);
    }

    public void insertRun(String runId, String scenario, String requirement, String state, int revision, long version, Instant createdAt, Instant updatedAt, String baseline, String baselineHash) {
        db.update("INSERT INTO workflow_runs(id,scenario,requirement,state,revision,version,created_at,updated_at,baseline,baseline_hash) VALUES(?,?,?,?,?,?,?,?,?,?)", runId, scenario, requirement, state, revision, version, createdAt, updatedAt, baseline, baselineHash);
    }

    public List<Map<String, Object>> findTaskSummaries(String runId, int revision) {
        return db.query("SELECT task_id,state,attempts,fence,evidence_hash,candidate_hash,input_hash FROM workflow_tasks WHERE run_id=? AND revision=? ORDER BY task_id", ROW_MAPPER, runId, revision);
    }

    public List<Map<String, Object>> findQuestionEvents(String runId, int revision, String eventType) {
        return db.query("SELECT detail FROM workflow_events WHERE run_id=? AND revision=? AND event_type=? ORDER BY sequence", ROW_MAPPER, runId, revision, eventType);
    }

    public long countUnresolvedAttempts(String runId) {
        return db.queryForObject("SELECT COUNT(*) FROM workflow_attempts WHERE run_id=? AND state IN ('RUNNING','UNKNOWN')", Long.class, runId);
    }

    public List<Map<String, Object>> findRevisionAttemptsNewestFirst(String runId, int revision) {
        return db.query("SELECT * FROM workflow_attempts WHERE run_id=? AND revision=? ORDER BY started_at DESC", ROW_MAPPER, runId, revision);
    }

    public List<Map<String, Object>> findSucceededRevisionTasks(String runId, int revision) {
        return db.query("SELECT * FROM workflow_tasks WHERE run_id=? AND revision=? AND state='SUCCEEDED'", ROW_MAPPER, runId, revision);
    }

    public List<Map<String, Object>> findEventHistory(String runId) {
        return db.query("SELECT sequence,revision,task_id,event_type,detail,occurred_at FROM workflow_events WHERE run_id=? ORDER BY sequence", ROW_MAPPER, runId);
    }

    public List<Map<String, Object>> findAttemptHistory(String runId) {
        return db.query("SELECT id,revision,task_id,fence,invocation_id,input_hash,state,started_at,deadline,ended_at,exit_code,input_tokens,cached_input_tokens,output_tokens,evidence_hash,detail FROM workflow_attempts WHERE run_id=? ORDER BY started_at", ROW_MAPPER, runId);
    }

    public List<Map<String, Object>> findApprovalHistory(String runId) {
        return db.query("SELECT revision,task_id,run_version,evidence_hash,approved,principal,rationale,occurred_at FROM workflow_approvals WHERE run_id=? ORDER BY occurred_at", ROW_MAPPER, runId);
    }

    public List<Map<String, Object>> findGraphHistory(String runId) {
        return db.query("SELECT revision,task_id,definition,state,evidence_hash,candidate_hash,input_hash FROM workflow_tasks WHERE run_id=? ORDER BY revision,task_id", ROW_MAPPER, runId);
    }

    public long countTerminalRuns() {
        return db.queryForObject("SELECT COUNT(*) FROM workflow_runs WHERE state IN ('SUCCEEDED','FAILED','SAFELY_STOPPED')", Long.class);
    }

    public long countSuccessfulRuns() {
        return db.queryForObject("SELECT COUNT(*) FROM workflow_runs WHERE state='SUCCEEDED'", Long.class);
    }

    public long countCancelledRuns() {
        return db.queryForObject("SELECT COUNT(*) FROM workflow_runs WHERE state='CANCELLED'", Long.class);
    }

    public long countImplementationRuns() {
        return db.queryForObject("SELECT COUNT(DISTINCT run_id) FROM workflow_attempts WHERE task_id='implementation'", Long.class);
    }

    public long countCompensatedRuns() {
        return db.queryForObject("SELECT COUNT(DISTINCT run_id) FROM workflow_events WHERE event_type='COMPENSATED'", Long.class);
    }

    public List<Map<String, Object>> findPublishedTaskEvidence(String runId) {
        return db.query("SELECT revision,task_id,state,evidence_path,evidence_hash,input_hash,candidate_hash FROM workflow_tasks WHERE run_id=? AND evidence_path IS NOT NULL ORDER BY revision,task_id", ROW_MAPPER, runId);
    }

    public List<Map<String, Object>> findPublishedAttemptEvidence(String runId) {
        return db.query("SELECT revision,task_id,state,evidence_path,evidence_hash,input_hash FROM workflow_attempts WHERE run_id=? AND evidence_path IS NOT NULL ORDER BY started_at", ROW_MAPPER, runId);
    }

    public void invalidateRevisionTasks(String runId, int revision) {
        db.update("UPDATE workflow_tasks SET state='INVALIDATED',fence=fence+1 WHERE run_id=? AND revision=? AND state<>'CANCELLED'", runId, revision);
    }

    public void activateClarifiedRevision(String requirement, int revision, Instant updatedAt, String runId) {
        db.update("UPDATE workflow_runs SET requirement=?,revision=?,state='RUNNING',version=version+1,updated_at=? WHERE id=?", requirement, revision, updatedAt, runId);
    }

    public List<Map<String, Object>> findTask(String runId, int revision, String taskId) {
        return db.query("SELECT * FROM workflow_tasks WHERE run_id=? AND revision=? AND task_id=?", ROW_MAPPER, runId, revision, taskId);
    }

    public void insertApproval(String approvalId, String runId, int revision, String taskId, long runVersion, String evidenceHash, boolean approved, String principal, String rationale, Instant occurredAt) {
        db.update("INSERT INTO workflow_approvals(id,run_id,revision,task_id,run_version,evidence_hash,approved,principal,rationale,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?)", approvalId, runId, revision, taskId, runVersion, evidenceHash, approved, principal, rationale, occurredAt);
    }

    public void recordGateDecision(String state, String runId, int revision, String taskId) {
        db.update("UPDATE workflow_tasks SET state=? WHERE run_id=? AND revision=? AND task_id=?", state, runId, revision, taskId);
    }

    public void cancelUnfinishedRevisionTasks(String runId, int revision) {
        db.update("UPDATE workflow_tasks SET state='CANCELLED',fence=fence+1 WHERE run_id=? AND revision=? AND state<>'SUCCEEDED'", runId, revision);
    }

    public long countRunningAttempts() {
        return db.queryForObject("SELECT COUNT(*) FROM workflow_attempts WHERE state='RUNNING'", Long.class);
    }

    public List<Map<String, Object>> findDispatchableRuns() {
        return db.query("SELECT * FROM workflow_runs WHERE state='RUNNING' ORDER BY created_at", ROW_MAPPER);
    }

    public List<Map<String, Object>> findOrderedRevisionTasks(String runId, int revision) {
        return db.query("SELECT * FROM workflow_tasks WHERE run_id=? AND revision=? ORDER BY task_id", ROW_MAPPER, runId, revision);
    }

    public void publishGateEvidence(String state, String evidencePath, String evidenceHash, String candidatePath, String candidateHash, String inputHash, String runId, int revision, String taskId) {
        db.update("UPDATE workflow_tasks SET state=?,evidence_path=?,evidence_hash=?,candidate_path=?,candidate_hash=?,input_hash=? WHERE run_id=? AND revision=? AND task_id=?", state, evidencePath, evidenceHash, candidatePath, candidateHash, inputHash, runId, revision, taskId);
    }

    public List<Map<String, Object>> findRunningTimeReservations(String runId) {
        return db.query("SELECT started_at,deadline FROM workflow_attempts WHERE run_id=? AND state='RUNNING'", ROW_MAPPER, runId);
    }

    public void claimTask(int attempts, long fence, Instant leaseUntil, String inputHash, String runId, int revision, String taskId) {
        db.update("UPDATE workflow_tasks SET state='RUNNING',attempts=?,fence=?,lease_until=?,input_hash=? WHERE run_id=? AND revision=? AND task_id=?", attempts, fence, leaseUntil, inputHash, runId, revision, taskId);
    }

    public void insertAttemptReservation(String attemptId, String runId, int revision, String taskId, long fence, String invocationId, String inputHash, String workspace, String state, Instant startedAt, Instant deadline) {
        db.update("INSERT INTO workflow_attempts(id,run_id,revision,task_id,fence,invocation_id,input_hash,workspace,state,started_at,deadline) VALUES(?,?,?,?,?,?,?,?,?,?,?)", attemptId, runId, revision, taskId, fence, invocationId, inputHash, workspace, state, startedAt, deadline);
    }

    public void blockUnfinishedRevisionTasks(String runId, int revision) {
        db.update("UPDATE workflow_tasks SET state='BLOCKED',fence=fence+1 WHERE run_id=? AND revision=? AND state IN ('PENDING','RUNNING','AWAITING_APPROVAL')", runId, revision);
    }

    public List<Map<String, Object>> findDependencyEvidence(String runId, int revision, String taskId) {
        return db.query("SELECT evidence_path,evidence_hash FROM workflow_tasks WHERE run_id=? AND revision=? AND task_id=?", ROW_MAPPER, runId, revision, taskId);
    }

    public void renewTaskLease(Instant leaseUntil, String runId, int revision, String taskId, long fence) {
        db.update("UPDATE workflow_tasks SET lease_until=? WHERE run_id=? AND revision=? AND task_id=? AND fence=? AND state='RUNNING'", leaseUntil, runId, revision, taskId, fence);
    }

    public long countCurrentTaskClaim(String runId, int revision, String taskId, long fence, String inputHash, Instant leaseAfter) {
        return db.queryForObject("SELECT COUNT(*) FROM workflow_tasks WHERE run_id=? AND revision=? AND task_id=? AND fence=? AND input_hash=? AND lease_until>? AND state='RUNNING'", Long.class, runId, revision, taskId, fence, inputHash, leaseAfter);
    }

    public void recordProcessIdentity(long pid, Instant processStart, String attemptId) {
        db.update("UPDATE workflow_attempts SET pid=?,process_start=? WHERE id=? AND state='RUNNING'", pid, processStart, attemptId);
    }

    public List<Map<String, Object>> findAttempt(String attemptId) {
        return db.query("SELECT * FROM workflow_attempts WHERE id=?", ROW_MAPPER, attemptId);
    }

    public void markAttemptUnknown(Instant endedAt, String detail, String attemptId) {
        db.update("UPDATE workflow_attempts SET state='UNKNOWN',ended_at=?,detail=? WHERE id=?", endedAt, detail, attemptId);
    }

    public void recordAttemptOutcome(String state, Instant endedAt, int exitCode, Long inputTokens, Long cachedInputTokens, Long outputTokens, String evidencePath, String evidenceHash, String detail, String attemptId) {
        db.update("UPDATE workflow_attempts SET state=?,ended_at=?,exit_code=?,input_tokens=?,cached_input_tokens=?,output_tokens=?,evidence_path=?,evidence_hash=?,detail=? WHERE id=?", state, endedAt, exitCode, inputTokens, cachedInputTokens, outputTokens, evidencePath, evidenceHash, detail, attemptId);
    }

    public void consumeActiveTime(long elapsedMs, String runId) {
        db.update("UPDATE workflow_runs SET active_ms=active_ms+? WHERE id=?", elapsedMs, runId);
    }

    public void blockTaskForClarification(String evidencePath, String evidenceHash, String runId, int revision, String taskId, long fence) {
        db.update("UPDATE workflow_tasks SET state='BLOCKED',evidence_path=?,evidence_hash=? WHERE run_id=? AND revision=? AND task_id=? AND fence=?", evidencePath, evidenceHash, runId, revision, taskId, fence);
    }

    public void completeFencedTask(String evidencePath, String evidenceHash, String candidatePath, String candidateHash, String runId, int revision, String taskId, long fence) {
        db.update("UPDATE workflow_tasks SET state='SUCCEEDED',evidence_path=?,evidence_hash=?,candidate_path=?,candidate_hash=? WHERE run_id=? AND revision=? AND task_id=? AND fence=?", evidencePath, evidenceHash, candidatePath, candidateHash, runId, revision, taskId, fence);
    }

    public void failFencedTask(String evidencePath, String evidenceHash, String runId, int revision, String taskId, long fence) {
        db.update("UPDATE workflow_tasks SET state='FAILED',evidence_path=?,evidence_hash=?,lease_until=NULL WHERE run_id=? AND revision=? AND task_id=? AND fence=? AND state='RUNNING'", evidencePath, evidenceHash, runId, revision, taskId, fence);
    }

    public int findTaskAttemptCount(String runId, int revision, String taskId) {
        return db.queryForObject("SELECT attempts FROM workflow_tasks WHERE run_id=? AND revision=? AND task_id=?", Integer.class, runId, revision, taskId);
    }

    public void scheduleTaskRetry(Instant retryAfter, String runId, int revision, String taskId) {
        db.update("UPDATE workflow_tasks SET state='PENDING',retry_after=? WHERE run_id=? AND revision=? AND task_id=?", retryAfter, runId, revision, taskId);
    }

    public List<Map<String, Object>> findRevisionTasksForRepair(String runId, int revision) {
        return db.query("SELECT * FROM workflow_tasks WHERE run_id=? AND revision=?", ROW_MAPPER, runId, revision);
    }

    public void carryApprovedTaskEvidence(String evidencePath, String evidenceHash, String candidatePath, String candidateHash, String inputHash, String runId, int revision, String taskId) {
        db.update("UPDATE workflow_tasks SET state='SUCCEEDED',evidence_path=?,evidence_hash=?,candidate_path=?,candidate_hash=?,input_hash=? WHERE run_id=? AND revision=? AND task_id=?", evidencePath, evidenceHash, candidatePath, candidateHash, inputHash, runId, revision, taskId);
    }

    public void setApprovedRepairBaseline(String candidatePath, String candidateHash, String runId, int revision) {
        db.update("UPDATE workflow_tasks SET candidate_path=?,candidate_hash=? WHERE run_id=? AND revision=? AND task_id='design-approval'", candidatePath, candidateHash, runId, revision);
    }

    public void invalidateRepairDescendants(String runId, int revision) {
        db.update("UPDATE workflow_tasks SET state='INVALIDATED',fence=fence+1 WHERE run_id=? AND revision=? AND task_id NOT IN ('requirements','architecture','design-approval')", runId, revision);
    }

    public void activateRepairRevision(int revision, Instant updatedAt, String runId) {
        db.update("UPDATE workflow_runs SET revision=?,repair_count=repair_count+1,state='RUNNING',version=version+1,updated_at=? WHERE id=?", revision, updatedAt, runId);
    }

    public List<Map<String, Object>> findRunningAttempts() {
        return db.query("SELECT * FROM workflow_attempts WHERE state='RUNNING'", ROW_MAPPER);
    }

    public List<Map<String, Object>> findExpiredLeaseAttempts(Instant leaseBefore) {
        return db.query("SELECT a.* FROM workflow_attempts a JOIN workflow_tasks t ON a.run_id=t.run_id AND a.revision=t.revision AND a.task_id=t.task_id WHERE a.state='RUNNING' AND t.state='RUNNING' AND t.lease_until<?", ROW_MAPPER, leaseBefore);
    }

    public List<Map<String, Object>> findExpiredHumanWaitRuns(Instant updatedBefore) {
        return db.query("SELECT * FROM workflow_runs WHERE state IN ('AWAITING_APPROVAL','AWAITING_INPUT') AND updated_at<?", ROW_MAPPER, updatedBefore);
    }

    public List<Map<String, Object>> findProcessesRequiringTermination() {
        return db.query("SELECT a.* FROM workflow_attempts a JOIN workflow_runs r ON a.run_id=r.id WHERE a.state='RUNNING' AND (r.state IN ('CANCELLED','SAFELY_STOPPED') OR a.revision<>r.revision)", ROW_MAPPER);
    }
}
