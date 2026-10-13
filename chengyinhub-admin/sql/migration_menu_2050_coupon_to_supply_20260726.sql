-- =====================================================================================
-- menu 2050『优惠券目录』:资金与交易(2214) → 内容与供给(2209)
-- 2026-07-26 · 影响分类 ③(要在库上跑的 SQL)
-- =====================================================================================
--
-- 【为什么迁】
--   代码侧早已把优惠券归在「供给与权益」,是菜单归属这一侧对不上:
--     · chengyinhub-ui/src/views/business/coupon/index.vue  eyebrow="供给与权益"
--     · chengyinhub-ui/src/router/index.js                  静态路由 path '/supply/coupon'
--     · 同上                                                 name 'supplyCoupon'
--   且 coupon/index.vue 的 ensureCanonicalRoute() 会把任何其它 path 主动 replace 成
--   '/supply/coupon' ⇒ 生产上用户实际停留的地址【今天就已经是 /supply/coupon】。
--   本次只是让 sys_menu 的归属追上代码。
--
-- 【同 path 撞车:已确认良性,不是本迁移要规避的风险】
--   迁移后服务端菜单生成的动态路由路径 = '/supply/coupon',与静态路由同 path。
--   vue-router 3.4.9(chengyinhub-ui/package.json 锁定)dist/vue-router.common.js:1436-1439
--     if (!pathMap[record.path]) { pathList.push(...); pathMap[record.path] = record }
--   ⇒ 同 path 首个注册者胜出,后来者静默丢弃。
--   注册顺序:静态在 src/store/modules/permission.js:43 先注册,
--             服务端菜单在 src/permission.js:36 后注册 ⇒ 静态那份生效。
--   两份指向同一组件(2050.component = 'business/coupon/index'
--   ⇔ 静态路由 component: () => import('@/views/business/coupon/index'))
--   ⇒ 页面照常渲染;name 'supplyCoupon' vs RuoYi 生成的 'Coupon' 不冲突,无重名告警。
--   已有真 VueRouter 实例的负控测试坐实该机制:
--     chengyinhub-ui/tests/unit/coupon-migration-contract.test.js:37-48
--
-- 【order_num 取 150 的理由】
--   1. 2209 现有子菜单是 P2-4 定的十位等差 10/20/.../140(migration_p2_4_admin_ia_menu.sql:202-220),
--      150 是唯一既不与任何既有值冲突、又不需要重排任何既有行的自然下一位
--      ⇒ 本文件真正做到「只动 2050 一行」。
--   2. 2050 是本轮唯一并入该域的新成员,排在末尾在侧栏上表现为「新增项」,
--      不打乱运营已经熟悉的既有顺序。
--   3. 语义上它与 130『分类管理』/ 140『轮播图』同属目录·配置型供给,尾部相邻不违和。
--   4. 曾考虑挂到 70『票务管理』旁边(同为凭证类),但 PR #348 已把 2031 改名
--      「票务定位」—— 那一格不是凭证组,挂靠理由不成立,故放弃。
--   5. 回滚只需单行改回 parent_id=2214 / order_num=110,可逆。
--
-- 【本文件不动什么】(逐条都有回读断言把关)
--   · 2050.path            —— 必须保持 'coupon',动它就与静态路由 / ensureCanonicalRoute 判据全部对不上
--   · 2050.component / perms / menu_type / visible / status / menu_name
--   · 2050 下的全部 F 子权限(6 个 + P2-4 D9 补的『优惠券流水查询』business:couponhistory:list)
--   · sys_role_menu 一行都不动 —— 2209 与 2214 授权集完全相同(2,100,101,102,103),
--     迁移在授权轴上不让任何角色丢可见性(预检 P4/P5 会真验这一条,不是口头保证)
--
-- 【回滚】
--   本批只有一条单行 UPDATE,不做整表快照(P2-4 那种行级快照是为 124 条语句准备的,
--   在这里是过度设计;真正的可逆性由「只改两个字段 + 原值已由预检断言锁定」提供)。
--   回滚语句(需要时手工执行,刻意不写进本文件避免手滑):
--     UPDATE sys_menu SET parent_id = 2214, order_num = 110
--      WHERE menu_id = 2050 AND parent_id = 2209;
--
-- 【执行方式】
--   python3 ci/run_migration.py \
--     --sql chengyinhub-admin/sql/migration_menu_2050_coupon_to_supply_20260726.sql
--   run_migration.py 是 autocommit、逐条执行、任一条报错即 sys.exit(1)。
--   所以【预检断言全部排在 UPDATE 之前】:预检红 = 一行业务数据都没被改过。
--
-- 【断言怎么变红(机制说明,别改)】
--   SELECT CASE WHEN <条件> THEN 'XXX_OK'
--               ELSE (SELECT '..' FROM sys_menu UNION ALL SELECT '..' FROM sys_menu) END;
--   条件不成立时,ELSE 分支的标量子查询返回多行 → MySQL ERROR 1242 (21000)
--   → run_migration.py 立即退出。这不是「常量==常量」:两边都是从生产表实时读的。
--   同一形状已在 migration_p2_4_admin_ia_menu.sql:143-148 做过负控并投产。
-- =====================================================================================


