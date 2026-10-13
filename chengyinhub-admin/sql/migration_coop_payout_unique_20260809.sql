-- Coop T+7 打款流水唯一键。
-- 仅准备 migration，本任务不连接、不执行生产库；落库前必须走 chengyin-db-migrate 只读预检与人工确认。
--
-- Java 不读取本迁移新增的生成列：迁移未执行时，REQUIRES_NEW + 结算行锁/CAS 仍可安全运行；
-- 落库后由生成列唯一索引再兜住任何意外状态回退或非标准重放。
-- 新 writer 契约：event_type=5、event_id=coop_settlement.id、change_reason 以「Coop结算打款 - 主题」开头。

-- 前置数据闸：新 writer 流水必须能唯一、同受款人、同金额地回指一张已打款结算单；任一坏行撞主键并中止。
-- 同时阻断「旧 writer 已加余额、结算仍 status=0」的疑似存量；具体行先用
-- scripts/audit_coop_payout_legacy.py 只读列出并人工对账，禁止自动回填歧义历史。
DROP TEMPORARY TABLE IF EXISTS coop_payout_unique_guard;
CREATE TEMPORARY TABLE coop_payout_unique_guard (
  id TINYINT NOT NULL,
  PRIMARY KEY (id)
);
INSERT INTO coop_payout_unique_guard (id) VALUES (1);
INSERT INTO coop_payout_unique_guard (id)
SELECT 1
FROM (
  SELECT detail.event_id
  FROM ums_member_balance_detail detail
  LEFT JOIN coop_settlement settlement ON settlement.id = detail.event_id
  WHERE detail.event_type = 5
    AND detail.change_reason LIKE 'Coop结算打款 - 主题%'
    AND (detail.event_id IS NULL
      OR settlement.id IS NULL
      OR NOT (settlement.status <=> 1)
      OR NOT (settlement.merchant_id <=> detail.member_id)
      OR NOT (settlement.amount <=> detail.change_balance))
  UNION ALL
  SELECT detail.event_id
  FROM ums_member_balance_detail detail
  WHERE detail.event_type = 5
    AND detail.change_reason LIKE 'Coop结算打款 - 主题%'
  GROUP BY detail.event_id
  HAVING detail.event_id IS NULL OR COUNT(*) > 1
  UNION ALL
  SELECT settlement.id
  FROM coop_settlement settlement
  JOIN ums_member_balance_detail detail
    ON detail.event_type = 5
   AND detail.change_type = 1
   AND detail.del_flag = 0
   AND detail.event_id = settlement.topic_id
   AND detail.member_id = settlement.merchant_id
   AND detail.change_balance = settlement.amount
   AND detail.change_reason LIKE '商家核销分润 - 主题%'
  WHERE settlement.del_flag = 0
    AND settlement.status = 0
) violation
LIMIT 1;
DROP TEMPORARY TABLE coop_payout_unique_guard;

-- 同名生成列若已存在但定义不符，响亮失败；禁止静默复用错误列。
DROP TEMPORARY TABLE IF EXISTS coop_payout_schema_guard;
CREATE TEMPORARY TABLE coop_payout_schema_guard (
  id TINYINT NOT NULL,
  PRIMARY KEY (id)
);
INSERT INTO coop_payout_schema_guard (id) VALUES (1);
INSERT INTO coop_payout_schema_guard (id)
SELECT 1
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'ums_member_balance_detail'
  AND column_name = 'coop_settlement_payout_id'
  AND (data_type <> 'bigint'
    OR extra NOT LIKE '%VIRTUAL GENERATED%'
    OR LOCATE('event_type', generation_expression) = 0
    OR LOCATE('event_id', generation_expression) = 0
    OR LOCATE('Coop结算打款 - 主题', generation_expression) = 0)
LIMIT 1;
DROP TEMPORARY TABLE coop_payout_schema_guard;

SET @coop_payout_column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ums_member_balance_detail'
    AND column_name = 'coop_settlement_payout_id'
);
SET @coop_payout_add_column_sql := IF(
  @coop_payout_column_exists = 0,
  'ALTER TABLE ums_member_balance_detail ADD COLUMN coop_settlement_payout_id BIGINT GENERATED ALWAYS AS (CASE WHEN event_type = 5 AND change_reason LIKE ''Coop结算打款 - 主题%'' THEN event_id ELSE NULL END) VIRTUAL COMMENT ''Coop结算打款流水唯一业务键'' AFTER event_id',
  'SELECT 1'
);
PREPARE coop_payout_add_column_stmt FROM @coop_payout_add_column_sql;
EXECUTE coop_payout_add_column_stmt;
DEALLOCATE PREPARE coop_payout_add_column_stmt;

-- 同名索引定义错误时先移除，再按生成列单列唯一键恢复；正确时幂等跳过。
SET @coop_payout_unique_definition := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index)), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'ums_member_balance_detail'
    AND index_name = 'uk_coop_settlement_payout'
);
SET @coop_payout_drop_wrong_unique_sql := IF(
  @coop_payout_unique_definition <> ''
    AND @coop_payout_unique_definition <> '0:coop_settlement_payout_id',
  'ALTER TABLE ums_member_balance_detail DROP INDEX uk_coop_settlement_payout',
  'SELECT 1'
);
PREPARE coop_payout_drop_wrong_unique_stmt FROM @coop_payout_drop_wrong_unique_sql;
EXECUTE coop_payout_drop_wrong_unique_stmt;
DEALLOCATE PREPARE coop_payout_drop_wrong_unique_stmt;

SET @coop_payout_unique_definition := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index)), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'ums_member_balance_detail'
    AND index_name = 'uk_coop_settlement_payout'
);
SET @coop_payout_add_unique_sql := IF(
  @coop_payout_unique_definition = '0:coop_settlement_payout_id',
  'SELECT 1',
  'ALTER TABLE ums_member_balance_detail ADD UNIQUE KEY uk_coop_settlement_payout (coop_settlement_payout_id)'
);
PREPARE coop_payout_add_unique_stmt FROM @coop_payout_add_unique_sql;
EXECUTE coop_payout_add_unique_stmt;
DEALLOCATE PREPARE coop_payout_add_unique_stmt;

-- 执行后回读（对面事实）：两条都必须各返回一行且定义匹配。
SELECT column_name, column_type, extra, generation_expression
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'ums_member_balance_detail'
  AND column_name = 'coop_settlement_payout_id';
SELECT index_name, non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS indexed_columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'ums_member_balance_detail'
  AND index_name = 'uk_coop_settlement_payout'
GROUP BY index_name, non_unique;

-- 手工回滚（仅在确认没有依赖该唯一闸后执行，先索引后列）：
-- ALTER TABLE ums_member_balance_detail DROP INDEX uk_coop_settlement_payout;
-- ALTER TABLE ums_member_balance_detail DROP COLUMN coop_settlement_payout_id;
