-- 探店日 批5:后台两处入口的菜单与权限注册。
--
-- 【类别】③ 注册数据(要在库上跑)。不是 DDL,但**不跑 = 这两个入口对非超管根本不存在**
--   —— 菜单不出现、接口 403。超管有 *:*:* 恒能看见,所以**验收必须用非超管账号**。
--
-- 【落库时机】必须在 jar + chengyinhub-ui 都上线之后(本份没有 DDL,与 DDL 迁移「先于 jar」相反)。
--   跑本份前扫一眼 ui 部署:gh run list --workflow=deploy-ui.yml
--
-- 【本份注册两件事】
--   A. 核销异常复核:把 migration_commerce_order_fields_and_verification_review_20260805.sql
--      建的那个**裸 F 权限位**(parent_id=0 / path='#' / 无 component ⇒ 没有页面)升级成真页面,
--      并补处置权限 business:verificationReview:handle。
--      ⚠️ 为什么是 UPDATE 原行而不是 INSERT 新行:同 perms 插两行的话,
--         sys_role_menu 里已授出去的是老那一行,新页面对已配好的角色仍然不可见。
--   B. 平台建/改探店日节点:CmsTopicNodeController 的 add/edit/remove 三个权限点此前
--      **全仓 sys_menu 零注册** —— 端点存在、按钮在主题详情页上,但对非超管一律 403。
--
-- 【故意不注册】business:topicnode:list / :query —— 节点随 getTopic 一起返回,
--   本轮 UI 不调那两个端点。多一个没人配的权限点等于那个按钮没人能用。
--
-- 【幂等】A 的 UPDATE 按 perms 定位(重跑写同样的值);其余全部 NOT EXISTS 去重。
--
-- 【可逆】
--   DELETE FROM sys_menu WHERE perms = 'business:verificationReview:handle';
--   DELETE FROM sys_menu WHERE perms IN
--     ('business:topicnode:add','business:topicnode:edit','business:topicnode:remove');
--   -- A 的 UPDATE 不可逆(原状是一个没有页面的裸权限位,回退没有价值)。
--
-- 【风险】低。只动 sys_menu:一条 UPDATE + 四条 INSERT,不动目录结构、不动 sys_role_menu。
--
-- ======================= 第一步:只读预检 =======================
--
-- P1【必看】现状:
--   SELECT menu_id, menu_name, parent_id, menu_type, path, component, perms FROM sys_menu
--    WHERE perms LIKE 'business:verificationReview:%' OR perms LIKE 'business:topicnode:%';
--   · 只有 1 行 business:verificationReview:list(menu_type='F', parent_id=0)→ 正常,全执行。
--   · 0 行 → 20260805 那份没跑过;下面 A 的 INSERT 兜底会补出 C 菜单,无害。
--
-- P2【父级解析·必看】两个父级都必须找得到,否则对应段静默插 0 行:
--   SELECT menu_id, menu_name FROM sys_menu WHERE path = 'audit-center' AND parent_id = 0;
--   SELECT menu_id, menu_name, perms FROM sys_menu
--    WHERE menu_type = 'C' AND component = 'business/topic/index';
--   · 任一 0 行 → **停**。九域导航(migration_web_admin_nine_domains_20260719.sql)或
--     主题菜单(migration_topic_source_menu_20260722.sql)还没落库。先落它们再回来。
--     文件末尾的回读断言会把这种情况判红,不会让你以为跑成功了。


-- ======================= A. 核销异常复核:裸权限位 → 真页面 =======================

SET @cy_audit_center := (
    SELECT menu_id FROM sys_menu
     WHERE path = 'audit-center' AND parent_id = 0 AND menu_type = 'M'
     LIMIT 1);

-- A-1 存量那一行升级成 C 菜单(在原行上改,保住 sys_role_menu 里已有的授权)
UPDATE sys_menu
   SET menu_name = '核销异常复核',
       parent_id = @cy_audit_center,
       order_num = 60,
       path = 'verification-review',
       component = 'business/verificationReview/index',
       is_frame = 1,
       is_cache = 0,
       menu_type = 'C',
       visible = '0',
       status = '0',
       icon = 'audit',
       remark = '探店日批5:核销率/频次异常人工复核队列。只做人审,不改核销事实、不退款、不动资金'
 WHERE perms = 'business:verificationReview:list'
   AND @cy_audit_center IS NOT NULL;

-- A-2 兜底:20260805 那份从没跑过时,这里补出 C 菜单
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '核销异常复核', @cy_audit_center, 60, 'verification-review',
       'business/verificationReview/index', 1, 0, 'C', '0', '0',
       'business:verificationReview:list', 'audit', 'admin', NOW(),
       '探店日批5:核销率/频次异常人工复核队列。只做人审,不改核销事实、不退款、不动资金'
FROM dual
WHERE @cy_audit_center IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:verificationReview:list');

