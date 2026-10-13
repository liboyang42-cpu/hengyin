-- 合作条款三表(报告§7.2/7.3/7.5 · D-3/D-4/D-5):权益申报 / 合作单快照 / 信用记录
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 影响:新建三表,对存量无影响。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库(CoopOrder/CoopCreditLog Mapper 引用 coop_order/coop_credit_log)。
--    coop_perk 本批只铺表,Java 侧暂无读写(权益申报待前端 F1-4),不影响 jar 启动。

-- D-3 权益申报(礼品/优惠券/折扣体验),邀约时被邀商家申报,定价引擎输入
CREATE TABLE IF NOT EXISTS coop_perk (
    id          BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '主键',
    invite_id   BIGINT(20)    NOT NULL COMMENT '所属邀约',
    perk_type   TINYINT(1)    NOT NULL COMMENT '0礼品 1优惠券 2折扣体验',
    name        VARCHAR(64)   NULL COMMENT '名称',
    unit_cost   DECIMAL(10,2) NULL COMMENT '单人成本价(商家自报)',
    quota       INT           NULL COMMENT '总配额',
    redeem_rule VARCHAR(255)  NULL COMMENT '核销规则',
    valid_end   DATETIME      NULL COMMENT '有效期截止',
    create_time DATETIME      NULL,
    del_flag    TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0存在 2删除',
    PRIMARY KEY (id),
    KEY idx_perk_invite (invite_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='协作权益申报(礼品/券/折扣)';

-- D-4 合作单=条款快照,同意瞬间冻结,后续结算只认合作单
CREATE TABLE IF NOT EXISTS coop_order (
    id             BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '主键',
    invite_id      BIGINT(20)    NULL COMMENT '来源邀约(邀约侧候选;报名侧为空)',
    reg_id         BIGINT(20)    NULL COMMENT '来源报名(候选池报名侧;邀约侧为空)',
    topic_id       BIGINT(20)    NULL COMMENT '主题',
    game_id        BIGINT(20)    NULL COMMENT '城市定向:游戏',
    from_id        BIGINT(20)    NULL COMMENT '发起方memberId',
    to_id          BIGINT(20)    NULL COMMENT '受邀方(merchant=memberId/club=clubId)',
    to_type        VARCHAR(16)   NULL COMMENT '受邀方类型 merchant/club',
    share_mode     TINYINT(1)    NULL COMMENT '0引流 1分成 2固定',
    share_rate     DECIMAL(5,2)  NULL COMMENT '分成比例',
    fixed_fee      DECIMAL(10,2) NULL COMMENT '固定型每核销人头费',
    perks_snapshot JSON          NULL COMMENT '同意瞬间的权益快照',
    terms_version  VARCHAR(16)   NULL COMMENT '平台规则版本号',
    status         TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0生效 1作废 2已结算',
    create_time    DATETIME      NULL,
    del_flag       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0存在 2删除',
    PRIMARY KEY (id),
    KEY idx_order_invite (invite_id),
    KEY idx_order_reg (reg_id),
    KEY idx_order_topic (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合作单(条款快照)';

-- D-5 信用记录:履约/超时/违约,主页展示近90天履约率,P3 与槽位配额挂钩
CREATE TABLE IF NOT EXISTS coop_credit_log (
    id          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    member_id   BIGINT(20)   NOT NULL COMMENT '被记账方memberId',
    event_type  TINYINT(1)   NOT NULL COMMENT '0履约 1超时未配置 2保护期外取消 3保护期内违约',
    invite_id   BIGINT(20)   NULL COMMENT '关联邀约',
    reason      VARCHAR(255) NULL COMMENT '理由/备注',
    create_time DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_credit_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='协作信用记录';
