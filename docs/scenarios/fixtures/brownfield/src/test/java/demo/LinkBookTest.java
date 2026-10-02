package demo;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class LinkBookTest {
    private static final Instant EXPIRY = Instant.parse("2026-10-02T00:00:00Z");

    @Test void preservesDestinationAndReservesDeletedCode() {
        var links = new LinkBook();
        var destination = "https://example.com/a%2Fb?z=2&a=1#fragment";
        links.create(destination, "alpha", null);
        assertEquals(Optional.of(destination), links.resolve("alpha", EXPIRY, true));
        links.delete("alpha");
        assertTrue(links.resolve("alpha", EXPIRY, true).isEmpty());
        assertEquals(1, links.total("alpha"));
        assertThrows(IllegalArgumentException.class, () -> links.create(destination, "alpha", null));
    }

    @Test void expirationStartsAtEqualityAndFailedOrHeadRequestsAreNotCounted() {
        var links = new LinkBook();
        links.create("https://example.com", "expires", EXPIRY);
        assertTrue(links.resolve("expires", EXPIRY.minusNanos(1), false).isPresent());
        assertTrue(links.resolve("expires", EXPIRY.minusNanos(1), true).isPresent());
        assertTrue(links.resolve("expires", EXPIRY, true).isEmpty());
        assertTrue(links.resolve("expires", EXPIRY.plusSeconds(1), true).isEmpty());
        assertTrue(links.resolve("missing", EXPIRY, true).isEmpty());
        assertEquals(1, links.total("expires"));
        assertEquals(0, links.total("missing"));
    }

    @Test void utcBucketsAndDisableRetainCounts() {
        var links = new LinkBook();
        links.create("https://example.com", "daily", null);
        links.resolve("daily", EXPIRY.minusNanos(1), true);
        links.resolve("daily", EXPIRY, true);
        links.disable("daily");
        assertTrue(links.resolve("daily", EXPIRY.plusSeconds(1), true).isEmpty());
        assertEquals(2, links.total("daily"));
        assertEquals(1, links.daily("daily", LocalDate.of(2026, 10, 1)));
        assertEquals(1, links.daily("daily", LocalDate.of(2026, 10, 2)));
    }
}
