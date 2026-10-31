ALTER TABLE ai_realtime_session ADD COLUMN turn_detection_mode VARCHAR(16) NOT NULL DEFAULT 'manual',
 ADD CONSTRAINT chk_realtime_turn_detection CHECK (turn_detection_mode IN ('manual','server_vad'));
