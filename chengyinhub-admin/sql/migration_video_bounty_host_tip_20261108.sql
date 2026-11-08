-- 视频内容悬赏 + 主持小费（M6）。只新增 6 张表，不改任何既有表、不加任何余额/费率/支付状态列。
--
-- Source-only ordered migration。不执行生产 DDL；须走 chengyin-db-migrate 只读预检并由 root 决定落库时点。
-- 依赖：无（未引用既有业务表；topic_id 只做本体域内引用，不加外键，避免与 cms_topic 生命周期耦合）。
--
-- 设计边界（写清楚，避免后来者误读成本域已可付钱）：
--   1. video_bounty_accrual.payout_state 默认 0=RECORDED_ONLY。本域不做现金兑付：
--      外部平台自然播放只能由可信外部证据或人工核验录入，没有通道就不得打款。
--   2. host_tip_intent 不写余额、不写分账。payment_channel 为空 = 未启用，
--      调用方得到 CHANNEL_NOT_ENABLED，不伪造收款。
--   3. 费率/封顶/窗口是活动配置列，不写进任何既有费率或报价地板。
--
-- runner: assert=scalar-true
SELECT CAST(IF(DATABASE() IS NOT NULL
  AND CAST(SUBSTRING_INDEX(VERSION(),'.',1) AS UNSIGNED)=8
  AND (CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'.',2),'.',-1) AS UNSIGNED)>0
    OR CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'-',1),'.',-1) AS UNSIGNED)>=16)
  AND (FIND_IN_SET('STRICT_TRANS_TABLES',@@sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@sql_mode)>0)
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('video_bounty_campaign','video_bounty_submission','video_bounty_evidence',
                       'video_bounty_accrual','host_tip_config','host_tip_intent'))=0,
  1,JSON_EXTRACT('ASSERT_FAILED_video_bounty_host_tip_preflight','$')) AS UNSIGNED) AS assertion_ok;

