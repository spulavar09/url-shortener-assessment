package com.example.shortener.security;

import com.example.shortener.common.ErrorCodes;

import com.example.shortener.common.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestBoundaryFilter extends OncePerRequestFilter {
    private static final Logger LOG = LogManager.getLogger(RequestBoundaryFilter.class);
    private static final int BODY_LIMIT = 16 * 1024;
    private final ProblemResponses problems;
    private final Clock clock;
    private final boolean limitsEnabled;
    private final Map<String, Window> windows = new HashMap<>();

    public RequestBoundaryFilter(ProblemResponses problems, Clock clock,
                                 @Value("${app.limits.enabled:true}") boolean limitsEnabled) {
        this.problems = problems;
        this.clock = clock;
        this.limitsEnabled = limitsEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var suppliedId = request.getHeader("X-Correlation-ID");
        var correlationId = suppliedId != null && suppliedId.matches("[a-zA-Z0-9_-]{1,64}")
                ? suppliedId : UUID.randomUUID().toString();
        request.setAttribute("correlationId", correlationId);
        response.setHeader("X-Correlation-ID", correlationId);
        ThreadContext.put("correlationId", correlationId);
        long started = System.nanoTime();
        try {
            if (limitsEnabled && !allow(request)) {
                LOG.warn("Request rejected errorCode={}", ErrorCodes.RATE_LIMIT_EXCEEDED);
                response.setHeader("Retry-After", "60");
                problems.write(request, response, 429, ErrorCodes.RATE_LIMIT_EXCEEDED, "Request quota exceeded; retry later");
                return;
            }
            if (request.getRequestURI().startsWith("/api/") &&
                    (request.getMethod().equals("POST") || request.getMethod().equals("PATCH") || request.getMethod().equals("PUT"))) {
                if (request.getContentLengthLong() > BODY_LIMIT) {
                    LOG.warn("Request rejected errorCode={}", ErrorCodes.BODY_TOO_LARGE);
                    problems.write(request, response, 413, ErrorCodes.BODY_TOO_LARGE, "Request body exceeds 16 KiB");
                    return;
                }
                byte[] body = request.getInputStream().readNBytes(BODY_LIMIT + 1);
                if (body.length > BODY_LIMIT) {
                    LOG.warn("Request rejected errorCode={}", ErrorCodes.BODY_TOO_LARGE);
                    problems.write(request, response, 413, ErrorCodes.BODY_TOO_LARGE, "Request body exceeds 16 KiB");
                    return;
                }
                chain.doFilter(new BufferedRequest(request, body), response);
            } else {
                chain.doFilter(request, response);
            }
        } finally {
            LOG.debug("Request completed method={} status={} elapsedMs={}",
                    request.getMethod(), response.getStatus(), (System.nanoTime() - started) / 1_000_000);
            ThreadContext.remove("correlationId");
        }
    }

    private synchronized boolean allow(HttpServletRequest request) {
        var path = request.getRequestURI();
        var method = request.getMethod();
        long now = clock.millis();
        windows.values().removeIf(window -> now - window.started >= window.duration);
        String group;
        int limit;
        long duration = 60_000;
        if (path.equals("/api/v1/links") && method.equals("POST")) {
            group = "create"; limit = 30;
            if (!take("global:create", 300, duration, now)) return false;
        } else if (path.startsWith("/api/v1/links") && (method.equals("PATCH") || method.equals("DELETE"))) {
            group = "manage"; limit = 60;
        } else if (path.startsWith("/r/")) {
            group = "redirect"; limit = 6000;
        } else if (path.equals("/api/v1/workflow-runs") && method.equals("POST")) {
            group = "workflow"; limit = 4; duration = 3_600_000;
        } else return true;
        return take(group + ":" + request.getRemoteAddr(), limit, duration, now);
    }

    private boolean take(String key, int limit, long duration, long now) {
        var window = windows.get(key);
        if (window == null) {
            if (windows.size() >= 10_000) return false;
            window = new Window(now, duration);
            windows.put(key, window);
        }
        return ++window.count <= limit;
    }

    private static final class Window {
        private final long started;
        private final long duration;
        private int count;
        private Window(long started, long duration) { this.started = started; this.duration = duration; }
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private BufferedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous request processing"); }
            };
        }
    }
}
