-- R10 admin-consoles（2026-11-15）：运营后台五个控制台的菜单/权限 + 奖励承诺审核备注表。
--
-- 【回滚】
--   DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms IN ('business:rewardDrop:list', 'business:rewardDrop:add', 'business:rewardDrop:edit', 'business:rewardPledge:list', 'business:rewardPledge:review', 'business:rewardPledge:policy', 'business:channelModeration:list', 'business:channelModeration:message', 'business:channelModeration:member', 'business:journeyRules:list', 'business:journeyRules:edit', 'business:roamOps:list')) x);
--   DELETE FROM sys_menu WHERE perms IN ('business:rewardDrop:list', 'business:rewardDrop:add', 'business:rewardDrop:edit', 'business:rewardPledge:list', 'business:rewardPledge:review', 'business:rewardPledge:policy', 'business:channelModeration:list', 'business:channelModeration:message', 'business:channelModeration:member', 'business:journeyRules:list', 'business:journeyRules:edit', 'business:roamOps:list');
--   DROP TABLE IF EXISTS r10_reward_pledge_review_note;   -- 只在确认不需要审核备注历史时执行
--
-- 【类别】① 新表（IF NOT EXISTS）+ ③ 注册数据（sys_menu，按 perms 去重）。不改任何既有表与既有菜单。
-- 【幂等】可重复执行：建表 IF NOT EXISTS；每条菜单 NOT EXISTS(perms) 守卫；父目录不存在或不唯一时整条 0 行（fail-closed）。
-- 【授权】只注册菜单与按钮，不给任何角色授权；超管 *:*:* 恒可见，其余角色由管理员在「角色管理」勾选。
-- 【生产】执行前走 chengyin-db-migrate 只读预检；本文件不在本任务执行。

