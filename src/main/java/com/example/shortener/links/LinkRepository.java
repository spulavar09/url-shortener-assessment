package com.example.shortener.links;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LinkRepository {
    public record StoredLink(String id, String code, String destinationUrl, String lifecycle,
                             Instant createdAt, Instant expiresAt, long version) {
    }

    public record CreationRecord(String payloadHash, String originalShortUrl, StoredLink link) {
    }

    private final JdbcTemplate jdbc;

    public LinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredLink> findByCode(String code) {
        return jdbc.query("SELECT * FROM short_link WHERE code=?", (rs, n) -> mapLink(rs), code)
                .stream().findFirst();
    }

    public Optional<CreationRecord> findRetainedCreation(String key, Instant now) {
        return jdbc.query("SELECT l.*,r.payload_hash,r.original_short_url FROM link_create_request r JOIN short_link l ON l.id=r.link_id WHERE r.request_key=? AND r.retain_until>?",
                (rs, n) -> new CreationRecord(rs.getString("payload_hash"), rs.getString("original_short_url"), mapLink(rs)),
                key, at(now)).stream().findFirst();
    }

    public void insertLink(String id, String code, String destinationUrl, Instant createdAt, Instant expiresAt) {
        jdbc.update("INSERT INTO short_link(id,code,destination_url,lifecycle,created_at,expires_at,resource_version) VALUES(?,?,?,'ACTIVE',?,?,0)",
                id, code, destinationUrl, at(createdAt), at(expiresAt));
    }

    public void insertCreation(String key, String payloadHash, String originalShortUrl, String linkId, Instant retainUntil) {
        jdbc.update("INSERT INTO link_create_request(request_key,payload_hash,original_short_url,link_id,retain_until) VALUES(?,?,?,?,?)",
                key, payloadHash, originalShortUrl, linkId, at(retainUntil));
    }

    public void deleteExpiredCreation(String key, Instant now) {
        jdbc.update("DELETE FROM link_create_request WHERE request_key=? AND retain_until<=?", key, at(now));
    }

    public void deleteExpiredCreations(Instant now) {
        jdbc.update("DELETE FROM link_create_request WHERE retain_until<=?", at(now));
    }

    public boolean disableAtVersion(String code, long expectedVersion) {
        return jdbc.update("UPDATE short_link SET lifecycle='DISABLED',resource_version=resource_version+1 WHERE code=? AND resource_version=? AND lifecycle<>'DELETED'",
                code, expectedVersion) == 1;
    }

    public void tombstone(String code, Instant deletedAt) {
        jdbc.update("UPDATE short_link SET lifecycle='DELETED',deleted_at=?,resource_version=resource_version+1 WHERE code=? AND lifecycle<>'DELETED'",
                at(deletedAt), code);
    }

    private static StoredLink mapLink(ResultSet rs) throws SQLException {
        OffsetDateTime expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
        return new StoredLink(rs.getString("id"), rs.getString("code"), rs.getString("destination_url"),
                rs.getString("lifecycle"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                expiresAt == null ? null : expiresAt.toInstant(), rs.getLong("resource_version"));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
