-- 商家常备权益模板(报告§5.1 · F1-3):商家维护礼品/券/折扣模板,邀约时快速引用
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 影响:新建表,对存量无影响。
-- ⚠️ 部署顺序:先于新 jar 落库。

CREATE TABLE IF NOT EXISTS coop_perk_template (
    id           BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '主键',
    merchant_id  BIGINT(20)    NOT NULL COMMENT '商家memberId(模板归属)',
    perk_type    TINYINT(1)    NOT NULL COMMENT '0礼品 1优惠券 2折扣体验',
    name         VARCHAR(64)   NOT NULL COMMENT '名称',
    unit_cost    DECIMAL(10,2) NULL COMMENT '单人成本价(商家自报)',
    quota        INT           NULL COMMENT '总配额',
    redeem_rule  VARCHAR(255)  NULL COMMENT '核销/使用规则',
    valid_end    DATETIME      NULL COMMENT '有效期截止',
    create_time  DATETIME      NULL,
    del_flag     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0存在 2删除',
    PRIMARY KEY (id),
    KEY idx_perktpl_merchant (merchant_id, del_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家常备权益模板(礼品/券/折扣)';
