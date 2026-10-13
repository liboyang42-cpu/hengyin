-- =====================================================================================
-- migration_p2_4_admin_ia_menu.sql
-- 城瘾 Web 后台 P2-4 信息架构迁移 · sys_menu + sys_role_menu(L1)
-- 生成日期:2026-07-26   代码基线:github/master @ 67592071
-- 依据:vault 04-未开始/《Web后台信息架构重查与域划分方案_20260726.md》(方向定稿)
--       vault 04-未开始/《Web后台P2-4字段级迁移矩阵_20260726.md》+ .csv(字段级评审)
--
-- ⛔⛔ 本文件【尚未在生产执行】。执行必须走 chengyin-db-migrate:
--     只读预检 → 用户确认 → 手动落库 → SELECT 回读。作者不得自行执行。
-- ⛔ 本迁移(L1)与 chengyinhub-ui/src/router/index.js 补丁(L2)【必须同一发布窗口】
--    (方案 §7 约束 1)。只落库不发 UI = 侧栏高亮/面包屑/旧书签全断。
--
-- =====================================================================================
-- 一、本批的裁决记录(用户 2026-07-26 拍板 + 矩阵建议项),合并前请逐条过目
-- =====================================================================================
-- [Q1 = A · 用户裁决] 目标域目录按"子页角色并集"补 sys_role_menu,先保住现状可见性,
--   权限收窄另开一轮。★补授权与 parent_id 改动必须同一批(见第七节)。
-- [Q6 = 三显一隐 · 用户裁决] 2025/2019/2031 由 visible='1'(隐藏)改为 '0'(显示);
--   2031 同时改名「票务管理」;2043 编辑商品保持隐藏。
--   背景:这三个功能在整个 sys_menu 里各自只有这一条菜单且都被隐藏,运营从侧栏根本
--   点不到订单管理/积分商城商品/票务管理,只能靠记 URL —— 与"优惠券流水对非超管
--   静默不存在"是同一类病。方案 §3.8 称它们是"hidden 壳"不准确:它们不是壳,是唯一
--   可用的真页面,只是被藏起来了。2043 是列表页"编辑"按钮带 id 跳进来的子页
--   (AddProduct.vue:5 标题随 form.id 变、:218 从 $route.params.id 取 ID),
--   放侧栏点进去是无上下文空白页,RuoYi 里详情/编辑页本来就该 hidden。
-- [Q10 = 全加 redirect · 用户裁决] 见 router 补丁,本文件不涉及。
-- [Q11 = B · 用户裁决] 止损用行级快照备份表。★见第五节:快照必须在【同一次会话、
--   所有迁移语句之前】执行,并当场回读行数;不一致即中止(第五节的断言会真的报错)。
--   建表 DDL 用户已确认授权。
-- [Q13 = B · 用户裁决] 2117『参数设置』visible 改为 '1'(隐藏)。
--   ★不动任何 perms、不动 sys_role_menu 一个字节 —— 只是不在侧栏出现。
--   理由:2117 是纯 redirect 空壳(setting/index.vue 的 created() 里 replace 到结算规则),
--   而 role 2 与 role 101 持有 business:setting:* 却【没有】business:settlementrule:*
--   ⇒ 它们今天点「参数设置」落到一个自己无权的路由上,就是 404。
--   隐藏既消除这个 404 体验,又不碰提权风险(给它们补 settlementrule 权限才是提权);
--   business:setting:* 仍照常发权,CmsSettingController 仍可访问,旧书签仍能跳转。
-- [Q14 = B · 用户裁决] /supply/official-event 按方案改 redirect,并改写 P2-3 那条把它
--   断言为 canonical 的契约测试。属 router 补丁,不在本文件。
--
-- 以下为矩阵已陈述倾向、按倾向执行的项(用户可在此叫停):
-- [Q2 = A] 2219/2222/2224/2226/2227 五个零角色授权的 C 本轮不补授权,维持"仅超管可见"。
--   理由:补授权是独立的权限决定,不该混进 IA 迁移批次。★但它们是已建成却没人看得见
--   的暗能力,必须单独立卡,否则与 §6.2 优惠券流水同病。
--   ⇒ 直接后果:新建的「漫游」域下只有 2224 一页且零授权 ⇒ 该域并集为空 ⇒
--     本批【不给漫游目录任何角色授权】,它对非超管不可见。这是 Q2=A 的必然结果,
--     不是遗漏。要让漫游域对运营可见,须先裁决 2224 的授权对象。
-- [Q3 = A] role 2「普通角色」纳入并集补齐。
--   理由:只读核实发现生产 sys_user 仅 3 个账号(admin/tim 超管、ry 已 del_flag=2、
--   system/管理员),sys_user_role 仅 (1,1) 与 (100,2) ⇒ role 100/101/102/103 四个角色
--   一个账号都没有,role 2 是【唯一真实在用的非超管角色】。不给它补授权,唯一的非超管
--   账号会掉页面。
-- [Q4 = A] 接受四层 2216→1→108→500/501,改文档措辞,不动 sys_menu 结构。
-- [Q5 = A] 两个写死旧 fullpath 的壳改为按 route name 跳转(router 补丁,不在本文件)。
-- [Q7] 新目录 path 取 roam / rules;一级 order_num 按方案 §2 的十域顺序重排为 1..10
--   (原矩阵提案的 45/85 只是占位,已按方案顺序修正)。
-- [Q8 = A] 接受 canonical 静态路由前缀与新域名不一致(静态路由是 hidden 兼容入口)。
-- [Q9 = B] 2186/2187 两条裸挂 F re-parent 到 2184/2111。★见第八节的强警告。
-- [矩阵建议] 一级目录只改 menu_name,【不改 path】(workbench/clubs-roam/funds-aftercare
--   保持原值)。理由:改 path 会让所有子页 fullpath 再变一次、redirect 兜底翻倍,
--   而 path 不上侧栏显示(侧栏显示 menu_name)。收益低风险高。
--
-- =====================================================================================
-- 二、开工前只读核查的结论(改变了 H2/H3/H6 的处置,与矩阵初版不同)
-- =====================================================================================
-- [H2 · 2068 模板库管理] 矩阵初版标 BLOCKED,现【解除为 KEEP 完全不动】。
--   核查:CmsTemplateLibraryController 是活控制器(@RequestMapping /business/templatelibrary),
--   6 个 @PreAuthorize 全部引用 business:templatelibrary:*;templatelibrary/detail.vue
--   (static hidden 路由, permissions business:templatelibrary:query)确实 import
--   getTemplatelibrary。⇒ 这些 perms 不是死的,删会让 role 2/100 失去一个活接口。
--   虽然 2069-2073 与 2087-2091 的角色集完全相同(映射在数据上等价、不提权),
--   但映射意味着删 templatelibrary perms ⇒ detail.vue 会 403。
--   ⇒ 本轮 2068 已经在 2209(内容与供给)下,parent 正确,【一个字段都不改】。
--     "2086 为 canonical、未来删 2068" 是 (b) 期的事。
-- [H3 · 2117 参数设置] 矩阵初版标 BLOCKED,现【解除为"原样保留 + 随域迁移"】。
--   核查:CmsSettingController 是活控制器(/business/setting),6 个 @PreAuthorize
--   引用 business:setting:*,还有守卫测试 CmsSettingPlatformAccountAuthorizationGuardTest;
--   前端 api/business/setting.js 【零消费】(setting/index.vue 是 21 行 redirect 壳)。
--   ⇒ 废弃不可选(会剥夺 role 2/100 对一个活接口的访问);
--     映射到 settlementrule 更不可选 —— role 2 与 role 101 持有 business:setting:*
--     却【没有】business:settlementrule:*,映射等于给这两个角色白送结算规则编辑权,
--     是提权不是等价映射(用户已确认此判断)。
--   ⇒ 唯一无副作用的做法:2117 的 C 与 2118-2122 五条 F 【perms、sys_role_menu
--     一个字节都不动】,只把 2117 的 parent_id 迁到 2214(方案 §3.8 本来就把它归资金域)。
--     这样目录 4 才能清空删除。"BLOCKED" 针对的是"删不删",不是"迁不迁"。
-- [H6 · D9 优惠券流水 PII] SmsCouponHistory 实体字段全部是 id/couponId/memberId/
--   couponCode/qrcodeUrl/getType/getTime/useStatus/useTime/orderId/orderSn/
--   startTime/endTime/note/delFlag ——【无手机号/姓名/证件/银行卡等直接 PII】;
--   memberId 是内部 ID,couponCode/qrcodeUrl 属核销凭证(非 PII 但敏感)。
--   coupon/ledger.vue 展示列为 领取用户/优惠券码/使用状态/关联订单/领取方式/时间/备注。
--   ⇒ 授权面按【与 2051『优惠券查询』同角色集】处理,不额外收紧也不额外放宽。
--   ⇒ ★只补 list 一条。SmsCouponHistoryController 另有 query/add/edit/remove/export
--     五个 @PreAuthorize,它们在 sys_menu 里【同样 0 行】;前端只用 list,
--     故其余五条继续落空 = 最小授权,不是遗漏。
--
-- =====================================================================================
-- 三、执行前只读预检(逐条跑,任何一条不符即停止,不要继续)
-- =====================================================================================
-- P1 快照一致性(矩阵基线 2026-07-26):
--   SELECT COUNT(*) FROM sys_menu;                                  -- 期望 319
--   SELECT menu_type, visible, COUNT(*) FROM sys_menu
--     GROUP BY menu_type, visible;                                  -- 期望 C/0=67 C/1=4 F/0=232 M/0=16
--   SELECT COUNT(*) FROM sys_role_menu;                             -- 期望 573
--   SELECT MAX(menu_id) FROM sys_menu;                              -- 期望 2239
--   SHOW CREATE TABLE sys_menu;                                     -- 期望 AUTO_INCREMENT=2240
-- P2 无悬空授权基线:
--   SELECT COUNT(*) FROM sys_role_menu rm
--     LEFT JOIN sys_menu m ON rm.menu_id = m.menu_id
--     WHERE m.menu_id IS NULL;                                      -- 期望 0
-- P3 R4 角色差集(H1 的前提):
--   SELECT role_id FROM sys_role_menu WHERE menu_id = 2080;         -- 期望 2,100,101,102,103
--   SELECT role_id FROM sys_role_menu WHERE menu_id = 2161;         -- 期望 100,101,102,103
--   -- 差集必须恰好是 {2};若不是,停止并重新评审 H1
--   SELECT COUNT(*) FROM sys_menu WHERE parent_id = 2161;           -- 期望 0(2161 现无 F 子项)
-- P4 D9 落空确认:
--   SELECT COUNT(*) FROM sys_menu WHERE perms LIKE '%couponhistory%';  -- 期望 0
-- P5 待删目录必须为空(在第十三节执行前【再跑一次】):
--   SELECT parent_id, COUNT(*) FROM sys_menu
--     WHERE parent_id IN (4,2155,2162,2215) GROUP BY parent_id;     -- 期望空结果集
--
-- =====================================================================================
-- 四、回滚
-- =====================================================================================
-- [Q11 = B] 已选定行级快照方案。快照的建表与回读断言【就在第五节,是本文件的一部分】,
-- 不是"执行前另外记得跑一下" —— 那种约定必漏。除这两张备份表外,本批无其他 DDL。
-- 回滚步骤见第五节末尾。


