-- ================= migration_merchant_referral_bounty.sql =================
SELECT 'migration_merchant_referral_bounty.sql' AS running;
-- 拓展人激活赏金(冷启动):有人帮平台拓来商家,该商家真跑起来后给拓展人一次性 ¥30。
--
-- 参数(2026-07-17 用户拍板,见《后台发主题与商家报名_拆除复制层_施工图_20260717.md》§3.3.1):
--   金额 ¥30 一次性 / 激活闸「商家注册起 30 天内、自己扫码核销人头数 ≥ 3」/ 单拓展人封顶 20 家
--   / 退款致核销 < 3 则追回 / 入账后 48h 冻结不可提现
--
-- ★ 为什么新建表而不复用 cms_registration_merchant:
--   那张表的 sharing_rate/sharing_amount 已被结算回写污染(结算跑一次污染一次),
--   且它表达的是「报名/中标」,与「推荐关系」不是一回事(推荐关系挂在 ums_member.invite_user_id 上)。
--
-- ⚠️ 依赖:ums_member(invite_user_id 是 **String** 列,不是数字列 —— 服务层必须校验再转)。
--
-- 落库前只读预检:
--   SELECT COUNT(*) FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='merchant_referral_bounty';   -- 期望 0
--   SELECT COUNT(*) FROM ums_member WHERE invite_user_id IS NOT NULL AND invite_user_id <> '';  -- 现存推荐关系数

CREATE TABLE merchant_referral_bounty (
  id                      BIGINT       PRIMARY KEY AUTO_INCREMENT,
  merchant_member_id      BIGINT       NOT NULL COMMENT '被推荐的商家 ums_member.id',
  inviter_member_id       BIGINT       NOT NULL COMMENT '拓展人 ums_member.id(来源 ums_member.invite_user_id)',
  merchant_registered_at  DATETIME     NOT NULL COMMENT '商家注册时间 = 30 天激活窗口的起点',
  window_expires_at       DATETIME     NOT NULL COMMENT '= merchant_registered_at + 30 天;落值不落算式,免得改规则时追溯改写历史',
  status                  TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待激活 1=已发放 2=已追回 3=已失效(超窗未达标)',
  activated_at            DATETIME     NULL COMMENT '达到 3 次核销的时刻',
  verified_heads_snapshot VARCHAR(512) NULL COMMENT '★判定快照:构成激活的 cms_registration.id 列表(逗号分隔)。clawback 的唯一依据——没有它就不知道该追哪笔',
  bounty_amount           DECIMAL(10,2) NULL COMMENT '实发金额。★落值不落配置:日后改价不该追溯改写历史记录',
  paid_at                 DATETIME     NULL COMMENT '入账时刻',
  frozen_until            DATETIME     NULL COMMENT '= paid_at + 48h,冻结期内不可提现,让 clawback 在钱离开平台前完成',
  clawed_back_at          DATETIME     NULL COMMENT '追回时刻',
  clawback_reason         VARCHAR(255) NULL COMMENT '追回原因(如:构成激活的核销 #123 已退款)',
  del_flag                CHAR(1)      DEFAULT '0',
  create_time             DATETIME,
  update_time             DATETIME,
  -- ★ 一个商家一辈子只产生一次赏金:幂等的硬保证,也防「换个推荐人再领一次」。
  --   故唯一键在 merchant_member_id 单列,不是 (inviter, merchant)。
  UNIQUE KEY uk_merchant (merchant_member_id),
  -- 封顶 20 家要按拓展人数「已发放」的条数 ⇒ 这个索引是热路径
  KEY idx_inviter_status (inviter_member_id, status),
  -- 对账:找冻结期内的潜在追回敞口
  KEY idx_frozen (status, frozen_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拓展人激活赏金(冷启动;一次性¥30/30天3核销/封顶20/48h冻结)';

-- 回读断言:期望 1 行(表已建)+ 0 行数据
SELECT COUNT(*) AS 表已建 FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merchant_referral_bounty';
SELECT COUNT(*) AS 现有赏金记录 FROM merchant_referral_bounty;
