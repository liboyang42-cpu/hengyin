-- P1-3A 资金核心业务键。
-- 仅写 migration，不在本任务执行生产库；执行必须走 chengyin-db-migrate 的查重、备份与回读。
--
-- 前置负控是可执行合同：空串/纯空白/首尾空格/超过 VARCHAR(64) 合同的 request_id、
-- 重复 request_id、非法/重复命名空间奖励业务键任一存在都会撞临时表主键并停止。
-- 不用 CHECK：MySQL 8.0.15 及更早会解析但忽略 CHECK，主键冲突覆盖全部 MySQL 8。
--
-- ⚠️ 生产实测(2026-07-25)：cms_registration/ums_member_points_detail 是老库遗留表，
-- request_id/request_fingerprint/reward_business_key 等列的 collation 是 utf8mb4_general_ci，
-- 但 MySQL 8 会话默认按 utf8mb4_0900_ai_ci 解析字符串字面量 ⇒ 下面任何「列 vs ''/CONCAT(...)/
-- REGEXP 字面量」的比较都会撞 1267 Illegal mix of collations 并中断执行。本地测试库若建库时
-- 用的是 8.0 默认 collation 则不会复现，掩盖了这个问题。显式把连接 collation 钉成与老表一致，
-- 后续字面量按这个 collation 解析,不用逐条加 COLLATE。
SET collation_connection = utf8mb4_general_ci;

-- ⚠️ 真实 MySQL8 连测复现(2026-07-25)：生产库(information_schema 只读核实，8.0.45)其实
-- 是全库统一 utf8mb4_general_ci——ums_member 也不例外，SET collation_connection 已足够覆盖
-- 所有"列 vs 字面量"比较。但下面 EXISTS(...)/JOIN 里 TRIM(invitee.invite_user_id) =
-- CAST(detail.member_id AS CHAR) 是"列 vs CAST(...) 结果"的比较：CAST 结果和列一样都是
-- IMPLICIT（coercibility=2），不是字面量（coercibility=4），SET collation_connection 对它无效，
-- 两边各带各自的隐式 collation 就会撞 1267。本地测试库若建库用的是 MySQL8/9 新默认
-- utf8mb4_0900_ai_ci，会让这三处显式复现（生产因为全库 general_ci 反而不报错，属于本地测试
-- 更严格暴露问题）。已在这三处比较上给 CAST(...) 结果显式加 COLLATE utf8mb4_general_ci 收窄
-- 修复：invite_user_id 已被上方 REGEXP '^[1-9][0-9]*$' 与 CAST(member_id AS CHAR) 保证只含纯数字，
-- 数字字符在任意 utf8mb4 collation 下比较结果一致，加 COLLATE 不改变这条 guard 检测的业务语义。

SET @p1_request_fingerprint_column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'cms_registration'
    AND column_name = 'request_fingerprint'
);
SET @p1_add_request_fingerprint_column_sql := IF(
  @p1_request_fingerprint_column_exists = 0,
  'ALTER TABLE cms_registration ADD COLUMN request_fingerprint CHAR(64) NULL COMMENT ''服务端规范化请求指纹'' AFTER request_id',
  'SELECT 1'
);
PREPARE p1_add_request_fingerprint_column_stmt FROM @p1_add_request_fingerprint_column_sql;
EXECUTE p1_add_request_fingerprint_column_stmt;
DEALLOCATE PREPARE p1_add_request_fingerprint_column_stmt;

SET @p1_reward_key_column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ums_member_points_detail'
    AND column_name = 'reward_business_key'
);
SET @p1_add_reward_key_column_sql := IF(
  @p1_reward_key_column_exists = 0,
  'ALTER TABLE ums_member_points_detail ADD COLUMN reward_business_key VARCHAR(128) NULL COMMENT ''命名空间奖励业务键'' AFTER event_id',
  'SELECT 1'
);
PREPARE p1_add_reward_key_column_stmt FROM @p1_add_reward_key_column_sql;
EXECUTE p1_add_reward_key_column_stmt;
DEALLOCATE PREPARE p1_add_reward_key_column_stmt;

-- Preflight：先按回填后的业务键/旧三元组投影校验既有数据；任一坏行都在任何历史 UPDATE 前失败。
DROP TEMPORARY TABLE IF EXISTS p1_funds_core_guard;
CREATE TEMPORARY TABLE p1_funds_core_guard (
  id TINYINT NOT NULL,
  PRIMARY KEY (id)
);