-- =====================================================================================
-- 五、[Q11 = B] 行级快照 —— ★必须是本文件的第一批可执行语句,任何数据改动之前
-- =====================================================================================
-- run_migration.py 是 autocommit=True、无事务包裹,逐条执行;任一条报错即 sys.exit(1),
-- 但【此前已执行的语句已经提交】。所以快照必须排在最前,且它后面紧跟一条会真报错的断言。
-- 重复执行本文件时 CREATE TABLE 会因表已存在而失败 —— 这是有意的:防止二次运行把
-- 已经变过的数据当成"原始快照"覆盖掉真备份。
CREATE TABLE sys_menu_bak_p2_4      AS SELECT * FROM sys_menu;
CREATE TABLE sys_role_menu_bak_p2_4 AS SELECT * FROM sys_role_menu;
-- ★快照回读断言:行数与源表一致才继续。
--   不一致时 ELSE 分支的子查询返回多行 → MySQL ERROR 1242 → run_migration 立即 sys.exit(1),
--   此时还【没有任何一行业务数据被改过】。
--   负控已做(本机 MySQL 9.7.1 实测):一致 → 返回 'SNAPSHOT_OK';
--   人为让源表多一行 → ERROR 1242 (21000) Subquery returns more than 1 row。
SELECT CASE
         WHEN (SELECT COUNT(*) FROM sys_menu_bak_p2_4)      = (SELECT COUNT(*) FROM sys_menu)
          AND (SELECT COUNT(*) FROM sys_role_menu_bak_p2_4) = (SELECT COUNT(*) FROM sys_role_menu)
         THEN 'SNAPSHOT_OK'
         ELSE (SELECT 'SNAPSHOT_MISMATCH' FROM sys_menu UNION ALL SELECT 'SNAPSHOT_MISMATCH' FROM sys_menu)
       END AS snapshot_guard;
