package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.enums.SettlementDetailState;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.service.ISettlementEntryService;
import com.chengyinhub.business.service.ISettlementClaimService;
import com.chengyinhub.business.service.ISettlementRuleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * P3 结算自动入账：报名/优惠券核销成功后，按 settlement_rule 把金额拆成
 * 商家 / 平台 / 创作者 多条待结算明细（status=0），等结算批次消费。
 */
@Service
public class SettlementEntryServiceImpl implements ISettlementEntryService {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(SettlementEntryServiceImpl.class);

    @Autowired
    private SettlementDetailMapper settlementDetailMapper;

    @Autowired
    private ISettlementRuleService settlementRuleService;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    @Autowired
    private CmsActivityMapper cmsActivityMapper;

    @Autowired
    private CoopOrderMapper coopOrderMapper;

    @Autowired
    private ISettlementClaimService settlementClaimService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recordOnPayment(CmsRegistration registration) {
        if (registration == null || registration.getId() == null) {
            return 0;
        }
        // 主题报名的执行商家在核销时才由商家池确定，支付阶段不入账，避免错误归属到主题创建者
        if (Long.valueOf(1L).equals(registration.getOwnerType())) {
            return 0;
        }
        // ★★ [P0-14 资损] ③ 探索票同样不在支付阶段入账 —— 上面那句理由**逐字适用**:
        // 它的执行商家(可能有 N 家)也是到店核销时才确定,支付时入账必然错误归属。
        // ⚠️ 但 ③ 是 owner_type=**2**,上面那道 ownerType==1 的闸拦不住它
        // ⇒ 不显式跳过的话:玩家一支付,resolveActivityMerchant 取活动创建者
        // → recordPaidRegistration 把**整单票款**入账给他,而真正逐章端出权益的 N 家商家一分没有。
        // 这是核销之前就发生的资损,比「最后扫的那家白拿全额」更早、更彻底。
        // ⚠️ 必须在这里**跳过(返 0)**而不是靠 recordPaidRegistration 抛 —— 这条在**支付回调**里,
        //    抛会让整个支付回调事务回滚 = 玩家付了钱订单却没成。被调方那道闸是给新入口兜底的。
        if (registration.getPurchaseKind() != null
                && registration.getPurchaseKind() == PurchaseKind.EXPLORE_PASS) {
            return 0;
        }
        CmsActivity activity = resolveActivity(registration);
        if (activity == null || activity.getMemberId() == null) {
            return 0;
        }
        return recordPaidRegistration(registration, activity.getMemberId(), activity);
    }

