-- Additive companion for the existing im_notification_intent ledger; no historical backfill.
-- Identity and UNKNOWN rows are permanent. Never delete/rearm them to recover delivery.
-- Run only after the documented read-only preflight and the coordinator's migration approval.
CREATE TABLE committed_official_notice_wx (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recipient BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    im_intent_id BIGINT NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    wx_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    owner_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL,
    attempted_at DATETIME(3) NULL,
    accepted_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_committed_official_notice_event (event_key),
    UNIQUE KEY uk_committed_official_notice_im (im_intent_id),
    KEY idx_committed_official_notice_pending (wx_state,id),
    CONSTRAINT ck_committed_official_notice_recipient CHECK (recipient > 0 AND im_intent_id > 0),
    CONSTRAINT ck_committed_official_notice_state CHECK (wx_state IN ('PENDING','UNKNOWN','ACCEPTED')),
    CONSTRAINT ck_committed_official_notice_owner CHECK (
        (wx_state='PENDING' AND owner_token IS NULL AND attempted_at IS NULL AND accepted_at IS NULL)
        OR (wx_state='UNKNOWN' AND owner_token IS NOT NULL AND attempted_at IS NOT NULL AND accepted_at IS NULL)
        OR (wx_state='ACCEPTED' AND owner_token IS NOT NULL AND attempted_at IS NOT NULL AND accepted_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Frozen official-result notice: local IM uses its existing receipt; WeChat UNKNOWN never auto-retries';

-- Dedicated external-attempt recovery; do not broaden the existing local-only IM job.
-- Preserve any existing row/status. New registration is paused and must not be auto-enabled.
INSERT INTO sys_job
    (job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '原业务官方通知微信恢复','DEFAULT','committedOfficialNoticeTask.deliverPending()',
       '0 0/1 * * * ?','3','1','1','system',NOW(),
       '默认暂停；仅领取PENDING原意图；UNKNOWN保留原号人工核实，禁止自动重发'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_job WHERE invoke_target='committedOfficialNoticeTask.deliverPending()'
);
