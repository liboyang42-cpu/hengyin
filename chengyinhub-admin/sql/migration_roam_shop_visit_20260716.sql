-- migration_roam_shop_visit_20260716.sql
-- 走 chengyin-db-migrate;生产 MySQL 8。全文件只有 CREATE TABLE IF NOT EXISTS + INSERT,
-- 不加列 → 不涉及 ADD COLUMN IF NOT EXISTS(那是 MariaDB 专属,MySQL8 报 1064)。
--
-- 【硬依赖警告 —— 必须整文件先于 jar 上线】
-- ① 没有 roam_shop_visit 表:POST /api/roam/shop/visit 报错、/finish 数不出探店数。
-- ② 没有 ROAM_SHOP_STREAK 这行 def:ApiRoamController.awardShopStreak 里
--    growthBadgeMapper.selectByCode() 返回 null 即 return —— **静默不发章、无报错无日志、零告警**
--    (与 migration_p4_badge_def_engine.sql 开头警告的是同一个坑)。
--
-- 背景:此前「探店 3 家 → 三店连亮」是**纯前端假徽章** —— _popMedal() 只有 setData、零 wx.request,
-- 跨 *.java/*.sql grep「三店/连亮/ROAM_SHOP」零命中,勋章墙永远查不到;而 shareMedal() 还能把这枚
-- 不存在的徽章画成「徽章到手」的图导出分享。本迁移 + 配套 jar 让它真落 player_badge。
--
-- 上线后校验:
--   SELECT COUNT(*) FROM information_schema.tables WHERE table_name='roam_shop_visit';               -- 应 1
--   SELECT badge_code, unlock_rule_json FROM growth_badge WHERE badge_code='ROAM_SHOP_STREAK';       -- 应 1 行

-- ── ① 会话内探店记录 ─────────────────────────────────────────────────────────
-- 为什么要独立一张表(而不是复用 roam_poi_found):
--   roam_poi_found 的 poi_id 指向 roam_poi;而「任意真商家」还包括 /api/map/nearby 那路的
--   cms_registration_merchant(报名商家),它不是 roam_poi、没有 roam_poi.id,塞不进去。
--   故按 (source_type, source_id) 记多态来源。
-- shop_name 由服务端从来源表读出后落库,仅供展示/审计 —— 结算【不】按它计数。
-- 结算按 count(distinct source_type, source_id) 收敛(见 RoamMapper.xml):
--   与前端 utils/roam-poi-group.js 一致 —— 前端每家实体店只报一次代表条目的 source,
--   同名连锁分店各自 source_id 不同、各计一家(按店名会把 3 家瑞幸塌成 1 家 → 弹卡没章)。
CREATE TABLE IF NOT EXISTS `roam_shop_visit` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `member_id`   bigint       NOT NULL,
  `session_id`  bigint       NOT NULL COMMENT 'roam_session.id',
  `source_type` tinyint      NOT NULL COMMENT '1=roam_poi 2=cms_registration_merchant',
  `source_id`   bigint       NOT NULL COMMENT '来源表主键',
  `shop_name`   varchar(128) NOT NULL COMMENT '服务端从来源表读的真名,不采信客户端',
  `visited_at`  datetime     DEFAULT NULL,
  PRIMARY KEY (`id`),
  -- 幂等:同一会话同一来源重复上报只留一条(配合 insert ignore)
  UNIQUE KEY `uk_session_source` (`session_id`, `source_type`, `source_id`),
  KEY `idx_member_time` (`member_id`, `visited_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='漫游会话内探店记录(任意真商家:roam据点/报名商家)';

-- ── ② 三店连亮的勋章定义 ─────────────────────────────────────────────────────
-- ★ badge_code 里绝不写阈值数字。现有 ROAM_TILES_50/200/1000(ApiRoamController)踩过这个坑:
--   改阈值 = 改 code = 已发的章变孤儿(uk 变了、四层墙 def 对不上)。阈值只活在 unlock_rule_json,
--   于是「配置化」= UPDATE 一行 JSON,不发版、不改代码、不重部署,已发的章不动。
-- ★ aggregate/threshold 里只有 threshold 生效:RuleEvaluator 类注释原文「引擎实际只判 threshold;
--   def 里的 aggregate/aggregate_field/window 目前不参与判定 —— 由调用方预聚合出 observedValue 再传入」。
--   真正的聚合在 ApiRoamController.finish 里 count(distinct source_type, source_id)(见 RoamMapper.xml)。
-- status '0' = 启用(沿用现表约定,见 migration_p4_badge_def_engine.sql)。
INSERT INTO growth_badge
  (badge_code, badge_name, name_en, statement, icon_url,
   asset_type, category, trigger_event, unlock_rule_json, rule_version, status, sort_no, create_time)
VALUES
  ('ROAM_SHOP_STREAK', '初探 · 连亮三店', 'THREE_SHOPS', '一口气点亮 3 处,城市开始记得你', '',
   'GROWTH', 'EXPLORE', 'roam_session_finished',
   '{"aggregate":"distinct","threshold":3}', 1, '0', 10, now())
ON DUPLICATE KEY UPDATE
  badge_name       = VALUES(badge_name),
  statement        = VALUES(statement),
  trigger_event    = VALUES(trigger_event),
  unlock_rule_json = VALUES(unlock_rule_json);
