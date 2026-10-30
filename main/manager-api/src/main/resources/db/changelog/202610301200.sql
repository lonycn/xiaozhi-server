-- B1 human text support, additive to the conversation ledger. Legacy history stays untouched.
ALTER TABLE ai_conversation ADD COLUMN control_epoch BIGINT NOT NULL DEFAULT 0;

CREATE TABLE support_agent_profile (
    user_id BIGINT NOT NULL PRIMARY KEY,
    role VARCHAR(32) NOT NULL DEFAULT 'support_agent',
    enabled TINYINT NOT NULL DEFAULT 1,
    capacity_text INT NOT NULL DEFAULT 3,
    accepting TINYINT NOT NULL DEFAULT 0,
    presence_expires_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_support_profile_role CHECK (role IN ('support_agent', 'expert')),
    CONSTRAINT chk_support_profile_capacity CHECK (capacity_text BETWEEN 1 AND 20),
    CONSTRAINT chk_support_profile_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT chk_support_profile_accepting CHECK (accepting IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE support_request (
    id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    list_sequence BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
    conversation_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'queued',
    requested_mode VARCHAR(16) NOT NULL DEFAULT 'text',
    target_agent_user_id BIGINT NULL,
    assigned_agent_user_id BIGINT NULL,
    requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    assigned_at DATETIME(3) NULL,
    first_response_at DATETIME(3) NULL,
    ended_at DATETIME(3) NULL,
    end_reason VARCHAR(500) NULL,
    active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('queued', 'active') THEN 1 ELSE NULL END) STORED,
    UNIQUE KEY uk_support_request_active (conversation_id, active_slot),
    UNIQUE KEY uk_support_request_client (conversation_id, client_request_id),
    UNIQUE KEY uk_support_request_conversation (id, conversation_id),
    INDEX idx_support_queue (status, list_sequence),
    INDEX idx_support_assigned (assigned_agent_user_id, status),
    CONSTRAINT fk_support_request_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id),
    CONSTRAINT chk_support_request_status CHECK (status IN ('queued', 'active', 'resolved', 'canceled')),
    CONSTRAINT chk_support_request_mode CHECK (requested_mode = 'text'),
    CONSTRAINT chk_support_request_assignee CHECK ((status = 'active' AND assigned_agent_user_id IS NOT NULL) OR status != 'active')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE support_assignment (
    id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    request_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_user_id BIGINT NOT NULL,
    assigned_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    released_at DATETIME(3) NULL,
    read_through_seq BIGINT NULL,
    read_through_event_seq BIGINT NULL,
    active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN released_at IS NULL THEN 1 ELSE NULL END) STORED,
    UNIQUE KEY uk_support_primary_assignment (request_id, active_slot),
    INDEX idx_support_assignment_actor (agent_user_id, released_at),
    INDEX idx_support_assignment_history (conversation_id, agent_user_id),
    CONSTRAINT fk_support_assignment_request FOREIGN KEY (request_id, conversation_id) REFERENCES support_request(id, conversation_id),
    CONSTRAINT chk_support_assignment_cutoff CHECK (
        (released_at IS NULL AND read_through_seq IS NULL AND read_through_event_seq IS NULL)
        OR (released_at IS NOT NULL AND read_through_seq IS NOT NULL AND read_through_event_seq IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve every accepted request key, including keys coalesced into an existing queue item.
CREATE TABLE support_request_key (
    conversation_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (conversation_id, client_request_id),
    CONSTRAINT fk_support_request_key FOREIGN KEY (request_id, conversation_id) REFERENCES support_request(id, conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
