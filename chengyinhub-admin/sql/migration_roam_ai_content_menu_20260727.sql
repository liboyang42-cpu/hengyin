-- 漫游 AI 陪伴内容审核:注册后台菜单与权限(sys_menu)
--
-- 【类别】③ 注册数据(要在库上跑)。不是 DDL,但**不跑 = 这个页面对非超管根本不存在**
--   —— 菜单不出现、接口 403。超管有 *:*:* 恒能看见,所以**验收必须用非超管账号**。
--
-- 【背景】roam_ai_content 表、CmsRoamAiContentController、Vue 页面都已就绪,
--   但 sys_menu 里没有对应条目 ⇒ 后台侧边栏看不到该菜单,6 个 @PreAuthorize 权限
--   字符串(sys_menu.perms)没人配 ⇒ 非超管全部 403。
--   ⚠️ 零告警型:没有任何日志会说「这个菜单/权限没注册」。
--
-- 【落库时机】jar + chengyinhub-ui 都已上线,**跑本份前先跑 P1 预检确认父级存在**。
--
-- 【幂等】按 perms 去重(NOT EXISTS),重复执行安全(0 行受影响)。
--
-- 【可逆】
--   DELETE FROM sys_menu WHERE perms IN (
--     'business:roamAiContent:list','business:roamAiContent:query',
--     'business:roamAiContent:add','business:roamAiContent:edit',
--     'business:roamAiContent:audit','business:roamAiContent:remove');
--
-- 【风险】低。只往 sys_menu 插 1 个 C + 5 个 F,不动既有菜单与目录结构。
--   三个失手点都 fail-closed:
--   ① roam 父级缺失/重复 → @parent=NULL ⇒ 整份零写入;
--   ② C 菜单挂错父级 → @menu 严格限定 parent_id=@parent,不会误解析;
--   ③ C 菜单重复(同 parent 下两行完全相同的 C) → @menu_unique=COUNT(*) 限定 =1,
--      >1 时 @menu=NULL,5 条 F INSERT 全被挡 → 零写入。
--
-- 【为什么必须看末尾的回读断言 + 负控】
--   INSERT 报 "0 rows affected" 可能是「已存在,幂等跳过」也可能是「guard 拦截」——
--   回执分不清。末尾的 SELECT 是唯一观测手段。负控验证 guard 本身没被误删。


-- ======================= 第一步:只读预检 =======================
--
-- P1【必看】「漫游」M 目录是否存在且唯一:
--   SELECT menu_id, menu_name, menu_type, path, parent_id FROM sys_menu
--    WHERE path = 'roam' AND parent_id = 0 AND menu_type = 'M';
--   · 0 行 → **停**。「漫游」还没落库(migration_p2_4_admin_ia_menu.sql),
--     下面 @parent=NULL 且 @parent_unique=0,整份静默插 0 行。
--   · >1 行 → **停**。数据重复,@parent_unique>1 ⇒ @parent=NULL,整份静默插 0 行。
--   · =1 行 → 正常,全执行。
--
-- P2【必看】是否已注册过:
--   SELECT menu_id, menu_name, menu_type, perms FROM sys_menu
--    WHERE perms LIKE 'business:roamAiContent:%';
--   · 0 行 → 正常。· 有行 → 已跑过,NOT EXISTS 跳过,无害。
--
-- P3【父级重复负控】若本库真实存在 roam 父级重复,下面 @parent 是 NULL。
--   末尾负控 NC-0 会报 parent_not_unique=FAIL。


-- ======================= 第二步:注册 =======================

-- ① 父目录动态解析(勿硬编码 menu_id)。挂「漫游」一级 M 目录(path='roam',parent_id=0):
--    2240 = P2-4 落库的 roam M 目录,但本份不写死 menu_id,按 path 解析,抗 menu_id 漂移。
--    双变量:@parent(menu_id) + @parent_unique(COUNT(*),用于 fail-closed)。
--    ⚠️ @parent_unique≠1 时 @parent=NULL,下面所有 INSERT 所有 guard 都会拦住。
SET @parent_unique := (SELECT COUNT(*) FROM sys_menu WHERE path = 'roam' AND parent_id = 0 AND menu_type = 'M');
SET @parent := (SELECT menu_id FROM sys_menu WHERE path = 'roam' AND parent_id = 0 AND menu_type = 'M' AND @parent_unique = 1 LIMIT 1);

