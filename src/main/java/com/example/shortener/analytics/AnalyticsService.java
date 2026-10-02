package com.example.shortener.analytics;

import com.example.shortener.common.ErrorCodes;

import com.example.shortener.common.ApiException;
import com.example.shortener.links.LinkService;
import com.example.shortener.links.LinkRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnalyticsService {
    private static final Logger LOG = LogManager.getLogger(AnalyticsService.class);
    public record RedirectEvent(String eventId, String linkId, Instant occurredAt) {
    }

    public record DailyBucket(LocalDate date, long recordedCount) {
    }

    public record AnalyticsResponse(String code, long lifetimeRecordedTotal, long requestedRangeRecordedTotal,
                                    LocalDate from, LocalDate to, LocalDate earliestRetainedUtcDate,
                                    List<DailyBucket> daily,
                                    String measurement, String consistency) {
    }

    private final AnalyticsRepository repository;
    private final LinkRepository linkRepository;
    private final LinkService links;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate snapshot;
    private final BlockingQueue<RedirectEvent> queue;
    private final boolean enabled;
    private final Counter dropped;
    private final Counter recorded;
    private final Counter cleanupFailures;
    private volatile boolean running;
    private Thread worker;

    public AnalyticsService(AnalyticsRepository repository, LinkRepository linkRepository, PlatformTransactionManager manager, LinkService links, Clock clock, MeterRegistry registry,
                            @Value("${app.analytics.queue-capacity:1000}") int capacity, @Value("${app.analytics.enabled:true}") boolean enabled) {
        if (capacity < 1 || capacity > 100000)
            throw new IllegalArgumentException("Analytics queue capacity must be 1-100000");
        this.repository = repository;
        this.linkRepository = linkRepository;
        this.links = links;
        this.clock = clock;
        this.enabled = enabled;
        queue = new ArrayBlockingQueue<>(capacity);
        tx = new TransactionTemplate(manager);
        tx.setTimeout(2);
        snapshot = new TransactionTemplate(manager);
        snapshot.setTimeout(3);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        dropped = registry.counter("shortener.analytics.dropped");
        recorded = registry.counter("shortener.analytics.recorded");
        cleanupFailures = registry.counter("shortener.analytics.cleanup.failures");
        registry.gauge("shortener.analytics.queued", queue, BlockingQueue::size);
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        running = true;
        worker = new Thread(this::work, "redirect-analytics");
        worker.setDaemon(true);
        worker.start();
        LOG.info("Analytics worker started");
    }

    public void enqueue(String linkId) {
        if (!enabled) return;
        if (!queue.offer(new RedirectEvent(UUID.randomUUID().toString(), linkId, clock.instant()))) {
            dropped.increment();
            LOG.debug("Analytics queue full; event dropped id={}", linkId);
        }
    }

    private void work() {
        while (running || !queue.isEmpty()) {
            try {
                RedirectEvent event = queue.poll(200, TimeUnit.MILLISECONDS);
                if (event != null) recordWithRetries(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public boolean recordWithRetries(RedirectEvent event) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (record(event)) recorded.increment();
                return true;
            } catch (DataAccessException e) {
                if (attempt == 2) {
                    dropped.increment();
                    LOG.warn("Analytics recording exhausted bounded retries id={} failureType={}",
                            event.linkId(), e.getClass().getSimpleName());
                    return false;
                }
            }
        }
        return false;
    }

    public boolean record(RedirectEvent event) {
        if (event.occurredAt().isBefore(clock.instant().minus(Duration.ofDays(7)))) return false;
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                repository.insertEvent(event.eventId(), event.linkId(), event.occurredAt());
                LocalDate date = event.occurredAt().atOffset(ZoneOffset.UTC).toLocalDate();
                repository.incrementRecordedCounts(event.linkId(), date);
                return true;
            }));
        } catch (DuplicateKeyException e) {
            if (repository.containsEvent(event.eventId()))
                return false;
            throw e;
        }
    }

    public AnalyticsResponse get(String code, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate earliest = today.minusDays(89);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        if (start.isBefore(earliest) || end.isAfter(today) || start.isAfter(end) || java.time.temporal.ChronoUnit.DAYS.between(start, end) > 89)
            throw ApiException.badRequest(ErrorCodes.INVALID_DATE_RANGE, "Date range must cover 1-90 retained UTC dates, without future dates");
        try {
            return snapshot.execute(status -> {
                String id = links.get(code).id();
                long total = repository.lifetimeRecordedTotal(id);
                Map<LocalDate, Long> counts = repository.dailyRecordedCounts(id, start, end);
                List<DailyBucket> buckets = new ArrayList<>();
                for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1))
                    buckets.add(new DailyBucket(day, counts.getOrDefault(day, 0L)));
                return new AnalyticsResponse(code, total, buckets.stream().mapToLong(DailyBucket::recordedCount).sum(), start, end, earliest, List.copyOf(buckets), "Resolved GET attempts; bots and repeats count. HEAD and failed resolutions do not count. Lifetime totals survive pruning.", "Eventual, best effort; queued and lost events are excluded. Crash losses may be unmeasured.");
            });
        } catch (DataAccessException e) {
            LOG.warn("Analytics query failed failureType={}", e.getClass().getSimpleName());
            throw ApiException.unavailable(ErrorCodes.ANALYTICS_UNAVAILABLE, "Analytics storage unavailable; retry later");
        }
    }

    @Scheduled(fixedDelayString = "${app.analytics.retention-interval-ms:3600000}")
    public void prune() {
        try {
            tx.executeWithoutResult(status -> {
                Instant now = clock.instant();
                repository.deleteEventsBefore(now.minus(Duration.ofDays(7)));
                repository.deleteDailyCountsBefore(LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(89));
                linkRepository.deleteExpiredCreations(now);
            });
        } catch (DataAccessException e) {
            cleanupFailures.increment();
            LOG.warn("Analytics retention cleanup failed failureType={}", e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) try {
            worker.join(4000);
            LOG.info("Analytics worker shutdown requested remainingQueue={} workerAlive={}", queue.size(), worker.isAlive());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
