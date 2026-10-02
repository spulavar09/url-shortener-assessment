package com.example.shortener.links;

import com.example.shortener.analytics.AnalyticsService;
import com.example.shortener.analytics.AnalyticsRepository;
import com.example.shortener.common.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.example.shortener.common.ErrorCodes;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.junit.jupiter.api.Assertions.*;
import static com.example.shortener.links.LinkModels.*;
import static org.mockito.Mockito.*;

class LinkIntegrationTest {
    static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    static final Validator BEAN_VALIDATOR = VALIDATORS.getValidator();

    @AfterAll
    static void closeValidatorFactory() { VALIDATORS.close(); }

    final Instant now = Instant.parse("2026-10-01T12:00:00Z");
    MutableClock clock;
    JdbcTemplate jdbc;
    LinkService links;
    AnalyticsService analytics;
    LinkRepository linkRepository;
    AnalyticsRepository analyticsRepository;
    CodeGenerator generator;

    @BeforeEach
    void setup() throws Exception {
        initialize("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000", true);
    }

    void initialize(String url, boolean migration) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url);
        ds.setUser("sa");
        if (migration) try (Connection connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V1__links.sql"));
        }
        jdbc = new JdbcTemplate(ds);
        clock = new MutableClock(now);
        generator = mock(CodeGenerator.class);
        when(generator.next()).thenAnswer(call -> UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        var manager = new DataSourceTransactionManager(ds);
        linkRepository = new LinkRepository(jdbc);
        analyticsRepository = new AnalyticsRepository(jdbc);
        links = new LinkService(linkRepository, analyticsRepository, manager, clock, generator, BEAN_VALIDATOR, "http://localhost:8080");
        analytics = new AnalyticsService(analyticsRepository, linkRepository, manager, links, clock, new SimpleMeterRegistry(), 2, true);
    }

    @Test
    void exactDestinationLifecycleAndNoReuse() {
        String url = "https://example.org/a%2Fb?q=2&q=1#part";
        var link = links.create(new CreateRequest(url, "custom", now.plusSeconds(1)), null);
        assertEquals(url, links.resolve("custom").destinationUrl());
        clock.time.set(now.plusSeconds(1));
        assertEquals("EXPIRED", links.get("custom").state());
        assertStatus(410, () -> links.resolve("custom"));
        var disabled = links.disable("custom", new DisableRequest(true, 0L));
        assertEquals("DISABLED", disabled.state());
        assertEquals(disabled, links.disable("custom", new DisableRequest(true, 1L)));
        assertStatus(409, () -> links.disable("custom", new DisableRequest(true, 0L)));
        links.delete("custom");
        links.delete("custom");
        assertEquals("DELETED", links.get("custom").state());
        assertStatus(410, () -> links.disable("custom", new DisableRequest(true, 2L)));
        assertStatus(409, () -> links.create(new CreateRequest(url, "custom", null), null));
        links.delete("unknown");
        assertStatus(404, () -> links.get("unknown"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.org/a", "https://u:p@example.org", "https://example.org:99999", "https://example.org:", "https://éxample.org", " https://example.org", "https://example.org/a b", "http://999.1.1.1", "http://localhost:8080/r/test", "http://localhost:8080/%72/test"})
    void invalidDestinations(String value) {
        assertStatus(400, () -> links.create(new CreateRequest(value, null, null), null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class));
    }

    @Test
    void directServiceCallsEnforceCreateFieldConstraintsWithoutPersisting() {
        for (String destination : new String[]{null, "", "   ", "https://example.org/" + "a".repeat(4096)}) {
            var failure = assertThrows(ApiException.class,
                    () -> links.create(new CreateRequest(destination, null, null), null));
            assertEquals(ErrorCodes.INVALID_DESTINATION, failure.code());
        }
        for (String alias : new String[]{"", "   ", "abc", "a".repeat(33), "contains.dot", "ünicode"}) {
            var failure = assertThrows(ApiException.class,
                    () -> links.create(new CreateRequest("https://example.org", alias, null), null));
            assertEquals(ErrorCodes.INVALID_ALIAS, failure.code());
        }
        assertEquals(ErrorCodes.INVALID_REQUEST, assertThrows(ApiException.class,
                () -> links.create(null, null)).code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class));
    }

    @Test
    void directServiceCallsEnforceDisableFieldsBeforeMutating() {
        links.create(new CreateRequest("https://example.org", "validate", null), null);
        for (DisableRequest request : List.of(new DisableRequest(null, 0L),
                new DisableRequest(false, 0L), new DisableRequest(true, null), new DisableRequest(true, -1L))) {
            assertEquals(ErrorCodes.INVALID_DISABLE,
                    assertThrows(ApiException.class, () -> links.disable("validate", request)).code());
        }
        assertEquals(ErrorCodes.INVALID_REQUEST,
                assertThrows(ApiException.class, () -> links.disable("validate", null)).code());
        assertEquals("ACTIVE", links.get("validate").state());
        assertEquals(0, links.get("validate").version());
    }

    @Test
    void idempotencyKeysRejectNonVisibleOrOversizedValues() {
        var request = new CreateRequest("https://example.org", "keycheck", null);
        for (String key : List.of("", " ", "has space", "tab\tkey", "nonascii-é", "a".repeat(129))) {
            assertEquals(ErrorCodes.INVALID_IDEMPOTENCY_KEY,
                    assertThrows(ApiException.class, () -> links.create(request, key)).code());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class));
    }

    @Test
    void idempotencyOriginalSuccessSurvivesExpiryAndDeletion() {
        var request = new CreateRequest("https://example.org", "replay", now.plusSeconds(1));
        var original = links.create(request, "key");
        clock.time.set(now.plusSeconds(2));
        assertTrue(BEAN_VALIDATOR.validate(new CreateRequest("https://example.org", "replay", Instant.EPOCH)).isEmpty(),
                "Expiry is a replay-aware business rule, not a Bean Validation constraint");
        links.delete("replay");
        assertEquals(original, links.create(request, "key"));
        assertStatus(409, () -> links.create(new CreateRequest("https://different.org", "replay", request.expiresAt()), "key"));
        assertStatus(400, () -> links.create(request, null));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class));
    }

    @Test
    void replayPreservesNanosecondsAndOriginalPublicOrigin() {
        clock.time.set(now.plusNanos(123456789));
        var request = new CreateRequest("https://example.org", "precise", now.plusSeconds(20).plusNanos(987654321));
        var original = links.create(request, "precision-key");
        LinkService changedOrigin = new LinkService(linkRepository, analyticsRepository, new DataSourceTransactionManager(jdbc.getDataSource()), clock, generator, BEAN_VALIDATOR, "https://short.example");
        assertEquals(original, changedOrigin.create(request, "precision-key"));
        assertEquals(original.createdAt(), links.get("precise").createdAt());
        assertEquals(original.expiresAt(), links.get("precise").expiresAt());
    }

    @Test
    void failedLifetimeInitializationRollsBackCreationAcrossRepositories() {
        AnalyticsRepository unavailableTotals = new AnalyticsRepository(jdbc) {
            @Override
            public void initializeLifetimeTotal(String linkId) {
                throw new org.springframework.dao.DataAccessResourceFailureException("Injected total initialization failure");
            }
        };
        LinkService failingCreation = new LinkService(linkRepository, unavailableTotals,
                new DataSourceTransactionManager(jdbc.getDataSource()), clock, generator, BEAN_VALIDATOR, "http://localhost:8080");
        var request = new CreateRequest("https://example.org", "rollback", null);
        assertStatus(503, () -> failingCreation.create(request, "rollback-key"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM link_create_request", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM link_total", Integer.class));
        var successful = links.create(request, "rollback-key");
        assertEquals("rollback", successful.code());
        assertEquals(successful, links.create(request, "rollback-key"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM link_create_request", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM link_total", Integer.class));
    }

    @Test
    void generatedCollisionUsesFreshTransaction() {
        links.create(new CreateRequest("https://example.org", "abcdefghij", null), null);
        when(generator.next()).thenReturn("abcdefghij", "newcode123");
        var result = links.create(new CreateRequest("https://example.org", null, null), null);
        assertEquals("newcode123", result.code());
        when(generator.next()).thenReturn("abcdefghij");
        assertStatus(503, () -> links.create(new CreateRequest("https://example.org", null, null), null));
    }

    @Test
    void concurrentAliasAndIdempotencyArbitration() throws Exception {
        var aliases = concurrent(8, () -> {
            try {
                links.create(new CreateRequest("https://example.org", "samealias", null), null);
                return 201;
            } catch (ApiException e) {
                return e.status();
            }
        });
        assertEquals(1, aliases.stream().filter(s -> s == 201).count());
        assertEquals(7, aliases.stream().filter(s -> s == 409).count());
        var identical = concurrent(8, () -> links.create(new CreateRequest("https://example.org", null, null), "concurrent"));
        assertEquals(1, identical.stream().map(LinkResponse::id).distinct().count());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class));
    }

    @Test
    void analyticsAtomicDeduplicationConcurrentWritesAndRetention() throws Exception {
        var link = links.create(new CreateRequest("https://example.org", "counts", null), null);
        var event = new AnalyticsService.RedirectEvent(UUID.randomUUID().toString(), link.id(), now);
        assertTrue(analytics.record(event));
        assertFalse(analytics.record(event));
        concurrent(12, () -> analytics.recordWithRetries(new AnalyticsService.RedirectEvent(UUID.randomUUID().toString(), link.id(), now)));
        assertEquals(13, analytics.get("counts", null, null).lifetimeRecordedTotal());
        assertEquals(13, analytics.get("counts", null, null).requestedRangeRecordedTotal());
        links.delete("counts");
        assertEquals(13, analytics.get("counts", null, null).lifetimeRecordedTotal());
        clock.time.set(now.plus(Duration.ofDays(91)));
        analytics.prune();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM redirect_event", Integer.class));
        assertEquals(0, analytics.get("counts", null, null).requestedRangeRecordedTotal());
        assertEquals(13, analytics.get("counts", null, null).lifetimeRecordedTotal());
        assertStatus(400, () -> analytics.get("counts", LocalDate.parse("2026-10-01"), null));
    }

    @Test
    void headFailedRedirectAndQueueOverflowDoNotPreventResolution() {
        var link = links.create(new CreateRequest("https://example.org", "heads", null), null);
        var controller = new LinkController(links, analytics);
        assertEquals(302, controller.head("heads").getStatusCode().value());
        assertEquals(0, analytics.get("heads", null, null).lifetimeRecordedTotal());
        for (int i = 0; i < 5; i++) assertEquals(302, controller.redirect("heads").getStatusCode().value());
        assertStatus(404, () -> controller.redirect("missing"));
        assertEquals(0, analytics.get("heads", null, null).lifetimeRecordedTotal());
    }

    @Test
    void actualWorkerDrainsBoundedQueue() throws Exception {
        var link = links.create(new CreateRequest("https://example.org", "worker", null), null);
        analytics.start();
        try {
            analytics.enqueue(link.id());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (analytics.get("worker", null, null).lifetimeRecordedTotal() == 0 && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(1, analytics.get("worker", null, null).lifetimeRecordedTotal());
        } finally {
            analytics.stop();
        }
    }

    @Test
    void recorderFailureRetriesExactlyTwiceAndDrops() {
        AnalyticsRepository broken = mock(AnalyticsRepository.class);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected"))
                .when(broken).insertEvent(anyString(), anyString(), any(Instant.class));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        var recorder = new AnalyticsService(broken, linkRepository, new DataSourceTransactionManager(jdbc.getDataSource()), links, clock, metrics, 1, true);
        assertFalse(recorder.recordWithRetries(new AnalyticsService.RedirectEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(), now)));
        verify(broken, times(3)).insertEvent(anyString(), anyString(), any(Instant.class));
        verify(broken, never()).incrementRecordedCounts(anyString(), any(LocalDate.class));
        assertEquals(1, metrics.get("shortener.analytics.dropped").counter().count());
    }

    @Test
    void redirectsSurviveRecorderFailureAndUnavailableStorageFailsClosed() {
        links.create(new CreateRequest("https://example.org", "failure", null), null);
        AnalyticsService broken = mock(AnalyticsService.class);
        doThrow(new IllegalStateException("injected recorder failure")).when(broken).enqueue(anyString());
        assertEquals(302, new LinkController(links, broken).redirect("failure").getStatusCode().value());
        LinkRepository unavailable = mock(LinkRepository.class);
        when(unavailable.findByCode("failure")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("injected outage"));
        LinkService service = new LinkService(unavailable, analyticsRepository, new DataSourceTransactionManager(jdbc.getDataSource()), clock, generator, BEAN_VALIDATOR, "http://localhost:8080");
        assertStatus(503, () -> service.resolve("failure"));
    }

    @Test
    void disableDeleteRaceCannotResurrect() throws Exception {
        links.create(new CreateRequest("https://example.org", "racecode", null), null);
        concurrent(2, () -> {
            try {
                links.disable("racecode", new DisableRequest(true, 0L));
            } catch (ApiException e) {
                assertTrue(e.status() == 409 || e.status() == 410);
            }
            links.delete("racecode");
            return true;
        });
        assertEquals("DELETED", links.get("racecode").state());
        assertStatus(410, () -> links.resolve("racecode"));
    }

    @Test
    void validEncodedAndIpDestinationsPreserved() {
        for (String url : List.of("http://[::1]:8081/a%2Fb?q=%23#frag", "https://xn--bcher-kva.example/a", "http://127.0.0.1:9090/?a=2&a=1"))
            assertEquals(url, links.create(new CreateRequest(url, null, null), null).destinationUrl());
    }

    @Test
    void pruningBoundaryPreservesLifetimeAndNinetyDates() {
        var link = links.create(new CreateRequest("https://example.org", "boundary", null), null);
        Instant boundary = now.minus(Duration.ofDays(7));
        assertTrue(analytics.record(new AnalyticsService.RedirectEvent(UUID.randomUUID().toString(), link.id(), boundary)));
        assertFalse(analytics.record(new AnalyticsService.RedirectEvent(UUID.randomUUID().toString(), link.id(), boundary.minusNanos(1))));
        analytics.prune();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM redirect_event", Integer.class));
        var range = analytics.get("boundary", LocalDate.now(clock).minusDays(89), LocalDate.now(clock));
        assertEquals(90, range.daily().size());
        assertEquals(1, range.requestedRangeRecordedTotal());
        clock.time.set(now.plusNanos(1));
        analytics.prune();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM redirect_event", Integer.class));
        assertEquals(1, analytics.get("boundary", null, null).lifetimeRecordedTotal());
    }

    @Test
    void fileDatabaseRestart(@TempDir Path temp) throws Exception {
        String url = "jdbc:h2:file:" + temp.resolve("links") + ";WRITE_DELAY=0";
        initialize(url, true);
        var original = links.create(new CreateRequest("https://example.org", "persist", null), "persist-key");
        analytics.record(new AnalyticsService.RedirectEvent(UUID.randomUUID().toString(), original.id(), now));
        jdbc.execute("SHUTDOWN");
        initialize(url, false);
        assertEquals(original, links.get("persist"));
        assertEquals(original, links.create(new CreateRequest("https://example.org", "persist", null), "persist-key"));
        assertEquals(1, analytics.get("persist", null, null).lifetimeRecordedTotal());
    }

    <T> List<T> concurrent(int count, Callable<T> action) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++)
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return action.call();
                }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            List<T> values = new ArrayList<>();
            for (var future : futures) values.add(future.get(10, TimeUnit.SECONDS));
            return values;
        }
    }

    static void assertStatus(int status, org.junit.jupiter.api.function.Executable action) {
        assertEquals(status, assertThrows(ApiException.class, action).status());
    }

    static class MutableClock extends Clock {
        final AtomicReference<Instant> time;

        MutableClock(Instant time) {
            this.time = new AtomicReference<>(time);
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return time.get();
        }
    }
}