-- 人工再核一次(期望 319 / 573,与第三节 P1 一致):
--   SELECT COUNT(*) FROM sys_menu_bak_p2_4;
--   SELECT COUNT(*) FROM sys_role_menu_bak_p2_4;
--
-- 回滚方式(整表覆盖回去):
--   DELETE FROM sys_menu;      INSERT INTO sys_menu      SELECT * FROM sys_menu_bak_p2_4;
--   DELETE FROM sys_role_menu; INSERT INTO sys_role_menu SELECT * FROM sys_role_menu_bak_p2_4;
--   回滚后同样要回读行数 319 / 573,并重新走非超管验收。
--   ⚠️ 回滚 SQL 本身不写进本文件,避免手滑整表清空;需要时由执行人另建文件。

-- =====================================================================================
-- 六、INSERT 两个新一级目录(漫游 / 玩法与规则)
--     menu_id 由 AUTO_INCREMENT 生成(已核 AUTO_INCREMENT=2240 ⇒ 落 2240、2241)
-- =====================================================================================
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  ('漫游', 0, 5, 'roam', NULL, NULL, 1, 0, 'M', '0', '0', '', 'guide', 'p2-4', NOW(), 'P2-4 D7:漫游拆为独立一级域');
SET @roam_id = LAST_INSERT_ID();
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  ('玩法与规则', 0, 9, 'rules', NULL, NULL, 1, 0, 'M', '0', '0', '', 'guide', 'p2-4', NOW(), 'P2-4 D1:跨对象生效的玩法规则');
SET @rules_id = LAST_INSERT_ID();

-- =====================================================================================
-- 七、一级目录:改名 + 按方案 §2 十域顺序重排 order_num(path 一律不改,见裁决记录)
-- =====================================================================================
UPDATE sys_menu SET menu_name = '总览', order_num = 1 WHERE menu_id = 2208;  -- 「工作台」→「总览」 path 保持 'workbench' 不变
UPDATE sys_menu SET order_num = 2 WHERE menu_id = 2209;  -- 「供给与发布」→「内容与供给」 path 保持 'supply' 不变
UPDATE sys_menu SET order_num = 3 WHERE menu_id = 2210;  -- 「审核与风控」→「审核与风控」 path 保持 'audit-center' 不变
UPDATE sys_menu SET menu_name = '俱乐部', order_num = 4 WHERE menu_id = 2211;  -- 「俱乐部与漫游」→「俱乐部」 path 保持 'clubs-roam' 不变
-- 漫游:order_num = 5 已在第五节 INSERT 时写入,此处不重复 UPDATE
UPDATE sys_menu SET order_num = 6 WHERE menu_id = 2212;  -- 「商家与合作」→「商家与合作」 path 保持 'merchants-coop' 不变
UPDATE sys_menu SET order_num = 7 WHERE menu_id = 2213;  -- 「用户与客服」→「用户与客服」 path 保持 'users-support' 不变
UPDATE sys_menu SET menu_name = '资金与交易', order_num = 8 WHERE menu_id = 2214;  -- 「资金与售后」→「资金与交易」 path 保持 'funds-aftercare' 不变
-- 玩法与规则:order_num = 9 已在第五节 INSERT 时写入,此处不重复 UPDATE
UPDATE sys_menu SET order_num = 10 WHERE menu_id = 2216;  -- 「系统与运维」→「系统与运维」 path 保持 'system-ops' 不变
UPDATE sys_menu SET parent_id = 2216, order_num = 10 WHERE menu_id = 1;  -- 系统管理 降为「系统与运维」分组
UPDATE sys_menu SET parent_id = 2216, order_num = 20 WHERE menu_id = 2;  -- 系统监控 降为分组
UPDATE sys_menu SET parent_id = 2216, order_num = 30 WHERE menu_id = 3;  -- 系统工具 降为分组
-- ★Q4=A:108『日志管理』是 menu_type='M' 且 parent_id=1,故最终链路是
--   2216 → 1 → 108 → 500/501 = 四层,不是方案 §3.10/§5/§11.2 说的三层。
--   已核 SysMenuServiceImpl.buildMenus 递归无深度假设、非根 M 走 ParentView,技术上不炸。
--   本批【不动 108/500/501 的结构】,只改方案措辞。

