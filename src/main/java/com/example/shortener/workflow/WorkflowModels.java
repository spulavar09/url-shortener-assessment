package com.example.shortener.workflow;

import java.util.List;

import jakarta.validation.constraints.*;

public final class WorkflowModels {
    private WorkflowModels() {
    }

    public record CreateRun(@NotBlank @Size(max = 32) String scenario, @NotBlank @Size(max = 8000) String requirement,
                            boolean ambiguous) {
    }

    public record Approval(int revision, long version, @NotBlank String taskId, @NotBlank String evidenceHash,
                           boolean approved, @NotBlank @Size(max = 2048) String rationale) {
    }

    public record Clarification(long version, @NotBlank @Size(max = 8000) String answer) {
    }

    public record Task(String id, String type, String skill, String prompt, List<String> dependencies,
                       List<String> allowedPaths) {
    }

    public record Claim(String runId, int revision, String taskId, long fence, String attemptId, String invocationId,
                        String inputHash, String workspace, String baseline, String requirement, String skillHash,
                        Task task) {
    }

    public record Outcome(boolean success, boolean transientFailure, String detail, String evidencePath,
                          String evidenceHash, String candidatePath, String candidateHash, int exitCode,
                          java.util.Map<String, Long> usage, List<String> questions) {
        public Outcome(boolean success, boolean transientFailure, String detail, String evidencePath, String evidenceHash, String candidatePath, String candidateHash, int exitCode, java.util.Map<String, Long> usage) {
            this(success, transientFailure, detail, evidencePath, evidenceHash, candidatePath, candidateHash, exitCode, usage, List.of());
        }

        public Outcome {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }

        public Outcome(boolean success, boolean transientFailure, String detail, String evidencePath, String evidenceHash, String candidatePath, String candidateHash, int exitCode) {
            this(success, transientFailure, detail, evidencePath, evidenceHash, candidatePath, candidateHash, exitCode, null, List.of());
        }
    }
}
