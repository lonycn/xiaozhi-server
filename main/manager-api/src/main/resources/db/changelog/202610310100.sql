-- B6 append-only identities, source revocation tombstones, opaque credentials and capabilities.
CREATE TABLE external_identity (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 issuer VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
 external_subject VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_external_identity(issuer,external_subject)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE external_tenant_principal (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,identity_id BIGINT NOT NULL,
 tenant_type VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,tenant_id VARCHAR(19) COLLATE utf8mb4_bin NOT NULL,
 user_id BIGINT NOT NULL,display_name VARCHAR(80) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_external_tenant(identity_id,tenant_type,tenant_id),UNIQUE KEY uq_external_user(user_id),
 FOREIGN KEY(identity_id) REFERENCES external_identity(id),FOREIGN KEY(user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE external_source_session (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 issuer VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,external_subject VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 source_session_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,expires_at DATETIME(3) NOT NULL,
 revoked_at DATETIME(3) NULL,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_external_source(issuer,external_subject,source_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE external_signature_nonce (
 key_id VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,nonce CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expires_at DATETIME(3) NOT NULL,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(key_id,nonce)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE external_launch_ticket (
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,source_id BIGINT NOT NULL,principal_id BIGINT NOT NULL,
 scope_json JSON NOT NULL,expires_at DATETIME(3) NOT NULL,consumed_at DATETIME(3) NULL,revoked_at DATETIME(3) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(source_id) REFERENCES external_source_session(id),FOREIGN KEY(principal_id) REFERENCES external_tenant_principal(id),
 KEY ix_external_ticket_source(source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE external_access_token (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 source_id BIGINT NOT NULL,principal_id BIGINT NOT NULL,scope_json JSON NOT NULL,
 expires_at DATETIME(3) NOT NULL,revoked_at DATETIME(3) NULL,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(source_id) REFERENCES external_source_session(id),FOREIGN KEY(principal_id) REFERENCES external_tenant_principal(id),
 KEY ix_external_token_source(source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE external_media_ticket (
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,bearer_id BIGINT NOT NULL,attachment_id BIGINT NOT NULL,
 variant VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,expires_at DATETIME(3) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),FOREIGN KEY(bearer_id) REFERENCES external_access_token(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