-- =====================================================================================
-- 八、C 菜单:parent_id / menu_name / component / visible / order_num
--     ★ path 一律不改(re-parent 已足够,RuoYi 由 parent 链拼 fullpath),
--       只改必要字段以缩小爆炸半径。F 子项一律随父保留、parent_id 不动。
-- =====================================================================================
UPDATE sys_menu SET parent_id = 2208, order_num = 10 WHERE menu_id = 2156;  -- [2156] 增长漏斗  /p3-platform/analytics → /workbench/analytics
UPDATE sys_menu SET parent_id = 2209, order_num = 10 WHERE menu_id = 2007;  -- [2007] 官方主题  /supply/official-topic → /supply/official-topic
UPDATE sys_menu SET parent_id = 2209, order_num = 20 WHERE menu_id = 2237;  -- [2237] 主题广场  /supply/topic-plaza → /supply/topic-plaza
UPDATE sys_menu SET parent_id = 2209, order_num = 30 WHERE menu_id = 2192;  -- [2192] 官方活动  /supply/officialevent → /supply/officialevent
UPDATE sys_menu SET parent_id = 2209, order_num = 40 WHERE menu_id = 2068;  -- [2068] 模板库管理  /supply/templatelibrary → /supply/templatelibrary
--        ★H2:2068 本轮完全不动(它已在 2209 下),此行只重排 order_num。
UPDATE sys_menu SET parent_id = 2209, order_num = 50, menu_name = '模板中心' WHERE menu_id = 2086;  -- [2086] 用户模板库  /business/membertemplate → /supply/membertemplate
--        改名「用户模板库」→「模板中心」
UPDATE sys_menu SET parent_id = 2209, order_num = 60 WHERE menu_id = 2062;  -- [2062] 活动管理  /business/activity → /supply/activity
UPDATE sys_menu SET parent_id = 2209, order_num = 70, menu_name = '票务管理', visible = '0' WHERE menu_id = 2031;  -- [2031] 主题/活动  /business/ticket → /supply/ticket
--        改名「主题/活动」→「票务管理」
--        visible '1'→'0'(★Q6 用户裁决:改为显示)
UPDATE sys_menu SET parent_id = 2209, order_num = 80 WHERE menu_id = 2159;  -- [2159] 推荐配置  /p3-platform/recommendation → /supply/recommendation
UPDATE sys_menu SET parent_id = 2209, order_num = 90 WHERE menu_id = 2178;  -- [2178] IP世界观  /p3-platform/world → /supply/world
UPDATE sys_menu SET parent_id = 2209, order_num = 100 WHERE menu_id = 2163;  -- [2163] 主题风格  /preset/style → /supply/style
UPDATE sys_menu SET parent_id = 2209, order_num = 110 WHERE menu_id = 2168;  -- [2168] 城市路线  /preset/route → /supply/route
UPDATE sys_menu SET parent_id = 2209, order_num = 120, component = 'business/content/guide/index' WHERE menu_id = 2129;  -- [2129] 玩法说明  /business/infomation → /supply/infomation
--        组件改指 canonical(原 'business/infomation/index' 是 redirect 壳)
UPDATE sys_menu SET parent_id = 2209, order_num = 130 WHERE menu_id = 2056;  -- [2056] 分类管理  /business/category → /supply/category
UPDATE sys_menu SET parent_id = 2209, order_num = 140 WHERE menu_id = 2001;  -- [2001] 轮播图  /business/banner → /supply/banner
UPDATE sys_menu SET parent_id = 2210, order_num = 10 WHERE menu_id = 2226;  -- [2226] 核销证据  /audit-center/verifyevidence → /audit-center/verifyevidence
UPDATE sys_menu SET parent_id = 2210, order_num = 20 WHERE menu_id = 2157;  -- [2157] 审核工作台  /p3-platform/audit → /audit-center/audit
UPDATE sys_menu SET parent_id = 2210, order_num = 30, menu_name = '创作者准入', component = 'business/risk/creator-admission/index' WHERE menu_id = 2180;  -- [2180] 创作者中心  /p3-platform/creator → /audit-center/creator
--        改名「创作者中心」→「创作者准入」
--        组件改指 canonical(原 'business/creator/index' 是 redirect 壳)
UPDATE sys_menu SET parent_id = 2210, order_num = 40, menu_name = 'AI 调用审计' WHERE menu_id = 2205;  -- [2205] AI生成日志  /business/ailog → /audit-center/ailog
--        改名「AI生成日志」→「AI 调用审计」
UPDATE sys_menu SET parent_id = 2210, order_num = 50 WHERE menu_id = 2044;  -- [2044] 创意广场  /users-support/square → /audit-center/square
UPDATE sys_menu SET parent_id = 2210, order_num = 60 WHERE menu_id = 2092;  -- [2092] 评论管理  /users-support/comment → /audit-center/comment
UPDATE sys_menu SET parent_id = 2211, order_num = 10, menu_name = '俱乐部工作台' WHERE menu_id = 2147;  -- [2147] 俱乐部管理  /system/club → /clubs-roam/club
--        改名「俱乐部管理」→「俱乐部工作台」
UPDATE sys_menu SET parent_id = 2211, order_num = 20 WHERE menu_id = 2227;  -- [2227] 带队场次  /clubs-roam/clubrun → /clubs-roam/clubrun
UPDATE sys_menu SET parent_id = @roam_id, order_num = 10 WHERE menu_id = 2224;  -- [2224] POI治理  /clubs-roam/roampoi → /roam/roampoi
UPDATE sys_menu SET parent_id = 2212, order_num = 10, menu_name = '商家准入' WHERE menu_id = 2013;  -- [2013] 商家管理  /merchants-coop/merchant → /merchants-coop/merchant
--        改名「商家管理」→「商家准入」
UPDATE sys_menu SET parent_id = 2212, order_num = 20 WHERE menu_id = 2222;  -- [2222] 三档承接审核  /merchants-coop/chapteroffer → /merchants-coop/chapteroffer
UPDATE sys_menu SET parent_id = 2212, order_num = 30, menu_name = '商家报名与调配' WHERE menu_id = 2161;  -- [2161] 报名调配审核  /merchants-coop/registrationmerchant-dispatch → /merchants-coop/registrationmerchant-dispatch
--        改名「报名调配审核」→「商家报名与调配」
UPDATE sys_menu SET parent_id = 2212, order_num = 40 WHERE menu_id = 2196;  -- [2196] 商家目录  /merchantlead → /merchants-coop/merchantlead
UPDATE sys_menu SET parent_id = 2212, order_num = 50 WHERE menu_id = 2193;  -- [2193] 客诉工单  /coopcomplaint → /merchants-coop/coopcomplaint
UPDATE sys_menu SET parent_id = 2212, order_num = 60 WHERE menu_id = 2182;  -- [2182] 商家CRM  /p3-platform/crm → /merchants-coop/crm
UPDATE sys_menu SET parent_id = 2213, order_num = 10 WHERE menu_id = 2000;  -- [2000] 用户管理  /users-support/member → /users-support/member
UPDATE sys_menu SET parent_id = 2213, order_num = 20, menu_name = '客服会话' WHERE menu_id = 2173;  -- [2173] 客服消息  /im → /users-support/im
--        改名「客服消息」→「客服会话」
UPDATE sys_menu SET parent_id = 2214, order_num = 10, menu_name = '订单查询', component = 'business/finance/order/index', visible = '0' WHERE menu_id = 2025;  -- [2025] 订单管理  /business/order → /funds-aftercare/order
--        改名「订单管理」→「订单查询」
--        组件改指 canonical(原 'business/order/index' 是 redirect 壳)
--        visible '1'→'0'(★Q6 用户裁决:改为显示)
UPDATE sys_menu SET parent_id = 2214, order_num = 20, menu_name = '微信提现回补' WHERE menu_id = 2219;  -- [2219] 提现出金管理  /business/cashwithdraw → /funds-aftercare/cashwithdraw
--        改名「提现出金管理」→「微信提现回补」
UPDATE sys_menu SET parent_id = 2214, order_num = 30, menu_name = '银行卡提现审核' WHERE menu_id = 2111;  -- [2111] 提现管理  /business/memberwithdrawal → /funds-aftercare/memberwithdrawal
--        改名「提现管理」→「银行卡提现审核」
UPDATE sys_menu SET parent_id = 2214, order_num = 40, menu_name = '资金流水', component = 'business/finance/ledger' WHERE menu_id = 2141;  -- [2141] 余额明细  /business/memberbalancedetail → /funds-aftercare/memberbalancedetail
--        改名「余额明细」→「资金流水」
--        组件改指 canonical(原 'business/memberbalancedetail/index' 是 redirect 壳)
UPDATE sys_menu SET parent_id = 2214, order_num = 50, menu_name = '退款处理台', component = 'business/finance/refund/index' WHERE menu_id = 2190;  -- [2190] 退款审核  /p3-platform/refund → /funds-aftercare/refund
--        改名「退款审核」→「退款处理台」
--        组件改指 canonical(原 'business/refund/index' 是 redirect 壳)
UPDATE sys_menu SET parent_id = 2214, order_num = 60, menu_name = '应付月结', component = 'business/finance/payable-batch/index' WHERE menu_id = 2217;  -- [2217] ③应付批次  /p3-platform/payablebatch → /funds-aftercare/payablebatch
--        改名「③应付批次」→「应付月结」
--        组件改指 canonical(原 'business/payablebatch/index' 是 redirect 壳)
UPDATE sys_menu SET parent_id = 2214, order_num = 70 WHERE menu_id = 2184;  -- [2184] 结算管理  /p3-platform/settlement → /funds-aftercare/settlement
UPDATE sys_menu SET parent_id = 2214, order_num = 80 WHERE menu_id = 2188;  -- [2188] 结算规则  /p3-platform/settlementrule → /funds-aftercare/settlementrule
UPDATE sys_menu SET parent_id = 2214, order_num = 90, visible = '1' WHERE menu_id = 2117;  -- [2117] 参数设置  /business/setting → /funds-aftercare/setting
--        visible '0'→'1'(★Q13 用户裁决:改为隐藏 —— 它是纯 redirect 空壳,而 role 2/101 持 setting:* 却无 settlementrule:* ⇒ 今天点进去就是 404;只藏菜单,perms 与授权一字不动)
--        ★H3:perms('business:setting:*')与 sys_role_menu 一个字节都不动,只迁 parent。
UPDATE sys_menu SET parent_id = 2214, order_num = 100, menu_name = '报名台账' WHERE menu_id = 2074;  -- [2074] 用户报名管理  /business/registration → /funds-aftercare/registration
--        改名「用户报名管理」→「报名台账」
UPDATE sys_menu SET parent_id = 2214, order_num = 110, menu_name = '优惠券目录' WHERE menu_id = 2050;  -- [2050] 优惠券管理  /business/coupon → /funds-aftercare/coupon
--        改名「优惠券管理」→「优惠券目录」
UPDATE sys_menu SET parent_id = 2214, order_num = 120, menu_name = '礼物目录与定价', component = 'business/gift-catalog/index' WHERE menu_id = 2037;  -- [2037] 众筹礼物  /business/gift → /funds-aftercare/gift
--        改名「众筹礼物」→「礼物目录与定价」
--        组件改指 canonical(原 'business/gift/index' 是 redirect 壳)
UPDATE sys_menu SET parent_id = 2214, order_num = 130, component = 'business/config/points-product/index', visible = '0' WHERE menu_id = 2019;  -- [2019] 积分商城商品  /business/product → /funds-aftercare/product
--        组件改指 canonical(原 'business/product/index' 是 redirect 壳)
--        visible '1'→'0'(★Q6 用户裁决:改为显示)
UPDATE sys_menu SET parent_id = @rules_id, order_num = 10, menu_name = '成长任务配置' WHERE menu_id = 2176;  -- [2176] 玩家成长  /p3-platform/growth → /rules/growth
--        改名「玩家成长」→「成长任务配置」
UPDATE sys_menu SET parent_id = @rules_id, order_num = 20, menu_name = '积分配置', component = 'business/points/index' WHERE menu_id = 2135;  -- [2135] 积分规则设置  /business/pointsetting → /rules/pointsetting
--        改名「积分规则设置」→「积分配置」
--        组件改指 canonical(原 'business/pointsetting/index' 是 redirect 壳)
UPDATE sys_menu SET parent_id = @rules_id, order_num = 30 WHERE menu_id = 2153;  -- [2153] 角色配额  /system/club/userpermission → /rules/userpermission
UPDATE sys_menu SET parent_id = @rules_id, order_num = 40 WHERE menu_id = 2098;  -- [2098] 游戏管理  /business/betagame → /rules/betagame
-- 2043『编辑商品』保持 parent_id=2019、visible='1'(隐藏),随 2019 一起进入资金与交易。
--   ★Q6 用户裁决:它是列表页『编辑』按钮带 id 跳进来的子页,放侧栏是无上下文空白页。

