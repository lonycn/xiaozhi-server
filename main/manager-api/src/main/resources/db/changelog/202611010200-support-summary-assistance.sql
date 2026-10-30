-- Additive internal-only jobs. No customer ledger or device/tool writes.
ALTER TABLE support_summary_revision ALTER CHECK chk_summary_origin NOT ENFORCED;
ALTER TABLE support_summary_revision ADD CONSTRAINT chk_summary_origin_v2 CHECK(origin IN ('deterministic_excerpt','staff_edit','model_assisted'));
CREATE TABLE support_summary_job (
 id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 list_sequence BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
 conversation_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_user_id BIGINT NOT NULL,
 assignment_id VARCHAR(32) NOT NULL,
 client_request_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 base_version BIGINT NOT NULL,
 source_through_seq BIGINT NOT NULL,
 source_json JSON NOT NULL,
 source_sha256 CHAR(64) NOT NULL,
 model VARCHAR(128) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'queued',
 attempt INT NOT NULL DEFAULT 0,
 lease_token VARCHAR(64) NULL,
 lease_owner VARCHAR(128) NULL,
 lease_expires_at DATETIME(3) NULL,
 error_code VARCHAR(80) NULL,
 result_version BIGINT NULL,
 suggestions_json JSON NULL,
 usage_json JSON NULL,
 provider_request_id VARCHAR(256) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_summary_job_request(conversation_id,actor_user_id,client_request_id),
 KEY ix_summary_job_claim(status,created_at),
 CONSTRAINT fk_summary_job_conversation FOREIGN KEY(conversation_id) REFERENCES ai_conversation(id),
 CONSTRAINT chk_summary_job_status CHECK(status IN ('queued','running','completed','failed','stale'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE support_summary_attempt (
 job_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 attempt INT NOT NULL,
 model VARCHAR(128) NOT NULL,
 worker_id VARCHAR(128) NOT NULL,
 status VARCHAR(16) NOT NULL,
 error_code VARCHAR(80) NULL,
 usage_json JSON NULL,
 provider_request_id VARCHAR(256) NULL,
 started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 finished_at DATETIME(3) NULL,
 PRIMARY KEY(job_id,attempt),
 CONSTRAINT fk_summary_attempt_job FOREIGN KEY(job_id) REFERENCES support_summary_job(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
