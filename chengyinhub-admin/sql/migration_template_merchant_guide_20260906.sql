-- ============================================================================
-- P0-B:把「给商家/店员看的话」从 rule_instructions 里分出来,单独存 merchant_guide。
--
-- 为什么必须分:rule_instructions 在玩家到达节点后原样下发并渲染成规则文案
--   (ApiPlayProgressController#nodes 出参;pages/play/index.js normNode 读 n.ruleInstructions)。
--   而现有 18 个具名玩法包的种子把「商家改造指南:①把题面里的细节换成…」和
--   「店员一页纸剧本:①客人对你说「今晚的月亮不错」②你回答「可惜看不见星星」」
--   全写在这一列里 —— 玩家现在直接就能看到改造指南;
--   「神秘店员的暗号」那一包更严重:店员剧本连回令一起发给玩家,等于题目自带答案。
--
-- 分列之后:
--   rule_instructions = 只给玩家看的规则说明(继续下发)
--   merchant_guide    = 只给商家/店员看的(玩家侧任何端点都不下发,见 TemplateSecrets)
--
-- ⚠️ 本文件只准备,不执行。生产库写操作在用户点头后由 chengyin-db-migrate 执行。
-- ⚠️ 不声明 requires=transaction:本文件含 ALTER/CREATE,MySQL 的 DDL 是隐式提交、
--   不可回滚,runner 的窄事务模式也明确拒绝普通 DDL。按 autocommit 逐句执行。
--   文件里的 UPDATE 是幂等的(搬完就不再匹配 WHERE),重跑安全。
-- ============================================================================

-- ======================= 第一步:只读预检 =======================
-- 期望两条都返回 0 行。返回非 0 说明列已存在,本迁移已跑过,不要重复执行。
SELECT table_name, column_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('cms_member_template', 'cms_template_library')
  AND column_name = 'merchant_guide';

-- 存量里有多少行会被这次迁移搬动(先看清楚再动)。
SELECT 'cms_member_template' AS src, COUNT(*) AS rows_to_move
FROM cms_member_template
WHERE del_flag = 0
  AND (rule_instructions LIKE '商家改造指南%' OR rule_instructions LIKE '店员一页纸剧本%')
UNION ALL
SELECT 'cms_template_library', COUNT(*)
FROM cms_template_library
WHERE del_flag = 0
  AND (rule_instructions LIKE '商家改造指南%' OR rule_instructions LIKE '店员一页纸剧本%');


-- ======================= 第二步:加列 =======================
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS,靠上面的预检把关,不要盲跑。
ALTER TABLE cms_member_template
    ADD COLUMN merchant_guide VARCHAR(1000) NULL
    COMMENT '给商家/店员看的执行说明;玩家侧一律不下发' AFTER rule_instructions;

ALTER TABLE cms_template_library
    ADD COLUMN merchant_guide VARCHAR(1000) NULL
    COMMENT '给商家/店员看的执行说明;玩家侧一律不下发' AFTER rule_instructions;


-- ======================= 第三步:存量搬家 =======================
-- 判据只认种子写死的两个前缀。⚠️ 前缀不是保护,只是「好找」——
-- 商家自己写的、没有前缀的指南搬不走,那部分靠上线后编辑器引导商家自己挪。
-- 搬走后 rule_instructions 置空而不是保留副本:留着就等于没修。
UPDATE cms_member_template
   SET merchant_guide = rule_instructions,
       rule_instructions = NULL,
       update_time = NOW()
 WHERE del_flag = 0
   AND (rule_instructions LIKE '商家改造指南%' OR rule_instructions LIKE '店员一页纸剧本%');

UPDATE cms_template_library
   SET merchant_guide = rule_instructions,
       rule_instructions = NULL,
       update_time = NOW()
 WHERE del_flag = 0
   AND (rule_instructions LIKE '商家改造指南%' OR rule_instructions LIKE '店员一页纸剧本%');


-- ======================= 第四步:写后断言 =======================
-- 断言一:两张表都不该再有以那两个前缀开头的 rule_instructions。
-- ⚠️ 断言写成「条件不满足就喂 JSON_EXTRACT 一段非 JSON」,让 MySQL 当场报错 ——
--    runner 不回显结果集,SELECT COUNT(*) 当断言等于没断言。
SELECT JSON_EXTRACT(IF(COUNT(*) = 0, '[]', 'ASSERT_FAILED__leftover_rule_instructions'), '$[0]') AS leftover_rule_instructions
FROM (
    SELECT id FROM cms_member_template
     WHERE del_flag = 0
       AND (rule_instructions LIKE '商家改造指南%' OR rule_instructions LIKE '店员一页纸剧本%')
    UNION ALL
    SELECT id FROM cms_template_library
     WHERE del_flag = 0
       AND (rule_instructions LIKE '商家改造指南%' OR rule_instructions LIKE '店员一页纸剧本%')
) t;

-- 断言二:两张表的新列都建上了(期望 2 列)。
SELECT JSON_EXTRACT(IF(COUNT(*) = 2, '[]', 'ASSERT_FAILED__merchant_guide_columns_added'), '$[0]') AS merchant_guide_columns_added
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('cms_member_template', 'cms_template_library')
  AND column_name = 'merchant_guide';
