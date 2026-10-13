-- AI 治理 item2:AI 生成日志后台菜单 + 权限(sys_menu 注册)
--
-- 【背景 —— 为什么这份不跑就等于功能不存在】
--   ai_generation_log 从 AI 网关上线起就一直在落库,但后台**没有任何入口能看**
--   (Mapper 此前零 select)。item2 补齐了 Controller(/business/ailog/*)+ Vue 页面,
--   但 RuoYi 的路由是 **sys_menu.component 动态下发**的:这份 SQL 不跑,
--   jar 里 Controller 在、chengyinhub-ui 里页面在,**后台侧边栏就是没有这个菜单**,
--   @PreAuthorize('business:ailog:list') 也会把直连的请求 403 掉。
--   ⚠️ 同样是零告警型:没有任何日志会说「你菜单没注册」。
--
-- 【类别】③ 迁移/注册数据(要在库上跑)。**不是 DDL,但不跑=功能不存在。**
--   顺序:本份可先于 / 后于 jar 部署都行(菜单先在、页面 404;或页面先在、菜单没入口)。
--   推荐 **jar + ui 上线后再跑本份**,避免运营点到 404。
--   ⚠️ chengyinhub-ui 需另行构建部署(不在云效 Flow 的构建/部署链路里,详见 PR 描述)。
--
-- 【幂等】幂等。C 菜单按 component 去重,F 按钮按 perms 去重(照抄 migration_official_event_menu.sql)。
--   重复执行安全:NOT EXISTS 命中就是 0 行受影响。
--
-- 【可逆】
--   DELETE FROM sys_menu WHERE perms IN ('business:ailog:list','business:ailog:query');
--   (删 C 菜单会连带让 F 按钮悬空,故两条一起删)
--
-- 【风险】—— **低**。只往 sys_menu 插 2 行,menu_id 自增不撞。
--   失手点有两个,都只影响菜单位置、不影响可用:
--     a) @parent 解析不到任何目录 ⇒ 挂到根(parent_id=0),侧边栏顶级多一项。
--     b) @parent 解析到**错的**目录(如「系统管理」)⇒ 挂错地方。
--        ⚠️ b 是 2026-07-16 生产实测真会发生的那个(见 ① 的注释),而不是理论风险。
--   两种都由末尾 A2 的 verdict 列暴露(它按 parent_name 判,不按「≠0」判)。
--
-- 【为什么必须看末尾的回读断言】
--   sys_menu **全仓没有对账脚本**(audit_sys_job.py 只查 sys_job)。
--   INSERT 报 "0 rows affected" 既可能是「已存在,幂等跳过」也可能是「@parent 是 NULL 被 WHERE 挡了」——
--   回执分不清这两种。**末尾的 SELECT 是唯一观测手段。**

-- ① 业务管理目录 menu_id 动态解析(勿硬编码 parent_id)
-- ⚠️ 原写法是 `ORDER BY parent_id LIMIT 1`,在本库上是坏的:生产实测「系统管理」(menu_id=1)
--    与「业务管理」(menu_id=4) 的 parent_id **都是 0**,排序完全打平 ⇒ LIMIT 1 选谁由执行计划
--    决定,实测选中的是「系统管理」—— 而本菜单是 business:ailog:list / business/ailog/index,
--    该挂「业务管理」。且打平的结果不稳定,换个计划就可能变。
--    FIELD() 把优先级写死成「业务管理 > 内容管理 > 系统管理」,menu_id 兜底断平,结果确定。
SET @parent := (SELECT menu_id FROM sys_menu
                 WHERE menu_name IN ('业务管理','内容管理','系统管理')
                 ORDER BY FIELD(menu_name, '业务管理', '内容管理', '系统管理'), menu_id
                 LIMIT 1);

-- ② C 菜单(按 component 幂等)。★ C 菜单行自带 :list perms —— 不要再插一条 list 的 F 按钮行,会 perms 重复。
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 'AI生成日志', IFNULL(@parent, 0), 90, 'ailog', 'business/ailog/index', 1, 0, 'C', '0', '0', 'business:ailog:list', 'log', 'admin', NOW(), 'AI 网关调用审计 / 成本复盘 / 申诉追溯(只读)'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component = 'business/ailog/index');

-- ③ 若此前挂错父级且现在能解析到业务管理目录,则归位
UPDATE sys_menu SET parent_id = @parent
WHERE component = 'business/ailog/index' AND @parent IS NOT NULL AND parent_id <> @parent;

-- ④ 该 C 菜单 menu_id(用于挂按钮权限)
SET @aiLogMenu := (SELECT menu_id FROM sys_menu WHERE component = 'business/ailog/index' LIMIT 1);

-- ⑤ 按钮权限(F,按 perms 幂等):list 已在 C 菜单;只补 query(详情弹窗 + 快照下钻)
--    ⚠️ 这里的字符串必须与 AiGenerationLogController 的 @PreAuthorize 字符串**逐字一致**,
--    对不上 = 点开详情/快照被 403(前端不做 v-hasPermi 灰化,只有服务端这一道)。
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT 'AI生成日志查询', @aiLogMenu, 1, '', '', 1, 0, 'F', '0', '0', 'business:ailog:query', '#', 'admin', NOW()
FROM dual WHERE @aiLogMenu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:ailog:query');

-- ============================================================================
-- 回读断言(必跑,别只看 "Query OK")
-- ============================================================================

-- A1:**必须看到 2 行**(1 条 C 'AI生成日志' + 1 条 F 'AI生成日志查询')。
--     看到 0 行 = 两条 INSERT 都没进,回去看 @parent。
--     看到 1 行 = F 没进 ⇒ @aiLogMenu 是 NULL ⇒ C 菜单也没进(或 component 写错)。
SELECT menu_id, menu_name, parent_id, menu_type, perms, component
FROM sys_menu
WHERE perms IN ('business:ailog:list', 'business:ailog:query')
ORDER BY menu_type DESC;

-- A2:**parent_name 必须**逐字等于「业务管理」。
--     ⚠️ 别用「parent_id 不是 0」当判据 —— 那拦不住本份真正的失手方式。原写法
--     `ORDER BY parent_id LIMIT 1` 会挂到「系统管理」(menu_id=1,parent_id 也不是 0),
--     于是「≠0」这条判据照样绿,而菜单在错的地方。**判据要盯你要的那个结果,
--     不是盯它的某个必要条件。**
--     · parent_name = '业务管理' → 对。
--     · parent_name = '系统管理'/其他 → @parent 解析歪了,回去看 ① 的 FIELD() 排序。
--     · parent_id = 0 / parent_name 为 NULL → 该库没有这三个目录名,菜单挂在顶级;
--       功能能用但位置不对,手工 UPDATE parent_id 归位。
SELECT c.menu_id AS ailog_menu_id, c.parent_id, p.menu_name AS parent_name,
       CASE WHEN p.menu_name = '业务管理' THEN 'OK' ELSE 'WRONG_PARENT' END AS verdict
FROM sys_menu c LEFT JOIN sys_menu p ON p.menu_id = c.parent_id
WHERE c.component = 'business/ailog/index';

-- A3:**perms 不许重复**。期望两行 cnt 都 = 1。
--     >1 = 本份被改过后重复执行过(NOT EXISTS 只防同名 perms,这条兜底核一次)。
SELECT perms, COUNT(*) AS cnt
FROM sys_menu
WHERE perms IN ('business:ailog:list', 'business:ailog:query')
GROUP BY perms;

-- A4:★ **前三条只是回执(证明我写进去了),这条才是观测(证明对面真变成我要的样子)。**
--     必须在 **jar + chengyinhub-ui 都已上线之后**,用**超管以外的一个运营账号**登录后台,
--     看到侧边栏「业务管理 → AI生成日志」、点进去列表出数、点行能弹详情。
--     (超管走 getRouters 返回全部,**看得见不代表 perms 配对了** —— 拿超管验等于没验。)
--     非超管账号还需先在「角色管理」里被分配这两个权限。