-- =====================================================================================
-- 一、原值快照(存进会话变量,供第三节回读比对)
--     ★必须在任何写操作之前执行。这些变量是「不许变的字段」的唯一真源。
-- =====================================================================================
SET @before_parent      = (SELECT parent_id  FROM sys_menu WHERE menu_id = 2050);
SET @before_order       = (SELECT order_num  FROM sys_menu WHERE menu_id = 2050);
SET @before_path        = (SELECT path       FROM sys_menu WHERE menu_id = 2050);
SET @before_component   = (SELECT IFNULL(component, '<NULL>') FROM sys_menu WHERE menu_id = 2050);
SET @before_perms       = (SELECT IFNULL(perms, '<NULL>')     FROM sys_menu WHERE menu_id = 2050);
SET @before_menu_type   = (SELECT menu_type  FROM sys_menu WHERE menu_id = 2050);
SET @before_visible     = (SELECT visible    FROM sys_menu WHERE menu_id = 2050);
SET @before_status      = (SELECT status     FROM sys_menu WHERE menu_id = 2050);
SET @before_menu_name   = (SELECT menu_name  FROM sys_menu WHERE menu_id = 2050);

SET @before_child_cnt   = (SELECT COUNT(*) FROM sys_menu WHERE parent_id = 2050);
SET @before_child_ids   = (SELECT GROUP_CONCAT(menu_id ORDER BY menu_id) FROM sys_menu WHERE parent_id = 2050);
SET @before_child_perms = (SELECT GROUP_CONCAT(IFNULL(perms,'<NULL>') ORDER BY menu_id) FROM sys_menu WHERE parent_id = 2050);

SET @before_roles_2050  = (SELECT GROUP_CONCAT(role_id ORDER BY role_id) FROM sys_role_menu WHERE menu_id = 2050);
SET @before_roles_2209  = (SELECT GROUP_CONCAT(role_id ORDER BY role_id) FROM sys_role_menu WHERE menu_id = 2209);
SET @before_roles_2214  = (SELECT GROUP_CONCAT(role_id ORDER BY role_id) FROM sys_role_menu WHERE menu_id = 2214);

SET @before_menu_rows   = (SELECT COUNT(*) FROM sys_menu);
SET @before_rolemenu_rows = (SELECT COUNT(*) FROM sys_role_menu);

-- 人工核对用(执行时会打印;期望值见第二节各条断言的注释):
SELECT @before_parent      AS `2050.parent_id(期望 2214)`,
       @before_order       AS `2050.order_num(期望 110)`,
       @before_path        AS `2050.path(期望 coupon)`,
       @before_component   AS `2050.component(期望 business/coupon/index)`,
       @before_menu_type   AS `2050.menu_type(期望 C)`,
       @before_child_cnt   AS `2050 子节点数`,
       @before_child_ids   AS `2050 子节点 id`,
       @before_roles_2050  AS `2050 授权集(期望 2,100,101,102,103)`,
       @before_roles_2209  AS `2209 授权集(期望 2,100,101,102,103)`,
       @before_menu_rows   AS `sys_menu 行数`,
       @before_rolemenu_rows AS `sys_role_menu 行数`;


