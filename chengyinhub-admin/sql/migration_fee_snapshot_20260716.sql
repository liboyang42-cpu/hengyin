-- P0-28 批1:费率进库(收敛单真源)+ 锁价快照(冻结费率)
-- 来源:《城瘾代码侧_现状与改造施工图_TODO_2026-07-15》V9 / P0-28 / D7
--
-- 【背景 —— 两个独立的病,这份迁移各治一半】
--
--  病1「费率是编译期常量」:PricingServiceImpl 里三个数字全是 Java 源码常量,全仓**零 DB 对应物**:
--      PLATFORM_RATE = 0.10、initiatorGuaranteeRate = 0.05 + 0.02×min(n,5)。
--      而库里 settlement_rule.platform_commission_rate(DDL 默认 0.1000)是**同一个概念的另一份**,
--      后台「结算规则」页可改。⇒ 运营在后台把抽成调成 0.15,PricingServiceImpl 还是 0.10,
--      定出的地板价低 5 个点 → 结算时 margin<0 → 守恒断言拒结 → 款项悬空转人工。
--      D7 拍板抽成 10%→3% 后,不收敛则**每次调价都要走一遍发版**(mvn → 合 master → Flow 部署 ECS)。
--
--  病2「费率没有版本快照 = 发版即资损」⭐ 这个比病1 严重,而且文档没写:
--      coop_order 有 termsVersion='v1' 冻结**商家条款**,平台率/保底率却**没有任何快照** ——
--      定价时固化的是**价格**(写进 oms_ticket.price / cms_topic.self_play_price),
--      结算时却**现取**(CmsTopicServiceImpl:845 pricingService.platformRate()、
--      :901 initiatorGuaranteeRate(n))。
--      ⇒ 把 0.10 调成 0.15,**已售票的存量主题**结算时平台多抽 5%,直接从发起人毛利里挖 →
--      marginAmount<0 撞守恒断言 → 拒结、款项悬空。**改一个数追溯改写所有已售未结订单。**
--      业界对这条没有争议:「三月的账单在四月改价之后也必须永远重现三月的算法。
--      不要拿今天的费率去重算旧账单。」
--
-- 【本迁移做什么】
--   ① settlement_rule 加 3 列 —— 把 initiatorGuaranteeRate 的三个魔数请进库,与 platform_commission_rate
--      并列成为费率的**单一真源**;
--   ② cms_topic 加 4 列 —— 锁价(lifecycle 2→3)那一刻把当时的费率**快照**进主题,结算只读快照。
--
-- 【为什么快照打在 cms_topic 而不是新建 fee_schedule_version 表】
--   MVP 取舍。版本表更漂亮(可审计「这一版费率什么时候生效过」),但首轮 = ③-自营、零分账,
--   用不上那个查询能力。4 个可空列能 100% 堵住「发版即资损」,是最短的止血路径。
--   ⚠️ **这不是死路**:将来上 order_split(一次解析成绝对金额、之后只读)时,快照维度会从
--   「主题级费率」下沉到「订单级金额」,这 4 列届时直接退役,不构成迁移债。
--
-- 【为什么 guarantee 存三个参数而不是存算出来的率】
--   保底率是 `base + step × min(已核销商家数, max)` —— **已核销商家数只有结算时才知道**,
--   锁价时算不出。所以能冻的只有公式参数,不是结果。冻参数已经足够:同一批参数 + 同一个 n
--   恒得同一个率。
--
-- 【幂等】
--   7 个 ADD COLUMN **不幂等**(重复执行报 1060)—— MySQL 8 无 ADD COLUMN IF NOT EXISTS。
--   执行前必须跑 P1 预检。
--
-- 【可逆】—— ⚠️ 仅适用于「jar 已部署后整体回退」,不适用于本迁移中途失败(见【执行方式】)
--   ALTER TABLE settlement_rule DROP COLUMN guarantee_base_rate, DROP COLUMN guarantee_step_rate,
--       DROP COLUMN guarantee_max_merchant
--   ALTER TABLE cms_topic DROP COLUMN fee_platform_rate, DROP COLUMN fee_guarantee_base_rate,
--       DROP COLUMN fee_guarantee_step_rate, DROP COLUMN fee_guarantee_max_merchant
--   回退顺序 = 先回 jar 再 DROP。
--
-- 【风险】
--   **低 —— 本迁移刻意做到零行为变化**:三个新列的 DEFAULT 逐一等于代码里原来的硬编码值
--   (0.0500 / 0.0200 / 5),cms_topic 的 4 个快照列可空(NULL = 未锁价/存量 → 读侧回落 live 规则,
--   与今天行为一致)。落库后不改任何一笔钱的算法,只是把数从代码搬进库。
--
--   ⚠️⚠️ **本迁移不改 D7 的 10%→3%** —— 那是一行 UPDATE,**故意留给你按**:
--   收敛的全部意义就是「以后改值不用发版」,所以改值应该是你在后台点一下、或跑下面这行,
--   而不是由一个代码 PR 替你动真钱。收敛落地后:
--       UPDATE settlement_rule SET platform_commission_rate = 0.0300
--        WHERE merchant_id = 0 AND biz_type = 'topic' AND del_flag = 0;
--   ⚠️ 改之前先想清楚:这一列**同时**被 SettlementEntryServiceImpl(活动/券的三方拆分)读,
--   改它会一起改掉那条路的抽成。要只改 coop 那条,得先给 coop 单开一行 biz_type。
--
-- ⚠️⚠️ 生产库 MySQL 8。禁止直接执行。必须走 chengyin-db-migrate:先只读预检 → 用户确认 → 才执行。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库,否则 PricingServiceImpl 读新列会 SQL 报错。