INSERT INTO p1_funds_core_guard (id) VALUES (1);
INSERT INTO p1_funds_core_guard (id)
SELECT 1
FROM (
  SELECT 1 AS violation
  FROM cms_registration
  WHERE request_id IS NOT NULL
    AND (TRIM(request_id) = ''
      OR request_id <> TRIM(request_id)
      OR CHAR_LENGTH(request_id) > 64)
  UNION ALL
  SELECT 1
  FROM cms_registration
  WHERE request_fingerprint IS NOT NULL
    AND request_fingerprint NOT REGEXP '^[0-9a-f]{64}$'
  UNION ALL
  SELECT 1
  FROM cms_registration
  WHERE request_id IS NULL
    AND request_fingerprint IS NOT NULL
  UNION ALL
  SELECT 1
  FROM (
    SELECT member_id, request_id
    FROM cms_registration
    WHERE request_id IS NOT NULL
    GROUP BY member_id, request_id
    HAVING COUNT(*) > 1
  ) duplicate_registration_request
  UNION ALL
  SELECT 1
  FROM ums_member_points_detail
  WHERE reward_business_key IS NOT NULL
    AND (TRIM(reward_business_key) = ''
      OR reward_business_key <> TRIM(reward_business_key)
      OR CHAR_LENGTH(reward_business_key) > 128)
  UNION ALL
  SELECT 1
  FROM ums_member_points_detail detail
  WHERE detail.reward_business_key IS NULL
    AND detail.change_type = 1
    AND ((detail.event_type IN (3, 6)
        AND ((detail.event_type = 3 AND detail.change_reason = '参与活动')
          OR (detail.event_type = 6 AND detail.change_reason = '被邀请用户报名任意活动'))
        AND detail.event_id IS NULL)
      OR (detail.event_type = 5
        AND detail.change_reason = '邀请1位完成首次购买的用户'
        AND NOT EXISTS (
          SELECT 1 FROM ums_member invitee
          WHERE invitee.id = detail.event_id
            AND invitee.invite_user_id REGEXP '^[1-9][0-9]*$'
            AND TRIM(invitee.invite_user_id) = CAST(detail.member_id AS CHAR) COLLATE utf8mb4_general_ci
        )))
  UNION ALL
  SELECT 1
  FROM (
    SELECT member_id, event_type,
      CASE WHEN reward_business_key IS NULL AND change_type = 1
          AND event_type = 2 AND change_reason = '首次活动报名' THEN 0
        ELSE event_id END AS projected_event_id
    FROM ums_member_points_detail
  ) projected_legacy_event
  WHERE member_id IS NOT NULL AND event_type IS NOT NULL AND projected_event_id IS NOT NULL
  GROUP BY member_id, event_type, projected_event_id
  HAVING COUNT(*) > 1
  UNION ALL
  SELECT 1
  FROM (
    SELECT detail.member_id,
      CASE
        WHEN detail.reward_business_key IS NOT NULL THEN detail.reward_business_key
        WHEN detail.change_type = 1 AND detail.event_type = 2
          AND detail.change_reason = '首次活动报名' THEN 'registration:2:0'
        WHEN detail.change_type = 1 AND detail.event_type = 5
          AND detail.change_reason = '邀请1位完成首次购买的用户'
          AND EXISTS (SELECT 1 FROM ums_member invitee WHERE invitee.id = detail.event_id
            AND invitee.invite_user_id REGEXP '^[1-9][0-9]*$'
            AND TRIM(invitee.invite_user_id) = CAST(detail.member_id AS CHAR) COLLATE utf8mb4_general_ci)
          THEN CONCAT('registration:5:', detail.event_id)
        WHEN detail.change_type = 1 AND ((detail.event_type = 3 AND detail.change_reason = '参与活动')
          OR (detail.event_type = 6 AND detail.change_reason = '被邀请用户报名任意活动'))
          THEN CONCAT('registration:', detail.event_type, ':', detail.event_id)
      END AS projected_reward_business_key
    FROM ums_member_points_detail detail
  ) projected_reward_business_key
  WHERE member_id IS NOT NULL AND projected_reward_business_key IS NOT NULL
  GROUP BY member_id, projected_reward_business_key
  HAVING COUNT(*) > 1
) p1_funds_core_violation
LIMIT 1;

