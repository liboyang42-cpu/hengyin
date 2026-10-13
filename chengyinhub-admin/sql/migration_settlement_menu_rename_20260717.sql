-- P0-1 结算菜单正名:「结算打款」→「结算对账确认」(纯显示名,不动 perms 码)
--
-- 背景:
--   perms='business:settlement:confirm' 这个按钮级权限(menu_type='F')的 menu_name 至今仍叫
--   「结算打款」,remark 还写着 'settlement payout confirm permission'。
--   但 PR #78 已把系统2 的打款能力(SettlementServiceImpl 的 addBalance + payout)整个拆除
--   ⇒ 该按钮今天只做 confirmBatch = 把批次与明细置为已结算,**零打款、不动任何余额**。
--   ⇒ 管理员在 RuoYi 角色授权树上看到「结算打款」,会以为自己在授予一项打款权限。
--
--   这是同一句谎话的第三个副本,前两个已在同 PR 修掉:
--     ① 后端 @Log(title) —— #78 已从「商家结算打款」改为「商家结算对账确认」
--     ② 后台 UI —— 本 PR 已把按钮「确认打款」→「确认对账」,并删掉弹窗里
--        「打款后将把结算金额计入各收款方余额」这句假承诺
--     ③ 本文件 —— sys_menu 显示名,库里的最后一份
--
--   ⚠️ perms 码本身('business:settlement:confirm')是**准确的**,不要动它:
--      改 perms 会让 @PreAuthorize / v-hasPermi 全部失配,已授权的角色当场失去该按钮。
--      本迁移只改 menu_name + remark 两个纯展示字段。
--
-- 幂等:按 perms 定位 UPDATE,重复执行第二次改 0 行(值已相同),无副作用。
-- 可逆:见文件末尾【回滚】段。按 perms 定位,不按 menu_name 谓词
--       (若按 menu_name='结算对账确认' 回滚,会误伤日后任何同名菜单)。
-- 风险:极低。只动显示文案,不影响鉴权、不影响任何余额/账本数据。
-- 顺序:与 jar 部署**无先后依赖**(纯展示字段,新旧 jar 都不读 menu_name 做逻辑)。
--       但按仓库铁律 ③ 仍应在合并前落库,避免合了忘跑。

-- ───────────────────────────────────────────────────────────────
-- 【只读预检】⚠️ 别读下面这段的期望值当结论 —— 它是写文件那一刻的快照。
--    落库前**当场重跑**,拿真实输出对照(2026-07-16 就因为照抄快照差点吃一个 1050)。
--
--   SELECT menu_id, menu_name, menu_type, perms, remark
--   FROM sys_menu WHERE perms = 'business:settlement:confirm';
--
--   写文件时的仓库真源(migration_p3_phase_c_crm_finance.sql:106 / _deploy_ALL_20260704.sql:540):
--     menu_name='结算打款' · menu_type='F' · remark='P3 Phase C settlement payout confirm permission'
--   期望:恰好 1 行。
--     · 0 行 ⇒ 该菜单从没落过库 —— **先查清楚为什么**(可能生产用的是另一套菜单),别直接跑本迁移
--       (UPDATE 影响 0 行会静默"成功",什么都没发生却看着像跑过了)。
--     · >1 行 ⇒ 重复注册,先人工去重再跑。
-- ───────────────────────────────────────────────────────────────

UPDATE sys_menu
SET menu_name = '结算对账确认',
    remark    = '结算批次对账确认(仅置账本状态,不打款;打款由系统1 settleActivity/settleTopic 独占)',
    update_by = 'system',
    update_time = NOW()
WHERE perms = 'business:settlement:confirm';

-- ───────────────────────────────────────────────────────────────
-- 【回读断言】跑完必须当场执行,拿到 1 行才算数。
--   这条断言能红:UPDATE 没生效 / 命中 0 行时,它返 0 行。
--   (刻意把 menu_name 写进 WHERE 而不是只 select 出来看 —— 只 select 的话,
--    肉眼扫过去很容易把旧值当新值,和 2026-07-16 那条「只查 scope_type=3 恒返 1 行」的假绿同型。)
--
--   SELECT menu_id, menu_name, perms FROM sys_menu
--   WHERE perms = 'business:settlement:confirm' AND menu_name = '结算对账确认';
--   期望:1 行。0 行 = 没跑成,别当成功。
-- ───────────────────────────────────────────────────────────────

-- ───────────────────────────────────────────────────────────────
-- 【回滚】
--   UPDATE sys_menu
--   SET menu_name = '结算打款',
--       remark    = 'P3 Phase C settlement payout confirm permission'
--   WHERE perms = 'business:settlement:confirm';
--
--   ⚠️ 回滚只是把谎话放回去 —— 除非同时回滚 #78(把系统2 的打款能力接回来),
--      否则「结算打款」这个名字在任何情况下都是错的。
-- ───────────────────────────────────────────────────────────────

-- ⚠️ 留给后人:两份**历史**种子文件里的 INSERT 仍写着 '结算打款'
--    (migration_p3_phase_c_crm_finance.sql:106 · _deploy_ALL_20260704.sql:540)。
--    刻意不改它们 —— 已跑过的迁移是「当时跑了什么」的记录,改它等于篡改历史。
--    两者都是 `WHERE NOT EXISTS (... perms='business:settlement:confirm')` 的插入,
--    对存量库是 no-op;全新库按序跑到本文件时会被正名。
--    ⇒ 但若日后有人**只**跑 _deploy_ALL 快照建库,菜单名会退回「结算打款」。