-- ======================= 【执行方式】 =======================
-- · 七条按顺序逐条执行,失败即停。
-- · **中途失败无害,不要回滚**:新增列全部可空或带 DEFAULT,jar 尚未部署 ⇒ 无代码读写。
--   正确处置 = 修好失败那条继续,别去跑【可逆】的 DROP。
-- · MySQL DDL 隐式提交、不可回滚 —— 这正是「失败即停 + 继续而非回滚」的原因。


-- ======================= 第一步:只读预检 =======================
--
-- P1【必看 · 决定跳过哪些 ALTER】七个新列是否已存在:
--   SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, COLUMN_DEFAULT
--     FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND ((TABLE_NAME = 'settlement_rule' AND COLUMN_NAME IN
--             ('guarantee_base_rate','guarantee_step_rate','guarantee_max_merchant'))
--        OR (TABLE_NAME = 'cms_topic' AND COLUMN_NAME IN
--             ('fee_platform_rate','fee_guarantee_base_rate','fee_guarantee_step_rate','fee_guarantee_max_merchant')));
--   · 0 行 → 七个 ALTER 全执行。
--   · 有命中 → 逐条把已存在的划掉(撞已存在列 = 1060)。
--
-- P2【★ 必看 · 这条决定收敛后抽成会不会变】settlement_rule 现在的真实值:
--   SELECT id, merchant_id, biz_type, rule_name, platform_commission_rate, merchant_rate,
--          creator_rate, inviter_rate, status, del_flag
--     FROM settlement_rule WHERE del_flag = 0 ORDER BY biz_type, merchant_id;
--   ⚠️ **收敛后 PricingServiceImpl 会开始读 (merchant_id=0, biz_type='topic') 这一行的
--      platform_commission_rate,取代原来写死的 0.10。**
--   · 若该行的值 = 0.1000 → 收敛是**零行为变化**,放心跑。
--   · 若该行的值 ≠ 0.1000(有人在后台改过)→ **收敛当天 coop 主题的地板价与结算抽成会跟着跳**。
--     先想清楚再跑,或先把它改回 0.1000 再收敛、之后单独调。
--   · 若查不到 (0,'topic') 这一行 → 代码会 fallback 到 0.10 并打 error 日志(与今天等价),
--     但**请补上这一行**,否则「单真源」名存实亡。
--
-- P3【锁表评估】cms_topic 行数(七条 ALTER 都带 ALGORITHM=INSTANT,不是 INSTANT 会硬报 1845 停):
--   SELECT COUNT(*) FROM cms_topic;
--   SHOW CREATE TABLE cms_topic;   -- 看 ROW_FORMAT,决定 INSTANT 可不可用
--   ⚠️ 真撞 1845:去掉那条的 ALGORITHM 子句、择低峰再跑,别硬扛。


-- ======================= 第二步:费率进库(收敛单真源) =======================
-- 三个 DEFAULT 逐一等于 PricingServiceImpl 里原来的硬编码值 ⇒ 落库后行为零变化。
-- 保底率公式:base + step × min(已核销商家数, max)。已核销商家数只有结算时才知道,
-- 所以库里存的是**公式参数**不是算好的率。

ALTER TABLE settlement_rule
    ADD COLUMN guarantee_base_rate DECIMAL(10,4) NOT NULL DEFAULT 0.0500 COMMENT '发起人保底基础率(原PricingServiceImpl硬编码0.05)。单位:小数', ALGORITHM=INSTANT;

ALTER TABLE settlement_rule
    ADD COLUMN guarantee_step_rate DECIMAL(10,4) NOT NULL DEFAULT 0.0200 COMMENT '每个已核销商家追加的保底率(原硬编码0.02)。单位:小数', ALGORITHM=INSTANT;

ALTER TABLE settlement_rule
    ADD COLUMN guarantee_max_merchant TINYINT NOT NULL DEFAULT 5 COMMENT '保底率封顶的商家家数(原硬编码5,即保底率封顶base+step×5)', ALGORITHM=INSTANT;


-- ======================= 第三步:锁价快照(冻结费率) =======================
-- 可空:NULL = 未锁价 / 存量主题 ⇒ 读侧回落 settlement_rule 的 live 值(与今天行为一致)。
-- 非空 = 该主题在锁价那一刻冻住的费率,结算只认它 ⇒ 之后再怎么调库里的费率都不会追溯改写它。
--
-- ⚠️ 为什么冻在主题级而不是订单级:MVP 取舍。coop 主题的阵容在 lifecycle≥3 后被冻结
--    (acceptInviteWithLock 拒绝新邀约),所以「主题级费率 + 冻结阵容」已能保证同一主题的每一单
--    算出同一个结果。将来上 order_split(订单级绝对金额)时这 4 列直接退役。

ALTER TABLE cms_topic
    ADD COLUMN fee_platform_rate DECIMAL(10,4) NULL COMMENT '锁价时冻结的平台抽成率。NULL=未锁价/存量,读侧回落settlement_rule。单位:小数', ALGORITHM=INSTANT;

ALTER TABLE cms_topic
    ADD COLUMN fee_guarantee_base_rate DECIMAL(10,4) NULL COMMENT '锁价时冻结的保底基础率。NULL同上', ALGORITHM=INSTANT;

ALTER TABLE cms_topic
    ADD COLUMN fee_guarantee_step_rate DECIMAL(10,4) NULL COMMENT '锁价时冻结的保底追加率。NULL同上', ALGORITHM=INSTANT;

ALTER TABLE cms_topic
    ADD COLUMN fee_guarantee_max_merchant TINYINT NULL COMMENT '锁价时冻结的保底封顶家数。NULL同上', ALGORITHM=INSTANT;
