-- M1：已有 booking_request 表升级；部署新 jar 前手工执行一次，不可重复执行。
ALTER TABLE booking_request
    ADD COLUMN requester_role TINYINT NOT NULL DEFAULT 0 COMMENT '请求方角色，助手记录默认住客 0' AFTER user_id,
    ADD COLUMN request_hash CHAR(64) NULL COMMENT '请求摘要 SHA-256，助手记录为空',
    ADD COLUMN fail_status INT NULL COMMENT 'FAILED 时的 HTTP 状态',
    ADD COLUMN fail_message VARCHAR(255) NULL COMMENT 'FAILED 时的失败原因',
    ADD KEY idx_booking_request_created (created_at);
