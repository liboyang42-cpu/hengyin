-- 探店日第3批 A4：俱乐部对公收款人最小资料 + 开售快照。
-- 文件名保留 funds 字样：ci/funds_it_gate.py 的 REQUIRED_TRIGGER_PATHS 认
-- chengyinhub-admin/sql/migration_*funds*.sql，改名即资金复验不触发。
--
-- 敏感列只存 v1: 密文（PiiCryptoTypeHandler），统一社会信用代码另建 blind index 列做精确检索。
-- 表结构本身不认明文，是否明文由应用侧 handler 保证；ClubCorporatePayeeMybatisIntegrationTest 直查本表回读证明。
-- 前置依赖 migration_web_admin_roles_20260719.sql：下面按 role_key 取 cy_finance / cy_operator
--   做审核与受控解密的互斥分派，角色行由那份迁移创建；缺它则 @cy_payee_role_* 取到 NULL，
--   授权落空而末尾回读断言变红。

CREATE TABLE IF NOT EXISTS club_corporate_payee_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    club_id BIGINT NOT NULL,
    profile_version BIGINT NOT NULL,
    is_current TINYINT NOT NULL,
    legal_name VARCHAR(512) NOT NULL COMMENT '企业法定名称（密文）',
    unified_social_credit_code VARCHAR(512) NOT NULL COMMENT '统一社会信用代码（密文）',
    credit_code_index CHAR(64) NOT NULL COMMENT '信用代码 blind index（HMAC-SHA256 十六进制）',
    license_object_key VARCHAR(512) NOT NULL COMMENT '私有对象存储中的营业执照 key，不是公网 URL',
    license_hash CHAR(64) NOT NULL COMMENT '执照对象内容 hash；与观测值不符即拒绝审核/开售/FINAL',
    bank_account_name VARCHAR(512) NOT NULL COMMENT '银行账户名（密文），必须等于企业法定名称',
    bank_name VARCHAR(255) NOT NULL,
    corporate_bank_account_no VARCHAR(512) NOT NULL COMMENT '对公银行账号（密文）',
    invoice_type VARCHAR(32) NOT NULL,
    tax_no VARCHAR(512) NOT NULL COMMENT '税号（密文），默认等于统一社会信用代码',
    finance_contact_name VARCHAR(512) NOT NULL COMMENT '财务联系人姓名（密文）',
    finance_contact_phone VARCHAR(512) NOT NULL COMMENT '财务联系人手机号（密文）',
    facts_hash CHAR(64) NOT NULL COMMENT '九组关键事实指纹；开售快照与 FINAL 靠它证明历史未被改写',
    verify_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    verified_by BIGINT NULL,
    verified_at DATETIME(3) NULL,
    verify_result VARCHAR(32) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_club_payee_version (club_id, profile_version),
    KEY idx_club_payee_credit_index (credit_code_index),
    CONSTRAINT ck_club_payee_verify_status CHECK (verify_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    CONSTRAINT ck_club_payee_current CHECK (is_current IN (0, 1)),
    CONSTRAINT ck_club_payee_verified_audit CHECK
        ((verify_status = 'PENDING' AND verified_by IS NULL AND verified_at IS NULL)
         OR (verify_status <> 'PENDING' AND verified_by IS NOT NULL AND verified_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部对公收款人最小资料（版本化，敏感列密文）';

-- 每个俱乐部最多一个 current 版本。MySQL 8 无部分索引，沿用 topic_edition_cost 的既有写法：
-- 生成列对 current 行取常量、对历史行取 NULL（唯一索引里 NULL 可重复）⇒ 并发建版本只有一个能落到 current。
SET @payee_current_guard_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'club_corporate_payee_profile'
      AND column_name = 'current_guard'
);
SET @payee_current_guard_sql := IF(
    @payee_current_guard_exists = 0,
    'ALTER TABLE club_corporate_payee_profile ADD COLUMN current_guard TINYINT GENERATED ALWAYS AS (CASE WHEN is_current = 1 THEN 1 ELSE NULL END) STORED, ADD UNIQUE KEY uk_club_payee_single_current (club_id, current_guard)',
    'SELECT 1'
);
PREPARE payee_current_guard_stmt FROM @payee_current_guard_sql;
EXECUTE payee_current_guard_stmt;
DEALLOCATE PREPARE payee_current_guard_stmt;

CREATE TABLE IF NOT EXISTS topic_sale_payee_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    club_id BIGINT NOT NULL,
    profile_id BIGINT NOT NULL,
    profile_version BIGINT NOT NULL,
    facts_hash CHAR(64) NOT NULL,
    license_hash CHAR(64) NOT NULL,
    frozen_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_sale_payee (topic_id, club_id),
    KEY idx_topic_sale_payee_profile (profile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开售冻结的对公收款人事实；写入后不得更新，FINAL 只读它';

-- 权限种子：写资料 / 财务一次审核 / 受控解密三者分离，挂在业务菜单下的功能位。
SET @cy_payee_parent := (
    SELECT menu_id FROM sys_menu
    WHERE menu_type = 'C' AND (perms = 'business:club:list' OR component = 'business/club/index')
    ORDER BY CASE WHEN perms = 'business:club:list' THEN 0 ELSE 1 END, menu_id
    LIMIT 1
);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '俱乐部对公收款人审核', @cy_payee_parent, 91, '', '', 1, 0,
       'F', '0', '0', 'business:clubpayee:verify', '#', 'admin', NOW(),
       -- ⚠️ remark 不得再写「不得由俱乐部写资料者兼任」：service 侧<b>刻意没有</b> createdBy != verifier
       --    这条两人制比较（ClubCorporatePayeeServiceImpl 有显式理由：后台单超管账号下加了就永不过审）。
       --    E3「宁可明确没有，不要假装有」——写成有闸的措辞就是那个「让人以为有闸」的东西。
       '探店日A4：财务一次审核；不设 maker/checker，理由见 ClubCorporatePayeeServiceImpl.verify；俱乐部侧不得自审由 app 侧零 payee 入口保证'
FROM dual
WHERE @cy_payee_parent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:clubpayee:verify');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '俱乐部对公收款人付款解密', @cy_payee_parent, 92, '', '', 1, 0,
       'F', '0', '0', 'business:clubpayee:payout', '#', 'admin', NOW(),
       '探店日A4：受控完整解密，仅付款登记环节使用；与审核权限互斥分派（见本文件末的角色分派与回读）'
FROM dual
WHERE @cy_payee_parent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:clubpayee:payout');

-- ★把上面那句「与审核权限互斥分派」<b>做成事实</b>，别让它停在 remark 里。
--   remark 会永久留在生产库的表结构里，比源码注释更难被后来的人证伪 —— 声称一条不存在的
--   访问控制事实，正是裁决表 §7.1 E3 点名的「让人以为有闸」。
--   分派方向与 club_compensation 那两条一致：审核 → 财务结算(cy_finance)，
--   受控解密 → 日常运营(cy_operator，也就是做付款登记的那一侧，「仅付款登记环节使用」)。
--   ⚠️ 只解决「权限落在两个不同角色上」；「至少存在两个非超管 sys_user 分别持这两个角色」
--      是部署侧的事，本迁移不造账号（不虚构 ID）。
SET @cy_payee_role_finance := (SELECT role_id FROM sys_role WHERE role_key = 'cy_finance' AND del_flag = '0' LIMIT 1);
SET @cy_payee_role_operator := (SELECT role_id FROM sys_role WHERE role_key = 'cy_operator' AND del_flag = '0' LIMIT 1);
SET @cy_perm_payee_verify := (SELECT menu_id FROM sys_menu WHERE perms = 'business:clubpayee:verify' LIMIT 1);
SET @cy_perm_payee_payout := (SELECT menu_id FROM sys_menu WHERE perms = 'business:clubpayee:payout' LIMIT 1);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @cy_payee_role_finance, @cy_perm_payee_verify FROM dual
WHERE @cy_payee_role_finance IS NOT NULL AND @cy_perm_payee_verify IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu
                  WHERE role_id = @cy_payee_role_finance AND menu_id = @cy_perm_payee_verify);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @cy_payee_role_operator, @cy_perm_payee_payout FROM dual
WHERE @cy_payee_role_operator IS NOT NULL AND @cy_perm_payee_payout IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu
                  WHERE role_id = @cy_payee_role_operator AND menu_id = @cy_perm_payee_payout);

-- 两头不得落在同一个角色上。窄范围删除：两个变量都命中才删，任一为 NULL 时 `role_id = NULL`
-- 恒不成立 ⇒ 删 0 行，不会扩大到别的授权。
DELETE FROM sys_role_menu WHERE role_id = @cy_payee_role_operator AND menu_id = @cy_perm_payee_verify;
DELETE FROM sys_role_menu WHERE role_id = @cy_payee_role_finance AND menu_id = @cy_perm_payee_payout;

-- 回读合同：执行后必须从对面读回为 1。0 表示父菜单没找到或权限行形状不对，视为迁移未生效。
-- runner: assert=scalar-true
SELECT COUNT(*) = 2
FROM sys_menu
WHERE perms IN ('business:clubpayee:verify', 'business:clubpayee:payout')
  AND menu_type = 'F' AND status = '0' AND parent_id = @cy_payee_parent;

-- 回读合同：审核与受控解密必须各落一个角色、且两头不同角色。
-- 0 表示四角色基线不在这个库上，或授权/互斥没落地 —— 那 remark 里那句「互斥分派」就又是假的了。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_role_menu
        WHERE role_id = @cy_payee_role_finance AND menu_id = @cy_perm_payee_verify) = 1
   AND (SELECT COUNT(*) FROM sys_role_menu
        WHERE role_id = @cy_payee_role_operator AND menu_id = @cy_perm_payee_payout) = 1
   AND (SELECT COUNT(*) FROM sys_role_menu
        WHERE menu_id = @cy_perm_payee_payout
          AND role_id IN (SELECT role_id FROM sys_role_menu a
                          WHERE a.menu_id = @cy_perm_payee_verify)) = 0;