-- ---------------------------------------------------------------------------
-- 1) 悬赏活动配置
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS video_bounty_campaign (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    campaign_key VARCHAR(40) NOT NULL COMMENT '活动标识,与推广凭据 campaignKey 对齐',
    title VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=草稿 1=进行中 2=暂停 3=关闭',
    owner_member_id BIGINT NULL COMMENT '活动归属的创作者会员 id;成交归因只按它聚合。NULL=未指定,读面显示未配置而不是 0 成交',
    currency CHAR(3) NOT NULL COMMENT 'ISO-4217;只记录口径,不做汇率换算',
    rate_per_thousand DECIMAL(12,4) NOT NULL COMMENT '每千次有效自然播放的应计金额(规则快照,非线上费率)',
    per_video_cap DECIMAL(12,2) NOT NULL COMMENT '同一原视频跨平台合计封顶',
    window_days SMALLINT NOT NULL COMMENT '发布后计入应计的天数窗口',
    total_budget_cap DECIMAL(14,2) NULL COMMENT '活动应计预算上限;NULL=未设上限(不代表已批准支出)',
    cash_payout_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '0=现金兑付未启用(默认) 1=仅配置标记,仍需独立兑付集成',
    rule_version VARCHAR(24) NOT NULL COMMENT '规则版本;改费率/封顶/窗口必须换版本',
    config_json TEXT NULL COMMENT '平台白名单/去重策略等附加配置',
    del_flag CHAR(1) NOT NULL DEFAULT '0',
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_key (campaign_key),
    KEY idx_campaign_status (status, id),
    CONSTRAINT ck_campaign_status CHECK (status IN (0,1,2,3)),
    CONSTRAINT ck_campaign_rate CHECK (rate_per_thousand >= 0),
    CONSTRAINT ck_campaign_cap CHECK (per_video_cap >= 0),
    CONSTRAINT ck_campaign_window CHECK (window_days > 0 AND window_days <= 365),
    CONSTRAINT ck_campaign_payout_flag CHECK (cash_payout_enabled IN (0,1)),
    CONSTRAINT ck_campaign_budget CHECK (total_budget_cap IS NULL OR total_budget_cap >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频内容悬赏活动配置:只有规则与预算,没有任何支付或余额字段';

-- ---------------------------------------------------------------------------
-- 2) 投稿(作者作品归属 + 去重)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS video_bounty_submission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    campaign_id BIGINT UNSIGNED NOT NULL,
    campaign_key VARCHAR(40) NOT NULL COMMENT '冗余,便于按活动过滤且防止活动键变更后失联',
    member_id BIGINT NOT NULL COMMENT '投稿作者;申诉/取消校验此归属',
    topic_id BIGINT NOT NULL COMMENT '作品归属的真实已上架主题;不加外键,避免与内容生命周期耦合',
    platform VARCHAR(16) NOT NULL COMMENT 'tiktok/reels/youtube',
    source_url VARCHAR(512) NOT NULL COMMENT '作者提交的原始链接',
    canonical_video_key VARCHAR(80) NOT NULL COMMENT '平台+外部视频 id 规范化键,去重依据',
    origin_group_key VARCHAR(64) NOT NULL COMMENT '同一原视频分组键;跨平台搬运必须同组',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=已投稿 1=审核中 2=已通过 3=已拒绝 4=已撤销 5=申诉中 6=已取消',
    submitted_at DATETIME NOT NULL,
    published_at DATETIME NULL COMMENT '审核通过时确定的真实发布时间(窗口起点)',
    window_ends_at DATETIME NULL COMMENT '窗口终点',
    reviewed_by VARCHAR(64) NULL COMMENT '审核人标识,带 realm 前缀(如 sys:1);后台用户与会员是两套 id 空间,不能只存数字',
    reviewed_at DATETIME NULL,
    review_note VARCHAR(500) NULL,
    del_flag CHAR(1) NOT NULL DEFAULT '0',
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_canonical (campaign_id, canonical_video_key) COMMENT '同一活动内同一视频只能投稿一次',
    UNIQUE KEY uk_member_canonical (member_id, canonical_video_key) COMMENT '同一作者在任何活动里都不能重复投同一视频(换活动重投=绕开封顶)',
    KEY idx_submission_member (member_id, campaign_id),
    KEY idx_submission_group (campaign_id, origin_group_key, status),
    KEY idx_submission_review (status, id),
    CONSTRAINT ck_submission_platform CHECK (platform IN ('tiktok','reels','youtube')),
    CONSTRAINT ck_submission_status CHECK (status IN (0,1,2,3,4,5,6)),
    CONSTRAINT ck_submission_window CHECK (window_ends_at IS NULL OR published_at IS NULL OR window_ends_at > published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频悬赏投稿:去重键 + 作品归属 + 审核状态机';

-- ---------------------------------------------------------------------------
-- 3) 外部平台播放证据(insert-only)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS video_bounty_evidence (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    submission_id BIGINT UNSIGNED NOT NULL,
    evidence_type TINYINT NOT NULL COMMENT '1=人工核验录入 2=平台导出报表导入',
    source_ref VARCHAR(200) NOT NULL COMMENT '外部来源引用(报表编号/文件名摘要/截图 SHA-256);不接受空',
    captured_by VARCHAR(64) NOT NULL COMMENT '录入人或导入任务标识',
    period_start DATETIME NOT NULL,
    period_end DATETIME NOT NULL,
    views BIGINT NOT NULL COMMENT '该区间播放次数(平台口径)',
    organic TINYINT NOT NULL DEFAULT 1 COMMENT '1=自然流量 0=付费/推广,不计应计',
    verifiable TINYINT NOT NULL DEFAULT 1 COMMENT '1=可核验 0=无法核验/疑似刷量,不计应计',
    note VARCHAR(500) NULL,
    evidence_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化字段 SHA-256;唯一键做录入幂等',
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_evidence_hash (evidence_hash),
    KEY idx_evidence_submission (submission_id, id),
    CONSTRAINT ck_evidence_type CHECK (evidence_type IN (1,2)),
    CONSTRAINT ck_evidence_views CHECK (views >= 0),
    CONSTRAINT ck_evidence_period CHECK (period_end >= period_start),
    CONSTRAINT ck_evidence_flags CHECK (organic IN (0,1) AND verifiable IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部平台播放证据:必须有 source_ref,我方设备/账号数据不能自证播放';

-- ---------------------------------------------------------------------------
-- 4) 应计记录(只记录,不支付)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS video_bounty_accrual (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    submission_id BIGINT UNSIGNED NOT NULL,
    campaign_id BIGINT UNSIGNED NOT NULL,
    group_key VARCHAR(64) NOT NULL COMMENT '同一原视频分组键,封顶按组合计',
    rule_version VARCHAR(24) NOT NULL COMMENT '计算时生效的规则版本;历史行永不被改写',
    basis_views BIGINT NOT NULL COMMENT '本条投稿窗口内有效自然播放合计',
    rate_per_thousand DECIMAL(12,4) NOT NULL COMMENT '规则快照',
    per_video_cap DECIMAL(12,2) NOT NULL COMMENT '规则快照',
    group_basis_views BIGINT NOT NULL COMMENT '同组窗口内播放合计',
    gross_amount DECIMAL(14,2) NOT NULL COMMENT '未封顶前金额',
    payable_amount DECIMAL(14,2) NOT NULL COMMENT '按组封顶分摊后金额(应计,不是已打款)',
    currency CHAR(3) NOT NULL,
    payout_state TINYINT NOT NULL DEFAULT 0 COMMENT '0=仅记录(默认) 1=待兑付 2=已打款 3=已被重算替代 4=已撤销',
    idempotency_key VARCHAR(160) NOT NULL,
    window_start DATETIME NOT NULL,
    window_end DATETIME NOT NULL,
    evidence_watermark BIGINT NOT NULL COMMENT '证据水位(同组最大证据 id)',
    note VARCHAR(500) NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_accrual_idempotency (idempotency_key),
    KEY idx_accrual_submission (submission_id, payout_state),
    KEY idx_accrual_group (campaign_id, group_key),
    KEY idx_accrual_campaign (campaign_id, payout_state),
    CONSTRAINT ck_accrual_state CHECK (payout_state IN (0,1,2,3,4)),
    CONSTRAINT ck_accrual_amounts CHECK (gross_amount >= 0 AND payable_amount >= 0),
    CONSTRAINT ck_accrual_cap CHECK (payable_amount <= per_video_cap),
    CONSTRAINT ck_accrual_views CHECK (basis_views >= 0 AND group_basis_views >= basis_views)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频悬赏应计:只有计算与幂等,没有余额/支付/分账;默认 payout_state=0 不可兑付';

-- ---------------------------------------------------------------------------
-- 5) 主持小费配置
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS host_tip_config (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    host_member_id BIGINT NOT NULL COMMENT '受益主持:小费明确给谁',
    club_id BIGINT NOT NULL DEFAULT 0 COMMENT '作用域俱乐部;0=不限定',
    enabled TINYINT NOT NULL DEFAULT 0 COMMENT '0=未启用(默认) 1=已启用(仍需受支持收款通道)',
    host_share_percent TINYINT NOT NULL DEFAULT 100 COMMENT '归主持比例,固定 100;平台不抽成',
    channel_fee_bearer TINYINT NOT NULL DEFAULT 0 COMMENT '0=host 承担通道手续费 1=平台承担(单独配置)',
    min_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '单笔最低小费;0=不设下限',
    currency CHAR(3) NOT NULL,
    payment_channel VARCHAR(32) NULL COMMENT '受支持的收款通道标识;为空 = 未启用',
    config_version INT NOT NULL DEFAULT 1,
    del_flag CHAR(1) NOT NULL DEFAULT '0',
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tip_config_host_scope (host_member_id, club_id),
    CONSTRAINT ck_tip_config_enabled CHECK (enabled IN (0,1)),
    CONSTRAINT ck_tip_config_share CHECK (host_share_percent = 100),
    CONSTRAINT ck_tip_config_bearer CHECK (channel_fee_bearer IN (0,1)),
    CONSTRAINT ck_tip_config_min CHECK (min_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主持小费配置:100% 归 host;通道手续费承担方单列;不含钱包/余额';

-- ---------------------------------------------------------------------------
-- 6) 主持小费意向单
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS host_tip_intent (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    config_id BIGINT UNSIGNED NOT NULL COMMENT '下单时的配置版本行',
    payer_member_id BIGINT NOT NULL COMMENT '付款顾客;取消校验此归属',
    host_member_id BIGINT NOT NULL COMMENT '受益主持,100%',
    club_id BIGINT NOT NULL DEFAULT 0,
    order_ref_type TINYINT NOT NULL DEFAULT 0 COMMENT '0=无关联订单 1=关联既有商城订单(只读校验)',
    order_ref_id BIGINT NULL,
    amount DECIMAL(12,2) NOT NULL COMMENT '顾客自愿支付的小费金额',
    currency CHAR(3) NOT NULL,
    host_share_percent TINYINT NOT NULL COMMENT '配置快照',
    channel_fee_bearer TINYINT NOT NULL COMMENT '配置快照',
    payment_channel VARCHAR(32) NULL COMMENT '配置快照;为空 = 通道未启用',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=已创建 1=待支付 2=已付(仅真实回执) 3=已取消 4=已退款 5=通道未启用',
    external_trade_no VARCHAR(64) NULL COMMENT '通道交易号;通道未启用时为空',
    settlement_ref VARCHAR(64) NULL COMMENT '既有结算/分账引用(集成契约,未启用时为空)',
    idempotency_key VARCHAR(120) NOT NULL,
    note VARCHAR(500) NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tip_intent_idempotency (idempotency_key),
    KEY idx_tip_intent_host (host_member_id, status),
    KEY idx_tip_intent_payer (payer_member_id, status),
    CONSTRAINT ck_tip_intent_status CHECK (status IN (0,1,2,3,4,5)),
    CONSTRAINT ck_tip_intent_amount CHECK (amount > 0),
    CONSTRAINT ck_tip_intent_share CHECK (host_share_percent = 100),
    CONSTRAINT ck_tip_intent_bearer CHECK (channel_fee_bearer IN (0,1)),
    CONSTRAINT ck_tip_intent_ref CHECK (order_ref_type IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主持小费意向单:不改原订单/票券/结算;未启用通道时落 5 且不加任何余额';

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('video_bounty_campaign','video_bounty_submission','video_bounty_evidence',
                       'video_bounty_accrual','host_tip_config','host_tip_intent')
    AND UPPER(engine)='INNODB')=6
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND index_name IN ('uk_campaign_key','uk_video_canonical','uk_member_canonical','uk_evidence_hash',
                       'uk_accrual_idempotency','uk_tip_config_host_scope','uk_tip_intent_idempotency')
    AND non_unique=0)>=7
  -- 关键否定断言:本域任何表都不许出现余额/支付状态/分账列
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name IN ('video_bounty_campaign','video_bounty_submission','video_bounty_evidence',
                       'video_bounty_accrual','host_tip_config','host_tip_intent')
    AND column_name IN ('balance','member_balance','payment_status','pay_status','settlement_id',
                        'fee_rate','commission_rate'))=0
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='video_bounty_accrual' AND column_name='payout_state'
    AND column_default='0')=1
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='host_tip_config' AND column_name='enabled' AND column_default='0')=1,
  1,JSON_EXTRACT('ASSERT_FAILED_video_bounty_host_tip_readback','$')) AS UNSIGNED) AS assertion_ok;