-- =====================================================================================
-- 二、只读预检 —— 任一条不符即 ERROR 1242 中止,此时【零行被改动】
-- =====================================================================================

-- P1:2050 存在,且现状 = parent 2214 / order 110 / path 'coupon' / component 'business/coupon/index' / menu_type 'C'
--     期望:'P1_OK'。不符 ⇒ 说明库里的 2050 不是我们调研过的那一行(可能已被别的会话迁走,
--     或 P2-4 那批没落库)⇒ 必须停下来重查,不许改 WHERE 绕过。
SELECT CASE
         WHEN @before_parent    = 2214
          AND @before_order     = 110
          AND @before_path      = 'coupon'
          AND @before_component = 'business/coupon/index'
          AND @before_menu_type = 'C'
         THEN 'P1_OK'
         ELSE (SELECT 'P1_FAIL_2050_STATE' FROM sys_menu UNION ALL SELECT 'P1_FAIL_2050_STATE' FROM sys_menu)
       END AS p1_menu_2050_state;

-- P2:2050 的授权集恰好 = 2,100,101,102,103(GROUP_CONCAT 按 role_id 数值升序)
--     期望:'P2_OK'。不符 ⇒ 授权面与调研时不同,迁移的「授权轴不变」前提失效。
SELECT CASE
         WHEN @before_roles_2050 = '2,100,101,102,103'
         THEN 'P2_OK'
         ELSE (SELECT 'P2_FAIL_2050_ROLES' FROM sys_menu UNION ALL SELECT 'P2_FAIL_2050_ROLES' FROM sys_menu)
       END AS p2_menu_2050_roles;

-- P3:目标父目录 2209 存在,且 parent_id=0 / path='supply' / menu_type='M'
--     期望:'P3_OK'。不符 ⇒ 目标域不是我们以为的那个一级目录。
SELECT CASE
         WHEN (SELECT COUNT(*) FROM sys_menu
                WHERE menu_id = 2209 AND parent_id = 0 AND path = 'supply' AND menu_type = 'M') = 1
         THEN 'P3_OK'
         ELSE (SELECT 'P3_FAIL_2209_STATE' FROM sys_menu UNION ALL SELECT 'P3_FAIL_2209_STATE' FROM sys_menu)
       END AS p3_menu_2209_state;

-- P4:★阻断项。2209 的授权集必须【覆盖】2050 的授权集。
--     RuoYi 的菜单树是在已授权集合内从 parent_id=0 递归建的:父目录不在某角色的授权集里,
--     其子菜单挂不上树 ⇒ 从该角色侧栏整个消失,零报错零告警
--     (机制与教训见 migration_p2_4_admin_ia_menu.sql 第十节)。
--     这里断言 2209 授权集 ⊇ 2050 授权集,即「不存在某个持有 2050 的角色没持有 2209」。
--     期望:'P4_OK'。
SELECT CASE
         WHEN (SELECT COUNT(*) FROM sys_role_menu r2050
                WHERE r2050.menu_id = 2050
                  AND NOT EXISTS (SELECT 1 FROM sys_role_menu r2209
                                   WHERE r2209.menu_id = 2209
                                     AND r2209.role_id = r2050.role_id)) = 0
         THEN 'P4_OK'
         ELSE (SELECT 'P4_FAIL_2209_NOT_COVERING_2050' FROM sys_menu UNION ALL SELECT 'P4_FAIL_2209_NOT_COVERING_2050' FROM sys_menu)
       END AS p4_target_parent_covers;

-- P5:2209 与 2214 授权集完全相同(调研结论:迁移在授权轴上不让任何角色丢可见性)。
--     P4 已足以保证「不丢」;P5 额外确认「也不多」,即迁移不会意外扩大可见面。
--     期望:'P5_OK'。
SELECT CASE
         WHEN @before_roles_2209 = @before_roles_2214
         THEN 'P5_OK'
         ELSE (SELECT 'P5_FAIL_2209_2214_ROLES_DIFFER' FROM sys_menu UNION ALL SELECT 'P5_FAIL_2209_2214_ROLES_DIFFER' FROM sys_menu)
       END AS p5_source_target_roles_equal;

