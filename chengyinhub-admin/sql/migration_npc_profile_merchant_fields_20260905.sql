-- ================= migration_npc_profile_merchant_fields_20260905.sql =================
SELECT 'migration_npc_profile_merchant_fields_20260905.sql' AS running;
-- 门店 NPC 两个新列:knowledge(店铺知识)/audit_reason(审核驳回原因)。
--
-- 【背景】
--   PR #820 把 scope_type=2「门店分身」接通到「后台配头像 → 店铺主页/漫游地图静态出场」,
--   persona 刻意落空串(静态出场不读人设)。本批把它接进**对话**:
--   商家在 App/小程序自己填人设与店铺知识 → 待审 → 过审后玩家可以问「有什么推荐」。
--   knowledge 就是那句「推荐」的事实来源 —— 没有它,模型只能编。
--
-- 【类别】③ 迁移(DDL,要在库上跑)。**合并 jar 前先跑**:
--   ApiMerchantController 的 insert/update 会写 knowledge 列,列不存在 = Unknown column 报错。
--
-- 【幂等】幂等。两列各自 NOT EXISTS 判断后再 ADD;重复执行 0 行受影响。
--   ⚠️ MySQL 不支持 ADD COLUMN IF NOT EXISTS(8.0 也不支持),故用 information_schema +
--      动态 SQL。直接写 ADD COLUMN 重跑会 1060 Duplicate column,让整份迁移中途断在这。
--
-- 【可逆】
--   ALTER TABLE npc_profile DROP COLUMN knowledge, DROP COLUMN audit_reason;
--   ⚠️ 回滚会丢商家已填的店铺知识,回滚前先导出:
--      SELECT id, scope_id, knowledge FROM npc_profile WHERE scope_type = 2 AND knowledge IS NOT NULL;
--
-- 【风险】—— **低**。两列都可空、都有默认 NULL,既有行不受影响;
--   读路径 SELECT * 会多带两列,resultMap 未映射的列 MyBatis 直接忽略,老 jar 也不会炸
--   ⇒ 本迁移**可以先于 jar 跑**,这正是要求的顺序。

-- 只读预检:先看现在有没有这两列(期望 0 行 = 全新)
SELECT column_name, column_type, is_nullable
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'npc_profile'
   AND column_name IN ('knowledge', 'audit_reason');

-- ① knowledge:店铺知识。喂给 NpcChatService 的事实包,商家自己填。
SET @sql := (SELECT IF(
  EXISTS(SELECT 1 FROM information_schema.columns
          WHERE table_schema = DATABASE() AND table_name = 'npc_profile' AND column_name = 'knowledge'),
  'SELECT ''knowledge 已存在,跳过'' AS skipped',
  'ALTER TABLE npc_profile ADD COLUMN knowledge TEXT NULL COMMENT ''店铺知识:菜单/招牌/营业信息/老板故事,喂给对话事实包'''
));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ② audit_reason:驳回原因。
--    ★ 没有它,审核员只能「拒绝」而说不出为什么,商家会原样再交一遍 —— 一个死循环。
--      给了原因,前端 statusText 才能从「未通过审核」变成「未通过:头像不是本人」。
SET @sql := (SELECT IF(
  EXISTS(SELECT 1 FROM information_schema.columns
          WHERE table_schema = DATABASE() AND table_name = 'npc_profile' AND column_name = 'audit_reason'),
  'SELECT ''audit_reason 已存在,跳过'' AS skipped',
  'ALTER TABLE npc_profile ADD COLUMN audit_reason VARCHAR(255) NULL COMMENT ''驳回原因;仅 audit_status=2 时有值'''
));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 回读断言:期望两行,全部 IS_NULLABLE=YES
SELECT column_name, column_type, is_nullable
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'npc_profile'
   AND column_name IN ('knowledge', 'audit_reason')
 ORDER BY column_name;