CREATE TABLE IF NOT EXISTS r10_reward_pledge_review_note (
  id BIGINT NOT NULL AUTO_INCREMENT,
  version_id VARCHAR(64) NOT NULL,
  pledge_id VARCHAR(64) NOT NULL,
  decision VARCHAR(16) NOT NULL COMMENT 'APPROVE/REJECT/NOTE',
  note VARCHAR(500) NULL,
  sys_user_id BIGINT NOT NULL COMMENT '后台操作账号 sys_user.user_id',
  create_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_r10_pledge_note_pledge (pledge_id, id),
  KEY idx_r10_pledge_note_version (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='R10 奖励承诺平台审核备注（只追加）';


-- ---------------------------------------------------------------- 掉落规则（/data-config/reward-drop）
SET @r10_dir_unique := (SELECT COUNT(*) FROM sys_menu WHERE path = 'data-config' AND parent_id = 0 AND menu_type = 'M');
SET @r10_dir := (SELECT menu_id FROM sys_menu WHERE path = 'data-config' AND parent_id = 0 AND menu_type = 'M' AND @r10_dir_unique = 1 LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '掉落规则', @r10_dir, 40, 'reward-drop', 'business/rewarddrop/index', 1, 0, 'C', '0', '0', 'business:rewardDrop:list', 'star', 'admin', NOW(), 'R10 admin-consoles'
FROM dual
WHERE @r10_dir IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:rewardDrop:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE component = 'business/rewarddrop/index');  -- sys-random 已建同一页面时不再建第二行
SET @r10_page := (SELECT menu_id FROM sys_menu WHERE (perms = 'business:rewardDrop:list' OR component = 'business/rewarddrop/index') AND menu_type = 'C' ORDER BY menu_id LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '新建掉落规则', @r10_page, 1, '', '', 1, 0, 'F', '0', '0', 'business:rewardDrop:add', '#', 'admin', NOW(), 'R10 admin-consoles'
FROM dual
WHERE @r10_page IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:rewardDrop:add');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '编辑与启停掉落规则', @r10_page, 2, '', '', 1, 0, 'F', '0', '0', 'business:rewardDrop:edit', '#', 'admin', NOW(), 'R10 admin-consoles'
FROM dual
WHERE @r10_page IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:rewardDrop:edit');

-- ---------------------------------------------------------------- 奖励承诺审核（/audit-center/reward-pledge）
SET @r10_dir_unique := (SELECT COUNT(*) FROM sys_menu WHERE path = 'audit-center' AND parent_id = 0 AND menu_type = 'M');
SET @r10_dir := (SELECT menu_id FROM sys_menu WHERE path = 'audit-center' AND parent_id = 0 AND menu_type = 'M' AND @r10_dir_unique = 1 LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '奖励承诺审核', @r10_dir, 40, 'reward-pledge', 'business/rewardpledge/index', 1, 0, 'C', '0', '0', 'business:rewardPledge:list', 'checkbox', 'admin', NOW(), 'R10 admin-consoles'
FROM dual
WHERE @r10_dir IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:rewardPledge:list');
SET @r10_page := (SELECT menu_id FROM sys_menu WHERE perms = 'business:rewardPledge:list' AND menu_type = 'C' ORDER BY menu_id LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '审核奖励承诺', @r10_page, 1, '', '', 1, 0, 'F', '0', '0', 'business:rewardPledge:review', '#', 'admin', NOW(), 'R10 admin-consoles'
FROM dual
WHERE @r10_page IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:rewardPledge:review');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '编辑奖励政策', @r10_page, 2, '', '', 1, 0, 'F', '0', '0', 'business:rewardPledge:policy', '#', 'admin', NOW(), 'R10 admin-consoles'
FROM dual
WHERE @r10_page IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:rewardPledge:policy');

-- ---------------------------------------------------------------- 频道治理（/audit-center/channel-moderation）
SET @r10_dir_unique := (SELECT COUNT(*) FROM sys_menu WHERE path = 'audit-center' AND parent_id = 0 AND menu_type = 'M');
SET @r10_dir := (SELECT menu_id FROM sys_menu WHERE path = 'audit-center' AND parent_id = 0 AND menu_type = 'M' AND @r10_dir_unique = 1 LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '频道治理', @r10_dir, 41, 'channel-moderation', 'business/channelmoderation/index', 1, 0, 'C', '0', '0', 'business:channelModeration:list', 'message', 'admin', NOW(), 'R10 admin-consoles'
FROM dual
WHERE @r10_dir IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:channelModeration:list');
SET @r10_page := (SELECT menu_id FROM sys_menu WHERE perms = 'business:channelModeration:list' AND menu_type = 'C' ORDER BY menu_id LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '屏蔽与恢复消息', @r10_page, 1, '', '', 1, 0, 'F', '0', '0', 'business:channelModeration:message', '#', 'admin', NOW(), 'R10 admin-consoles'
FROM dual
WHERE @r10_page IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:channelModeration:message');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '禁言与移出成员', @r10_page, 2, '', '', 1, 0, 'F', '0', '0', 'business:channelModeration:member', '#', 'admin', NOW(), 'R10 admin-consoles'
FROM dual
WHERE @r10_page IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:channelModeration:member');

-- ---------------------------------------------------------------- 旅程规则（/supply/journey-rules）
SET @r10_dir_unique := (SELECT COUNT(*) FROM sys_menu WHERE path = 'supply' AND parent_id = 0 AND menu_type = 'M');
SET @r10_dir := (SELECT menu_id FROM sys_menu WHERE path = 'supply' AND parent_id = 0 AND menu_type = 'M' AND @r10_dir_unique = 1 LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '旅程规则', @r10_dir, 40, 'journey-rules', 'business/journeyrules/index', 1, 0, 'C', '0', '0', 'business:journeyRules:list', 'edit', 'admin', NOW(), 'R10 admin-consoles'
FROM dual
WHERE @r10_dir IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:journeyRules:list');
SET @r10_page := (SELECT menu_id FROM sys_menu WHERE perms = 'business:journeyRules:list' AND menu_type = 'C' ORDER BY menu_id LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '编辑旅程规则与状态键', @r10_page, 1, '', '', 1, 0, 'F', '0', '0', 'business:journeyRules:edit', '#', 'admin', NOW(), 'R10 admin-consoles'
FROM dual
WHERE @r10_page IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:journeyRules:edit');

-- ---------------------------------------------------------------- 漫游与游玩运营（/clubs-roam/roam-ops）
SET @r10_dir_unique := (SELECT COUNT(*) FROM sys_menu WHERE path = 'clubs-roam' AND parent_id = 0 AND menu_type = 'M');
SET @r10_dir := (SELECT menu_id FROM sys_menu WHERE path = 'clubs-roam' AND parent_id = 0 AND menu_type = 'M' AND @r10_dir_unique = 1 LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '漫游与游玩运营', @r10_dir, 40, 'roam-ops', 'business/roamops/index', 1, 0, 'C', '0', '0', 'business:roamOps:list', 'chart', 'admin', NOW(), 'R10 admin-consoles'
FROM dual
WHERE @r10_dir IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:roamOps:list');
SET @r10_page := (SELECT menu_id FROM sys_menu WHERE perms = 'business:roamOps:list' AND menu_type = 'C' ORDER BY menu_id LIMIT 1);

-- 回读：应为 12（5 个页面 + 7 个按钮）；少于该数说明有父目录缺失或不唯一。
-- runner: assert=scalar-true
SELECT COUNT(*) = 12 FROM sys_menu WHERE perms IN ('business:rewardDrop:list', 'business:rewardDrop:add', 'business:rewardDrop:edit', 'business:rewardPledge:list', 'business:rewardPledge:review', 'business:rewardPledge:policy', 'business:channelModeration:list', 'business:channelModeration:message', 'business:channelModeration:member', 'business:journeyRules:list', 'business:journeyRules:edit', 'business:roamOps:list') AND status = '0';
