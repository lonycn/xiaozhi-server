-- Display information is separate from account identity and staff permissions.
CREATE TABLE support_public_profile (
    user_id BIGINT NOT NULL PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL DEFAULT '',
    title VARCHAR(120) NOT NULL DEFAULT '',
    bio VARCHAR(1000) NOT NULL DEFAULT '',
    specialties_json JSON NOT NULL,
    listed TINYINT NOT NULL DEFAULT 1,
    avatar_url VARCHAR(1000) NOT NULL DEFAULT '',
    avatar_content MEDIUMBLOB NULL,
    avatar_mime VARCHAR(32) NULL,
    avatar_version CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_support_public_staff FOREIGN KEY (user_id) REFERENCES support_agent_profile(user_id),
    CONSTRAINT chk_support_public_listed CHECK (listed IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
