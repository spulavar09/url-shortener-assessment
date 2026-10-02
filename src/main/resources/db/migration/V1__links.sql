CREATE TABLE short_link (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    destination_url VARCHAR(4096) NOT NULL,
    lifecycle VARCHAR(16) NOT NULL CHECK (CASE lifecycle WHEN 'ACTIVE' THEN TRUE WHEN 'DISABLED' THEN TRUE WHEN 'DELETED' THEN TRUE ELSE FALSE END),
    created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(9) WITH TIME ZONE,
    deleted_at TIMESTAMP(9) WITH TIME ZONE,
    resource_version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX short_link_page ON short_link(created_at, id);
CREATE TABLE link_create_request (
    request_key VARCHAR(128) PRIMARY KEY,
    payload_hash VARCHAR(64) NOT NULL,
    original_short_url VARCHAR(512) NOT NULL,
    link_id VARCHAR(36) NOT NULL REFERENCES short_link(id),
    retain_until TIMESTAMP(9) WITH TIME ZONE NOT NULL
);
CREATE INDEX link_create_retention ON link_create_request(retain_until);
CREATE TABLE link_total (
    link_id VARCHAR(36) PRIMARY KEY REFERENCES short_link(id),
    counted_events BIGINT NOT NULL DEFAULT 0 CHECK (counted_events >= 0)
);
CREATE TABLE redirect_event (
    event_id VARCHAR(36) PRIMARY KEY,
    link_id VARCHAR(36) NOT NULL REFERENCES short_link(id),
    occurred_at TIMESTAMP(9) WITH TIME ZONE NOT NULL
);
CREATE INDEX redirect_event_retention ON redirect_event(occurred_at);
CREATE TABLE link_daily_count (
    link_id VARCHAR(36) NOT NULL REFERENCES short_link(id),
    utc_date DATE NOT NULL,
    counted_events BIGINT NOT NULL CHECK (counted_events >= 0),
    PRIMARY KEY(link_id, utc_date)
);