-- ② C 菜单:挂「漫游」目录下。
--    ⚠️ C 菜单行自带 :list perms —— 不要再插一条 list 的 F 按钮行,会 perms 重复。
--    path='ai-content'(与 router /roam/ai-content 一致),
--    component='business/roamaicontent/index'(与 router import 一致)。
--    perms 字符串必须与 CmsRoamAiContentController 的 @PreAuthorize 逐字一致。
--    INSERT 自身受 @parent IS NOT NULL + @parent_unique=1 双条件保护:任一不过即 0 行。
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '漫游陪伴内容审核', @parent, 2, 'ai-content', 'business/roamaicontent/index', 1, 0, 'C', '0', '0', 'business:roamAiContent:list', 'guide', 'admin', NOW(),
       '漫游无形陪伴V1:route_story/poi_meaning 内容审核台'
FROM dual
WHERE @parent IS NOT NULL AND @parent_unique = 1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:roamAiContent:list');

-- ③ C 菜单唯一性重算(必须在 C INSERT **之后**,否则首跑时 @menu_unique=0,会错误挡掉 @menu 解析
--   和所有 F INSERT,整份原子只落 1 个 C)。同条件 COUNT:若 >1(@parent 下重复 C),下面 @menu
--   会被 @menu_unique=1 挡成 NULL ⇒ 5 F 全被挡 ⇒ 零写入。
SET @menu_unique := (SELECT COUNT(*) FROM sys_menu
                     WHERE menu_type = 'C'
                       AND path = 'ai-content'
                       AND component = 'business/roamaicontent/index'
                       AND parent_id = @parent
                       AND perms = 'business:roamAiContent:list'
                       AND @parent IS NOT NULL
                       AND @parent_unique = 1);

-- ④ 严格重新解析 C 菜单的 menu_id。
--    ⚠️ 必须在 C 插入之后再查(回读),且必须限定:
--       a) menu_type='C' + path='ai-content' + component='business/roamaicontent/index'
--          —— 确保解析到的是我们刚插的那行 C 菜单,而不是碰巧同 perms 的其他行。
--       b) parent_id=@parent —— 确保只认父级正确的那行。若 C 挂错父级,@menu=NULL。
--       c) @parent IS NOT NULL + @parent_unique=1 —— 父级必须对。
--       d) @menu_unique=1 —— C 必须唯一。若同 parent 下重复,@menu=NULL,防止 LIMIT 1 任选。
--    任一条件不满足 → @menu=NULL → 下面 5 条 F INSERT 全被挡 → 零写入。
SET @menu := (SELECT menu_id FROM sys_menu
              WHERE menu_type = 'C'
                AND path = 'ai-content'
                AND component = 'business/roamaicontent/index'
                AND parent_id = @parent
                AND perms = 'business:roamAiContent:list'
                AND @parent IS NOT NULL
                AND @parent_unique = 1
                AND @menu_unique = 1
              LIMIT 1);

-- ⑤ F 按钮:挂 C 菜单之下(非父目录 M)。
--    ⚠️ 每条 F INSERT WHERE 必须四层守卫(缺任一 = guard 漏 = 零写入不保证):
--       a) @parent IS NOT NULL AND @parent_unique = 1 —— 父级必须对;
--       b) @menu_unique = 1 —— C 菜单必须唯一(防止 LIMIT 1 任选);
--       c) @menu IS NOT NULL —— C 菜单必须正确解析;
--       d) NOT EXISTS —— 幂等去重。
--    任一层不过即 0 行受影响。显式就是文档,删掉任一 guard 末尾负控/源码负控会报红。
--    每条 perms 必须与 CmsRoamAiContentController 的 @PreAuthorize 逐字一致。
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '漫游陪伴内容查询', @menu, 1, '', '', 1, 0, 'F', '0', '0', 'business:roamAiContent:query', '#', 'admin', NOW(), '查看详情'
FROM dual WHERE @parent IS NOT NULL AND @parent_unique = 1 AND @menu_unique = 1 AND @menu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:roamAiContent:query');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '漫游陪伴内容新增', @menu, 2, '', '', 1, 0, 'F', '0', '0', 'business:roamAiContent:add', '#', 'admin', NOW(), '新增内容'
FROM dual WHERE @parent IS NOT NULL AND @parent_unique = 1 AND @menu_unique = 1 AND @menu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:roamAiContent:add');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '漫游陪伴内容编辑', @menu, 3, '', '', 1, 0, 'F', '0', '0', 'business:roamAiContent:edit', '#', 'admin', NOW(), '编辑内容'
FROM dual WHERE @parent IS NOT NULL AND @parent_unique = 1 AND @menu_unique = 1 AND @menu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:roamAiContent:edit');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '漫游陪伴内容审核', @menu, 4, '', '', 1, 0, 'F', '0', '0', 'business:roamAiContent:audit', '#', 'admin', NOW(), '通过/驳回审核'
FROM dual WHERE @parent IS NOT NULL AND @parent_unique = 1 AND @menu_unique = 1 AND @menu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:roamAiContent:audit');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '漫游陪伴内容删除', @menu, 5, '', '', 1, 0, 'F', '0', '0', 'business:roamAiContent:remove', '#', 'admin', NOW(), '删除内容'
FROM dual WHERE @parent IS NOT NULL AND @parent_unique = 1 AND @menu_unique = 1 AND @menu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:roamAiContent:remove');


