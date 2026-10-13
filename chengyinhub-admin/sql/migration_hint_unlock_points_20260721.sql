-- 积分解锁游玩提示(2026-07-21)
-- 规则行即开关:落库(分值>0)= 开闸收费;不落库 / 后台「积分规则」把数值改 0 = 保持免费明文(功能关)。
-- ⚠️ 落库时机:须在带解锁弹窗的新版小程序【发布之后】再执行(与常规「迁移先于 jar」相反,这行不是 DDL 是行为开关)。
--    开闸后老版本客户端提示按钮由 !!hint1 驱动会自然隐藏(优雅降级不炸),但为体验平滑仍按「先发版后开闸」执行。
-- 注:cms_points_setting.id 无 AUTO_INCREMENT(现有 1-7 + 11/12/13),必须显式给 id;新用 14。
INSERT INTO `cms_points_setting` (`id`, `title`, `value`, `description`, `type`, `event_type`, `status`, `sort_order`, `del_flag`, `create_by`, `create_time`)
SELECT * FROM (SELECT 14 AS k, '解锁提示' AS a, 10 AS b, '游玩中积分解锁本节点提示(改0=免费关闸)' AS c, 1 AS d, 14 AS e, 1 AS f, 14 AS g, 0 AS h, 'admin' AS i, NOW() AS j) t
WHERE NOT EXISTS (SELECT 1 FROM `cms_points_setting` cps WHERE cps.`type`=1 AND cps.`event_type`=14 AND cps.`del_flag`=0);
