package demo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory collection of fixed-destination short links.
 */
public final class LinkBook {
    private final Map<String, Link> links = new HashMap<>();

    public synchronized void create(String destination, String code, Instant expiresAt) {
        if (links.containsKey(code)) {
            throw new IllegalArgumentException("Code is already reserved: " + code);
        }
        links.put(code, new Link(destination, expiresAt));
    }

    public synchronized Optional<String> resolve(String code, Instant now, boolean record) {
        Link link = links.get(code);
        if (link == null || link.disabled || link.deleted || link.isExpiredAt(now)) {
            return Optional.empty();
        }

        if (record) {
            link.record(now);
        }
        return Optional.of(link.destination);
    }

    public synchronized void disable(String code) {
        Link link = links.get(code);
        if (link != null) {
            link.disabled = true;
        }
    }

    public synchronized void delete(String code) {
        Link link = links.get(code);
        if (link != null) {
            link.deleted = true;
        }
    }

    public synchronized long total(String code) {
        Link link = links.get(code);
        return link == null ? 0 : link.total;
    }

    public synchronized long daily(String code, LocalDate utcDate) {
        Link link = links.get(code);
        return link == null ? 0 : link.daily.getOrDefault(utcDate, 0L);
    }

    private static final class Link {
        private final String destination;
        private final Instant expiresAt;
        private final Map<LocalDate, Long> daily = new HashMap<>();
        private long total;
        private boolean disabled;
        private boolean deleted;

        private Link(String destination, Instant expiresAt) {
            this.destination = destination;
            this.expiresAt = expiresAt;
        }

        private boolean isExpiredAt(Instant now) {
            return expiresAt != null && !now.isBefore(expiresAt);
        }

        private void record(Instant now) {
            LocalDate utcDate = now.atZone(ZoneOffset.UTC).toLocalDate();
            total++;
            daily.merge(utcDate, 1L, Long::sum);
        }
    }
}