-- P6:目标位 order_num=150 在 2209 下空闲(证明本文件真的不需要重排任何既有行)。
--     期望:'P6_OK'。不符 ⇒ 有别的会话已经往 2209 尾部插了东西,要重新选位。
SELECT CASE
         WHEN (SELECT COUNT(*) FROM sys_menu WHERE parent_id = 2209 AND order_num = 150) = 0
         THEN 'P6_OK'
         ELSE (SELECT 'P6_FAIL_ORDER_150_TAKEN' FROM sys_menu UNION ALL SELECT 'P6_FAIL_ORDER_150_TAKEN' FROM sys_menu)
       END AS p6_order_slot_free;

-- P7:全库悬空授权 = 0(sys_role_menu 引用了不存在的 menu_id)。
--     期望:'P7_OK'。⚠️ 若这条红了,说明库里【本来就有】悬空授权,与本次迁移无关:
--     先单独排查,不要绕过本断言 —— 带着悬空数据做迁移会让第三节的回读失去参照。
SELECT CASE
         WHEN (SELECT COUNT(*) FROM sys_role_menu rm
                WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = rm.menu_id)) = 0
         THEN 'P7_OK'
         ELSE (SELECT 'P7_FAIL_DANGLING_ROLE_MENU' FROM sys_menu UNION ALL SELECT 'P7_FAIL_DANGLING_ROLE_MENU' FROM sys_menu)
       END AS p7_no_dangling_grant;

-- P8:全库孤儿菜单 = 0(parent_id 非 0 却指向不存在的 menu_id)。
--     期望:'P8_OK'。
--     ⚠️ parent_id = 0 的裸挂行【不算孤儿】:2186 / 2187 就是 menu_type='F' 且 parent_id=0 的
--     合法裸挂权限行,role 103(cy_finance)靠它们拿结算对账确认与提现审核权,严禁清理
--     (见 migration_p2_4_admin_ia_menu.sql 9.2 节)。本条刻意用 parent_id <> 0 排除它们。
SELECT CASE
         WHEN (SELECT COUNT(*) FROM sys_menu c
                WHERE c.parent_id <> 0
                  AND NOT EXISTS (SELECT 1 FROM sys_menu p WHERE p.menu_id = c.parent_id)) = 0
         THEN 'P8_OK'
         ELSE (SELECT 'P8_FAIL_ORPHAN_MENU' FROM sys_menu UNION ALL SELECT 'P8_FAIL_ORPHAN_MENU' FROM sys_menu)
       END AS p8_no_orphan_menu;


-- =====================================================================================
-- 三、唯一的写操作 —— 只动 2050 一行的 parent_id 与 order_num
-- =====================================================================================
-- ★ AND parent_id = 2214 是防误伤守卫:若 2050 已被别的会话迁到别处,这条不会盲改。
--   ⚠️ 说清楚,别当成「重复执行安全」:重复执行本文件会在 P1 就中止(此时 parent 已是 2209
--   ⇒ P1_FAIL_2050_STATE / ERROR 1242),压根走不到这条 UPDATE。这是有意的 —— 已落库的
--   迁移再跑一遍应该【响亮报错】,而不是静默地什么都不做让人以为跑成功了。
--   (本机 MySQL 9.7.1 + ci/sql_splitter.py 真执行器已验:重跑在第 19 条语句红,
--    且 2050 仍为 parent_id=2214 / order_num=110,一行未改。)
-- ★ path / component / perms / menu_type / visible / status / menu_name 一个都不在 SET 里。
-- ★ 2050 的子节点(6 个 F + 2242『优惠券流水查询』)不在 WHERE 命中范围,一行都不动。
UPDATE sys_menu
   SET parent_id = 2209,
       order_num = 150
 WHERE menu_id = 2050
   AND parent_id = 2214;


-- =====================================================================================
-- 四、回读断言 —— 每条都拿【生产表实时值】与【第一节快照】比,不是常量比常量
-- =====================================================================================

-- A1:该变的变了。期望:'A1_OK'(2050.parent_id = 2209 且 order_num = 150)
SELECT CASE
         WHEN (SELECT COUNT(*) FROM sys_menu
                WHERE menu_id = 2050 AND parent_id = 2209 AND order_num = 150) = 1
         THEN 'A1_OK'
         ELSE (SELECT 'A1_FAIL_MOVE_NOT_APPLIED' FROM sys_menu UNION ALL SELECT 'A1_FAIL_MOVE_NOT_APPLIED' FROM sys_menu)
       END AS a1_moved;