-- =====================================================================================
-- 九、F 按钮权限
-- =====================================================================================
-- 9.1 R4:2081-2085 从 2080 re-parent 到 2161。★sys_role_menu 一行都不动。
UPDATE sys_menu SET parent_id = 2161 WHERE menu_id = 2081;  -- 商家主题报名查询 (business:registrationmerchant:query) 角色 [2, 100, 101, 102, 103] 保持不变
UPDATE sys_menu SET parent_id = 2161 WHERE menu_id = 2082;  -- 商家主题报名新增 (business:registrationmerchant:add) 角色 [2, 100] 保持不变
UPDATE sys_menu SET parent_id = 2161 WHERE menu_id = 2083;  -- 商家主题报名修改 (business:registrationmerchant:edit) 角色 [2, 100] 保持不变
UPDATE sys_menu SET parent_id = 2161 WHERE menu_id = 2084;  -- 商家主题报名删除 (business:registrationmerchant:remove) 角色 [2, 100] 保持不变
UPDATE sys_menu SET parent_id = 2161 WHERE menu_id = 2085;  -- 商家主题报名导出 (business:registrationmerchant:export) 角色 [2, 100] 保持不变

-- 9.2 ★★★ 严禁清理:2186 / 2187 是 menu_type='F' 且 parent_id=0 的裸挂权限行。
--     selectMenuTreeByUserId 只取 M/C ⇒ 它们不进侧栏;
--     但 selectMenuPermsByUserId 是平查、不看 parent 也不看 menu_type
--     ⇒ role 103(cy_finance)【就是靠这两行】拿到结算对账确认与提现审核权。
--     任何"顶级只应有 M / 清理孤儿行"的动作都会静默剥夺 cy_finance 两项核心动作权,
--     前端表现只是按钮不显示,零报错零告警。【它们必须永远存在于 sys_menu】。
--     [Q9 = B] 本批把它们 re-parent 到各自的宿主页,让树变干净;perms 与授权不动。
UPDATE sys_menu SET parent_id = 2184 WHERE menu_id = 2186;  -- 结算对账确认 (business:settlement:confirm) → 2184 结算管理;角色 [103] 保持不变 ★勿删
UPDATE sys_menu SET parent_id = 2111 WHERE menu_id = 2187;  -- 提现审核 (business:memberwithdrawal:review) → 2111 提现管理;角色 [103] 保持不变 ★勿删

