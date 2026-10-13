-- ORDER 2/2：必须在 migration_20260815_tandianri_h8_publisher_points_funds.sql 后执行。
-- 探店日 H8：费率收敛与未结算账本必须在同一 InnoDB 事务中切换。
-- runner: requires=transaction

CREATE TEMPORARY TABLE tandianri_h8_rate_guard (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO tandianri_h8_rate_guard (id) VALUES (1);

-- 三条全局 live rule 缺失、重复或无法表示 H8 目标时，在改任何账前停止。
-- 这是业务基线(三类玩法各有且仅有一条兜底规则)，与下面那道全目标闸是两件事，不可互相替代。
INSERT INTO tandianri_h8_rate_guard (id)
SELECT 1
WHERE (SELECT COUNT(*) FROM settlement_rule
       WHERE merchant_id = 0 AND biz_type = 'activity' AND status = 1 AND del_flag = 0) <> 1
   OR (SELECT COUNT(*) FROM settlement_rule
       WHERE merchant_id = 0 AND biz_type = 'topic' AND status = 1 AND del_flag = 0) <> 1
   OR (SELECT COUNT(*) FROM settlement_rule
       WHERE merchant_id = 0 AND biz_type = 'topic_club' AND status = 1 AND del_flag = 0) <> 1;

-- 全体迁移目标的写前 fail-closed。目标集 = 【运行时可达】的全部 active/nondeleted 规则，
-- 不分 merchant_id —— 判据追到读侧真源，不是猜的:
--   SettlementRuleServiceImpl.getActiveRule:38-46 先按传入的 merchantId 查
--   SettlementRuleMapper.xml:54-62 selectActiveRule(merchant_id = ? AND biz_type = ?
--   AND status = 1 AND del_flag = 0 ORDER BY id DESC LIMIT 1)，查不到才回落 merchant_id = 0；
--   而主题核销腿传的是真实商家 id(SettlementEntryServiceImpl:149 ruleOwnerId = merchantId)。
-- ⇒ merchant_id <> 0 的覆盖规则同样会被命中。只清 merchant_id = 0 等于把这些行留成
--   「迁移全绿、CI 全绿、部署后现场核销整批抛」的哑弹，而且部署前查不出来。
-- 同一 (merchant_id, biz_type) 下 ORDER BY id DESC LIMIT 1 只回最高 id 那条，但低 id 的
-- active 行在高 id 行被停用的那一刻就接手 ⇒ 一并收敛，不留下一颗按 id 排队的哑弹。
-- 「无法安全配平」在改任何账之前就要红:四项出现 NULL(=不知道)、负数，或四项和 > 1(形状异常)。
-- 四项非负 ∧ 四项和 <= 1 ⇒ merchant_rate + creator_rate <= 1，合并后不会越界，故不另立冗余项。
-- status/del_flag 过滤原样保留:历史 inactive/deleted 行运行时够不到，不在本迁移的动账范围。
INSERT INTO tandianri_h8_rate_guard (id)
SELECT 1
WHERE EXISTS (
    SELECT 1 FROM settlement_rule
    WHERE biz_type IN ('activity', 'topic', 'topic_club')
      AND status = 1 AND del_flag = 0
      AND (platform_commission_rate IS NULL
           OR merchant_rate IS NULL
           OR creator_rate IS NULL
           OR inviter_rate IS NULL
           OR platform_commission_rate < 0
           OR merchant_rate < 0
           OR creator_rate < 0
           OR inviter_rate < 0
           OR platform_commission_rate + merchant_rate + creator_rate + inviter_rate > 1)
);

-- 顺序 guard：逆序执行必须真红。--transaction 禁止跨 schema 引用，因此只读同 schema 事实：
-- ORDER 1/2 种的专用规则行缺失/漂移 → 本 guard 主键冲突；rule_version 列缺失 → ERROR 1054。
INSERT INTO tandianri_h8_rate_guard (id)
SELECT 1
WHERE (SELECT COUNT(*) FROM cms_points_setting
       WHERE id = 15 AND type = 1 AND event_type = 15 AND title = '作品结算奖励'
         AND value = 100 AND rule_version >= 1 AND status = 1 AND del_flag = 0) <> 1;

-- 同上：publisher_settlement_topic_id 列缺失 → ERROR 1054；
-- uk_publisher_settlement_topic 未建 → FORCE INDEX 报 ERROR 1176；投影列错值 → 撞 guard。
INSERT INTO tandianri_h8_rate_guard (id)
SELECT 1
WHERE (SELECT COUNT(*) FROM ums_member_points_detail FORCE INDEX (uk_publisher_settlement_topic)
       WHERE publisher_settlement_topic_id IS NOT NULL
         AND NOT (event_type = 15 AND change_type = 1
                  AND publisher_settlement_topic_id = event_id)) <> 0;

-- 已有正向销售的活动若找不到稳定发布者，不猜归属，也不允许切换资金规则。
-- 「正向销售」逐字用结算腿自己的销售口径(CmsRegistrationMapper.selectTotalSalesByActivityId：
-- payment_status=2 已付款 ∧ registration_status=2 未取消未退单，金额取 payable_amount)。
-- ⚠️ cms_registration 上没有 pay_status / refund_status / amount 三列，写错即 ERROR 1054
-- 整份迁移跑不完；契约 ci/test_migration_h8_registration_columns.py 锁住这三个旧列名不许复发。
--
-- ★ 判据必须与 awardPublisherSettlement 的【真实会员口径】逐字对齐，不能只判 IS NULL：
--   PointsAwardServiceImpl:110-112 对 memberId 为空【或 <= 0】直接抛；:126-128 再要求
--   umsMemberService.lockMemberById(memberId) 非空，而 UmsMemberMapper.xml:90-92 是
--   `select id from ums_member where id = ? for update` —— 判据只有「行在不在」，
--   **不带 status / del_flag 过滤**。故这里 LEFT JOIN ums_member 判存在性，且不加状态条件：
--   加了会把「会员被停用但行还在」误判成缺失(假红)，少了会放过「会员行已被硬删」(假绿)。
--
-- ★ 判据放宽了,候选集就必须跟着收窄到【运行时真走得到 :240 那一跳】的活动,否则拦的是
--   本闸修不了的东西 —— 假红比缺闸更坏。逐跳真源:
--   ① 候选 CmsActivityMapper.xml:348-364 selectActivitiesForSettlement：
--      activity.del_flag = 0 ∧ activity.status = 1 ∧ activity.is_settlement = 0
--      ∧ end_date 已过 ∧ (topic_id 为空 或 来源主题 product_type <> 2)；
--   ② CmsActivityServiceImpl.settleActivity:191 来源主题 product_type = 2 时先撞
--      FREE_EXPLORE_LEGACY_SETTLEMENT_FORBIDDEN ⇒ 到不了 :240,故要求 product_type <> 2；
--   ③ 同文件 :208-211 有销售且 product_type 为空时先撞 NULL_PRODUCT_TYPE_WITH_SALES_FORBIDDEN
--      ⇒ 同样到不了,故要求 product_type IS NOT NULL(`<> 2` 对 NULL 恒不成立,必须分开写)。
-- ⚠️ 故意【不抄】end_date <= NOW() - 12h:那只决定"哪一轮轮到它",不决定可达性,与主题腿同口径。
-- ⚠️ 更要当心:来源主题自己的 del_flag / status【也不抄】。活动腿取来源主题走的是
--    CmsTopicMapper.xml:500-503 selectCmsTopicById(`where id = #{id}`,**不带 del_flag /
--    status 过滤**),候选查询那条 NOT EXISTS 也只判 product_type = 2 ⇒ 来源主题被软删或下架时
--    活动腿【照样】走到 :240 硬抛,排除它们是【假绿】。这与主题腿(候选查询 CmsTopicMapper.xml
--    :789-798 自己就过滤 del_flag/status)不是一回事,两条腿不能对称照抄。
INSERT INTO tandianri_h8_rate_guard (id)
SELECT 1
WHERE EXISTS (
    SELECT 1
    FROM cms_topic topic
    LEFT JOIN ums_member member
      ON member.id = topic.member_id
    JOIN cms_activity activity
      ON activity.topic_id = topic.id AND activity.is_settlement = 0
    JOIN cms_registration registration
      ON registration.owner_type = 2 AND registration.owner_id = activity.id
    WHERE activity.del_flag = 0
      AND activity.status = 1
      AND topic.product_type IS NOT NULL
      AND topic.product_type <> 2
      AND (topic.member_id IS NULL OR topic.member_id <= 0 OR member.id IS NULL)
      AND registration.payment_status = 2
      AND registration.registration_status = 2
      AND registration.payable_amount > 0
);

-- 现金腿在 member_id 为空或会员行不存在时硬抛（payActivityCommission）。存量必须有对称前置闸，
-- 否则改完费率就得到「每轮回滚 + 每轮告警、现金永不出账」的活动。这里只停下，不猜收款人。
--
-- 目标集与上面同源,但硬抛点是 :219(现金)不是 :240(积分),故来源主题那一段判据的形状不同:
--   · topic_id 为空的活动不做任何来源主题检查,直达 :219 ⇒ 必须【留在】目标集里；
--   · topic_id 非空时,:191 与 :208-211 两道硬抛都排在 :219 之前 ⇒ 来源主题行必须存在
--     (行已被硬删时 topic == null,撞 :208-211)且 product_type 非空、非 2,否则现金到不了。
-- 来源主题的 del_flag / status 同样不判,理由与上面那道闸逐字相同。
INSERT INTO tandianri_h8_rate_guard (id)
SELECT 1
WHERE EXISTS (
    SELECT 1
    FROM cms_activity activity
    LEFT JOIN ums_member member
      ON member.id = activity.member_id
    JOIN cms_registration registration
      ON registration.owner_type = 2 AND registration.owner_id = activity.id
    WHERE activity.del_flag = 0
      AND activity.status = 1
      AND activity.is_settlement = 0
      AND (activity.topic_id IS NULL
           OR EXISTS (SELECT 1 FROM cms_topic source_topic
                      WHERE source_topic.id = activity.topic_id
                        AND source_topic.product_type IS NOT NULL
                        AND source_topic.product_type <> 2))
      AND (activity.member_id IS NULL OR member.id IS NULL)
      AND registration.payment_status = 2
      AND registration.registration_status = 2
      AND registration.payable_amount > 0
);

-- 主题腿的【对称闸】。上面两条只沿 cms_registration.owner_type = 2(活动腿)追，
-- 而 awardPublisherSettlement 在主题腿也会硬抛(CmsTopicServiceImpl:925 直接把
-- topic.member_id 传进去，调用前没有任何判空)——那是本 PR 新增的第二条硬抛路径。
-- 缺这道闸的后果与上面注释点名的完全同族，只是换了一条腿：费率一收敛，这些主题每轮
-- 走到发积分那一跳抛 → 独立事务整个回滚(含 CAS 与 settleCoopTopic 已写的商家应付台账)
-- → 每轮回滚 + 每轮告警、应付永不出账，而且部署前查不出来。这里只停下，不猜发布者。
--
-- 目标集 = 【运行时真走得到那一跳】的主题，逐跳照抄 CmsTopicServiceImpl.settleTopic：
--   ① 候选 CmsTopicMapper.xml:789-798 selectTopicsForSettlement：
--      del_flag = 0 ∧ status = 1 ∧ is_settlement = 0 ∧ product_type IS NULL 或 <> 2；
--   ② 正销售 CmsRegistrationMapper.selectTotalSalesByTopicId(owner_type = 1，
--      payment_status = 2 ∧ registration_status = 2，金额取 payable_amount)；
--   ③ product_type 为空且有销售时先撞 NULL_PRODUCT_TYPE_WITH_SALES_FORBIDDEN(:894-899)
--      ⇒ 到不了发积分，故这里要求 product_type IS NOT NULL；
--   ④ 无生效 coop 单时先撞 NON_COOP_TOPIC_CASH_SETTLEMENT_UNPROVEN(:916-920)⇒ 同样到不了，
--      故按 CoopOrderMapper.selectEffectiveOrdersByTopicId 的谓词(status = 0 ∧ del_flag = 0
--      ∧ 排除 invite_type = 2 的历史邀约)要求确有生效单。
-- ⚠️ ③④ 这两条【必须】写进来：裁决第 7 条有意让「无俱乐部直发主题」永久结不掉，
--    把它们也拦红等于让迁移永远跑不了 —— 假红比缺闸更坏，它拦的是本闸修不了的东西。
-- ⚠️ 故意【不抄】end_date <= NOW() - 12h：那只决定「哪一轮轮到它」，不决定可达性；
--    还没到期的主题迟早到期，漏掉它只是把哑弹推迟到下一轮。
-- 会员口径与上面那条逐字相同(存在性，不判状态)，理由见上。
-- 契约 ci/test_migration_h8_publisher_guard.py 在替身库上真跑这三道闸并做摘闸负控。
INSERT INTO tandianri_h8_rate_guard (id)
SELECT 1
WHERE EXISTS (
    SELECT 1
    FROM cms_topic topic
    LEFT JOIN ums_member member
      ON member.id = topic.member_id
    JOIN cms_registration registration
      ON registration.owner_type = 1 AND registration.owner_id = topic.id
    WHERE topic.del_flag = 0
      AND topic.status = 1
      AND topic.is_settlement = 0
      AND topic.product_type IS NOT NULL
      AND topic.product_type <> 2
      AND (topic.member_id IS NULL OR topic.member_id <= 0 OR member.id IS NULL)
      AND registration.payment_status = 2
      AND registration.registration_status = 2
      AND registration.payable_amount > 0
      AND EXISTS (
          SELECT 1
          FROM coop_order
          WHERE coop_order.topic_id = topic.id
            AND coop_order.status = 0
            AND coop_order.del_flag = 0
            AND NOT EXISTS (
                SELECT 1
                FROM coop_invite legacy_invite
                WHERE legacy_invite.id = coop_order.invite_id
                  AND legacy_invite.invite_type = 2
            )
      )
);

-- 旧 creator 待结明细必须与同报名单的 merchant 待结明细【一一配对】；不能在归属不明时猜测。
-- 主题腿与活动腿同口径：只动未结算的那一侧，已结算主题/活动的明细一律不可变
-- （它们是退款回冲的金额基数，清零会让平台永远追不回已付出的历史现金）。
--
-- ★ 配对必须【双向】数。只数 merchant 侧是不够的：幂等键含 receiverId
--   (SettlementEntryServiceImpl:283)，发布者在两条结算路之间易主就会给同一张报名留下
--   两条 receiver_type='creator' AND status=0 的明细，而 merchant 仍只有一条 ⇒ 单向闸不红。
--   后果:候选临时表产出两行 move 共享同一个 merchant_detail_id 与同一份前值快照；
--   MySQL 多表 JOIN UPDATE 对同一目标行【只更新一次】⇒ merchant 只收下一份，
--   而下面那条 UPDATE 把【两条】creator 全部清零 ⇒ 差额凭空消失；
--   回读 4/4 逐 move 行比对，两条金额相等时两边都成立 ⇒ 恒真通过、静默丢钱。
--   而同一张报名、同一份 gross、同一条费率算出来的两条金额【通常就是相等的】。
--   ⇒ 候选 creator 的同 source_type/source_id 待结 creator 明细数必须【恰好 1】，
--     否则在改任何账之前停下。判据与候选集(下面的临时表)逐字同谓词:
--     status = 0 AND settlement_amount > 0 —— 已被本迁移清零过的行不再是候选，重跑幂等。
--   契约 ci/test_migration_h8_detail_move.py 在替身库上真跑这段并做摘闸负控。
INSERT INTO tandianri_h8_rate_guard (id)
SELECT 1
WHERE EXISTS (
    SELECT 1
    FROM settlement_detail creator
    JOIN cms_registration registration
      ON registration.id = creator.source_id
    LEFT JOIN cms_topic topic
      ON registration.owner_type = 1 AND topic.id = registration.owner_id
    LEFT JOIN cms_activity activity
      ON registration.owner_type = 2 AND activity.id = registration.owner_id
    WHERE creator.source_type = 'registration'
      AND creator.receiver_type = 'creator'
      AND creator.status = 0
      AND creator.settlement_amount > 0
      AND ((registration.owner_type = 1 AND topic.is_settlement = 0)
           OR (registration.owner_type = 2 AND activity.is_settlement = 0))
      AND (
          (
              SELECT COUNT(*) FROM settlement_detail merchant
              WHERE merchant.source_type = creator.source_type
                AND merchant.source_id = creator.source_id
                AND merchant.receiver_type = 'merchant'
                AND merchant.status = 0
          ) <> 1
          OR (
              SELECT COUNT(*) FROM settlement_detail sibling_creator
              WHERE sibling_creator.source_type = creator.source_type
                AND sibling_creator.source_id = creator.source_id
                AND sibling_creator.receiver_type = 'creator'
                AND sibling_creator.status = 0
                AND sibling_creator.settlement_amount > 0
          ) <> 1
      )
);

CREATE TEMPORARY TABLE tandianri_h8_pending_creator_move AS
SELECT creator.id AS creator_detail_id,
       merchant.id AS merchant_detail_id,
       creator.gross_amount AS creator_gross_amount,
       creator.commission_amount AS creator_commission_amount,
       creator.settlement_amount AS creator_settlement_amount,
       merchant.gross_amount AS merchant_gross_before,
       merchant.commission_amount AS merchant_commission_before,
       merchant.settlement_amount AS merchant_settlement_before
FROM settlement_detail creator
JOIN cms_registration registration
  ON registration.id = creator.source_id
LEFT JOIN cms_topic topic
  ON registration.owner_type = 1 AND topic.id = registration.owner_id
LEFT JOIN cms_activity activity
  ON registration.owner_type = 2 AND activity.id = registration.owner_id
JOIN settlement_detail merchant
  ON merchant.source_type = creator.source_type
 AND merchant.source_id = creator.source_id
 AND merchant.receiver_type = 'merchant'
 AND merchant.status = 0
WHERE creator.source_type = 'registration'
  AND creator.receiver_type = 'creator'
  AND creator.status = 0
  AND creator.settlement_amount > 0
  AND ((registration.owner_type = 1 AND topic.is_settlement = 0)
       OR (registration.owner_type = 2 AND activity.is_settlement = 0));

-- 单次引用临时表的 JOIN 更新：标量子查询写法会在同一语句里四次打开同一临时表，
-- MySQL 必报 ERROR 1137 Can't reopen table。
UPDATE settlement_detail AS detail
JOIN tandianri_h8_pending_creator_move AS move
  ON move.merchant_detail_id = detail.id
SET detail.gross_amount = detail.gross_amount + move.creator_gross_amount,
    detail.commission_amount = detail.commission_amount + move.creator_commission_amount,
    detail.settlement_amount = detail.settlement_amount + move.creator_settlement_amount,
    detail.update_time = NOW()
WHERE detail.status = 0;

UPDATE settlement_detail AS detail
SET gross_amount = 0,
    commission_amount = 0,
    settlement_amount = 0,
    update_time = NOW()
WHERE id IN (
    SELECT move.creator_detail_id FROM tandianri_h8_pending_creator_move move
);

-- 「creator 全并 merchant」是前后关系，不是三组绝对值。先快照 live 前值，再由语义自带断言地合并；
-- platform/inviter 一个字都不写，历史改率过的生产库不会被静默重设成种子值。
-- 快照与合并的范围 = 上面那道写前闸的同一个目标集(含 per-merchant 覆盖规则)，逐行留底。
CREATE TEMPORARY TABLE tandianri_h8_rate_before AS
SELECT id, biz_type, platform_commission_rate, merchant_rate, creator_rate, inviter_rate
FROM settlement_rule
WHERE biz_type IN ('activity', 'topic', 'topic_club')
  AND status = 1 AND del_flag = 0;

UPDATE settlement_rule
SET merchant_rate = merchant_rate + creator_rate,
    creator_rate = 0.0000
WHERE biz_type IN ('activity', 'topic', 'topic_club')
  AND status = 1 AND del_flag = 0;

-- 回读 1/4：前值快照必须【逐行】覆盖整个 live 目标集，否则下面的比对是子集恒真。
-- 右边独立重算一次目标集基数(UPDATE 不写 biz_type/status/del_flag，该基数写前写后相同):
-- 快照或 UPDATE 的 WHERE 被写窄(例如退回只清 merchant_id = 0)时两边不等 → 撞 guard。
-- 这不是拿刚写进去的值跟自己比。
INSERT INTO tandianri_h8_rate_guard (id)
SELECT 1
WHERE (SELECT COUNT(*) FROM tandianri_h8_rate_before)
   <> (SELECT COUNT(*) FROM settlement_rule
       WHERE biz_type IN ('activity', 'topic', 'topic_club')
         AND status = 1 AND del_flag = 0);

-- 回读 2/4：逐条比 live 前值 —— merchant 收全 creator、creator 归零、platform/inviter 不动、总盘守恒。
INSERT INTO tandianri_h8_rate_guard (id)
SELECT 1
WHERE EXISTS (
    SELECT 1
    FROM tandianri_h8_rate_before rate_before
    JOIN settlement_rule rule
      ON rule.id = rate_before.id
    WHERE rule.merchant_rate <> rate_before.merchant_rate + rate_before.creator_rate
       OR rule.creator_rate <> 0
       OR rule.platform_commission_rate <> rate_before.platform_commission_rate
       OR rule.inviter_rate <> rate_before.inviter_rate
       OR rule.platform_commission_rate + rule.merchant_rate + rule.creator_rate + rule.inviter_rate
          <> rate_before.platform_commission_rate + rate_before.merchant_rate
             + rate_before.creator_rate + rate_before.inviter_rate
);

-- 回读 3/4：整表终态观测，与快照无关 —— 问的是「生产现在还有没有会被写侧闸拒掉的规则」。
-- 快照 / UPDATE / 回读 1/4 若被同步写窄，漏掉的行只有这里能现形；
-- NULL 也只有这里点得到(`<> 0` 对 NULL 恒不成立，merchant_rate + NULL 会静默写成 NULL)。
-- 本条为真 = 部署后 SettlementEntryServiceImpl:227-234 那道写侧闸必然拒掉对应玩法的入账。
INSERT INTO tandianri_h8_rate_guard (id)
SELECT 1
WHERE EXISTS (
    SELECT 1 FROM settlement_rule
    WHERE biz_type IN ('activity', 'topic', 'topic_club')
      AND status = 1 AND del_flag = 0
      AND (creator_rate IS NULL OR creator_rate <> 0 OR merchant_rate IS NULL)
);

-- 回读 4/4：转移金额守恒 —— creator 行清零、merchant 行恰好收下它那一份。
INSERT INTO tandianri_h8_rate_guard (id)
SELECT 1
WHERE EXISTS (
    SELECT 1 FROM tandianri_h8_pending_creator_move move
    JOIN settlement_detail creator ON creator.id = move.creator_detail_id
    JOIN settlement_detail merchant ON merchant.id = move.merchant_detail_id
    WHERE creator.gross_amount <> 0
       OR creator.commission_amount <> 0
       OR creator.settlement_amount <> 0
       OR merchant.gross_amount <> move.merchant_gross_before + move.creator_gross_amount
       OR merchant.commission_amount <> move.merchant_commission_before + move.creator_commission_amount
       OR merchant.settlement_amount <> move.merchant_settlement_before + move.creator_settlement_amount
);

DROP TEMPORARY TABLE tandianri_h8_pending_creator_move;
DROP TEMPORARY TABLE tandianri_h8_rate_before;
DROP TEMPORARY TABLE tandianri_h8_rate_guard;
