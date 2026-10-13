-- 门店 AI 形象(npc_profile scope_type=2「门店分身」)后台菜单 + 权限(sys_menu 注册)
--
-- 【背景 —— 为什么这份不跑就等于功能不存在】
--   npc_profile 表里 scope_type=2「门店分身」从 migration_npc_profile.sql 起就存在,
--   但**一直是零代码路径**:读侧 resolveForActivity 写死 scope 1,写侧全仓无 CRUD,
--   全表 scope 2 行数为 0。本批补齐了 Controller(/business/merchantNpc/*)+ Vue 页面
--   (business/merchantnpc/index)+ 读路径(NpcProfileService.resolveForMerchant →
--   /api/merchant/public-home 的 data.npc)。
--   但 RuoYi 的路由是 **sys_menu.component 动态下发**的:这份 SQL 不跑,
--   jar 里 Controller 在、chengyinhub-ui 里页面在,**后台侧边栏就是没有这个菜单**,
--   @PreAuthorize('business:merchantNpc:*') 也会把直连的请求 403 掉。
--   ⚠️ 零告警型:没有任何日志会说「你菜单没注册」。玩家侧表现是「所有店铺都没有形象」,
--      跟「运营还没配」长得一模一样。
--
-- 【类别】③ 迁移/注册数据(要在库上跑)。**不是 DDL,不改表结构,但不跑=功能不存在。**
--   本批**没有 schema 变更** —— npc_profile 现有列(name/avatar/persona/greeting/sort/
--   audit_status/enabled)已够用,故只有这一份 SQL。
--   顺序:推荐 **jar + chengyinhub-ui 上线后再跑**,避免运营点到 404。
--   ⚠️ chengyinhub-ui 需另行构建部署(ci/deploy-ui.sh),不在后端 jar 的部署链路里。
--
-- 【幂等】幂等。C 菜单按 component 去重,F 按钮按 perms 去重(照抄 migration_ai_log_menu_20260716.sql)。
--   重复执行安全:NOT EXISTS 命中就是 0 行受影响。
--
-- 【可逆】
--   DELETE FROM sys_menu WHERE perms IN (
--     'business:merchantNpc:list','business:merchantNpc:query',
--     'business:merchantNpc:add','business:merchantNpc:edit','business:merchantNpc:remove');
--   (删 C 菜单会连带让 F 按钮悬空,故五条一起删)
--
-- 【风险】—— **低**。只往 sys_menu 插 5 行,menu_id 自增不撞;不碰 npc_profile 里的任何数据行。
--   失手点只影响菜单位置、不影响可用,由末尾 A2 的 verdict 列暴露(按 parent_name 判,不按「≠0」判)。

-- ① 业务管理目录 menu_id 动态解析(勿硬编码 parent_id)
--    ⚠️ 不要写 `ORDER BY parent_id LIMIT 1`:生产实测「系统管理」(menu_id=1)与「业务管理」
--       (menu_id=4) 的 parent_id 都是 0,排序打平后选谁由执行计划决定(2026-07-16 实测选中了
--       「系统管理」)。FIELD() 把优先级写死,结果确定。
SET @parent := (SELECT menu_id FROM sys_menu
                 WHERE menu_name IN ('业务管理','内容管理','系统管理')
                 ORDER BY FIELD(menu_name, '业务管理', '内容管理', '系统管理'), menu_id
                 LIMIT 1);

-- ② C 菜单(按 component 幂等)。★ C 菜单行自带 :list perms —— 不要再插一条 list 的 F 按钮行,会 perms 重复。
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '门店AI形象', IFNULL(@parent, 0), 91, 'merchantnpc', 'business/merchantnpc/index', 1, 0, 'C', '0', '0', 'business:merchantNpc:list', 'peoples', 'admin', NOW(), '门店分身(npc_profile scope_type=2):像素形象在店铺公开主页静态出场,不接对话'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component = 'business/merchantnpc/index');

-- ③ 若此前挂错父级且现在能解析到业务管理目录,则归位
UPDATE sys_menu SET parent_id = @parent
WHERE component = 'business/merchantnpc/index' AND @parent IS NOT NULL AND parent_id <> @parent;

-- ④ 该 C 菜单 menu_id(用于挂按钮权限)
SET @npcMenu := (SELECT menu_id FROM sys_menu WHERE component = 'business/merchantnpc/index' LIMIT 1);

-- ⑤ 按钮权限(F,按 perms 幂等):list 已在 C 菜单;补 query / add / edit / remove。
--    ⚠️ 这里的字符串必须与 CmsMerchantNpcController 的 @PreAuthorize 字符串**逐字一致**
--    (注意 merchantNpc 的驼峰 N),对不上 = 点按钮被 403。
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '门店AI形象查询', @npcMenu, 1, '', '', 1, 0, 'F', '0', '0', 'business:merchantNpc:query', '#', 'admin', NOW()
FROM dual WHERE @npcMenu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantNpc:query');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '门店AI形象新增', @npcMenu, 2, '', '', 1, 0, 'F', '0', '0', 'business:merchantNpc:add', '#', 'admin', NOW()
FROM dual WHERE @npcMenu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantNpc:add');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '门店AI形象编辑', @npcMenu, 3, '', '', 1, 0, 'F', '0', '0', 'business:merchantNpc:edit', '#', 'admin', NOW()
FROM dual WHERE @npcMenu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantNpc:edit');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '门店AI形象删除', @npcMenu, 4, '', '', 1, 0, 'F', '0', '0', 'business:merchantNpc:remove', '#', 'admin', NOW()
FROM dual WHERE @npcMenu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantNpc:remove');

-- ============================================================================
-- 回读断言(必跑,别只看 "Query OK")
-- sys_menu 全仓没有对账脚本(audit_sys_job.py 只查 sys_job)。
-- INSERT 的 "0 rows affected" 既可能是「已存在,幂等跳过」也可能是「@parent 是 NULL 被 WHERE 挡了」,
-- 回执分不清这两种 —— 下面的 SELECT 是唯一观测手段。
-- ============================================================================

-- ⚠️ 下面三条是 runner 断言,不是给人看的 SELECT。
--    没有 `-- runner: assert=` 注解的 SELECT,run_migration.py 只 execute 不 fetch ——
--    跑了一声不吭、什么也证明不了。那正是「回执≠观测」:执行成功的回执,冒充成了观测。
--    改成 scalar-true 后,任一条不成立 runner 直接非零退出。

-- A1:必须恰好 5 行(1 条 C 菜单 + 4 条 F 按钮)。
--    0 行 = 全没进,回去看 @parent;1 行 = @npcMenu 是 NULL(或 component 写错)。
-- runner: assert=scalar-true
SELECT COUNT(*) = 5
FROM sys_menu
WHERE perms LIKE 'business:merchantNpc:%';

-- A2:C 菜单必须挂在「业务管理」下。
--    ⚠️ 别用「parent_id 不是 0」当判据 —— 挂到「系统管理」时它照样非 0,判据会假绿。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM sys_menu c JOIN sys_menu p ON p.menu_id = c.parent_id
WHERE c.component = 'business/merchantnpc/index' AND p.menu_name = '业务管理';

-- A3:perms 不许重复(重复执行本文件也必须仍是 5 个各 1 条)。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM (SELECT perms FROM sys_menu
       WHERE perms LIKE 'business:merchantNpc:%'
       GROUP BY perms HAVING COUNT(*) > 1) dup;

-- ============================================================================
-- A4:★ 上面三条只是「我写进去了」。这条才是「玩家真看得到」,机器断不了,必须人工做。
--     必须在 jar + chengyinhub-ui 都已上线之后,按顺序走完:
--       1. 后台「业务管理 → 门店AI形象」→ 新增 → 选一个真实商家 → 传一张像素 PNG → 保存;
--       2. 回读这条形象真落到了 scope_type=2:
--          SELECT id, scope_type, scope_id, name, avatar, audit_status, enabled, active_slot
--          FROM npc_profile WHERE scope_type = 2;
--          ⚠️ active_slot **必须是 NULL** —— 若是 1,说明 insert 语句被改过,
--             第二条形象会撞 uk_active_scope(scope_type,scope_id,active_slot) 报 1062。
--       3. 用小程序打开这家店的公开主页(pages/userinfo/userinfo?userId=<memberId>),
--          形象卡应出现在 tab 之上;再进漫游地图,该商家点位应画成这个像素形象。
--          ⚠️ 地图头像走 canvas 取图 = downloadFile 通道,需要 www.chengyinhub.com
--             在小程序「downloadFile 合法域名」里;没配则静默 onerror,看起来像「还没配」。
--     ★ 后台列表里有行 = 我写进去了;主页和地图上看见形象 = 玩家真看得到。只有后者算数。
-- ============================================================================
