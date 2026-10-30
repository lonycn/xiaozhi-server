-- Internal append-only handoff revisions. Existing legacy summaries are not ledger-bound.
CREATE TABLE support_summary_revision (
 conversation_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 version BIGINT NOT NULL,
 parent_version BIGINT NULL,
 origin VARCHAR(32) NOT NULL,
 edited_by BIGINT NULL,
 source_through_seq BIGINT NOT NULL,
 source_truncated TINYINT NOT NULL,
 fields_json JSON NOT NULL,
 released_assignment_ids JSON NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(conversation_id,version),
 CONSTRAINT fk_summary_conversation FOREIGN KEY(conversation_id) REFERENCES ai_conversation(id),
 CONSTRAINT chk_summary_origin CHECK(origin IN ('deterministic_excerpt','staff_edit')),
 CONSTRAINT chk_summary_version CHECK(version > 0 AND (parent_version IS NULL OR parent_version < version))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