-- 回填只有报名资金模块写出的四种历史奖励，避免上线后对已支付订单补偿时二次发奖。
UPDATE ums_member_points_detail SET reward_business_key = 'registration:2:0', event_id = 0
WHERE reward_business_key IS NULL AND change_type = 1 AND event_type = 2 AND change_reason = '首次活动报名';

UPDATE ums_member_points_detail detail JOIN ums_member invitee ON invitee.id = detail.event_id
 AND invitee.invite_user_id REGEXP '^[1-9][0-9]*$'
 AND TRIM(invitee.invite_user_id) = CAST(detail.member_id AS CHAR) COLLATE utf8mb4_general_ci
SET detail.reward_business_key = CONCAT('registration:5:', detail.event_id)
WHERE detail.reward_business_key IS NULL AND detail.change_type = 1 AND detail.event_type = 5
  AND detail.change_reason = '邀请1位完成首次购买的用户' AND detail.event_id IS NOT NULL;

UPDATE ums_member_points_detail SET reward_business_key = CONCAT('registration:', event_type, ':', event_id)
WHERE reward_business_key IS NULL AND change_type = 1
  AND ((event_type = 3 AND change_reason = '参与活动') OR (event_type = 6 AND change_reason = '被邀请用户报名任意活动'));
--
-- 注意：禁止给 transaction_id 加全表唯一键。多票订单的支付主单与从单会共享同一个微信 transaction_id。

-- 先识别并移除“同名但定义错误”的索引，否则 ADD 会因 Duplicate key name 中断。
-- 数据查重已经在其前完成；若 DROP 后 ADD 意外中断，二次执行会从“缺键”状态继续恢复。
SET @p1_request_unique_definition := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index)), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'cms_registration'
    AND index_name = 'uk_reg_member_request'
);
SET @p1_drop_wrong_request_unique_sql := IF(
  @p1_request_unique_definition <> ''
    AND @p1_request_unique_definition <> '0:member_id,request_id',
  'ALTER TABLE cms_registration DROP INDEX uk_reg_member_request',
  'SELECT 1'
);
PREPARE p1_drop_wrong_request_unique_stmt FROM @p1_drop_wrong_request_unique_sql;
EXECUTE p1_drop_wrong_request_unique_stmt;
DEALLOCATE PREPARE p1_drop_wrong_request_unique_stmt;

SET @p1_add_request_unique_sql := IF(
  @p1_request_unique_definition = '0:member_id,request_id',
  'SELECT 1',
  'ALTER TABLE cms_registration ADD UNIQUE KEY uk_reg_member_request (member_id, request_id)'
);
PREPARE p1_add_request_unique_stmt FROM @p1_add_request_unique_sql;
EXECUTE p1_add_request_unique_stmt;
DEALLOCATE PREPARE p1_add_request_unique_stmt;

-- 奖励唯一键必须带域命名空间；裸 event_type/event_id 会与评价、任务等域碰撞。
SET @p1_reward_unique_definition := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index)), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'ums_member_points_detail'
    AND index_name = 'uk_member_reward_business'
);
SET @p1_drop_wrong_reward_unique_sql := IF(
  @p1_reward_unique_definition <> ''
    AND @p1_reward_unique_definition <> '0:member_id,reward_business_key',
  'ALTER TABLE ums_member_points_detail DROP INDEX uk_member_reward_business',
  'SELECT 1'
);
PREPARE p1_drop_wrong_reward_unique_stmt FROM @p1_drop_wrong_reward_unique_sql;
EXECUTE p1_drop_wrong_reward_unique_stmt;
DEALLOCATE PREPARE p1_drop_wrong_reward_unique_stmt;

SET @p1_add_reward_unique_sql := IF(
  @p1_reward_unique_definition = '0:member_id,reward_business_key',
  'SELECT 1',
  'ALTER TABLE ums_member_points_detail ADD UNIQUE KEY uk_member_reward_business (member_id, reward_business_key)'
);
PREPARE p1_add_reward_unique_stmt FROM @p1_add_reward_unique_sql;
EXECUTE p1_add_reward_unique_stmt;
DEALLOCATE PREPARE p1_add_reward_unique_stmt;

