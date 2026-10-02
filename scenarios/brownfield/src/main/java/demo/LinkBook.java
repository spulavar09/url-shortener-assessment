package demo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class LinkBook {
    private final Map<String, Link> links = new HashMap<>();

    public synchronized void create(String destination, String code, Instant expiresAt) {
        if (links.containsKey(code)) throw new IllegalArgumentException("Code already reserved");
        links.put(code, new Link(destination, expiresAt));
    }

    public synchronized Optional<String> resolve(String code, Instant now, boolean record) {
        var link = links.get(code);
        if (link == null || !link.active || (link.expiresAt != null && !now.isBefore(link.expiresAt))) {
            return Optional.empty();
        }
        if (record) {
            link.total++;
            link.daily.merge(now.atOffset(ZoneOffset.UTC).toLocalDate(), 1L, Long::sum);
        }
        return Optional.of(link.destination);
    }

    public synchronized void disable(String code) { if (links.containsKey(code)) links.get(code).active = false; }
    public synchronized void delete(String code) { disable(code); }
    public synchronized long total(String code) { return links.containsKey(code) ? links.get(code).total : 0; }
    public synchronized long daily(String code, LocalDate date) { return links.containsKey(code) ? links.get(code).daily.getOrDefault(date, 0L) : 0; }

    private static final class Link {
        private final String destination;
        private final Instant expiresAt;
        private boolean active = true;
        private long total;
        private final Map<LocalDate, Long> daily = new HashMap<>();
        private Link(String destination, Instant expiresAt) { this.destination = destination; this.expiresAt = expiresAt; }
    }
}
