package com.example.shortener;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "workflow.enabled=false", "workflow.workspace-root=target/http-workflows", "spring.datasource.url=jdbc:h2:mem:http-contract;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000",
        "app.security.operator-token=test-operator-credential", "app.limits.enabled=false", "server.address=127.0.0.1"
})
class ApiIntegrationTest {
    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;
    final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();

    HttpResponse<String> send(String method, String path, String body, String media, boolean operator) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10)).header("X-Correlation-ID", "http-contract-test");
        if (media != null) builder.header("Content-Type", media);
        if (operator) builder.header("Authorization", "Bearer test-operator-credential");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, null, false);
    }

    JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
        assertEquals(status, json(response).get("status").asInt());
        assertEquals(code, json(response).get("errorCode").asText());
        assertEquals("http-contract-test", json(response).get("correlationId").asText());
        assertFalse(response.body().contains("stackTrace"));
    }

    String uniqueAlias() {
        return "http" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void anonymousLinkContractLifecycleAndAnalytics() throws Exception {
        String alias = uniqueAlias();
        String destination = "https://example.org/a%2Fb?b=2&a=%23&a=1#part";
        var created = send("POST", "/api/v1/links", mapper.writeValueAsString(java.util.Map.of("destinationUrl", destination, "customAlias", alias)), "application/json", false);
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("/api/v1/links/" + alias, created.headers().firstValue("Location").orElseThrow());
        assertEquals("http-contract-test", created.headers().firstValue("X-Correlation-ID").orElseThrow());
        assertEquals("ACTIVE", json(created).get("state").asText());
        assertEquals(destination, json(created).get("destinationUrl").asText());
        var head = send("HEAD", "/r/" + alias, null, null, false);
        assertEquals(302, head.statusCode());
        assertEquals("", head.body());
        assertEquals(destination, head.headers().firstValue("Location").orElseThrow());
        assertEquals("no-store", head.headers().firstValue("Cache-Control").orElseThrow());
        var before = get("/api/v1/links/" + alias);
        assertEquals(200, before.statusCode(), before.body());
        assertEquals(destination, json(before).get("link").get("destinationUrl").asText());
        assertEquals("ACTIVE", json(before).get("link").get("state").asText());
        assertEquals(0, json(before).get("analytics").get("lifetimeRecordedTotal").asLong());
        var date = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        var range = get("/api/v1/links/" + alias + "?from=" + date + "&to=" + date);
        assertEquals(200, range.statusCode(), range.body());
        assertEquals(1, json(range).get("analytics").get("daily").size());
        problem(get("/api/v1/links/" + alias + "?from=" + date + "&to=" + date.minusDays(1)), 400, "INVALID_DATE_RANGE");
        problem(get("/api/v1/links/" + alias + "/analytics"), 404, "RESOURCE_NOT_FOUND");
        var redirect = get("/r/" + alias);
        assertEquals(302, redirect.statusCode());
        assertEquals(destination, redirect.headers().firstValue("Location").orElseThrow());
        assertEquals("no-store", redirect.headers().firstValue("Cache-Control").orElseThrow());
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        HttpResponse<String> counts;
        do {
            counts = get("/api/v1/links/" + alias);
            if (json(counts).get("analytics").get("lifetimeRecordedTotal").asLong() > 0) break;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertEquals(1, json(counts).get("analytics").get("lifetimeRecordedTotal").asLong());
        assertEquals(1, json(counts).get("analytics").get("requestedRangeRecordedTotal").asLong());
        assertEquals(7, json(counts).get("analytics").get("daily").size());
        var disabled = send("PATCH", "/api/v1/links/" + alias, "{\"disabled\":true,\"expectedVersion\":0}", "application/json", false);
        assertEquals(200, disabled.statusCode(), disabled.body());
        assertEquals(1, json(disabled).get("version").asLong());
        problem(send("PATCH", "/api/v1/links/" + alias, "{\"disabled\":true,\"expectedVersion\":0}", "application/json", false), 409, "STALE_VERSION");
        problem(get("/r/" + alias), 410, "LINK_GONE");
        assertEquals(204, send("DELETE", "/api/v1/links/" + alias, null, null, false).statusCode());
        assertEquals(204, send("DELETE", "/api/v1/links/" + alias, null, null, false).statusCode());
        assertEquals("DELETED", json(get("/api/v1/links/" + alias)).get("link").get("state").asText());
        problem(get("/r/" + alias), 410, "LINK_GONE");
        assertEquals(1, json(get("/api/v1/links/" + alias)).get("analytics").get("lifetimeRecordedTotal").asLong());
        problem(get("/r/" + uniqueAlias()), 404, "LINK_NOT_FOUND");
    }

    @Test
    void strictJsonAndHttpBoundaryFailures() throws Exception {
        problem(send("POST", "/api/v1/links", "{\"destinationUrl\":\"https://example.org\",\"unexpected\":true}", "application/json", false), 400, "INVALID_REQUEST");
        problem(send("POST", "/api/v1/links", "{broken", "application/json", false), 400, "INVALID_REQUEST");
        problem(send("POST", "/api/v1/links", "https://example.org", "text/plain", false), 415, "UNSUPPORTED_MEDIA_TYPE");
        problem(send("POST", "/api/v1/links", "{\"destinationUrl\":\"" + "a".repeat(17000) + "\"}", "application/json", false), 413, "BODY_TOO_LARGE");
        problem(get("/api/v1/links"), 405, "METHOD_NOT_ALLOWED");
    }

    @Test
    void annotatedLinkFieldsPreserveHttpErrorCodes() throws Exception {
        for (String value : new String[]{"", " ", "https://example.org/" + "a".repeat(4096)}) {
            var response = send("POST", "/api/v1/links",
                    mapper.writeValueAsString(java.util.Map.of("destinationUrl", value)), "application/json", false);
            problem(response, 400, "INVALID_DESTINATION");
            assertNotNull(json(response).get("fieldErrors").get("destinationUrl"));
        }
        problem(send("POST", "/api/v1/links", "{}", "application/json", false), 400, "INVALID_DESTINATION");
        problem(send("POST", "/api/v1/links", "{\"destinationUrl\":\"https://example.org\",\"customAlias\":\"abc\"}",
                "application/json", false), 400, "INVALID_ALIAS");
        String alias = uniqueAlias();
        assertEquals(201, send("POST", "/api/v1/links",
                mapper.writeValueAsString(java.util.Map.of("destinationUrl", "https://example.org", "customAlias", alias)),
                "application/json", false).statusCode());
        for (String body : new String[]{"{}", "{\"disabled\":false,\"expectedVersion\":0}",
                "{\"disabled\":true}", "{\"disabled\":true,\"expectedVersion\":-1}"}) {
            problem(send("PATCH", "/api/v1/links/" + alias, body, "application/json", false), 400, "INVALID_DISABLE");
        }
        assertEquals("ACTIVE", json(get("/api/v1/links/" + alias)).get("link").get("state").asText());
        problem(send("POST", "/api/v1/workflow-runs", "{\"scenario\":\"\",\"requirement\":\"\",\"ambiguous\":false}", "application/json", true), 400, "VALIDATION_FAILED");
    }

    @Test
    void workflowAndOperationsAreProtectedAndDocsPublic() throws Exception {
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_runs", Long.class);
        problem(send("POST", "/api/v1/workflow-runs", "{}", "application/json", false), 401, "AUTHENTICATION_REQUIRED");
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM workflow_runs", Long.class));
        problem(send("GET", "/api/v1/workflow-runs/unknown", null, null, true), 404, "WORKFLOW_NOT_FOUND");
        problem(get("/actuator/metrics"), 401, "AUTHENTICATION_REQUIRED");
        assertEquals(200, send("GET", "/actuator/metrics", null, null, true).statusCode());
        var docs = get("/v3/api-docs");
        assertEquals(200, docs.statusCode(), docs.body());
        assertNotNull(json(docs).get("paths").get("/api/v1/links"));
        var paths = json(docs).get("paths");
        assertNotNull(paths.get("/r/{code}").get("head"));
        assertNull(paths.get("/api/v1/links").get("get"));
        assertNull(paths.get("/api/v1/links/{code}/analytics"));
        assertNull(paths.get("/api/v1/workflow-runs/{id}/events"));
        assertNull(paths.get("/api/v1/workflow-runs/{id}/report"));
        assertNull(paths.get("/api/v1/workflow-runs/{id}/revisions/{revision}/tasks/{taskId}/evidence"));
        assertNull(paths.get("/api/v1/workflow-runs/{id}/plans"));
        assertEquals(404, send("GET", "/actuator/info", null, null, true).statusCode());
        var created = send("POST", "/api/v1/workflow-runs", "{\"scenario\":\"ambiguous\",\"requirement\":\"Make links permanent and improve analytics\",\"ambiguous\":true}", "application/json", true);
        assertEquals(201, created.statusCode(), created.body());
        String id = json(created).get("id").asText();
        var report = send("GET", "/api/v1/workflow-runs/" + id, null, null, true);
        assertEquals(200, report.statusCode(), report.body());
        assertEquals("AWAITING_INPUT", json(report).get("state").asText());
        for (String field : java.util.List.of("tasks", "attemptHistory", "events", "approvals", "metrics", "graphRevisions", "reviewEvidence"))
            assertNotNull(json(report).get(field), field);
        for (String suffix : java.util.List.of("/events", "/report", "/revisions/1/tasks/requirements/evidence"))
            problem(send("GET", "/api/v1/workflow-runs/" + id + suffix, null, null, true), 404, "RESOURCE_NOT_FOUND");
        problem(send("POST", "/api/v1/workflow-runs/" + id + "/plans", "{}", "application/json", true), 404, "RESOURCE_NOT_FOUND");
    }
}