-- 两组唯一键建立后再次从 information_schema 回读；任一定义不符都撞 guard 主键停止。
SET @p1_request_unique_definition := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index)), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'cms_registration'
    AND index_name = 'uk_reg_member_request'
);
SET @p1_reward_unique_definition := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index)), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'ums_member_points_detail'
    AND index_name = 'uk_member_reward_business'
);
INSERT INTO p1_funds_core_guard (id)
SELECT 1
WHERE @p1_request_unique_definition <> '0:member_id,request_id'
   OR @p1_reward_unique_definition <> '0:member_id,reward_business_key';

-- 只有新唯一键回读确认后，才删除可能存在的旧普通索引；缺失时幂等跳过。
SET @p1_old_request_index_exists := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'cms_registration'
    AND index_name = 'idx_reg_member_request'
);
SET @p1_drop_old_request_index_sql := IF(
  @p1_old_request_index_exists > 0,
  'ALTER TABLE cms_registration DROP INDEX idx_reg_member_request',
  'SELECT 1'
);
PREPARE p1_drop_old_request_index_stmt FROM @p1_drop_old_request_index_sql;
EXECUTE p1_drop_old_request_index_stmt;
DEALLOCATE PREPARE p1_drop_old_request_index_stmt;

-- 旧 writer 仍会写 NULL reward_business_key，必须继续由旧三元组唯一键兜底。
-- 若同名索引缺失或定义错误则修复；只有全部 writer 迁移后，后续独立迁移才可删除。
SET @p1_legacy_reward_unique_definition := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index)), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'ums_member_points_detail'
    AND index_name = 'uk_member_event_once'
);
SET @p1_drop_wrong_legacy_reward_unique_sql := IF(
  @p1_legacy_reward_unique_definition <> ''
    AND @p1_legacy_reward_unique_definition <> '0:member_id,event_type,event_id',
  'ALTER TABLE ums_member_points_detail DROP INDEX uk_member_event_once',
  'SELECT 1'
);
PREPARE p1_drop_wrong_legacy_reward_unique_stmt FROM @p1_drop_wrong_legacy_reward_unique_sql;
EXECUTE p1_drop_wrong_legacy_reward_unique_stmt;
DEALLOCATE PREPARE p1_drop_wrong_legacy_reward_unique_stmt;

SET @p1_add_legacy_reward_unique_sql := IF(
  @p1_legacy_reward_unique_definition = '0:member_id,event_type,event_id',
  'SELECT 1',
  'ALTER TABLE ums_member_points_detail ADD UNIQUE KEY uk_member_event_once (member_id, event_type, event_id)'
);
PREPARE p1_add_legacy_reward_unique_stmt FROM @p1_add_legacy_reward_unique_sql;
EXECUTE p1_add_legacy_reward_unique_stmt;
DEALLOCATE PREPARE p1_add_legacy_reward_unique_stmt;

SET @p1_legacy_reward_unique_definition := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index)), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'ums_member_points_detail'
    AND index_name = 'uk_member_event_once'
);
INSERT INTO p1_funds_core_guard (id)
SELECT 1 WHERE @p1_legacy_reward_unique_definition <> '0:member_id,event_type,event_id';

DROP TEMPORARY TABLE p1_funds_core_guard;

-- 末端回读：两组业务键都必须逐列、按顺序存在且 non_unique=0。
SELECT table_name, index_name, non_unique, column_name, seq_in_index
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND (
    (table_name = 'cms_registration' AND index_name = 'uk_reg_member_request')
    OR (table_name = 'ums_member_points_detail'
      AND index_name IN ('uk_member_reward_business', 'uk_member_event_once'))
  )
ORDER BY table_name, index_name, seq_in_index;

-- 末端数据回读：非空 request_id + NULL fingerprint 只允许走运行时逐项证明 + CAS 兼容路径；
-- 反向孤儿 fingerprint 与不合法 fingerprint 已由上方 guard 阻断。
SELECT
  SUM(request_id IS NOT NULL AND request_fingerprint IS NULL) AS legacy_request_pending_proof,
  SUM(request_id IS NULL AND request_fingerprint IS NOT NULL) AS orphan_fingerprint_count,
  SUM(request_fingerprint IS NOT NULL
      AND request_fingerprint NOT REGEXP '^[0-9a-f]{64}$') AS invalid_fingerprint_count
FROM cms_registration;