-- ======================= 回读断言(必跑,别只看 "Query OK") =======================

-- A0★【fail-closed 验伤】:父级解析状态 + C 唯一性必须都对。@parent_unique=1 且 @parent IS NOT NULL
--     且 @menu_unique=1 才是正常状态;任一不过 = 上面 INSERT 全被 WHERE 静默挡掉(0 行)。
--     先跑这条,不对就不要再往下看 A1-A5 了。
SELECT @parent_unique AS parent_count, @parent AS parent_menu_id,
       @menu_unique AS menu_c_count,
       IF(@parent_unique = 1 AND @parent IS NOT NULL AND @menu_unique = 1, 'OK', 'BLOCKED') AS parent_verdict;

-- A1:**必须看到 6 行**(1 个 C + 5 个 F)。<6 = 有 INSERT 没进去(回去看 @parent / @menu)。
--     0 行 = @parent 解析不到 ⇒ 整份静默什么都没做。
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu
 WHERE perms LIKE 'business:roamAiContent:%'
 ORDER BY menu_type DESC, order_num;

-- A2:C 菜单诊断 —— type/path/component/parent 四项必须全部吻合,任一项不对 = 挂错地方或
--     类型错误。parent_name 必须逐字等于「漫游」。
SELECT c.menu_id, c.menu_name, c.menu_type, c.path, c.component, c.parent_id,
       p.menu_name AS parent_name,
       CASE WHEN c.menu_type = 'C' THEN 'OK' ELSE 'WRONG_TYPE' END AS type_verdict,
       CASE WHEN c.path = 'ai-content' THEN 'OK' ELSE 'WRONG_PATH' END AS path_verdict,
       CASE WHEN c.component = 'business/roamaicontent/index' THEN 'OK' ELSE 'WRONG_COMPONENT' END AS comp_verdict,
       CASE WHEN p.menu_name = '漫游' THEN 'OK' ELSE 'WRONG_PARENT' END AS parent_verdict
  FROM sys_menu c LEFT JOIN sys_menu p ON p.menu_id = c.parent_id
 WHERE c.perms = 'business:roamAiContent:list';

-- A3:F 按钮的 parent 必须是 C 菜单本身(不是父目录「漫游」M)。
--     挂错层 = 权限树里勾不到它 = 非超管永远拿不到这个权限。
SELECT f.menu_name AS button, f.parent_id AS f_parent, f.perms AS f_perms,
       c.menu_name AS parent_menu, c.perms AS parent_perms, c.menu_type AS parent_type,
       CASE WHEN c.menu_type = 'C' AND c.perms = 'business:roamAiContent:list' THEN 'OK' ELSE 'WRONG_LAYER' END AS layer_verdict
  FROM sys_menu f JOIN sys_menu c ON f.parent_id = c.menu_id
 WHERE f.menu_type = 'F' AND f.perms LIKE 'business:roamAiContent:%'
 ORDER BY f.perms;

-- A4:perms 不许重复。期望 6 行 cnt 都 = 1。
--    >1 = 本份被改过后重复执行过(NOT EXISTS 只防同名 perms,这条兜底核一次)。
SELECT perms, COUNT(*) AS cnt FROM sys_menu
 WHERE perms IN ('business:roamAiContent:list','business:roamAiContent:query',
                 'business:roamAiContent:add','business:roamAiContent:edit',
                 'business:roamAiContent:audit','business:roamAiContent:remove')
 GROUP BY perms;

-- A5:F 按钮总数 + C 唯一性校验。C 正常时必须有恰好 5 个 F;@menu=NULL 时必须有 0 个。
--    @menu_unique>1 时 @menu=NULL,F INSERT 全被挡 → f_count=0 是期望。
SELECT @menu AS resolved_c_menu_id,
       @menu_unique AS menu_c_count,
       (SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'F' AND perms LIKE 'business:roamAiContent:%') AS f_count,
       CASE WHEN @menu_unique = 0 THEN 'SKIP_no_c'
            WHEN @menu_unique = 1 AND (SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'F' AND perms LIKE 'business:roamAiContent:%') = 5 THEN 'OK'
            WHEN @menu_unique > 1 THEN 'BLOCKED_duplicate_c'
            ELSE 'MISMATCH' END AS f_count_verdict;


