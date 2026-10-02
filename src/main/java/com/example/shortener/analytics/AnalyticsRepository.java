package com.example.shortener.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnalyticsRepository {
    private final JdbcTemplate jdbc;

    public AnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void initializeLifetimeTotal(String linkId) {
        jdbc.update("INSERT INTO link_total(link_id,counted_events) VALUES(?,0)", linkId);
    }

    public void insertEvent(String eventId, String linkId, Instant occurredAt) {
        jdbc.update("INSERT INTO redirect_event(event_id,link_id,occurred_at) VALUES(?,?,?)",
                eventId, linkId, occurredAt.atOffset(ZoneOffset.UTC));
    }

    public void incrementRecordedCounts(String linkId, LocalDate utcDate) {
        // The lifetime row lock serializes this link's daily insert/update writers.
        jdbc.update("UPDATE link_total SET counted_events=counted_events+1 WHERE link_id=?", linkId);
        int changed = jdbc.update("UPDATE link_daily_count SET counted_events=counted_events+1 WHERE link_id=? AND utc_date=?", linkId, utcDate);
        if (changed == 0) {
            jdbc.update("INSERT INTO link_daily_count(link_id,utc_date,counted_events) VALUES(?,?,1)", linkId, utcDate);
        }
    }

    public boolean containsEvent(String eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM redirect_event WHERE event_id=?", Boolean.class, eventId));
    }

    public long lifetimeRecordedTotal(String linkId) {
        Long total = jdbc.queryForObject("SELECT counted_events FROM link_total WHERE link_id=?", Long.class, linkId);
        return total == null ? 0 : total;
    }

    public Map<LocalDate, Long> dailyRecordedCounts(String linkId, LocalDate from, LocalDate to) {
        Map<LocalDate, Long> counts = new HashMap<>();
        jdbc.query("SELECT utc_date,counted_events FROM link_daily_count WHERE link_id=? AND utc_date BETWEEN ? AND ?",
                rs -> { counts.put(rs.getObject(1, LocalDate.class), rs.getLong(2)); }, linkId, from, to);
        return counts;
    }

    public void deleteEventsBefore(Instant cutoff) {
        jdbc.update("DELETE FROM redirect_event WHERE occurred_at<?", cutoff.atOffset(ZoneOffset.UTC));
    }

    public void deleteDailyCountsBefore(LocalDate earliestRetainedDate) {
        jdbc.update("DELETE FROM link_daily_count WHERE utc_date<?", earliestRetainedDate);
    }
}
