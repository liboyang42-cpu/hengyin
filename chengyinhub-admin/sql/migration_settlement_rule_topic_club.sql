-- 俱乐部主办主题的结算规则(biz_type = 'topic_club')
--
-- 背景:PR #589 把结算规则解析从批次级下沉到 per-topic,按 cms_topic.club_id 判主办方类型,
-- 俱乐部主题取 biz_type='topic_club'。**代码已上线,但没有这一行的话它会回落到全局 topic 规则**
-- —— 也就是「按商家自办的费率给俱乐部结算」,账面正常、零拦截(#589 只加了 logger.warn)。
-- 这个文件就是补上那一行。
--
-- 2026-08-07 用户拍板的口径:**商家池不动,俱乐部让 5 个点给平台**
--
--                merchant   creator   platform   inviter
--   topic          0.70      0.20       0.03      0.02     (商家自办,现行,不动)
--   topic_club     0.70      0.15       0.08      0.02     (俱乐部主办,本文件新增)
--                   ↑不动      ↓-5点      ↑+5点
--
-- 为什么商家池一分不动:履约成本(场地、人力、核销)全在商家身上,主办方是谁都一样。
-- 动了商家池,商家就会挑主办方类型接活 —— 那是在伤供给,而不是在分配收益。
-- 俱乐部只做组织不做履约,比自办商家少拿 5 点;平台在俱乐部场景多承担撮合与结算,多抽这 5 点。
--
-- 其余字段一律照 biz_type='topic' 那条抄(结算周期、起结金额、退款窗口、保证金三档),
-- 本次只区分分润,不顺手改别的 —— 一次只动一个变量。
--
-- 幂等:执行前已只读预检确认 topic_club 不存在。重跑会多插一行,
-- 而 selectActiveRule 取单条 ⇒ 重跑前必须先查,别盲目重放。
--
-- 约束自检:merchant_rate + creator_rate = 0.70 + 0.15 = 0.85 <= 1.0,
-- 过得了 CmsTopicServiceImpl.validateCommissionSettings 那道防超发闸。

insert into settlement_rule (
  merchant_id, rule_name, biz_type, settlement_cycle,
  platform_commission_rate, merchant_rate, creator_rate, inviter_rate,
  min_settlement_amount, refund_deadline_hours, allow_refund_after_verified, auto_approve_refund,
  guarantee_base_rate, guarantee_step_rate, guarantee_max_merchant,
  status, del_flag, create_time
) values (
  0, '俱乐部主办主题结算规则', 'topic_club', 'monthly',
  0.0800, 0.7000, 0.1500, 0.0200,
  100.00, 24, 0, 0,
  0.0500, 0.0200, 5,
  1, 0, now()
);