-- 9.3 D9:注册 business:couponhistory:list(生产 sys_menu 现为 0 行 ⇒ 优惠券流水
--     对全部非超管静默不存在:filterDynamicRoutes 权限不过不注册 couponLedger 路由,
--     coupon/index.vue:121,149 两处 v-hasPermi 按钮恒不显示)。
--     ★只补 list 一条;query/add/edit/remove/export 继续落空 = 最小授权(见第二节 H6)。
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  ('优惠券流水查询', 2050, 60, '#', '', NULL, 1, 0, 'F', '0', '0',
   'business:couponhistory:list', '#', 'p2-4', NOW(),
   'P2-4 D9:补齐落空权限,授权面与 2051 优惠券查询一致');
SET @couponhistory_id = LAST_INSERT_ID();
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (2, @couponhistory_id);  -- 与 2051『优惠券查询』同角色集
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (100, @couponhistory_id);  -- 与 2051『优惠券查询』同角色集
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (101, @couponhistory_id);  -- 与 2051『优惠券查询』同角色集
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (102, @couponhistory_id);  -- 与 2051『优惠券查询』同角色集
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (103, @couponhistory_id);  -- 与 2051『优惠券查询』同角色集

-- =====================================================================================
-- 十、[Q1 = A] 目标域目录的 sys_role_menu 补齐(★阻断项,必须与第八节同一批)
-- =====================================================================================
-- 机制(已核 SysMenuMapper.selectMenuTreeByUserId + SysMenuServiceImpl.getChildPerms):
--   菜单树是从 parent_id=0 起在【已授权集合内】递归建的。父目录不在授权集合里,
--   其子菜单挂不上树 ⇒ 从侧栏整个消失,零报错。
--   生产实测:2208/2210/2211/2214/2215/2216 六个一级 M 的 sys_role_menu 为 0 行,
--   若只改 parent_id 不补授权,资金与交易域 13 个页面(订单/退款/两类提现/结算/
--   应付月结/优惠券/报名台账…)对 role 100-103 全部消失。
--
-- ⚠️⚠️ 请用户在合并前确认这一条副作用:
--   2209/2212/2213 现有授权是 [100,101,102,103],【缺 role 2】。而 role 2 持有
--   2007/2237/2068/2013/2080/2000/2044/2092 这 8 个 C 的 sys_role_menu 行 ——
--   今天它们被父目录挡住,role 2 看不到;补上 role 2 之后就看得到了。
--   这不是"新授权",是让 sys_role_menu 里【本来就躺着的授权】生效。
--   但它确实【扩大了 role 2 的可见面】,不是纯粹的"保持现状"。
--   不补的代价:role 2 会丢掉今天在「业务管理(4)」下能看到的 6 个页面
--   (2001/2056/2086/2062/2031/2129),而 role 2 是唯一真实在用的非超管角色。
--   ⇒ 两害相权按 Q1=A 的裁决理由("先保住现状可见性不变")取补齐。如不同意请叫停。
-- 总览(2208):并集 [100, 101, 102, 103],现有 [] ⇒ 补 [100, 101, 102, 103]
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (100, 2208);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (101, 2208);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (102, 2208);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (103, 2208);
-- 内容与供给(2209):并集 [2, 100, 101, 102, 103],现有 [100, 101, 102, 103] ⇒ 补 [2]
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (2, 2209);
-- 审核与风控(2210):并集 [2, 100, 101, 102, 103],现有 [] ⇒ 补 [2, 100, 101, 102, 103]
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (2, 2210);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (100, 2210);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (101, 2210);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (102, 2210);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (103, 2210);
-- 俱乐部(2211):并集 [100, 101, 102, 103],现有 [] ⇒ 补 [100, 101, 102, 103]
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (100, 2211);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (101, 2211);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (102, 2211);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (103, 2211);
-- 漫游:子页并集为空(唯一子页 2224 POI治理 现 sys_role_menu = 0 行,按 Q2=A 本轮不补)⇒ 本批不给该目录任何授权,它对非超管不可见。
-- 商家与合作(2212):并集 [2, 100, 101, 102, 103],现有 [100, 101, 102, 103] ⇒ 补 [2]
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (2, 2212);
-- 用户与客服(2213):并集 [2, 100, 101, 102, 103],现有 [100, 101, 102, 103] ⇒ 补 [2]
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (2, 2213);
-- 资金与交易(2214):并集 [2, 100, 101, 102, 103],现有 [] ⇒ 补 [2, 100, 101, 102, 103]
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (2, 2214);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (100, 2214);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (101, 2214);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (102, 2214);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (103, 2214);
-- 玩法与规则(@rules_id):并集 [2, 100, 101, 102, 103],现有 [] ⇒ 补 [2, 100, 101, 102, 103]
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (2, @rules_id);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (100, @rules_id);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (101, @rules_id);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (102, @rules_id);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (103, @rules_id);
-- 系统与运维(2216):并集 [100, 101, 102, 103],现有 [] ⇒ 补 [100, 101, 102, 103]
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (100, 2216);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (101, 2216);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (102, 2216);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (103, 2216);

