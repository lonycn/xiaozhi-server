-- P1: additive persistent conversation ledger. Legacy history is deliberately untouched.
CREATE TABLE ai_conversation (
    id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    list_sequence BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
    tenant_id VARCHAR(64) NULL COMMENT 'Reserved for trusted future tenant identity; never supplied by a client',
    owner_user_id BIGINT NOT NULL,
    agent_id VARCHAR(32) NULL,
    title VARCHAR(200) NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'AI_ACTIVE',
    last_seq BIGINT NOT NULL DEFAULT 0,
    last_event_seq BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_conversation_owner_list (owner_user_id, list_sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_message (
    id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    conversation_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seq BIGINT NOT NULL,
    client_message_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sender_type VARCHAR(32) NOT NULL,
    sender_id VARCHAR(64) NOT NULL,
    parts_json LONGTEXT NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_message_seq (conversation_id, seq),
    UNIQUE KEY uk_message_client (conversation_id, client_message_id),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation (id),
    CONSTRAINT chk_message_parts CHECK (JSON_VALID(parts_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_conversation_event (
    id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    conversation_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seq BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    data_json LONGTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_conversation_event_seq (conversation_id, seq),
    CONSTRAINT fk_event_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation (id),
    CONSTRAINT chk_conversation_event_data CHECK (JSON_VALID(data_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
