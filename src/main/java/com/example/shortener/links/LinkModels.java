package com.example.shortener.links;

import java.time.Instant;

import com.example.shortener.common.ErrorCodes;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.example.shortener.analytics.AnalyticsService.AnalyticsResponse;

public final class LinkModels {
    private LinkModels() {
    }

    public record CreateRequest(
            @NotBlank(message = ErrorCodes.INVALID_DESTINATION)
            @Size(max = 4096, message = ErrorCodes.INVALID_DESTINATION) String destinationUrl,
            @Size(min = 4, max = 32, message = ErrorCodes.INVALID_ALIAS)
            @Pattern(regexp = "[A-Za-z0-9_-]+", message = ErrorCodes.INVALID_ALIAS) String customAlias,
            Instant expiresAt) {
    }

    public record DisableRequest(
            @NotNull(message = ErrorCodes.INVALID_DISABLE)
            @AssertTrue(message = ErrorCodes.INVALID_DISABLE) Boolean disabled,
            @NotNull(message = ErrorCodes.INVALID_DISABLE)
            @PositiveOrZero(message = ErrorCodes.INVALID_DISABLE) Long expectedVersion) {
    }

    public record LinkResponse(String id, String code, String shortUrl, String destinationUrl,
                               Instant createdAt, Instant expiresAt, String state, long version) {
    }

    public record LinkDetails(LinkResponse link, AnalyticsResponse analytics) {
    }

    public record ResolvedLink(String id, String destinationUrl) {
    }
}
