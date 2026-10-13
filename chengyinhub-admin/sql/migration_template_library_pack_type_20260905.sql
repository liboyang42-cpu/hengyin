-- ================= migration_template_library_pack_type_20260905.sql =================
SELECT 'migration_template_library_pack_type_20260905.sql' AS running;
-- 模板市场分类:pack_type(0 单节点玩法 / 1 剧情包 / 2 店铺 IP 包)。
--
-- 【背景 —— 为什么要这一列】
--   模板库现在是一锅端:12 套具名玩法包、单节点玩法、剧情模板全混在一个列表里,
--   只能按关键字和分类 ID 找。商家想找「能直接套到我店里的 IP 包」找不到 ——
--   他不知道哪些是整套的、哪些只是一个答题节点。
--   pack_type 就是那条筛选轴。
--
-- 【类别】③ 迁移(DDL)。**合并 jar 前跑**:列不存在时按 pack_type 过滤会 Unknown column。
--
-- 【幂等】幂等。information_schema 判存在后再 ADD。
--   ⚠️ MySQL 不支持 ADD COLUMN IF NOT EXISTS,直接 ADD 重跑会 1060 中断整份迁移。
--
-- 【可逆】ALTER TABLE cms_template_library DROP COLUMN pack_type;
--   回滚后所有模板退回「不分类」,列表少一条筛选轴,功能不坏。
--
-- 【风险】—— **低**。NOT NULL DEFAULT 0:既有行全部落「单节点玩法」,
--   这与它们现在的实际形态一致(库里绝大多数是单节点)。
--   ⚠️ 具名玩法包那 12 套其实是「剧情包」,但**本迁移刻意不去猜哪些是**——
--   按 id 硬编码分类会在种子重跑后指向错的行。分类由运营在后台逐个标,
--   或在下一批用 title 前缀这类可复核的判据单独做。默认值只保证不误标。

-- 只读预检(期望 0 行 = 全新)
SELECT column_name, column_type, is_nullable, column_default
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'cms_template_library'
   AND column_name = 'pack_type';

SET @sql := (SELECT IF(
  EXISTS(SELECT 1 FROM information_schema.columns
          WHERE table_schema = DATABASE() AND table_name = 'cms_template_library' AND column_name = 'pack_type'),
  'SELECT ''pack_type 已存在,跳过'' AS skipped',
  'ALTER TABLE cms_template_library ADD COLUMN pack_type TINYINT NOT NULL DEFAULT 0 COMMENT ''模板形态:0单节点玩法 1剧情包 2店铺IP包'''
));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 回读断言:pack_type 存在且默认 0。
SELECT column_name, column_type, is_nullable, column_default, column_comment
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'cms_template_library'
   AND column_name = 'pack_type';
