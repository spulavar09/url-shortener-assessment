package com.example.shortener.common;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Set;

/** Spring handles MVC exception dispatch, status and headers; we retain our problem contract. */
@RestControllerAdvice
public class ApiErrorAdvice extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LogManager.getLogger(ApiErrorAdvice.class);
    private static final Set<String> FIELD_ERROR_CODES = Set.of(
            ErrorCodes.INVALID_DESTINATION, ErrorCodes.INVALID_ALIAS, ErrorCodes.INVALID_DISABLE);

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest webRequest) {
        HttpServletRequest request = ((ServletWebRequest) webRequest).getRequest();
        ProblemDetail problem = switch (status.value()) {
            case 400 -> ProblemResponses.problem(request, 400, ErrorCodes.INVALID_REQUEST, "Request format or field value is invalid");
            case 404 -> ProblemResponses.problem(request, 404, ErrorCodes.RESOURCE_NOT_FOUND, "The requested resource does not exist");
            case 405 -> ProblemResponses.problem(request, 405, ErrorCodes.METHOD_NOT_ALLOWED, "This operation does not support the request method");
            case 406 -> ProblemResponses.problem(request, 406, ErrorCodes.UNSUPPORTED_RESPONSE_FORMAT, "The requested response format is unavailable");
            case 415 -> ProblemResponses.problem(request, 415, ErrorCodes.UNSUPPORTED_MEDIA_TYPE, "Use application/json for request bodies");
            default -> ProblemResponses.problem(request, status.value(),
                    status.is5xxServerError() ? ErrorCodes.INTERNAL_ERROR : ErrorCodes.INVALID_REQUEST,
                    "The request could not be completed");
        };
        if (exception instanceof MethodArgumentNotValidException invalid) {
            var errors = new LinkedHashMap<String, String>();
            invalid.getBindingResult().getFieldErrors().forEach(error ->
                    errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
            String code = errors.values().stream().filter(FIELD_ERROR_CODES::contains).findFirst()
                    .orElse(ErrorCodes.VALIDATION_FAILED);
            problem = ProblemResponses.problem(request, status.value(), code, "Request fields are invalid");
            problem.setProperty("fieldErrors", errors);
        }
        return super.handleExceptionInternal(exception, problem, headers, status, webRequest);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException exception, HttpServletRequest request) {
        return response(request, exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> database(DataAccessException exception, HttpServletRequest request) {
        return response(request, 503, ErrorCodes.DATABASE_UNAVAILABLE, "Persistence is temporarily unavailable; retry later");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        LOG.error("Unhandled request failure type={}", exception.getClass().getSimpleName());
        return response(request, 500, ErrorCodes.INTERNAL_ERROR, "The request could not be completed");
    }

    private ResponseEntity<ProblemDetail> response(HttpServletRequest request, int status, String code, String message) {
        var builder = ResponseEntity.status(status);
        if (status == 503) builder.header("Retry-After", "2");
        return builder.body(ProblemResponses.problem(request, status, code, message));
    }
}
