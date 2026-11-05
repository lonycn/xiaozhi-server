-- Existing accepting/presence_expires_at now belong to customer service only.
-- Preserve the old primary role's preference; never opt a second business in.
ALTER TABLE support_agent_profile
    ADD COLUMN expert_accepting TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN expert_presence_expires_at DATETIME(3) NULL;
UPDATE support_agent_profile SET expert_accepting=accepting,
    expert_presence_expires_at=presence_expires_at,accepting=0,presence_expires_at=NULL
    WHERE role='expert';
