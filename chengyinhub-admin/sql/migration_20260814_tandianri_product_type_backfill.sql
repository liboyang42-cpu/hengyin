-- 探店日第 0 批止血：回填唯一仍活跃的混票主题 product_type。
-- runner: requires=transaction
-- 枚举真源：ProductType.ORIENTEERING=1，ProductType.FREE_EXPLORE=2。
-- 2026-08-14 生产只读预检：product_type IS NULL 共 3 个主题；仅 id=23 同时满足
-- del_flag=0、有有效主题票、且票 mode 同时包含 1/2。总控裁定只回填该 active mixed topic。

-- 执行前 fail-closed 断言：目标事实漂移时 INSERT 违反 CHECK，整份脚本立即失败。
CREATE TEMPORARY TABLE assert_tandianri_pt_before (
    observed_rows INT NOT NULL,
    CONSTRAINT ck_tandianri_pt_before_exact CHECK (observed_rows = 1)
);
INSERT INTO assert_tandianri_pt_before(observed_rows)
SELECT COUNT(*)
FROM cms_topic t
WHERE t.id = 23
  AND t.del_flag = 0
  AND t.status = 1
  AND ((t.product_type IS NULL
        AND EXISTS (SELECT 1 FROM oms_ticket o
                    WHERE o.owner_type = 1 AND o.owner_id = t.id AND o.del_flag = 0 AND o.mode = 1)
        AND EXISTS (SELECT 1 FROM oms_ticket o
                    WHERE o.owner_type = 1 AND o.owner_id = t.id AND o.del_flag = 0 AND o.mode = 2))
       OR (t.product_type = 1
           AND t.update_by = 'migration_tandianri_product_type_backfill'));
DROP TEMPORARY TABLE assert_tandianri_pt_before;

-- 幂等、逐目标回填：事实漂移或已执行时影响 0 行。
UPDATE cms_topic t
SET t.product_type = 1,
    t.update_by = 'migration_tandianri_product_type_backfill',
    t.update_time = NOW()
WHERE t.id = 23
  AND t.del_flag = 0
  AND t.status = 1
  AND t.product_type IS NULL
  AND EXISTS (
      SELECT 1
      FROM oms_ticket mode1_ticket
      WHERE mode1_ticket.owner_type = 1
        AND mode1_ticket.owner_id = t.id
        AND mode1_ticket.del_flag = 0
        AND mode1_ticket.mode = 1
  )
  AND EXISTS (
      SELECT 1
      FROM oms_ticket mode2_ticket
      WHERE mode2_ticket.owner_type = 1
        AND mode2_ticket.owner_id = t.id
        AND mode2_ticket.del_flag = 0
        AND mode2_ticket.mode = 2
  );

-- 执行后 fail-closed 断言：目标必须且只能是 ORIENTEERING=1。
CREATE TEMPORARY TABLE assert_tandianri_pt_after (
    observed_rows INT NOT NULL,
    CONSTRAINT ck_tandianri_pt_after_exact CHECK (observed_rows = 1)
);
INSERT INTO assert_tandianri_pt_after(observed_rows)
SELECT COUNT(*) FROM cms_topic
WHERE id = 23 AND del_flag = 0 AND status = 1 AND product_type = 1
  AND update_by = 'migration_tandianri_product_type_backfill';
DROP TEMPORARY TABLE assert_tandianri_pt_after;

-- 全局 post guard：迁移结束后，不允许任何仍在售且有有效主题票的主题继续缺 product_type。
-- 这是可执行断言，不是供人阅读的裸 SELECT；新增/漂移主题只要命中就违反 CHECK 并中止脚本。
CREATE TEMPORARY TABLE assert_tandianri_pt_global_post (
    observed_rows INT NOT NULL,
    CONSTRAINT ck_tandianri_pt_global_post_zero CHECK (observed_rows = 0)
);
INSERT INTO assert_tandianri_pt_global_post(observed_rows)
SELECT COUNT(*)
FROM cms_topic t
WHERE t.del_flag = 0
  AND t.status = 1
  AND t.product_type IS NULL
  AND EXISTS (
      SELECT 1
      FROM oms_ticket active_ticket
      WHERE active_ticket.owner_type = 1
        AND active_ticket.owner_id = t.id
        AND active_ticket.del_flag = 0
  );
DROP TEMPORARY TABLE assert_tandianri_pt_global_post;

-- 重复执行 fail-closed 断言：同一 UPDATE 重放必须命中 0 行。
CREATE TEMPORARY TABLE assert_tandianri_pt_replay (
    observed_rows INT NOT NULL,
    CONSTRAINT ck_tandianri_pt_replay_zero CHECK (observed_rows = 0)
);
INSERT INTO assert_tandianri_pt_replay(observed_rows)
SELECT COUNT(*)
FROM cms_topic t
WHERE t.id = 23
  AND t.del_flag = 0
  AND t.status = 1
  AND t.product_type IS NULL
  AND EXISTS (
      SELECT 1
      FROM oms_ticket mode1_ticket
      WHERE mode1_ticket.owner_type = 1
        AND mode1_ticket.owner_id = t.id
        AND mode1_ticket.del_flag = 0
        AND mode1_ticket.mode = 1
  )
  AND EXISTS (
      SELECT 1
      FROM oms_ticket mode2_ticket
      WHERE mode2_ticket.owner_type = 1
        AND mode2_ticket.owner_id = t.id
        AND mode2_ticket.del_flag = 0
        AND mode2_ticket.mode = 2
  );
DROP TEMPORARY TABLE assert_tandianri_pt_replay;

-- 边界：本脚本只 UPDATE 总控明确批准的 id=23；全局 guard 只做 fail-closed 观测，不扩大写范围。
