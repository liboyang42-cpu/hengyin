-- runner: requires=transaction
-- ROAM_POI_ARRIVAL 曾把客户端自报经纬度直接转成 VALID 计奖证据。
-- 在可信设备位置事实适配器上线前关闭旧开关，并撤销这类不可信证据。

UPDATE sys_config
SET config_value = 'false',
    remark = '缺少可信设备位置事实，ROAM_POI_ARRIVAL 已停闸；完成独立真机负控和生产回读前不得开启',
    update_by = 'admin',
    update_time = NOW()
WHERE config_key = 'official_event.roam_poi_arrival_enabled'
  AND config_value <> 'false';

-- 先禁用任务，关闭“迁移已执行、旧进程仍持有开关缓存”的发布窗口。
UPDATE official_event_mission
SET status = 'DISABLED',
    update_time = NOW()
WHERE mission_type = 'ROAM_POI_ARRIVAL'
  AND status <> 'ARCHIVED';

UPDATE official_event_mission_evidence e
JOIN official_event_mission m ON m.id = e.mission_id
SET e.status = 'REVOKED',
    e.reason = '客户端自报位置不构成可信计奖事实，2026-08-22 安全停闸撤销',
    e.revoked_at = NOW(),
    e.update_time = NOW()
WHERE m.mission_type = 'ROAM_POI_ARRIVAL'
  AND e.status = 'VALID';

UPDATE official_event_reward_issuance ri
SET ri.status = 'MANUAL_REVIEW',
    ri.failure_reason = 'ROAM_POI_ARRIVAL 证据来自客户端自报位置，需人工复核',
    ri.update_time = NOW()
WHERE ri.status IN ('PENDING_ISSUE', 'ISSUED', 'CLAIMED')
  AND EXISTS (
    SELECT 1
    FROM official_event_mission_evidence e
    JOIN official_event_mission m ON m.id = e.mission_id
    WHERE e.event_id = ri.event_id
      AND e.member_id = ri.member_id
      AND m.mission_type = 'ROAM_POI_ARRIVAL'
      AND e.status = 'REVOKED'
  );

CREATE TEMPORARY TABLE assert_roam_gate_disabled (
  observed_rows INT NOT NULL,
  CONSTRAINT ck_roam_gate_disabled CHECK (observed_rows = 1)
);

INSERT INTO assert_roam_gate_disabled(observed_rows)
SELECT COUNT(*)
FROM sys_config
WHERE config_key = 'official_event.roam_poi_arrival_enabled'
  AND config_value = 'false';

DROP TEMPORARY TABLE assert_roam_gate_disabled;

CREATE TEMPORARY TABLE assert_untrusted_roam_missions_disabled (
  observed_rows INT NOT NULL,
  CONSTRAINT ck_untrusted_roam_missions_disabled CHECK (observed_rows = 0)
);

INSERT INTO assert_untrusted_roam_missions_disabled(observed_rows)
SELECT COUNT(*)
FROM official_event_mission
WHERE mission_type = 'ROAM_POI_ARRIVAL'
  AND status NOT IN ('DISABLED', 'ARCHIVED');

DROP TEMPORARY TABLE assert_untrusted_roam_missions_disabled;

CREATE TEMPORARY TABLE assert_untrusted_roam_evidence_revoked (
  observed_rows INT NOT NULL,
  CONSTRAINT ck_untrusted_roam_evidence_revoked CHECK (observed_rows = 0)
);

INSERT INTO assert_untrusted_roam_evidence_revoked(observed_rows)
SELECT COUNT(*)
FROM official_event_mission_evidence e
JOIN official_event_mission m ON m.id = e.mission_id
WHERE m.mission_type = 'ROAM_POI_ARRIVAL'
  AND e.status = 'VALID';

DROP TEMPORARY TABLE assert_untrusted_roam_evidence_revoked;

CREATE TEMPORARY TABLE assert_untrusted_roam_rewards_held (
  observed_rows INT NOT NULL,
  CONSTRAINT ck_untrusted_roam_rewards_held CHECK (observed_rows = 0)
);

INSERT INTO assert_untrusted_roam_rewards_held(observed_rows)
SELECT COUNT(*)
FROM official_event_reward_issuance ri
JOIN official_event_mission_evidence e
  ON e.event_id = ri.event_id AND e.member_id = ri.member_id
JOIN official_event_mission m ON m.id = e.mission_id
WHERE m.mission_type = 'ROAM_POI_ARRIVAL'
  AND e.status = 'REVOKED'
  AND ri.status IN ('PENDING_ISSUE', 'ISSUED', 'CLAIMED');

DROP TEMPORARY TABLE assert_untrusted_roam_rewards_held;