-- A2:不该变的一个都没变。期望:'A2_OK'
--     path / component / perms / menu_type / visible / status / menu_name 逐条 = 第一节快照。
SELECT CASE
         WHEN (SELECT path FROM sys_menu WHERE menu_id = 2050) = @before_path
          AND (SELECT IFNULL(component,'<NULL>') FROM sys_menu WHERE menu_id = 2050) = @before_component
          AND (SELECT IFNULL(perms,'<NULL>')     FROM sys_menu WHERE menu_id = 2050) = @before_perms
          AND (SELECT menu_type FROM sys_menu WHERE menu_id = 2050) = @before_menu_type
          AND (SELECT visible   FROM sys_menu WHERE menu_id = 2050) = @before_visible
          AND (SELECT status    FROM sys_menu WHERE menu_id = 2050) = @before_status
          AND (SELECT menu_name FROM sys_menu WHERE menu_id = 2050) = @before_menu_name
         THEN 'A2_OK'
         ELSE (SELECT 'A2_FAIL_IMMUTABLE_FIELD_CHANGED' FROM sys_menu UNION ALL SELECT 'A2_FAIL_IMMUTABLE_FIELD_CHANGED' FROM sys_menu)
       END AS a2_immutable_fields;

-- A2b:path 单独再钉一遍字面量 'coupon' —— 它是撞车分析与 ensureCanonicalRoute 判据的地基,
--      值得一条专属断言指名道姓。期望:'A2B_OK'
SELECT CASE
         WHEN (SELECT path FROM sys_menu WHERE menu_id = 2050) = 'coupon'
         THEN 'A2B_OK'
         ELSE (SELECT 'A2B_FAIL_PATH_CHANGED' FROM sys_menu UNION ALL SELECT 'A2B_FAIL_PATH_CHANGED' FROM sys_menu)
       END AS a2b_path_intact;

-- A3:2050 的子节点一行都没动 —— 数量、id 列表、perms 列表逐条 = 第一节快照,
--     且它们的 parent_id 仍然全是 2050。期望:'A3_OK'
SELECT CASE
         WHEN (SELECT COUNT(*) FROM sys_menu WHERE parent_id = 2050) = @before_child_cnt
          AND (SELECT GROUP_CONCAT(menu_id ORDER BY menu_id) FROM sys_menu WHERE parent_id = 2050) <=> @before_child_ids
          AND (SELECT GROUP_CONCAT(IFNULL(perms,'<NULL>') ORDER BY menu_id) FROM sys_menu WHERE parent_id = 2050) <=> @before_child_perms
         THEN 'A3_OK'
         ELSE (SELECT 'A3_FAIL_CHILDREN_CHANGED' FROM sys_menu UNION ALL SELECT 'A3_FAIL_CHILDREN_CHANGED' FROM sys_menu)
       END AS a3_children_intact;

-- A4:授权集一行都没动 —— 2050 / 2209 / 2214 三处逐条 = 第一节快照,
--     且 sys_role_menu 全表行数不变(证明既没新增也没删除)。期望:'A4_OK'
SELECT CASE
         WHEN (SELECT GROUP_CONCAT(role_id ORDER BY role_id) FROM sys_role_menu WHERE menu_id = 2050) <=> @before_roles_2050
          AND (SELECT GROUP_CONCAT(role_id ORDER BY role_id) FROM sys_role_menu WHERE menu_id = 2209) <=> @before_roles_2209
          AND (SELECT GROUP_CONCAT(role_id ORDER BY role_id) FROM sys_role_menu WHERE menu_id = 2214) <=> @before_roles_2214
          AND (SELECT COUNT(*) FROM sys_role_menu) = @before_rolemenu_rows
         THEN 'A4_OK'
         ELSE (SELECT 'A4_FAIL_GRANTS_CHANGED' FROM sys_menu UNION ALL SELECT 'A4_FAIL_GRANTS_CHANGED' FROM sys_menu)
       END AS a4_grants_intact;

