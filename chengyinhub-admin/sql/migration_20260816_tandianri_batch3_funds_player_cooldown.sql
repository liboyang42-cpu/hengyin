-- 探店日第3批 A3：PLAYER 投诉补偿提现冷静期（48 小时）。
-- 文件名保留 funds 字样：ci/funds_it_gate.py 的 REQUIRED_TRIGGER_PATHS 认
-- chengyinhub-admin/sql/migration_*funds*.sql，改名即资金复验不触发。
--
-- ⚠️ 既有 migration_20260808_player_withdrawable.sql 只读不改；本文件只新增。
-- 前置依赖 migration_web_admin_roles_20260719.sql：本文件按 role_key 取 cy_finance 授权冷静期
--   相关菜单，角色行由那份迁移创建。

CREATE TABLE IF NOT EXISTS player_withdrawal_cooldown (
    id BIGINT NOT NULL AUTO_INCREMENT,
    -- ⚠️ 起点<b>不是</b>这条明细的 create_time：那一列由 Java 的 DateUtils.getNowDate() 写，
    --    与求和时用的 CURRENT_TIMESTAMP(3) 是两个时钟。起点与到期都由 SQL 取库时钟，
    --    见 PlayerWithdrawalCooldownMapper.xml 的 insert…select。
    balance_detail_id BIGINT NOT NULL COMMENT '正向投诉补偿那条余额明细的 id；只做去重锚点，不提供时间',
    member_id BIGINT NOT NULL,
    complaint_id BIGINT NOT NULL,
    amount DECIMAL(14,2) NOT NULL,
    policy_key VARCHAR(96) NOT NULL,
    cooldown_hours INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    cooldown_until DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    -- ★一条明细只能产生一个冷静期：并发/重放 advance 撞这把唯一键。
    UNIQUE KEY uk_player_cooldown_detail (balance_detail_id),
    -- ★一张工单只能产生一笔正向补偿冷静期：把「同一投诉重放成第二笔」也堵死。
    UNIQUE KEY uk_player_cooldown_complaint (complaint_id),
    KEY idx_player_cooldown_member_until (member_id, cooldown_until),
    CONSTRAINT ck_player_cooldown_amount CHECK (amount > 0),
    CONSTRAINT ck_player_cooldown_hours CHECK (cooldown_hours > 0),
    CONSTRAINT ck_player_cooldown_window CHECK (cooldown_until > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PLAYER 投诉补偿提现冷静期；append-only，逐笔独立计时';

-- 冷静期时长配置：seed 48，只影响新事件；改它不回写任何既有 cooldown_until。
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '玩家投诉补偿提现冷静期(小时)', 'withdrawal.player-complaint-cooldown-hours', '48', 'N', 'admin', NOW(),
       '探店日A3：每笔正向投诉补偿到账后冻结的小时数；变更只影响新事件，既有 cooldown_until 是快照'
FROM dual
WHERE NOT EXISTS (
    SELECT 1 FROM sys_config WHERE config_key = 'withdrawal.player-complaint-cooldown-hours'
);

-- ★冷却配置「不是普通编辑接口」（审计 2 §「权限、审计和失败语义」开头那一句）。
--
--   历史：本文件曾经种过一条 business:withdrawal:cooldown-config，remark 写着「变更冷静期小时数
--   需本权限 + @Log 留前值/后值/操作者/理由」，而全仓<b>没有任何</b> @PreAuthorize 或按钮引用它 ——
--   真正能把 48 改成 1 的路径是 RuoYi 通用参数页（system:config:edit），与它毫无关系，
--   而且那条路只写新值、不留前值，「理由」这一项通用参数页本来就不采集。
--   那是一颗恒不生效的功能位却被写成了闸，所以上一版把它连同假 remark 一起删了
--   （裁决表 §7.1 E3：宁可明确没有，不要假装有），并如实登记「专用改写端点是一条没做的待办」。
--
--   现在补的就是当时登记为「没做」的那一半：**专用改写端点真的建起来了** ——
--   PlayerWithdrawalCooldownController#rewrite → IPlayerWithdrawalCooldownService#rewriteCooldownHours，
--   前值/后值/理由/操作人写进下面这张审计表，审计写不进去就整笔回滚、配置一个字都不动。
--   ⇒ 这条权限现在有真实消费方，重新种进来的是<b>事实</b>，不再是恒不生效的功能位。

CREATE TABLE IF NOT EXISTS player_withdrawal_cooldown_config_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    config_key VARCHAR(96) NOT NULL,
    before_hours INT NOT NULL,
    after_hours INT NOT NULL,
    -- ★理由必须落列，不指望 @Log：本仓 LogAspect 已加固到不写请求体
    --   （setOperParam/setJsonResult 全仓 0 命中），sys_oper_log 只有 title/method/url/操作人/耗时。
    reason VARCHAR(500) NOT NULL,
    changed_by BIGINT NOT NULL,
    changed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_cooldown_config_audit_time (config_key, changed_at),
    CONSTRAINT ck_cooldown_config_audit_hours CHECK (after_hours > 0 AND before_hours > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家投诉补偿提现冷静期时长改写审计；append-only，前值/后值/理由/操作人齐全';

-- 专用改写权限。挂在会员提现菜单下的功能位；找不到父菜单就不种（宁可没有，也不裸挂）。
SET @cy_cooldown_parent := (
    SELECT menu_id FROM sys_menu
    WHERE menu_type = 'C' AND perms IN ('business:memberwithdrawal:list', 'business:cashwithdraw:list')
    ORDER BY CASE WHEN perms = 'business:memberwithdrawal:list' THEN 0 ELSE 1 END, menu_id
    LIMIT 1
);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '玩家投诉补偿冷静期改写', @cy_cooldown_parent, 95, '', '', 1, 0,
       'F', '0', '0', 'business:withdrawal:cooldown-config', '#', 'admin', NOW(),
       '探店日A3：改冷静期小时数的专用入口（PlayerWithdrawalCooldownController#rewrite）；前值/后值/理由落 player_withdrawal_cooldown_config_audit，只影响新事件'
FROM dual
WHERE @cy_cooldown_parent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:withdrawal:cooldown-config');

-- 授给财务结算：这是资金政策参数，与通用参数页的 system:config:edit 分开。
-- 只种菜单不授权 = 又一颗谁也拿不到的功能位，所以授权与回读一起做。
SET @cy_cooldown_role := (SELECT role_id FROM sys_role WHERE role_key = 'cy_finance' AND del_flag = '0' LIMIT 1);
SET @cy_perm_cooldown := (SELECT menu_id FROM sys_menu WHERE perms = 'business:withdrawal:cooldown-config' LIMIT 1);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @cy_cooldown_role, @cy_perm_cooldown FROM dual
WHERE @cy_cooldown_role IS NOT NULL AND @cy_perm_cooldown IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu
                  WHERE role_id = @cy_cooldown_role AND menu_id = @cy_perm_cooldown);

-- 回读合同：执行后必须从对面读回为 1。
--   ①策略值真的落库；②审计表真的建出来了；③专用权限在；④它真的授到了角色上。缺一即红。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_config
        WHERE config_key = 'withdrawal.player-complaint-cooldown-hours' AND config_value = '48') = 1
   AND (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'player_withdrawal_cooldown_config_audit') = 1
   AND (SELECT COUNT(*) FROM sys_menu
        WHERE perms = 'business:withdrawal:cooldown-config' AND menu_type = 'F' AND status = '0') = 1
   AND (SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id
        WHERE r.role_key = 'cy_finance' AND rm.menu_id = @cy_perm_cooldown) = 1;
