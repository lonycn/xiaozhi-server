CREATE TABLE xiaozhi_chat_archive_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    archive_issuer VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_version BIGINT NOT NULL,
    tenant_id VARCHAR(18) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id VARCHAR(18) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload MEDIUMBLOB NOT NULL,
    status VARCHAR(16) NOT NULL,
    last_error VARCHAR(32),
    attempts INT NOT NULL DEFAULT 0,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    available_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_chat_archive_event (conversation_id, event_version),
    KEY idx_chat_archive_message (message_id),
    KEY idx_chat_archive_poll (archive_issuer, status, available_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