-- ======================= 负控:guard 自身校验(行为负控 / 源码负控底稿) =======================
-- 下面的查询不插不删,只读诊断。如果任一返回 FAIL 行,说明 guard 已被破坏或环境异常。

-- NC-0【父级唯一性】:roam 父级重复时 @parent=NULL,所有 INSERT 被挡。
--   期望:normal 库 parent_count=1 → PASS。
--   如果看到 FAIL → migrate 前手工修重复的 sys_menu 行。
SELECT 'NC-0-roam_parent_unique' AS check_name,
       @parent_unique AS parent_count,
       CASE WHEN @parent_unique = 1 THEN 'PASS'
            WHEN @parent_unique = 0 THEN 'FAIL_missing'
            WHEN @parent_unique > 1 THEN 'FAIL_duplicate' END AS verdict;

-- NC-1【C 菜单父级正控】:若 @menu 非空,其 parent 必须逐字等于解析到的 roam 父级 @parent。
--   如果 C 挂在不同父级下,这条会报 FAIL_wrong_parent。
--   期望:@menu IS NULL 时 SKIP(正常 fail-closed);@menu NOT NULL 时 PASS。
SELECT 'NC-1-c_parent_is_roam' AS check_name,
       c.menu_id, c.parent_id AS c_parent, @parent AS expected_parent,
       CASE WHEN @menu IS NULL THEN 'SKIP_no_menu'
            WHEN c.parent_id = @parent THEN 'PASS'
            ELSE 'FAIL_wrong_parent' END AS verdict
  FROM sys_menu c
 WHERE c.perms = 'business:roamAiContent:list' AND c.menu_type = 'C';

-- NC-2【F 按钮一致性】:每个 F 的 parent 必须是 roamAiContent C 菜单,不是 M 目录或其他 C。
--   如果任一 F 挂到漫游 M(2240)下或其他 C 下,对应行的 layer_verdict=FAIL。
--   期望:@menu IS NULL 时 0 行(SKIP);@menu NOT NULL 时每行 verdict=OK。
SELECT 'NC-2-f_parent_is_c' AS check_name,
       f.menu_name, f.perms, c.menu_type AS parent_type, c.perms AS parent_perms,
       CASE WHEN @menu IS NULL THEN 'SKIP_no_menu'
            WHEN c.menu_type = 'C' AND c.perms = 'business:roamAiContent:list' THEN 'OK'
            ELSE 'FAIL_wrong_layer' END AS verdict
  FROM sys_menu f JOIN sys_menu c ON f.parent_id = c.menu_id
 WHERE f.menu_type = 'F' AND f.perms LIKE 'business:roamAiContent:%'
 ORDER BY f.perms;

-- NC-3【同 perms 异父级诊断】:检查是否存在与 roamAiContent:list 同 perms 但
--    parent_id 不等于 @parent 的行(错误父级 C)。存在这种行时,如果 @menu 解析不严格
--    (不限定 parent_id=@parent),LIMIT 1 可能选出错误行来挂 F。
--    期望:0 行(或所有返回行 parent_id = @parent)。如果非 0 且 parent_id ≠ @parent → 串行风险。
SELECT 'NC-3-same_perms_wrong_parent' AS check_name,
       menu_id, parent_id, menu_type, path, component,
       CASE WHEN @parent IS NULL AND @parent_unique = 0 THEN 'SKIP_parent_missing'
            WHEN @parent IS NULL AND @parent_unique > 1 THEN 'SKIP_parent_duplicate'
            WHEN parent_id <> @parent THEN 'RISK_wrong_parent'
            ELSE 'OK' END AS verdict
  FROM sys_menu
 WHERE perms = 'business:roamAiContent:list';

-- NC-4【C 菜单唯一性】:同一正确 parent 下不得出现两条同 perms/type/path/component 的 C。
--   重复时 @menu_unique>1 ⇒ @menu=NULL(因为 AND @menu_unique=1),5 条 F INSERT 全被挡。
--   期望:normal 库 menu_c_count=1 → PASS;>1 → FAIL_duplicate。
SELECT 'NC-4-c_menu_unique' AS check_name,
       @menu_unique AS menu_c_count,
       CASE WHEN @menu_unique = 0 THEN 'SKIP_no_c'
            WHEN @menu_unique = 1 THEN 'PASS'
            WHEN @menu_unique > 1 THEN 'FAIL_duplicate' END AS verdict;
