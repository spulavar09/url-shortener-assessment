package com.example.shortener.common;

/** Stable error codes returned in API problem responses. */
public final class ErrorCodes {
    private ErrorCodes() {}

    // Request and HTTP errors.
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String UNSUPPORTED_RESPONSE_FORMAT = "UNSUPPORTED_RESPONSE_FORMAT";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String DATABASE_UNAVAILABLE = "DATABASE_UNAVAILABLE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    // Authentication and request limits.
    public static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String INVALID_OPERATOR_TOKEN = "INVALID_OPERATOR_TOKEN";
    public static final String OPERATOR_REQUIRED = "OPERATOR_REQUIRED";
    public static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";
    public static final String BODY_TOO_LARGE = "BODY_TOO_LARGE";

    // Link errors.
    public static final String INVALID_DESTINATION = "INVALID_DESTINATION";
    public static final String SELF_REDIRECT = "SELF_REDIRECT";
    public static final String INVALID_ALIAS = "INVALID_ALIAS";
    public static final String INVALID_IDEMPOTENCY_KEY = "INVALID_IDEMPOTENCY_KEY";
    public static final String INVALID_EXPIRATION = "INVALID_EXPIRATION";
    public static final String ALIAS_CONFLICT = "ALIAS_CONFLICT";
    public static final String CODE_ALLOCATION_FAILED = "CODE_ALLOCATION_FAILED";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String LINK_NOT_FOUND = "LINK_NOT_FOUND";
    public static final String LINK_GONE = "LINK_GONE";
    public static final String INVALID_DISABLE = "INVALID_DISABLE";
    public static final String STALE_VERSION = "STALE_VERSION";
    public static final String LINKS_UNAVAILABLE = "LINKS_UNAVAILABLE";

    // Analytics errors.
    public static final String INVALID_DATE_RANGE = "INVALID_DATE_RANGE";
    public static final String ANALYTICS_UNAVAILABLE = "ANALYTICS_UNAVAILABLE";

    // Workflow errors.
    public static final String INVALID_SCENARIO = "INVALID_SCENARIO";
    public static final String INVALID_WORKFLOW_PLAN = "INVALID_WORKFLOW_PLAN";
    public static final String STALE_WORKFLOW_STATE = "STALE_WORKFLOW_STATE";
    public static final String WORKFLOW_NOT_FOUND = "WORKFLOW_NOT_FOUND";
    public static final String WORKFLOW_RATE_LIMIT = "WORKFLOW_RATE_LIMIT";
    public static final String WORKSPACE_POLICY = "WORKSPACE_POLICY";
    public static final String WORKSPACE_UNAVAILABLE = "WORKSPACE_UNAVAILABLE";
    public static final String SKILL_UNAVAILABLE = "SKILL_UNAVAILABLE";
    public static final String EVIDENCE_UNAVAILABLE = "EVIDENCE_UNAVAILABLE";
}
