-- ============================================================
-- 拍照任务人工审核开关下线(2026-09-16 晚用户拍板第16条:只用机审)
-- 前置:依赖 migration_level_editor_story_photo_review.sql(photo_review 列已存在)。
-- 背景:photo_review=1 的节点当时提交只落待审态,而审核入口从未在小程序/后台落地,
--       玩家会卡在「审核中」。产品拍板关掉人工审核,一律机审(提交即完成)。
-- 影响:把存量开了人工审核的节点配置置回机审。新代码已不再读该开关(恒按机审处理),
--       本脚本只做数据清理,不做表结构变更,不涉及资金。
-- 执行前:只读预检 SELECT count(*) FROM cms_member_template WHERE photo_review <> 0;
-- 幂等:置 0 后再跑影响 0 行。
-- ============================================================

-- 1) 我的模板(节点实际绑定来源):全部置回机审
UPDATE cms_member_template
   SET photo_review = 0
 WHERE photo_review <> 0;

-- 2) 公共模板库:镜像同步,避免以后复制出来的模板再带上人工审核
UPDATE cms_template_library
   SET photo_review = 0
 WHERE photo_review <> 0;

-- ============================================================
-- 回滚:人工审核开关已下线,无回滚语义(如需恢复历史值,提前用
--   SELECT id, photo_review FROM cms_member_template WHERE photo_review <> 0; 留档)。
-- ============================================================
