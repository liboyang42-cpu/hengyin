-- 按章售后沿用原人工队列，不触发支付或改写已履约记录。
-- 仅在确认目标库后执行一次；上线另行预检和批准。
ALTER TABLE activity_cancel_manual_refund_case
  ADD COLUMN case_type VARCHAR(32) NOT NULL DEFAULT 'MIXED_TICKETS',
  ADD COLUMN chapter_snapshot_json JSON DEFAULT NULL,
  DROP CHECK chk_activity_cancel_manual_unverified_json,
  ADD CONSTRAINT chk_activity_cancel_manual_unverified_json CHECK (
    JSON_TYPE(unverified_registration_ids_json) = 'ARRAY'
    AND (JSON_LENGTH(unverified_registration_ids_json) > 0 OR case_type = 'PARTIAL_CHAPTERS')),
  ADD CONSTRAINT chk_manual_case_chapter_type CHECK (
    (case_type = 'MIXED_TICKETS' AND chapter_snapshot_json IS NULL)
    OR (case_type = 'PARTIAL_CHAPTERS' AND chapter_snapshot_json IS NOT NULL
      AND JSON_TYPE(chapter_snapshot_json) = 'ARRAY' AND JSON_LENGTH(chapter_snapshot_json) > 1));