SET @cy_review_menu := (
    SELECT menu_id FROM sys_menu WHERE perms = 'business:verificationReview:list' LIMIT 1);

-- A-3 处置按钮。⚠️ 权限串必须与 VerificationReviewController#handle 的 @PreAuthorize 逐字一致,
--     对不上 = 点了 403。挂在上面那个 C 菜单本身(不是父目录)—— 挂错层权限树里勾不到它。
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '核销异常处置', @cy_review_menu, 1, '', '', 1, 0, 'F', '0', '0',
       'business:verificationReview:handle', '#', 'admin', NOW(),
       '人工处置:1 已确认正常 / 2 确认异常。只翻 verification_review.status,不触发任何资金动作'
FROM dual
WHERE @cy_review_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:verificationReview:handle');


-- ======================= B. 平台建/改探店日节点 =======================

-- 挂在「官方主题」那个 C 菜单下:节点的增删改入口就在它的详情页(business/topic/detail)上。
SET @cy_topic_menu := (
    SELECT menu_id FROM sys_menu
     WHERE menu_type = 'C' AND component = 'business/topic/index'
     ORDER BY menu_id
     LIMIT 1);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '主题节点新增', @cy_topic_menu, 21, '', '', 1, 0, 'F', '0', '0',
       'business:topicnode:add', '#', 'admin', NOW(),
       '探店日批5:平台从零建店位节点。绑商家的节点由服务端强制按已过审留痕,否则玩家侧静默消失'
FROM dual
WHERE @cy_topic_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:topicnode:add');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '主题节点编辑', @cy_topic_menu, 22, '', '', 1, 0, 'F', '0', '0',
       'business:topicnode:edit', '#', 'admin', NOW(),
       '探店日批5:改店位节点内容。打卡码在页面上只读 —— 换码会让店里已印好的海报全部作废'
FROM dual
WHERE @cy_topic_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:topicnode:edit');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '主题节点删除', @cy_topic_menu, 23, '', '', 1, 0, 'F', '0', '0',
       'business:topicnode:remove', '#', 'admin', NOW(),
       '探店日批5:删店位节点。已有玩家进店记录的节点删掉会让那些记录指向空,删前先看漏斗'
FROM dual
WHERE @cy_topic_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:topicnode:remove');


-- ======================= 回读断言(必跑,别只看 "Query OK") =======================

-- A1:核销异常复核必须是「audit-center 下的 C 菜单」。0 = @cy_audit_center 没解析到 ⇒ A 段白跑。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
  FROM sys_menu
 WHERE perms = 'business:verificationReview:list'
   AND menu_type = 'C'
   AND component = 'business/verificationReview/index'
   AND status = '0'
   AND parent_id = (SELECT menu_id FROM (
        SELECT menu_id FROM sys_menu
         WHERE path = 'audit-center' AND parent_id = 0 AND menu_type = 'M' LIMIT 1
       ) audit_center_parent);

-- A2:处置按钮的 parent 必须是上面那个 C 菜单本身,不是父目录 —— 挂错层等于非超管永远勾不到它。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
  FROM sys_menu handle_button
  JOIN sys_menu review_menu ON handle_button.parent_id = review_menu.menu_id
 WHERE handle_button.perms = 'business:verificationReview:handle'
   AND handle_button.menu_type = 'F'
   AND review_menu.perms = 'business:verificationReview:list';

-- A3:节点三个写权限必须都挂在「官方主题」那个 C 菜单下。<3 = @cy_topic_menu 没解析到。
-- runner: assert=scalar-true
SELECT COUNT(*) = 3
  FROM sys_menu node_button
  JOIN sys_menu topic_menu ON node_button.parent_id = topic_menu.menu_id
 WHERE node_button.perms IN
       ('business:topicnode:add', 'business:topicnode:edit', 'business:topicnode:remove')
   AND node_button.menu_type = 'F'
   AND topic_menu.component = 'business/topic/index';

-- A4:同 perms 不许有两行 —— 有两行说明 A-1 的 UPDATE 没命中而 A-2 又插了一条,
--     此后 sys_role_menu 授的是哪一行就成了运气问题。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
  FROM (
    SELECT perms
      FROM sys_menu
     WHERE perms IN ('business:verificationReview:list', 'business:verificationReview:handle',
                     'business:topicnode:add', 'business:topicnode:edit', 'business:topicnode:remove')
     GROUP BY perms
    HAVING COUNT(*) > 1
  ) duplicated_perms;

-- A5【验收提醒,不是 SQL】注册完必须**用非超管账号**在角色里勾上这几个权限、再登进去点一遍。
--    超管的 *:*:* 恒能看见一切 ⇒ 用超管验收等于没验收。
--    处置按钮要额外验一条负控:不勾 handle 只勾 list 时,「已确认正常」必须 403 而不是静默成功。
