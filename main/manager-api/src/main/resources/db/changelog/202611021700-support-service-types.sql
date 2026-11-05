-- Preserve legacy roles and all live/history assignments. New grants are explicit admin updates.
ALTER TABLE support_agent_profile ADD COLUMN roles_json JSON NULL;
UPDATE support_agent_profile SET roles_json=JSON_ARRAY(role) WHERE roles_json IS NULL;
ALTER TABLE support_request ADD COLUMN service_type VARCHAR(32) NOT NULL DEFAULT 'support_agent',
    ADD CONSTRAINT chk_support_service_type CHECK (service_type IN ('support_agent','expert')),
    ADD INDEX idx_support_service_queue (service_type,status,list_sequence);
-- Existing assigned expert requests retain their assignee; directed expert queues retain their intent.
UPDATE support_request r LEFT JOIN support_agent_profile p
    ON p.user_id=COALESCE(r.assigned_agent_user_id,r.target_agent_user_id)
    SET r.service_type=COALESCE(p.role,'support_agent');
ALTER TABLE support_assignment ADD COLUMN service_type VARCHAR(32) NOT NULL DEFAULT 'support_agent',
    ADD CONSTRAINT chk_support_assignment_service_type CHECK (service_type IN ('support_agent','expert'));
UPDATE support_assignment a JOIN support_request r ON r.id=a.request_id
    LEFT JOIN support_agent_profile p ON p.user_id=a.agent_user_id
    SET a.service_type=COALESCE(p.role,r.service_type);
CREATE TABLE support_request_transfer (
    id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    request_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id BIGINT NOT NULL,
    client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_service_type VARCHAR(32) NOT NULL,
    service_type VARCHAR(32) NOT NULL,
    target_agent_user_id BIGINT NULL,
    reason VARCHAR(500) NULL,
    response_json JSON NULL,
    transferred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_support_transfer_client (request_id,actor_user_id,client_request_id),
    CONSTRAINT fk_support_transfer_request FOREIGN KEY (request_id) REFERENCES support_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
