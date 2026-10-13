-- 商家三档承接审核台:注册后台菜单与权限(sys_menu)
--
-- 【类别】③ 注册数据(要在库上跑)。不是 DDL,但**不跑 = 这个页面对非超管根本不存在**
--   —— 菜单不出现、接口 403。超管有 *:*:* 恒能看见,所以**验收必须用非超管账号**。
--
-- 【背景】merchant_chapter_offer 自 enroll 落库即生效,此前后台零可见零写入;本页是
--   《后台主题运营控制台_领域设计与工作台方案_20260718》§4.2 的商家承接审核台:
--   队列按主题→章节分组 + 定档定参(计酬校 max_per_head_fee / 权益校门槛与归属 / 二清边界)+ 停用。
--
-- 【落库时机】**必须在 jar + chengyinhub-ui 都上线之后**,否则运营点到一个后端还不认、
--   前端还没有的菜单。(与 DDL 迁移「先于 jar」的时机相反 —— 本份没有 DDL。)
--   跑本份前扫一眼 ui 部署:gh run list --workflow=deploy-ui.yml
--
-- 【幂等】按 perms 去重(NOT EXISTS),照抄 migration_revshare_payable_menu_20260717.sql 的范式。
--
-- 【可逆】
--   DELETE FROM sys_menu WHERE perms IN ('business:chapterOffer:list','business:chapterOffer:edit');
--
-- 【风险】低。只往 sys_menu 插 1 个 C 菜单 + 1 个 F 按钮,不动既有菜单与目录结构。
--   ⚠️ 生产的 offer 行今天可能寥寥无几,页面偏空是对的,不是坏了。


-- ======================= 第一步:只读预检 =======================
--
-- P1【必看】是否已注册过:
--   SELECT menu_id, menu_name, menu_type, perms FROM sys_menu
--    WHERE perms LIKE 'business:chapterOffer:%';
--   · 0 行 → 正常,全执行。
--   · 有行 → 已跑过,下面的 NOT EXISTS 会跳过,无害。
--
-- P2【父级解析】九域「商家与合作」目录必须找得到:
--   SELECT menu_id, menu_name FROM sys_menu WHERE path = 'merchants-coop' AND parent_id = 0;
--   · 0 行 → **停**。说明九域导航(migration_web_admin_nine_domains_20260719.sql)还没落库,
--     下面的 @parent 会是 NULL,整份静默插 0 行(NOT EXISTS + @parent IS NOT NULL 双条件)
--     ⇒ 你会以为跑成功了,其实什么都没发生。先落九域那份,再回来跑本份;A1 回读断言必跑。


-- ======================= 第二步:注册 =======================

-- ① 父目录动态解析(勿硬编码 menu_id)。挂九域「商家与合作」:
--    registrationmerchant(报名/调配)全在这个目录下,商家承接审核是同一摊事,运营找得到。
--    按 path+parent_id=0 找(九域迁移就是这么建的,目录无 component 可锚)。
SET @parent := (SELECT menu_id FROM sys_menu WHERE path = 'merchants-coop' AND parent_id = 0 LIMIT 1);

-- ② C 菜单
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '三档承接审核', @parent, 8, 'chapteroffer', 'business/chapteroffer/index', 1, 0, 'C', '0', '0', 'business:chapterOffer:list', 'merchant', 'admin', NOW(),
       '商家×章节供给(merchant_chapter_offer)审核台:主题→章节分组,看档位/条件/quota,定档定参走后端三档闸'
FROM dual
WHERE @parent IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:chapterOffer:list');

SET @menu := (SELECT menu_id FROM sys_menu WHERE perms = 'business:chapterOffer:list' LIMIT 1);

-- ③ F 按钮:定档定参 / 停用 —— 共用一个 edit 权限。
--    ⚠️ 不给每个动作单独立权限点:多一个没人配的权限点等于那个按钮没人能用。
--    ⚠️ 字符串必须与 MerchantChapterOfferController 的 @PreAuthorize 逐字一致,对不上 = 点了 403。
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '三档承接操作', @menu, 1, '', '', 1, 0, 'F', '0', '0', 'business:chapterOffer:edit', '#', 'admin', NOW(),
       '定档定参(对齐章节口径+跑三档闸)/停用供给。闸全在 service 层,按钮只是入口'
FROM dual
WHERE @menu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:chapterOffer:edit');


-- ======================= 回读断言(必跑,别只看 "Query OK") =======================

-- A1:**必须看到 2 行**(1 个 C + 1 个 F)。0 行 = @parent 解析不到 ⇒ 整份静默什么都没做。
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu
 WHERE perms LIKE 'business:chapterOffer:%'
 ORDER BY menu_type DESC;

-- A2:F 按钮的 parent 必须是上面那个 C 菜单本身(不是父目录)。
--     挂错层 = 权限树里勾不到它 = 非超管永远拿不到这个权限。
SELECT f.menu_name AS button, f.parent_id, c.menu_name AS parent_menu, c.perms AS parent_perms
  FROM sys_menu f JOIN sys_menu c ON f.parent_id = c.menu_id
 WHERE f.perms = 'business:chapterOffer:edit';

-- A3【验收提醒,不是 SQL】:注册完必须**用非超管账号**在角色里勾上这两个权限、再登进去点一遍。
--    超管的 *:*:* 恒能看见一切 ⇒ 用超管验收等于没验收。
