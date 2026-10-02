package com.example.shortener.workflow;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "workflow")
public record WorkflowProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue(".local-data/workflow") String workspaceRoot,
        @DefaultValue(".") String projectRoot,
        @DefaultValue("agent-skills") String skillsRoot,
        @DefaultValue("codex") String codexCommand,
        @DefaultValue("mvn") String mavenCommand,
        @DefaultValue("") String codexModel,
        @DefaultValue("medium") String reasoningEffort,
        @DefaultValue("") String mavenRepository,
        @DefaultValue("true") boolean verificationOffline,
        @DefaultValue("true") boolean scenarioFixtures,
        @DefaultValue("2") int concurrency,
        @DefaultValue("600") int agentTimeoutSeconds,
        @DefaultValue("300") int verificationTimeoutSeconds) {

    public WorkflowProperties {
        if (!Set.of("low", "medium", "high").contains(reasoningEffort)) {
            throw new IllegalArgumentException("Unsupported reasoning effort");
        }
        concurrency = Math.max(1, Math.min(2, concurrency));
        agentTimeoutSeconds = Math.max(1, Math.min(600, agentTimeoutSeconds));
        verificationTimeoutSeconds = Math.max(1, Math.min(300, verificationTimeoutSeconds));
    }
}
