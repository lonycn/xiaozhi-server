-- P3 realtime bridge. Additive ledger; no legacy data or changeset is changed.
CREATE TABLE ai_realtime_session (
 id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 list_sequence BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
 conversation_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 owner_user_id BIGINT NOT NULL, client_session_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'pending', control_epoch BIGINT NOT NULL,
 model VARCHAR(100) NOT NULL, consent_microphone TINYINT NOT NULL, consent_camera TINYINT NOT NULL DEFAULT 0,
 context_seq BIGINT NOT NULL, provider_session_id VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NULL,
 worker_id VARCHAR(100) NULL, server_id VARCHAR(100) NULL, lease_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_expires_at DATETIME(3) NULL, last_event_sequence BIGINT NOT NULL DEFAULT 0,
 error_code VARCHAR(64) NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 connected_at DATETIME(3) NULL, ended_at DATETIME(3) NULL,
 active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('pending','connecting','active') THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY uk_realtime_active(conversation_id,active_slot),
 UNIQUE KEY uk_realtime_client(conversation_id,client_session_id),
 KEY idx_realtime_recovery(status,lease_expires_at),
 CONSTRAINT fk_realtime_conversation FOREIGN KEY(conversation_id) REFERENCES ai_conversation(id),
 CONSTRAINT chk_realtime_status CHECK(status IN ('pending','connecting','active','ended','failed','expired','canceled')),
 CONSTRAINT chk_realtime_consent CHECK(consent_microphone=1 AND consent_camera IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_realtime_ticket (
 id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 session_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ticket_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 status VARCHAR(16) NOT NULL DEFAULT 'issued', expires_at DATETIME(3) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), consumed_at DATETIME(3) NULL,
 CONSTRAINT fk_realtime_ticket FOREIGN KEY(session_id) REFERENCES ai_realtime_session(id),
 CONSTRAINT chk_realtime_ticket CHECK(status IN ('issued','consumed','expired')),
 KEY idx_realtime_ticket_session(session_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_realtime_event (
 id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 session_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sequence BIGINT NOT NULL, provider_event_id VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 event_type VARCHAR(40) NOT NULL, request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_realtime_event_seq(session_id,sequence), UNIQUE KEY uk_realtime_event_id(session_id,provider_event_id),
 CONSTRAINT fk_realtime_event FOREIGN KEY(session_id) REFERENCES ai_realtime_session(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_realtime_turn (
 id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 session_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 provider_item_id VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(24) NOT NULL, source VARCHAR(50) NOT NULL, status VARCHAR(20) NOT NULL,
 message_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 committed_at DATETIME(3) NULL,
 output_text MEDIUMTEXT NOT NULL, finish_reason VARCHAR(30) NULL, usage_json JSON NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), ended_at DATETIME(3) NULL,
 active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN kind='assistant' AND status IN ('streaming','interrupting') THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY uk_realtime_turn_item(session_id,provider_item_id), UNIQUE KEY uk_realtime_turn_active(session_id,active_slot),
 CONSTRAINT fk_realtime_turn_session FOREIGN KEY(session_id) REFERENCES ai_realtime_session(id),
 CONSTRAINT fk_realtime_turn_message FOREIGN KEY(message_id) REFERENCES ai_message(id),
 CONSTRAINT chk_realtime_turn_kind CHECK(kind IN ('input_audio','assistant')),
 CONSTRAINT chk_realtime_turn_status CHECK(status IN ('streaming','interrupting','completed','canceled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_realtime_injection (
 id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 list_sequence BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
 session_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 message_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 provider_item_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 text_content TEXT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'queued', error_code VARCHAR(64) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), dispatched_at DATETIME(3) NULL, acknowledged_at DATETIME(3) NULL,
 CONSTRAINT fk_realtime_injection_session FOREIGN KEY(session_id) REFERENCES ai_realtime_session(id),
 CONSTRAINT fk_realtime_injection_message FOREIGN KEY(message_id) REFERENCES ai_message(id),
 CONSTRAINT chk_realtime_injection_status CHECK(status IN ('queued','dispatched','acknowledged','failed')),
 KEY idx_realtime_injection_queue(session_id,status,list_sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_realtime_command (
 id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 session_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 client_command_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 response_id VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 command_type VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'queued', error_code VARCHAR(64) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), dispatched_at DATETIME(3) NULL, acknowledged_at DATETIME(3) NULL,
 UNIQUE KEY uk_realtime_command_client(session_id,client_command_id),
 CONSTRAINT fk_realtime_command_session FOREIGN KEY(session_id) REFERENCES ai_realtime_session(id),
 CONSTRAINT chk_realtime_command_type CHECK(command_type='interrupt'),
 CONSTRAINT chk_realtime_command_status CHECK(status IN ('queued','dispatched','acknowledged','failed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
