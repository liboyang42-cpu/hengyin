-- 后台主题:补注册 business:topic:copy 按钮权限(sys_menu)
--
-- 【只补 copy,不补 add —— 这一条是查生产库改出来的,别照抄仓库推论】
--   我最初写这份时的判断是「business:topic:add 全仓任何 SQL 里都没有 ⇒ 非超管看不到新增按钮」。
--   **那个判断只验证了仓库、没验证生产,是错的。** 2026-07-17 只读预检生产库:
--       menu_id=2009  menu_name='主题新增'  perms='business:topic:add'  parent_id=2007
--   —— 早在外包初版就注册好了,一直在。仓库里没有 seed 只说明「seed 不在仓库里」,
--   不说明「生产里没有」。sys_menu 是外包当年直接在库上建的,仓库从来就没有那份真源。
--   ⇒ 后台发不了主题的真实原因只有两条:①前端没有「新增」按钮(本轮已补)
--     ②insertCmsTopic 恒把内容槽判成 PLATFORM(本轮已改为跟着发布者走)。
--
-- 【为什么 copy 是真缺的】生产库 sys_menu 里 business:topic:* 只有
--   list(2007 C菜单) / query(2008) / add(2009) / edit(2010) / remove(2011) / export(2012),
--   **没有 copy**。而 CmsTopicController.copy 要求 @PreAuthorize('business:topic:copy')
--   ⇒ 从前那个复制按钮【没有 v-hasPermi】、人人可见、点了 403(超管除外)。
--   本轮给按钮补了 v-hasPermi,权限也必须一并注册,否则按钮直接对非超管消失。
--
-- 【类别】③ 迁移/注册数据(要在库上跑)。不是 DDL,但不跑 = 复制按钮对非超管不存在。
--   顺序:jar + chengyinhub-ui 上线后再跑,避免运营点到一个后端还不认的按钮。
--   ⚠️ chengyinhub-ui 是【自动部署】的(.github/workflows/deploy-ui.yml,master CI 过后自动触发,
--      与 jar 那条 deploy.yml 平行)。别再抄「需另行构建部署」那句 —— 那是过时说法,我抄过一次。
--      ⚠️ 但这条链路 2026-07-16~17 一直红:生产机没装 rsync ⇒ deploy-ui.sh 第 3 步 exit 127。
--      已于 2026-07-17 `dnf install -y rsync` 修好并观测坐实(version.json 自报 commit + builtAt)。
--      教训:workflow 存在 ≠ 它在跑通。合并前扫一眼 `gh run list --workflow=deploy-ui.yml`。
--
-- 【幂等】按 perms 去重(照抄 migration_ai_log_menu_20260716.sql)。
-- 【可逆】DELETE FROM sys_menu WHERE perms = 'business:topic:copy';
-- 【风险】低。只往 sys_menu 插 1 行 F 按钮,不动 C 菜单、不动目录结构。

-- ① 主题 C 菜单 menu_id 动态解析(勿硬编码 —— 生产实测是 2007,但别写死)。
--    按 component 找最稳:菜单名可能被运营改过,component 是代码里的路径,改不动。
SET @topicMenu := (SELECT menu_id FROM sys_menu WHERE component = 'business/topic/index' LIMIT 1);

-- ② 复制主题按钮权限(F,按 perms 幂等)
--    ⚠️ 字符串必须与 CmsTopicController 的 @PreAuthorize 逐字一致,对不上 = 按钮点了 403。
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '主题复制', @topicMenu, 7, '', '', 1, 0, 'F', '0', '0', 'business:topic:copy', '#', 'admin', NOW(),
       '复制主题(后端一直要求该权限,但从前按钮没有 v-hasPermi ⇒ 人人可见、点了 403)'
FROM dual
WHERE @topicMenu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:topic:copy');

-- ============================================================================
-- 回读断言(必跑,别只看 "Query OK")
-- ============================================================================

-- A1:**必须看到 1 行**。0 行 = @topicMenu 解析不到(component 不叫 'business/topic/index')。
--     生产实测该 C 菜单 = menu_id 2007 / menu_name '主题管理'。
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu WHERE perms = 'business:topic:copy';

-- A2:parent 必须是主题 C 菜单本身。
SELECT c.menu_id, c.perms, p.menu_name AS parent_name,
       CASE WHEN p.component = 'business/topic/index' THEN 'OK' ELSE 'WRONG_PARENT' END AS verdict
FROM sys_menu c LEFT JOIN sys_menu p ON p.menu_id = c.parent_id
WHERE c.perms = 'business:topic:copy';

-- A3:perms 不许重复。期望 cnt = 1。
SELECT perms, COUNT(*) AS cnt FROM sys_menu WHERE perms = 'business:topic:copy' GROUP BY perms;

-- A4:★ 前三条只是回执(证明我写进去了),这条才是观测(证明对面真变成我要的样子)。
--     jar + chengyinhub-ui 上线后,用【超管以外】的运营账号登录后台:
--       · 主题列表页能看到「新增主题」按钮(add 权限本来就有,缺的是按钮 —— 本轮补的)
--       · 点开能选发布者(官方/俱乐部/会员),提交后主题真的建出来
--       · 复制按钮可见且点了不 403
--     ⚠️ 拿超管验等于没验 —— 超管 *:*:* 恒过。非超管还需先在「角色管理」里被分配 copy 权限。
--
--     ★★ 本轨真正的验收在这条:内容槽落对了,票款才会归对人。
--     SELECT topic_id, entity_type, entity_id FROM product_role
--     WHERE topic_id = <新建的主题id> AND role_type = 'CONTENT' AND status = 1;
--     · 选「官方」→ entity_type=PLATFORM 且 entity_id 为 NULL
--     · 选「俱乐部」→ entity_type=CLUB 且 entity_id=该 clubId  ← 从前这里恒是 PLATFORM,即本轮要治的病