-- =====================================================================================
-- 十一、R4 收尾:2161 接管角色,最后删 2080
--     ★顺序不可调换:必须先补 2161 的角色、先 re-parent 2081-2085(第九节),
--       再删 2080。中途任何一步失败都必须停止并回滚,不能只做一半。
-- =====================================================================================
-- 11.1 把"有 2080、无 2161"的角色补到 2161。P3 预检已确认差集恰好是 {2}。
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (2, 2161);
-- 11.2 中途回读(★手动跑,确认 2161 的角色集 ⊇ 2080 的角色集,不通过则停):
--   SELECT role_id FROM sys_role_menu WHERE menu_id = 2161 ORDER BY role_id;  -- 期望 2,100,101,102,103
--   SELECT parent_id FROM sys_menu WHERE menu_id IN (2081,2082,2083,2084,2085);  -- 期望全为 2161
-- 11.3 删 2080 的授权与菜单行(必须成对,否则留下悬空授权)
DELETE FROM sys_role_menu WHERE menu_id = 2080;
DELETE FROM sys_menu      WHERE menu_id = 2080;
-- ★router 补丁必须已为 2080 旧 path /merchants-coop/registrationmerchant 提供
--   同权限 hidden redirect 指向 2161;旧深链有权/无权回归通过后本步才算完成。

