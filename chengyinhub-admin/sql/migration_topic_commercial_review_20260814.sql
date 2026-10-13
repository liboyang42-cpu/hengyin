-- 探店日第1批 1-3/1-4：商业主题立项审 + 开售审（仅准备，禁止在本任务执行）。
-- 前置：migration_p3_platform_foundation.sql、migration_audit_open_task_guard_20260721.sql 已落库。
-- 部署顺序：本迁移先于引用 lifecycle=7 / 两个 phase biz_type 的 jar。

-- fail-closed preflight 1：旧 0-6 ordinal 必须原样保留，出现未知 lifecycle 就用主键冲突中止。
CREATE TEMPORARY TABLE topic_commercial_review_guard (id TINYINT NOT NULL PRIMARY KEY);
INSERT INTO topic_commercial_review_guard (id) VALUES (1);
INSERT INTO topic_commercial_review_guard (id)
SELECT 1 FROM cms_topic WHERE lifecycle NOT IN (0,1,2,3,4,5,6) LIMIT 1;

-- fail-closed preflight 2：重复提审依赖“按 biz_type+biz_id 单未结”唯一闸；名称或列结构漂移都中止。
INSERT INTO topic_commercial_review_guard (id)
SELECT 1
WHERE NOT EXISTS (
  SELECT 1 FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'audit_task'
    AND index_name = 'uk_audit_biz_open'
  GROUP BY index_name
  HAVING MIN(non_unique) = 0
     AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'biz_type,biz_id,open_guard'
);

-- fail-closed preflight 3：open_guard 必须真由 status 0/3 生成，不能只是同名普通列。
INSERT INTO topic_commercial_review_guard (id)
SELECT 1
WHERE NOT EXISTS (
  SELECT 1 FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'audit_task' AND column_name = 'open_guard'
    AND extra LIKE '%STORED GENERATED%'
    AND LOWER(REPLACE(REPLACE(generation_expression, '`', ''), ' ', '')) LIKE '%status%in%(0,3)%'
);
DROP TEMPORARY TABLE topic_commercial_review_guard;

-- 追加 7，不重排 0-6；未知值由 TopicLifecycleState.fromCode 明确拒绝。
ALTER TABLE cms_topic
  MODIFY COLUMN lifecycle TINYINT(1) NOT NULL DEFAULT 0
  COMMENT '生命周期 0草稿 1招募 2定价 3售票 4进行 5结算 6关闭 7立项待审';

-- 两阶段用独立 biz_type；uk_audit_biz_open 因包含 biz_type，天然按阶段隔离未结唯一性。
ALTER TABLE audit_task
  MODIFY COLUMN biz_type VARCHAR(40) NOT NULL
  COMMENT '业务类型；商业主题阶段为 TOPIC_PROJECT_REVIEW/TOPIC_SALE_REVIEW';

-- 生命周期字典（幂等）；后台不得把 7 或未知值按 ordinal 猜成“已售票”。
INSERT INTO sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
SELECT '主题生命周期', 'biz_topic_lifecycle', '0', 'migration', NOW(), '0-6存量ordinal保持不变；7=立项待审'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'biz_topic_lifecycle');

INSERT INTO sys_dict_data
  (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status,
   create_by, create_time, remark)
SELECT v.sort_no, v.label_text, v.value_text, 'biz_topic_lifecycle', '', v.list_class, 'N', '0',
       'migration', NOW(), '显式生命周期值；未知值拒绝'
FROM (
  SELECT 0 sort_no, '草稿' label_text, '0' value_text, 'default' list_class UNION ALL
  SELECT 1, '招募中', '1', 'info' UNION ALL
  SELECT 2, '待定价', '2', 'warning' UNION ALL
  SELECT 3, '售票中', '3', 'success' UNION ALL
  SELECT 4, '进行中', '4', 'primary' UNION ALL
  SELECT 5, '结算中', '5', 'warning' UNION ALL
  SELECT 6, '已关闭', '6', 'default' UNION ALL
  SELECT 7, '立项待审', '7', 'warning'
) v
WHERE NOT EXISTS (
  SELECT 1 FROM sys_dict_data d
  WHERE d.dict_type = 'biz_topic_lifecycle' AND d.dict_value = v.value_text
);

-- 提审权限独立于审核决定；Controller 还会执行 topic 对象级 scope 校验。
SET @audit_menu := (SELECT menu_id FROM sys_menu WHERE perms = 'business:audit:list' LIMIT 1);
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '商业主题提审', @audit_menu, 2, '', '', '', '', 1, 0, 'F', '0', '0',
       'business:audit:submit', '#', 'migration', NOW(), '', NULL, '提交立项审/开售审；仍需对象级scope'
WHERE @audit_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:audit:submit');

-- readback：执行方必须从数据库回读，不得以 SQL exit 0 代替观测。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name = 'lifecycle'
  AND column_type = 'tinyint(1)' AND column_default = '0'
  AND column_comment LIKE '%7立项待审%';

-- runner: assert=scalar-true
SELECT COUNT(*) = 8
FROM sys_dict_data
WHERE dict_type = 'biz_topic_lifecycle'
  AND dict_value IN ('0','1','2','3','4','5','6','7')
  AND status = '0';

-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM (
  SELECT index_name
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'audit_task'
    AND index_name = 'uk_audit_biz_open'
  GROUP BY index_name
  HAVING MIN(non_unique) = 0
     AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'biz_type,biz_id,open_guard'
) review_index;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM sys_menu
WHERE perms = 'business:audit:submit' AND menu_type = 'F' AND status = '0';