    private CmsActivity resolveActivity(CmsRegistration registration) {
        if (registration.getOwnerId() == null || !Long.valueOf(2L).equals(registration.getOwnerType())) {
            return null;
        }
        return cmsActivityMapper.selectCmsActivityById(registration.getOwnerId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recordPaidRegistration(CmsRegistration registration, Long merchantId) {
        return recordPaidRegistration(registration, merchantId, null);
    }

    private int recordPaidRegistration(CmsRegistration registration, Long merchantId, CmsActivity loadedActivity) {
        if (registration == null || registration.getId() == null || merchantId == null) {
            return 0;
        }
        // ★★★ [P0-14 资损·最深一道闸] ③ 探索票**绝不能整单入账**。
        //
        // 本方法的语义是「整单票款按 settlement_rule 拆给**单一** merchantId」。
        // 而 ③ 一张票跨 N 家商家逐章核销 ⇒ 整单给谁都是错的 ⇒ **谁调它谁就制造资损**。
        //
        // 为什么闸下沉到这里(而不是停在 verifyAndSettle):
        // 本轮同一个病犯了四次,每次都是「我以为我普查完了调用方」——
        //   ① ③ 分流放错分支;② 拿假不变量拆守卫;
        //   ③ 漏掉 ApiClubLeadController 领队全团结算;
        //   ④ 漏掉 recordOnPayment —— 它在**支付回调**里,ownerType==1 跳过但 ③ 是 owner_type=2
        //      ⇒ 玩家一支付整单就入账给活动创建者,比核销还早。
        // ⇒ 「钱」只有这一个出口。闸放在这里,现在的两个调用方
        //   (verifyAndSettle / recordOnPayment)+ 将来任何新入口一次性全保住。
        //
        // 抛而不是返 0:走到这里 = 调用方漏了 ③ 分流 = 编程错误,必须响亮。
        // 返 0 的语义是「已入过账/无需入账」,拿它掩盖会让新入口静默把 ③ 的钱结算走 —— 正是要杀的病。
        // ⚠️ 批量/回调类调用方(领队全团结算、支付回调)必须在**进来之前**就把 ③ 过滤掉,
        //    别指望靠这里的异常跳过 —— 它会中断整批 / 回滚支付回调。
        if (registration.getPurchaseKind() != null
                && registration.getPurchaseKind() == PurchaseKind.EXPLORE_PASS) {
            throw new IllegalStateException(
                    "探索票不能整单入账(一票跨N家逐章核销),registrationId=" + registration.getId());
        }
        BigDecimal amount = firstNonNull(registration.getPayableAmount(), registration.getTotalAmount(), registration.getOriginalAmount());
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0
                && registration.getFeePlatformRate() == null) {
            throw new IllegalStateException("可付款报名缺少冻结平台费率,registrationId=" + registration.getId());
        }
        boolean isTopic = Long.valueOf(1L).equals(registration.getOwnerType());

        // ★★★ [P0 资损·双付闸] 有生效合作单的主题,票款**只走 coop 结算**,这里不再整单入账。
        //
        // 病灶(2026-08-07 复现用例坐实,DoublePaymentReproductionTest):
        // 同一商家若既 audit_status=1(中标)又有 coop_order,一张 ¥100 的票会被记两次 ——
        //   核销 → settlement_detail 给他 ¥90(扣 10% 平台款)
        //   结算 → coop_settlement 同一 CoopOrder 再按 30% 给他 ¥30
        // 两笔指向**同一张报名**(用例断言 verifiedRegIds 相同),合计 ¥120 从 ¥100 里出去。
        // 这个组合日常就会出现:邀约接受后补一次报名+中标,两种身份就都有了。
        //
        // 判据与 CmsTopicServiceImpl 的分流**用同一个谓词**(selectEffectiveOrdersByTopicId 非空
        // ⇒ 走 settleCoopTopic),两边同源才不会各判各的又漂移;
        // 而「结算真源是 CoopOrder 不是 club_id」本就是该文件已确立的口径。
        //
        // ⚠️ 闸放在被调方:本文件自己的教训是「闸放在调用方就永远漏」——
        // 核销、支付回调、领队全团结算三个入口都经过这里,放这儿一次全保住。
        if (isTopic && registration.getOwnerId() != null
                && !coopOrderMapper.selectEffectiveOrdersByTopicId(registration.getOwnerId()).isEmpty()) {
            return 0;
        }
        String bizType = isTopic ? "topic" : "activity";
        // 系统1活动结算固定读取全局 activity 规则(ownerId=0);纯账本必须同源,不能误用商家覆盖规则。
        Long ruleOwnerId = isTopic ? merchantId : 0L;
        SettlementRule rule = settlementRuleService.getActiveRule(ruleOwnerId, bizType);
        BigDecimal platformRate = registration.getFeePlatformRate();

        // 平台率优先用锁价快照(拍板4:分账与定价同冻结,不被 live 费率追溯改写)。
        // creatorId(主题设计者 / 活动源主题发布者)在 H8 之后【不再参与分钱】,只用于在
        // 「规则里还留着非零 creator_rate ⇒ 拒绝入账」的异常里点名本该被错付的人,见 insertSplitDetails。
        Long creatorId = null;
        if (isTopic && registration.getOwnerId() != null) {
            CmsTopic topic = cmsTopicMapper.selectCmsTopicById(registration.getOwnerId());
            if (topic != null) {
                creatorId = topic.getMemberId();
            }
        } else if (registration.getOwnerId() != null) {
            CmsActivity activity = loadedActivity != null
                    ? loadedActivity : cmsActivityMapper.selectCmsActivityById(registration.getOwnerId());
            if (activity != null && activity.getTopicId() != null) {
                CmsTopic topic = cmsTopicMapper.selectCmsTopicById(activity.getTopicId());
                creatorId = topic == null ? null : topic.getMemberId();
            }
        }
        BigDecimal gross = amount == null ? BigDecimal.ZERO : amount;
        if (gross.compareTo(BigDecimal.ZERO) <= 0) {
            claimRegistration(registration, "MERCHANT", merchantId, "NONE", "DIRECT_REGISTRATION");
            return 0;
        }
        return insertSplitDetails("registration", registration.getId(), merchantId, creatorId,
                amount, rule, platformRate, registration);
    }

    @Override
    public int recordCouponVerification(SmsCouponHistory history, SmsCoupon coupon) {
        if (history == null || history.getId() == null || coupon == null) {
            return 0;
        }
        // sms_coupon.merchant_id 是 mms_merchant 行主键，只用于域归属；结算账本沿用
        // merchant_id=owner memberId 的既有资金口径，不能把商家行 ID 当成收款人。
        Long merchantId = coupon.getMemberId();
        if (merchantId == null || merchantId <= 0) {
            return 0;
        }
        BigDecimal amount = coupon.getAmount() == null ? BigDecimal.ZERO : coupon.getAmount();
        SettlementRule rule = settlementRuleService.getActiveRule(merchantId, "coupon");
        BigDecimal platformRate = rule == null ? null : rule.getPlatformCommissionRate();
        return insertSplitDetails("coupon", history.getId(), merchantId, null, amount, rule, platformRate, null);
    }

    @Override
    public int deletePendingCouponVerification(Long historyId) {
        if (historyId == null) {
            return 0;
        }
        int removed = settlementDetailMapper.deletePendingCouponVerification(historyId);
        // 已入批次/已结算的明细不在删除半径内,读回点名 —— 不静默。券没有打款路径
        // (见 RefundServiceImpl.resolveSystem1PaidCheck 的说明),正常恒无;出现即异常,必须留信号。
        SettlementDetail settled = settlementDetailMapper.selectSettledDetail("coupon", historyId);
        if (settled != null) {
            logger.warn("撤销券核销:该领取记录已有已结算分成明细,未回冲 historyId={} detailId={} amount={}",
                    historyId, settled.getId(), settled.getSettlementAmount());
        }
        return removed;
    }

    /**
     * 按规则把 gross 拆成 平台抽成 + 商家余额，商家拿剩余以保证两方合计==gross。
     * 无规则时商家拿全额（向后兼容）。每个收款方一条明细，幂等键含收款方。
     * platformRate 由调用方解析(主题优先锁价快照,其余用 live rule 的平台率)。
     * ★P1-5:两方之和必须=gross,不满足直接拒绝整笔入账(参照系统1
     * CmsActivityServiceImpl.validateActivityCommissionSettings() 的做法)——
     * 旧实现在平台份额超过 gross 时把 merchantShare 夹到 0 继续放行,
     * 会让平台份额静默突破 gross,守恒在账本层面被打破且没有任何信号。
     */
    private int insertSplitDetails(String sourceType, Long sourceId, Long merchantId, Long creatorId,
                                   BigDecimal gross, SettlementRule rule, BigDecimal platformRate,
                                   CmsRegistration registration) {
        BigDecimal grossAmount = gross == null ? BigDecimal.ZERO : gross;
        BigDecimal platformShare = share(grossAmount, platformRate);

        // ★★★ [H8 写侧 fail-closed] 发布者分润已取消,creator 份额【全并 merchant】:
        // 命中的规则只要 creator_rate 不是 0(含 NULL = 「不知道」),就【整笔拒绝入账】——
        // 不切份额、不写 creator 明细、也不写 merchant/platform 明细。
        //
        // 为什么闸必须放在这儿(只靠迁移 / 调用方 / 配置界面都堵不住):
        //   ① 迁移是一次性动作:migration_20260815_tandianri_h8_rate_convergence.sql 现在确实
        //      收敛了【全部】active/nondeleted 的三类规则(含 per-merchant 覆盖规则),但它只在跑的
        //      那一刻成立 —— 它刻意不动 status<>1 / del_flag<>0 的历史行,那些行被重新启用就又是
        //      非零 creator_rate;而且部署顺序反了(jar 先上)时整张表还是旧值;
        //   ② 两条读侧结算闸(CmsTopicServiceImpl / CmsActivityServiceImpl 的 creator_rate != 0 拒结)
        //      读的同样是 getActiveRule(0L, ...),也够不到 per-merchant 规则,而且它们拦【结算】不拦【入账】;
        //   ③ 本方法是核销、支付回调、领队全团结算三个入口的共用被调方 —— 与上面那道 coop 双付闸同址同理,
        //      「闸放在调用方就永远漏」是本文件自己的教训。
        // 为什么抛而不是把 creator 份额并回商家继续放行:并回只防住了【错付】,
        // 错规则本身照样活着、继续被每一笔核销命中,零信号 —— 这不是 fail-closed 而是静默兜底。
        // 抛会中断整批核销 / 回滚支付回调(见本方法顶部那段),而这正是要的:
        // 命中一条 H8 之后不该存在的费率规则 = 配置事故,必须响亮、必须停下,不能带病记账。
        // 判据取【真实命中的那条规则自己的 biz_type】(settlement_rule.biz_type NOT NULL),
        // 与 SettlementRuleServiceImpl.requireZeroCreatorRateForH8BizTypes 的写入侧闸同源同谓词;
        // 刻意不拿 creatorId 是否可解析当前提 —— 主题被删/memberId 为空时 creatorId 会是 null,
        // 拿它当前提等于给「发布者解析失败」开一条静默绕过。
        if (rule != null && isCreatorFreeBizType(rule.getBizType())) {
            BigDecimal creatorRate = rule.getCreatorRate();
            if (creatorRate == null || creatorRate.compareTo(BigDecimal.ZERO) != 0) {
                throw new IllegalStateException("H8 已取消发布者分润,creatorRate 必须为 0(份额全并商家),拒绝入账"
                        + " bizType=" + rule.getBizType() + " sourceType=" + sourceType
                        + " sourceId=" + sourceId + " creatorId=" + creatorId + " creatorRate=" + creatorRate);
            }
        }

        BigDecimal merchantShare = grossAmount.subtract(platformShare);
        if (merchantShare.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("结算费率配置错误,platformShare 超过可分配总额,拒绝入账 sourceType="
                    + sourceType + " sourceId=" + sourceId + " gross=" + grossAmount
                    + " platformShare=" + platformShare);
        }

        int rows = 0;
        rows += insertReceiver(sourceType, sourceId, merchantId, "merchant", merchantId, merchantShare, registration);
        if (platformShare.compareTo(BigDecimal.ZERO) > 0) {
            rows += insertReceiver(sourceType, sourceId, merchantId, "platform", 0L, platformShare, registration);
        }
        return rows;
    }

    /** H8 裁决点名的三类玩法;coupon 等其余 biz_type 不在裁决范围内,保护半径不扩大。 */
    private boolean isCreatorFreeBizType(String bizType) {
        if (bizType == null) {
            return false;
        }
        String normalized = bizType.trim();
        return "topic".equals(normalized) || "topic_club".equals(normalized) || "activity".equals(normalized);
    }

    private int insertReceiver(String sourceType, Long sourceId, Long merchantId,
                               String receiverType, Long receiverId, BigDecimal amount,
                               CmsRegistration registration) {
        SettlementDetail detail = new SettlementDetail();
        if (registration != null) {
            SettlementClaim claim = claimRegistration(registration, receiverType.toUpperCase(), receiverId,
                    "REGISTRATION_SPLIT", "DIRECT_REGISTRATION");
            detail.setClaimId(claim.getId());
            detail.setEntryKind("ORIGIN");
            detail.setEventKey("ORIGIN");
        }
        detail.setBatchId(0L);
        detail.setMerchantId(merchantId);
        detail.setSourceType(sourceType);
        detail.setSourceId(sourceId);
        detail.setReceiverType(receiverType);
        detail.setReceiverId(receiverId);
        detail.setGrossAmount(amount);
        detail.setCommissionAmount(BigDecimal.ZERO);
        detail.setSettlementAmount(amount);
        detail.setStatus(SettlementDetailState.PENDING.getCode());
        detail.setIdempotencyKey(sourceType + ":" + sourceId + ":" + receiverType + ":" + receiverId);
        if (settlementDetailMapper.insertAdjustment(detail) == 1) {
            return 1;
        }
        SettlementDetail current = settlementDetailMapper.selectByIdempotencyKeyForUpdate(
                detail.getIdempotencyKey());
        if (sameReceiverDetail(current, detail)) {
            return 0;
        }
        throw new IllegalStateException("分账明细幂等终态不一致 key=" + detail.getIdempotencyKey());
    }

    private boolean sameReceiverDetail(SettlementDetail current, SettlementDetail expected) {
        return current != null
                && sameText(expected.getSourceType(), current.getSourceType())
                && sameNumber(expected.getClaimId(), current.getClaimId())
                && sameNumber(expected.getSourceId(), current.getSourceId())
                && sameNumber(expected.getMerchantId(), current.getMerchantId())
                && sameText(expected.getReceiverType(), current.getReceiverType())
                && sameNumber(expected.getReceiverId(), current.getReceiverId())
                && sameMoney(expected.getGrossAmount(), current.getGrossAmount())
                && sameMoney(expected.getCommissionAmount(), current.getCommissionAmount())
                && sameMoney(expected.getSettlementAmount(), current.getSettlementAmount())
                && (Integer.valueOf(SettlementDetailState.PENDING.getCode()).equals(current.getStatus())
                    || Integer.valueOf(SettlementDetailState.SETTLED.getCode()).equals(current.getStatus()));
    }

    private boolean sameNumber(Number expected, Number actual) {
        return expected == null ? actual == null : actual != null && expected.longValue() == actual.longValue();
    }

    private boolean sameText(String expected, String actual) {
        return expected == null ? actual == null : expected.equals(actual);
    }

    private boolean sameMoney(BigDecimal expected, BigDecimal actual) {
        return expected == null ? actual == null : actual != null && expected.compareTo(actual) == 0;
    }

    private SettlementClaim claimRegistration(CmsRegistration registration, String beneficiaryRole,
            Long beneficiaryId, String route, String sourceContractType) {
        String contextType = Long.valueOf(1L).equals(registration.getOwnerType())
                ? "TOPIC_EDITION" : "ACTIVITY";
        return settlementClaimService.claim(SettlementClaim.request(contextType, registration.getOwnerId(),
                "REGISTRATION", registration.getId(), beneficiaryRole, beneficiaryId,
                "MERCHANT_PARTICIPATION", route, sourceContractType, registration.getId()));
    }

    private BigDecimal share(BigDecimal gross, BigDecimal rate) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return gross.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal firstNonNull(BigDecimal first, BigDecimal second, BigDecimal third) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return third == null ? BigDecimal.ZERO : third;
    }
}