-- A5:sys_menu 全表行数不变(本批不新增不删除任何菜单)。期望:'A5_OK'
SELECT CASE
         WHEN (SELECT COUNT(*) FROM sys_menu) = @before_menu_rows
         THEN 'A5_OK'
         ELSE (SELECT 'A5_FAIL_MENU_ROWCOUNT_CHANGED' FROM sys_menu UNION ALL SELECT 'A5_FAIL_MENU_ROWCOUNT_CHANGED' FROM sys_menu)
       END AS a5_menu_rowcount;

-- A6:全库悬空授权仍为 0。期望:'A6_OK'
SELECT CASE
         WHEN (SELECT COUNT(*) FROM sys_role_menu rm
                WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = rm.menu_id)) = 0
         THEN 'A6_OK'
         ELSE (SELECT 'A6_FAIL_DANGLING_ROLE_MENU' FROM sys_menu UNION ALL SELECT 'A6_FAIL_DANGLING_ROLE_MENU' FROM sys_menu)
       END AS a6_no_dangling_grant;

-- A7:全库孤儿菜单仍为 0(同 P8,parent_id=0 的合法裸挂 F 行不计)。期望:'A7_OK'
SELECT CASE
         WHEN (SELECT COUNT(*) FROM sys_menu c
                WHERE c.parent_id <> 0
                  AND NOT EXISTS (SELECT 1 FROM sys_menu p WHERE p.menu_id = c.parent_id)) = 0
         THEN 'A7_OK'
         ELSE (SELECT 'A7_FAIL_ORPHAN_MENU' FROM sys_menu UNION ALL SELECT 'A7_FAIL_ORPHAN_MENU' FROM sys_menu)
       END AS a7_no_orphan_menu;

-- 终局人工核对(执行时打印,与第一节那条 SELECT 对照看):
SELECT menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status
  FROM sys_menu WHERE menu_id = 2050;
SELECT menu_id, menu_name, parent_id, order_num, perms
  FROM sys_menu WHERE parent_id = 2050 ORDER BY order_num, menu_id;
SELECT menu_id, menu_name, order_num
  FROM sys_menu WHERE parent_id = 2209 ORDER BY order_num;


-- =====================================================================================
-- 五、落库之后
-- =====================================================================================
-- 本文件只改菜单归属,不含 sys_job 注册,无需 audit_sys_job.py 对账。
--
-- ★ 与前端 PR 的关系(两个 PR,分类不同,刻意不合成一个):
--   前端那半是 chengyinhub-ui/src/router/index.js 的两处 activeMenu
--   '/funds-aftercare/coupon' → '/supply/coupon'(否则侧栏该项不高亮)。
--   两个方向的中间态都【只掉侧栏高亮,不掉功能、不 404、不越权】:
--     · 本 SQL 先落库、UI 还是旧 build:菜单 path 变 /supply/coupon,静态路由先注册并胜出
--       ⇒ 页面照常打开;只是 meta.activeMenu 还指着 /funds-aftercare/coupon ⇒ 不高亮。
--     · UI 先发、本 SQL 未落库:菜单仍是 /funds-aftercare/coupon,页面 ensureCanonicalRoute
--       会 replace 到 /supply/coupon ⇒ 页面照常打开;activeMenu 指着尚不存在的 /supply/coupon
--       ⇒ 同样只是不高亮。
--   ⇒ 窗口宽松,不必卡在同一分钟。推荐顺序仍是【先落库、后发 UI】。
--
-- ★ 验收(必须是能变红的观测,不是回执):
--   1. 超管 + role 2 账号各点一次侧栏:「内容与供给」下出现「优惠券目录」且排在末位,
--      点进去 URL 落在 /supply/coupon、列表有数据、该项【高亮】。
--   2. 进「查看流水」→ 侧栏仍高亮「优惠券目录」→ 点返回 → 回到目录页。
--   3. 负控:临时把 router 里的 activeMenu 改成 '/supply/couponX',确认高亮确实丢失
--      —— 证明第 1 条的高亮不是恒真。
--   4. 负控:用不持 business:coupon:list 的角色直访 /supply/coupon,必须不可达
--      (filterDynamicRoutes 门禁,src/store/modules/permission.js:97)。
--   5. 「资金与交易」下不再出现「优惠券目录」。
