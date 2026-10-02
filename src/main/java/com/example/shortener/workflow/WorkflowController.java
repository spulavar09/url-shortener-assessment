package com.example.shortener.workflow;

import com.example.shortener.common.ErrorCodes;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;

import java.security.Principal;
import java.net.URI;
import java.util.*;

import static com.example.shortener.workflow.WorkflowModels.*;

/**
 * Operator-protected workflow API. Every endpoint requires the configured operator
 * bearer credential (401 when absent/invalid; 403 without the required role).
 * Reports expose compact, hash-validated review evidence; raw transcripts remain saved locally.
 */
@RestController
@RequestMapping("/api/v1/workflow-runs")
public class WorkflowController {
    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    /**
     * Creates a fixed-graph run from a supported scenario and bounded requirement.
     * Ambiguous requests wait for human clarification before execution.
     *
     * @param request scenario, requirement and ambiguity flag
     * @return 201 with the complete run report and its Location; 400 for invalid input,
     *         429 at the creation limit, or 503 when required storage is unavailable
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRun request) {
        var run = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/workflow-runs/" + run.get("id"))).body(run);
    }

    /**
     * Reads state, revision history, approvals, metrics and hash-validated review evidence.
     * Reading does not approve or advance the run.
     *
     * @param id workflow run identifier
     * @return 200 with the complete report; 404 for an unknown run
     */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Records the authenticated operator's approval or rejection of an exact revision,
     * run version, gate task and evidence hash. Final success requires the final gate.
     *
     * @param id workflow run identifier
     * @param approval exact gate binding, decision and human rationale
     * @param principal authenticated operator; model output cannot supply this identity
     * @return 200 with the updated report; 401 without an operator, 404 for an unknown
     *         run, or 409 for a stale or mismatched approval
     */
    @PostMapping("/{id}/approvals")
    public Map<String, Object> approve(@PathVariable String id, @Valid @RequestBody Approval approval, Principal principal) {
        if (principal == null)
            throw new com.example.shortener.common.ApiException(401, ErrorCodes.OPERATOR_REQUIRED, "Human operator authentication required");
        return service.approve(id, approval, principal.getName());
    }

    /**
     * Adds a version-checked human answer and creates a new graph revision, invalidating
     * prior evidence and approvals while retaining consumed budgets and history.
     * Only the narrowly validated legacy handoff-question stop can reopen a terminal run.
     *
     * @param id workflow run identifier
     * @param answer expected run version and bounded clarification text
     * @return 200 with the revised report; 400 for invalid input, 404 for an unknown
     *         run, or 409 for a stale version or an ineligible terminal run
     */
    @PostMapping("/{id}/clarifications")
    public Map<String, Object> clarify(@PathVariable String id, @Valid @RequestBody Clarification answer) {
        return service.clarify(id, answer);
    }

    /**
     * Cancels a nonterminal run, blocks new claims and fences late task results.
     * Owned child processes are reconciled by the scheduler; uncertain outcomes are retained.
     * Repeating cancellation on a terminal run leaves its outcome unchanged.
     *
     * @param id workflow run identifier
     * @return 200 with the current report; 404 for an unknown run
     */
    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        return service.cancel(id);
    }
}
