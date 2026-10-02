package com.example.shortener.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

@Component
public class ProblemResponses {
    private final ObjectMapper mapper;

    public ProblemResponses(ObjectMapper mapper) { this.mapper = mapper; }

    public static ProblemDetail problem(HttpServletRequest request, int status, String code, String message) {
        var problem = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status), message);
        problem.setType(URI.create("urn:shortener:error:" + code.toLowerCase().replace('_', '-')));
        problem.setProperty("errorCode", code);
        problem.setProperty("correlationId", request.getAttribute("correlationId"));
        return problem;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status,
                      String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        var body = Map.of("type", "urn:shortener:error:" + code.toLowerCase().replace('_', '-'),
                "title", org.springframework.http.HttpStatus.valueOf(status).getReasonPhrase(),
                "status", status, "detail", message, "errorCode", code,
                "correlationId", String.valueOf(request.getAttribute("correlationId")));
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