-- =====================================================================================
-- 十二、R2:删 2201『退款人工兜底』(2190 的 C 子项)
--   2201 角色 [103];2190 角色 [100,102,103] ⇒ 103 已持有 2190,删 2201 不丢入口。
--   2191 business:refund:review(角色 [103])保持不动。
--   static route refundManual(/refund/manual)保留,旧书签仍进 financeRefund?queue=manual。
-- =====================================================================================
DELETE FROM sys_role_menu WHERE menu_id = 2201;
DELETE FROM sys_menu      WHERE menu_id = 2201;

-- =====================================================================================
-- 十三、删除清空后的旧目录(★执行前【再跑一次】预检 P5,确认 parent_id 下已无子行)
-- =====================================================================================
-- [4] 业务管理:18 个 C 全部迁出(含 2117,见 H3);现持有角色 [2, 100, 101, 102, 103]
DELETE FROM sys_role_menu WHERE menu_id = 4;
DELETE FROM sys_menu      WHERE menu_id = 4;
-- [2155] P3平台运营:11 个 C 全部迁出;现持有角色 [100, 101, 102, 103]
DELETE FROM sys_role_menu WHERE menu_id = 2155;
DELETE FROM sys_menu      WHERE menu_id = 2155;
-- [2162] 建议版预设:2163/2168 迁出;现持有角色 [100, 101, 102, 103]
DELETE FROM sys_role_menu WHERE menu_id = 2162;
DELETE FROM sys_menu      WHERE menu_id = 2162;
-- [2215] 数据与配置:生产本就无任何子行;现持有角色 []
DELETE FROM sys_role_menu WHERE menu_id = 2215;
DELETE FROM sys_menu      WHERE menu_id = 2215;

-- =====================================================================================
-- 十五、非超管验收(方案 §7 约束 3)
-- =====================================================================================
-- [用户裁决] 不新建账号,用现有 system 账号(user_id=100,role 2)做验收。
-- 关键洞察:role 2 是"有些权限有、有些没有",所以同一个账号能【同时充当正控与负控】,
-- 这比只验有权方更强 —— 它能真正测出授权有没有被【意外扩大】。
--
-- 【正控】role 2 在 sys_role_menu 里持有的 C,迁移后必须仍可见、仍可用:
--   2007 官方主题 / 2237 主题广场 / 2068 模板库管理 / 2013 商家准入 /
--   2000 用户管理 / 2044 创意广场 / 2092 评论管理;
--   以及 2161 商家报名与调配(本批已把 role 2 从 2080 并入 2161,2080 C 已删)。
--   ★注意:上述前 7 个今天其实【看不到】—— 父目录 2209/2212/2213 未授权 role 2,
--     子菜单挂不上树。本批补授权后才首次可见。这正是 Q1=A 的"可见面扩大"副作用,
--     用户已知悉并接受。验收时要确认它们确实出现,而不是把"没出现"当正常。
--   ★另外 role 2 今天在「业务管理(4)」下能看到的 18 个页面(2001/2019/2025/2031/2037/
--     2050/2056/2062/2074/2086/2098/2111/2117/2129/2135/2141/2205/2219),迁移后除
--     2117 按 Q13 主动隐藏外,其余 17 个必须仍能看到(只是换了域)。
--
-- 【负控】role 2 【没有】授权的页面,迁移后必须仍然不可达。取 2188『结算规则』
--   (sys_role_menu 只授权 [100,102,103],role 2 不在其中)为主用例:
--   ① 侧栏「资金与交易」下【不出现】「结算规则」;
--   ② 直访新 fullpath /funds-aftercare/settlementrule 不可达;
--   ③ ★直访旧 fullpath /p3-platform/settlementrule 【同样不可达】——
--      本批新加了 58 条兼容 redirect,必须确认它们没有绕过 permissions gate。
--      每条 redirect 都带 permissions、filterDynamicRoutes 权限不过就不注册路由,
--      但这条必须【真点一遍】,不能只读代码断定。
--   ④ 同理验 2184 结算管理 / 2190 退款处理台 / 2111 银行卡提现审核(role 2 均无授权);
--      2111 还要验它的旧 fullpath /business/memberwithdrawal 也进不去。
--   ⑤ Q13 相关:2117『参数设置』隐藏后 role 2 侧栏不应再出现它;
--      但 business:setting:* 权限没动 ⇒ /business/setting 接口对 role 2 仍应可访问。
--      (这条同时验证了"只藏菜单、不碰权限"确实做到了。)
--
-- ⚠️⚠️ 必须如实标注的验收局限,不许含糊过去:
--   生产 sys_user 仅 3 个账号(1 admin/tim 超管、2 ry 已 del_flag=2、100 system→role 2),
--   sys_user_role 仅 (1,1) 与 (100,2)。
--   ⇒ cy_operator(100) / cy_auditor(101) / cy_support(102) / cy_finance(103) 四个角色
--     【零账号】,本批【无法对它们做真实登录验收】,只能靠第十四节 R1-R10 的
--     sys_role_menu 数据层 SELECT 断言。
--   ⇒ 也就是说:本批给这四个角色补的目录授权,其【真实登录效果未经实测】。
--     它们今天本来也没人在用,风险可接受;但这个缺口必须记录在案,
--     不能把"数据层断言通过"写成"验收通过"。

