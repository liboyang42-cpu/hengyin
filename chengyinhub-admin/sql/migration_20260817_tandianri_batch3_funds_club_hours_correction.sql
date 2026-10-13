-- 探店日第3批 A2 补口：已确认实际工时的**更正链**（append-only 留痕 + 带理由的退回）。
-- 文件名保留 funds 字样：ci/funds_it_gate.py 的 REQUIRED_TRIGGER_PATHS 认
-- chengyinhub-admin/sql/migration_*funds*.sql，改名即资金复验不触发。
--
-- 前置依赖 migration_20260816_tandianri_batch3_funds_club_compensation.sql：本文件把新权限位挂到
--   它种下的同一个父菜单（@cy_hc_parent 与那份迁移的 @cy_comp_parent 同一条解析），
--   并复用它建立的四角色授权基线。
-- 前置依赖 migration_web_admin_roles_20260719.sql：末尾回读断言要 cy_finance 这条 sys_role 行。
--
-- 病：club_edition_hours 上「已确认」是一条死路 ——
--   confirmActualHoursCas 的 where 写死 confirm_status='REPORTED'，
--   reportActualHoursCas 又不收 'CONFIRMED'。平台把 ONSITE 确认成 40（实际 4）之后，
--   两条 CAS 各命中 0 行 ⇒ 该类工时永久锁死在错值，只能改库。
--   上界虽被 payableHours = actualExtra.min(approved) 的事前审批额度兜住（有界超付），
--   但反向确认成偏小是**无补救的少付**，且 reaccrueAfterReversal 救不回来 —— 它按同一套错事实重算。
--   同批的 A1（质量维版本化更正）与 A2（EXTRA append-only 更正链）都有更正路径，唯独工时没有。
--
-- 修法**不是**放宽 confirmActualHoursCas 的旧状态：那会把「一次确认」变成「随便改」。
--   先把被推翻的那条确认事实（值 + 确认人 + 确认时刻）整份快照进本表，再把当前行退回 REPORTED
--   并清空 confirmed_*；重新确认仍然只能走原来那条唯一 CAS。
--   ⇒ 旧事实不被覆盖（它在本表里），写已确认工时的路径仍然只有一条。
-- ⚠️ club_edition_hours 一列不加、一个约束不改（它的四类枚举/状态枚举/非负/CONFIRMED 三字段齐备
--    CHECK 都在 migration_20260815_tandianri_batch3_funds_core.sql 里，本文件只新增一张审计表）。

CREATE TABLE IF NOT EXISTS club_edition_hours_correction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    club_id BIGINT NOT NULL,
    hour_kind VARCHAR(16) NOT NULL,
    previous_confirmed_hours DECIMAL(10,2) NOT NULL COMMENT '被推翻的那条确认事实的工时值',
    previous_confirmed_by BIGINT NOT NULL COMMENT '被推翻的那条确认事实是谁定的',
    previous_confirmed_at DATETIME(3) NOT NULL COMMENT '被推翻的那条确认事实定在哪一刻；同时是退回 CAS 的乐观锁比对值',
    reopened_by BIGINT NOT NULL COMMENT '谁发起这次更正；与 previous_confirmed_by 可以是同一人，但必须留名',
    reason VARCHAR(500) NOT NULL COMMENT '更正理由；空串在 CHECK 层就写不进来',
    reopened_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- ★同一条确认事实只能被推翻一次。没有它，两个并发更正会各写一条审计、各说自己推翻了同一条，
    --   而真正生效的只有一次退回 ⇒ 审计行数与实际更正次数不符（对账时无从判断哪条是真的）。
    UNIQUE KEY uk_club_edition_hours_correction_fact (topic_id, club_id, hour_kind, previous_confirmed_at),
    KEY idx_club_edition_hours_correction_club (club_id, topic_id),
    CONSTRAINT ck_club_edition_hours_correction_kind CHECK (hour_kind IN ('PREP', 'CONTENT', 'ONSITE', 'REVIEW')),
    CONSTRAINT ck_club_edition_hours_correction_nonnegative CHECK (previous_confirmed_hours >= 0),
    -- 理由是这条审计行的全部价值：没有理由的更正等于「有人改过，不知道为什么」。
    CONSTRAINT ck_club_edition_hours_correction_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已确认实际工时的更正留痕（append-only）；旧事实只在这里保存，不在 club_edition_hours 上被覆盖';

-- ★更正需要**独立**权限位，不复用 business:clubhours:confirm。
--   更正推翻的是确认人自己定下的事实，复用同一条权限就等于「谁确认谁能悄悄改」，
--   那正是这条链要避免的形状（与 A1 把 clubquality:exclude 从 clubquality:confirm 里分出来同理）。
SET @cy_hc_parent := (
    SELECT menu_id FROM sys_menu
    WHERE menu_type = 'C' AND (perms = 'business:topic:list' OR component = 'business/topic/index')
    ORDER BY CASE WHEN perms = 'business:topic:list' THEN 0 ELSE 1 END, menu_id
    LIMIT 1
);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '探店日已确认工时更正', @cy_hc_parent, 88, '', '', 1, 0,
       'F', '0', '0', 'business:clubhours:correct', '#', 'admin', NOW(),
       'A2：把已确认工时退回重确认，旧事实留痕在 club_edition_hours_correction；与确认权分开'
FROM dual
WHERE @cy_hc_parent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.perms = 'business:clubhours:correct');

-- 更正权 → cy_finance（财务结算侧，与「实际工时确认」同侧但另一条权限）。
-- 只授这一条 F 权限，不碰 business:topic:edit 之类的宽权限。
SET @cy_hc_role_finance := (SELECT role_id FROM sys_role WHERE role_key = 'cy_finance' AND del_flag = '0' LIMIT 1);
SET @cy_hc_perm_correct := (SELECT menu_id FROM sys_menu WHERE perms = 'business:clubhours:correct' LIMIT 1);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @cy_hc_role_finance, @cy_hc_perm_correct FROM dual
WHERE @cy_hc_role_finance IS NOT NULL AND @cy_hc_perm_correct IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu
                  WHERE role_id = @cy_hc_role_finance AND menu_id = @cy_hc_perm_correct);

-- 回读合同：表 + 四条约束 + 权限位 + 授权行，执行后必须从对面读回为 1。
-- 0 表示这份迁移没生效或形状不对 —— 那就等于「更正链有代码、没落点」，工时仍然锁死在错值上。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'club_edition_hours_correction') = 1
   AND (SELECT COUNT(*) FROM information_schema.table_constraints
        WHERE table_schema = DATABASE() AND table_name = 'club_edition_hours_correction'
          AND constraint_name IN ('ck_club_edition_hours_correction_kind',
                                  'ck_club_edition_hours_correction_nonnegative',
                                  'ck_club_edition_hours_correction_reason')) = 3
   AND (SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'club_edition_hours_correction'
          AND index_name = 'uk_club_edition_hours_correction_fact') = 4;

-- 回读合同：权限位真的种下了，且真的授到了 cy_finance。
-- 只种菜单不授权 = 今天只有超管点得动更正，而「后台只用超管单账号」正是这批要显式暴露的降级项。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_menu
        WHERE perms = 'business:clubhours:correct' AND menu_type = 'F' AND status = '0'
          AND parent_id = @cy_hc_parent) = 1
   AND (SELECT COUNT(*) FROM sys_role_menu
        WHERE role_id = @cy_hc_role_finance AND menu_id = @cy_hc_perm_correct) = 1;
