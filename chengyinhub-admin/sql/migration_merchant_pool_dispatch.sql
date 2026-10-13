-- 商家池开关 + 报名调配审核（2026-06-19）
-- 1) 商家报名记录新增"调配状态"：0=待调配 1=中标 2=落选
--    （与原有 status[后台审核态] 解耦：status 仍表达活动审核/进行态，audit_status 专表达择优结果）
ALTER TABLE `cms_registration_merchant`
    ADD COLUMN `audit_status` TINYINT DEFAULT 0 COMMENT '调配状态：0待调配 1中标 2落选' AFTER `status`;

-- 2) 存量数据回填：已是 status=1(进行中/通过) 的报名视为已中标，其余置待调配
UPDATE `cms_registration_merchant` SET `audit_status` = 1 WHERE `status` = 1 AND `del_flag` = 0;
UPDATE `cms_registration_merchant` SET `audit_status` = 0 WHERE (`status` IS NULL OR `status` <> 1) AND `del_flag` = 0;

-- 注：cms_topic.merchant_status 已存在（0=自办不进池 / 1=进商家池），本次仅改创建默认值（代码层），无需改表。

-- 3) 后台菜单：新增「报名调配审核」页（component=business/registrationmerchant/dispatch）
--    挂在现有"商家主题报名"菜单的同一父级下；权限沿用 business:registrationmerchant:list / :edit。
--    若现有菜单不存在(perms 不同)，请在后台「菜单管理」手动添加，component 填 business/registrationmerchant/dispatch。
SELECT parent_id INTO @cy_pid FROM sys_menu WHERE perms = 'business:registrationmerchant:list' AND menu_type = 'C' LIMIT 1;
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  ('报名调配审核', IFNULL(@cy_pid, 0), 50, 'registrationmerchant-dispatch', 'business/registrationmerchant/dispatch', '', 1, 0, 'C', '0', '0', 'business:registrationmerchant:list', 'tree-table', 'admin', NOW(), '主题→模板→商家报名 择优调配');

