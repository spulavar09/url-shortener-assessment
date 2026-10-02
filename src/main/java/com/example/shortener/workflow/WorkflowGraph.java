package com.example.shortener.workflow;

import com.example.shortener.common.ErrorCodes;

import java.util.*;

import com.example.shortener.common.ApiException;

import static com.example.shortener.workflow.WorkflowModels.*;

public final class WorkflowGraph {
    public static final Set<String> SKILLS = Set.of("shortener-requirements", "shortener-architecture", "shortener-implementation-repair", "shortener-verification-review", "shortener-handoff");

    private WorkflowGraph() {
    }

    public static List<Task> initial(String requirement) {
        return List.of(
                task("requirements", "AGENT", "shortener-requirements", requirement, List.of(), List.of("artifacts/requirements/")),
                task("architecture", "AGENT", "shortener-architecture", requirement, List.of("requirements"), List.of("artifacts/architecture/")),
                task("design-approval", "APPROVAL", null, "Review normalized requirements and architecture", List.of("requirements", "architecture"), List.of()),
                task("implementation", "AGENT", "shortener-implementation-repair", requirement, List.of("design-approval"), List.of("src/", "pom.xml", "artifacts/implementation/")),
                task("verify", "VERIFY", null, "Run Maven verification", List.of("implementation"), List.of()),
                task("review", "AGENT", "shortener-verification-review", "Review the exact implementation candidate", List.of("implementation"), List.of("artifacts/review/")),
                task("join", "JOIN", null, "Synchronize independent checks", List.of("verify", "review"), List.of()),
                task("handoff", "AGENT", "shortener-handoff", "Prepare release readiness report", List.of("join"), List.of("artifacts/handoff/")),
                task("final-approval", "APPROVAL", null, "Review final candidate and actual verification evidence", List.of("handoff", "join"), List.of()));
    }

    private static Task task(String id, String type, String skill, String prompt, List<String> deps, List<String> paths) {
        return new Task(id, type, skill, prompt, deps, paths);
    }

    public static void validate(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty() || tasks.size() > 20) bad("A bounded task graph is required");
        Map<String, Task> map = new HashMap<>();
        for (Task t : tasks) {
            if (t == null || t.id() == null || !t.id().matches("[a-z][a-z0-9-]{0,63}") || map.put(t.id(), t) != null)
                bad("Invalid or duplicate task ID");
            if (!Set.of("AGENT", "VERIFY", "APPROVAL", "JOIN").contains(t.type()) || t.dependencies() == null || t.allowedPaths() == null || t.prompt() == null || t.prompt().length() > 8000)
                bad("Invalid task declaration");
            if ("AGENT".equals(t.type()) && !SKILLS.contains(t.skill())) bad("Unknown versioned skill");
            for (String p : t.allowedPaths()) WorkspaceStore.validateRelative(p);
            if (!"AGENT".equals(t.type()) && !t.allowedPaths().isEmpty()) bad("Only agent tasks declare writes");
        }
        for (Task t : tasks) for (String d : t.dependencies()) if (!map.containsKey(d)) bad("Missing dependency");
        Set<String> visited = new HashSet<>(), active = new HashSet<>();
        for (String id : map.keySet()) visit(id, map, visited, active);
        require(map, "design-approval", "APPROVAL");
        require(map, "final-approval", "APPROVAL");
        require(map, "verify", "VERIFY");
        require(map, "join", "JOIN");
        require(map, "requirements", "AGENT");
        require(map, "architecture", "AGENT");
        require(map, "implementation", "AGENT");
        require(map, "review", "AGENT");
        require(map, "handoff", "AGENT");
        if (!ancestors("design-approval", map).containsAll(Set.of("requirements", "architecture")))
            bad("Design gate must bind requirements and architecture");
        for (Task t : tasks)
            if ("AGENT".equals(t.type()) && "shortener-implementation-repair".equals(t.skill())) {
                if (!t.id().equals("implementation"))
                    bad("This bounded prototype supports one mutation task per revision; parallel mutation merge is unsupported");
                if (!ancestors(t.id(), map).contains("design-approval")) bad("Implementation requires design approval");
            }
        if (!ancestors("verify", map).contains("implementation") || !ancestors("review", map).contains("implementation"))
            bad("Both verification branches require candidate");
        if (!ancestors("join", map).containsAll(Set.of("verify", "review")) || !ancestors("final-approval", map).containsAll(Set.of("join", "handoff", "design-approval")))
            bad("Mandatory verification and final gates are missing");
        requireSkill(map, "requirements", "shortener-requirements");
        requireSkill(map, "architecture", "shortener-architecture");
        requireSkill(map, "implementation", "shortener-implementation-repair");
        requireSkill(map, "review", "shortener-verification-review");
        requireSkill(map, "handoff", "shortener-handoff");
        for (Task t : tasks)
            if (!t.id().equals("final-approval") && !ancestors("final-approval", map).contains(t.id()))
                bad("All tasks must feed final approval");
    }

    private static void requireSkill(Map<String, Task> m, String id, String skill) {
        if (!skill.equals(m.get(id).skill())) bad("Mandatory role skill is fixed: " + id);
    }

    private static void require(Map<String, Task> m, String id, String type) {
        if (!m.containsKey(id) || !type.equals(m.get(id).type())) bad("Missing mandatory task " + id);
    }

    private static void visit(String id, Map<String, Task> map, Set<String> done, Set<String> active) {
        if (done.contains(id)) return;
        if (!active.add(id)) bad("Cyclic graph");
        for (String d : map.get(id).dependencies()) visit(d, map, done, active);
        active.remove(id);
        done.add(id);
    }

    public static Set<String> ancestors(String id, Map<String, Task> map) {
        Set<String> s = new HashSet<>();
        for (String d : map.get(id).dependencies()) {
            s.add(d);
            s.addAll(ancestors(d, map));
        }
        return s;
    }

    private static void bad(String message) {
        throw ApiException.badRequest(ErrorCodes.INVALID_WORKFLOW_PLAN, message);
    }
}
