ALTER TABLE support_media_session ADD COLUMN external_source_id BIGINT NULL,
 ADD COLUMN external_token_id BIGINT NULL, ADD COLUMN authorization_checked_at DATETIME(3) NULL,
 ADD KEY idx_media_external(external_source_id,external_token_id),
 ADD CONSTRAINT chk_media_external_pair CHECK ((external_source_id IS NULL AND external_token_id IS NULL) OR (external_source_id IS NOT NULL AND external_token_id IS NOT NULL));
ALTER TABLE support_media_member ADD COLUMN external_source_id BIGINT NULL,
 ADD COLUMN external_token_id BIGINT NULL,
 ADD CONSTRAINT chk_member_external_pair CHECK ((external_source_id IS NULL AND external_token_id IS NULL) OR (external_source_id IS NOT NULL AND external_token_id IS NOT NULL));
