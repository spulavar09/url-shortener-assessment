package com.example.shortener.links;

import com.example.shortener.common.ErrorCodes;

import com.example.shortener.common.ApiException;
import com.example.shortener.analytics.AnalyticsRepository;
import jakarta.validation.Validator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.example.shortener.links.LinkModels.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LinkService {
    private static final Logger LOG = LogManager.getLogger(LinkService.class);
    private final LinkRepository repository;
    private final AnalyticsRepository analyticsRepository;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final CodeGenerator codes;
    private final String base;
    private final DestinationValidator validator;
    private final Validator beanValidator;

    public LinkService(LinkRepository repository, AnalyticsRepository analyticsRepository, PlatformTransactionManager manager, Clock clock, CodeGenerator codes, Validator beanValidator,
                       @Value("${app.links.public-base-url:http://localhost:8080}") String base) {
        this.repository = repository;
        this.analyticsRepository = analyticsRepository;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(3);
        this.clock = clock;
        this.codes = codes;
        this.base = base.replaceAll("/$", "");
        this.validator = new DestinationValidator(base);
        this.beanValidator = beanValidator;
    }

    public LinkResponse create(CreateRequest request, String key) {

        if (request == null) throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "A create request is required");
        validateFields(request);
        validator.validate(request.destinationUrl());
        if (key != null && !key.matches("[!-~]{1,128}"))
            throw ApiException.badRequest(ErrorCodes.INVALID_IDEMPOTENCY_KEY, "Idempotency-Key must contain 1-128 visible ASCII characters");
        String hash = hash(request);
        try {
            if (key != null) {
                LinkResponse replay = replay(key, hash);
                if (replay != null) return replay;
            }
            for (int attempt = 0; attempt < 5; attempt++) {
                String code = request.customAlias() != null ? request.customAlias() : codes.next();
                String id = UUID.randomUUID().toString();
                try {
                    LinkResponse result = tx.execute(status -> {
                        Instant now = clock.instant();
                        if (key != null) {
                            repository.deleteExpiredCreation(key, now);
                            LinkResponse replay = replay(key, hash);
                            if (replay != null) return replay;
                        }
                        if (request.expiresAt() != null && !request.expiresAt().isAfter(now))
                            throw ApiException.badRequest(ErrorCodes.INVALID_EXPIRATION, "expiresAt must be in the future");
                        repository.insertLink(id, code, request.destinationUrl(), now, request.expiresAt());
                        analyticsRepository.initializeLifetimeTotal(id);
                        if (key != null)
                            repository.insertCreation(key, hash, base + "/r/" + code, id, now.plus(Duration.ofHours(24)));
                        return new LinkResponse(id, code, base + "/r/" + code, request.destinationUrl(), now, request.expiresAt(), "ACTIVE", 0);
                    });
                    if (id.equals(result.id())) LOG.info("Link created code={} id={}", result.code(), result.id());
                    return result;
                } catch (DuplicateKeyException collision) {
                    if (key != null) {
                        LinkResponse replay = replay(key, hash);
                        if (replay != null) return replay;
                    }
                    if (request.customAlias() != null)
                        throw ApiException.conflict(ErrorCodes.ALIAS_CONFLICT, "This code is already reserved");
                }
            }
            LOG.warn("Link code allocation exhausted after bounded collision retries");
            throw ApiException.unavailable(ErrorCodes.CODE_ALLOCATION_FAILED, "Could not allocate a unique code; retry later");
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    private LinkResponse replay(String key, String hash) {
        var retained = repository.findRetainedCreation(key, clock.instant());
        if (retained.isEmpty()) return null;
        var creation = retained.get();
        if (!hash.equals(creation.payloadHash()))
            throw ApiException.conflict(ErrorCodes.IDEMPOTENCY_CONFLICT, "Idempotency-Key was used with a different request");
        var link = creation.link();
        LOG.info("Link creation replayed code={} id={}", link.code(), link.id());
        return new LinkResponse(link.id(), link.code(), creation.originalShortUrl(), link.destinationUrl(),
                link.createdAt(), link.expiresAt(), "ACTIVE", 0);
    }

    public LinkResponse get(String code) {
        validateCode(code);
        try {
            var link = repository.findByCode(code)
                    .orElseThrow(() -> ApiException.notFound(ErrorCodes.LINK_NOT_FOUND, "Link not found"));
            String state = link.lifecycle();
            if ("ACTIVE".equals(state) && link.expiresAt() != null && !clock.instant().isBefore(link.expiresAt()))
                state = "EXPIRED";
            return new LinkResponse(link.id(), link.code(), base + "/r/" + link.code(), link.destinationUrl(),
                    link.createdAt(), link.expiresAt(), state, link.version());
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    public ResolvedLink resolve(String code) {
        LinkResponse link = get(code);
        if (!"ACTIVE".equals(link.state()))
            throw new ApiException(410, ErrorCodes.LINK_GONE, "Link is " + link.state().toLowerCase(Locale.ROOT));
        LOG.debug("Link resolved code={} id={}", link.code(), link.id());
        return new ResolvedLink(link.id(), link.destinationUrl());
    }

    public LinkResponse disable(String code, DisableRequest request) {
        if (request == null) throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "A disable request is required");
        validateFields(request);
        try {
            LinkResponse result = tx.execute(status -> {
                LinkResponse link = get(code);
                if ("DELETED".equals(link.state()))
                    throw new ApiException(410, ErrorCodes.LINK_GONE, "Link is deleted");
                if (link.version() != request.expectedVersion())
                    throw ApiException.conflict(ErrorCodes.STALE_VERSION, "Resource version is stale");
                if ("DISABLED".equals(link.state())) return link;
                boolean changed = repository.disableAtVersion(code, request.expectedVersion());
                if (!changed) throw ApiException.conflict(ErrorCodes.STALE_VERSION, "Resource changed concurrently");
                return get(code);
            });
            LOG.info("Link disable completed code={} id={}", result.code(), result.id());
            return result;
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    public void delete(String code) {
        if (!validCode(code)) return;
        try {
            repository.tombstone(code, clock.instant());
            LOG.info("Link delete completed code={}", code);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    private void validateFields(Object request) {
        beanValidator.validate(request).stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .findFirst()
                .ifPresent(violation -> {
                    throw ApiException.badRequest(violation.getMessage(), "Invalid " + violation.getPropertyPath());
                });
    }

    private static boolean validCode(String code) {
        return code != null && code.matches("[A-Za-z0-9_-]{4,32}");
    }

    private static void validateCode(String code) {
        if (!validCode(code)) throw ApiException.notFound(ErrorCodes.LINK_NOT_FOUND, "Link not found");
    }

    private static ApiException unavailable(DataAccessException cause) {
        LOG.warn("Link storage operation failed failureType={}", cause.getClass().getSimpleName());
        ApiException failure = ApiException.unavailable(ErrorCodes.LINKS_UNAVAILABLE, "Link storage unavailable; retry later");
        failure.initCause(cause);
        return failure;
    }

    private static String hash(CreateRequest request) {
        try {
            String value = request.destinationUrl().length() + ":" + request.destinationUrl() + "|" + Objects.toString(request.customAlias(), "") + "|" + Objects.toString(request.expiresAt(), "");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
