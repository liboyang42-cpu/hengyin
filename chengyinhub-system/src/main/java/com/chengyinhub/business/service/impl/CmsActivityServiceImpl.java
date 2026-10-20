package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.ActivitySettlementDTO;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.mapper.CmsCollaboratorsMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsSettingMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.support.OmsTicketWriteValidator;
import com.chengyinhub.business.support.SoldTicketEditGuard;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.enums.PublicActivityScope;
import com.chengyinhub.business.service.ICmsActivityService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 活动Service业务层处理
 *
 * @author webxh
 * @date 2025-08-31
 */
@Service
public class CmsActivityServiceImpl implements ICmsActivityService
{
    private static final Logger logger = LoggerFactory.getLogger(CmsActivityServiceImpl.class);

    @Autowired
    private CmsActivityMapper cmsActivityMapper;

    @Autowired
    private com.chengyinhub.business.service.IAuditTaskService auditTaskService;

    @Autowired
    private CmsCollaboratorsMapper cmsCollaboratorsMapper;

    @Autowired
    private OmsTicketMapper omsTicketMapper;

    @Autowired
    private MemberIdentityService memberIdentityService;

    @Autowired
    private CmsRegistrationMapper cmsRegistrationMapper;

    @Autowired
    private SettlementDetailMapper settlementDetailMapper;

    @Autowired
    private CmsSettingMapper cmsSettingMapper;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    /** 9-15 拍板:已发布免费主题的派生活动不能加收费票(= 转商业)。 */
    @Autowired
    private CommercialTopicPolicy commercialTopicPolicy;

    @Autowired
    private ClubMapper clubMapper;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private IUmsMemberBalanceDetailService umsMemberBalanceDetailService;

    @Autowired
    private IPointsAwardService pointsAwardService;

    // P0-28 抽成收敛:结算费率单一真源(小数),从 cms_setting 百分数列迁来;
    // cms_setting 仅保留读 platform_member_id(该字段不在 settlement_rule 上)。
    @Autowired
    private com.chengyinhub.business.service.ISettlementRuleService settlementRuleService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 与报名二清闸同一判据：独立活动只有平台自办（发布者=平台会员）才可收费。 */
    @org.springframework.beans.factory.annotation.Value("${chengyin.platform.member-id:100000}")
    private Long platformMemberId;

    /** 客诉期提现闸:涉诉报名单结算暂扣,客诉关闭后补结。 */
    @Autowired
    private com.chengyinhub.business.mapper.ActivitySettlementHoldMapper activitySettlementHoldMapper;

    /** 收款方欠款:结算入账先抵欠款;逾期未清不能发布新活动。 */
    @Autowired
    private com.chengyinhub.business.service.support.SettlementDebtLedger settlementDebtLedger;

    /**
     * 获取需要结算的活动列表
     */
    @Override
    public List<CmsActivity> selectActivitiesForSettlement() {
        return cmsActivityMapper.selectActivitiesForSettlement();
    }

    /**
     * 执行活动结算
     */
    @Override
    public int executeActivitySettlement() {
        int settledCount = 0;

        // 1. 获取需要结算的活动列表 + 客诉已结、待补结的暂扣单
        List<CmsActivity> activities = selectActivitiesForSettlement();
        List<com.chengyinhub.business.domain.ActivitySettlementHold> releasable =
                activitySettlementHoldMapper.selectReleasableHolds();
        if (activities.isEmpty() && releasable.isEmpty()) {
            logger.info("没有需要结算的活动");
            return 0;
        }

        // 2. 获取并验证结算费率(P0-28 单一真源:settlement_rule,小数)
        SettlementRule rule = settlementRuleService.getActiveRule(0L, "activity");
        if (!validateActivityCommissionSettings(rule)) {
            logger.error("活动结算费率配置异常，拒结整批，请检查 settlement_rule(biz_type=activity)");
            throw new ServiceException("活动结算费率配置异常，拒结整批，请检查 settlement_rule(biz_type=activity)");
        }

        // 3. 遍历每个活动进行结算
        // HF-02(A-29/A-36): 每个活动独立事务结算——一个活动失败仅回滚该活动,不再因批量 @Transactional
        // 被异常标记 rollback-only 而牵连整批;配合 settleActivity 内的原子领取,做到"恰好一次"入账。
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<String> failures = new java.util.ArrayList<>();
        IllegalStateException failure = new IllegalStateException();
        for (CmsActivity activity : activities) {
            try {
                Boolean settled = tx.execute(status -> {
                    // 平台收款账户可在批次执行期间被管理员更换，必须在每个活动事务内重新读取；
                    // 不能让后续活动继续使用批次开始时的旧快照。
                    CmsSetting setting = cmsSettingMapper.selectCmsSettingById(1L);
                    if (setting == null) {
                        throw new ServiceException("活动结算配置不存在，拒绝结算 activityId=" + activity.getId());
                    }
                    return settleActivity(activity, setting, rule);
                });
                if (Boolean.TRUE.equals(settled)) {
                    settledCount++;
                }
            } catch (Exception e) {
                logger.error("【结算告警】活动结算失败已回滚该活动,activityId={},请人工核查", activity.getId(), e);
                // 继续处理下一个活动;该活动 is_settlement 已随事务回滚复位,下轮可安全重试
                failures.add("activityId=" + activity.getId() + ": " + e.getMessage());
                failure.addSuppressed(e);
            }
        }
        // 客诉期提现闸:结算时暂扣的涉诉报名单,客诉关闭后逐单补结(报名已退则作废)。
        for (com.chengyinhub.business.domain.ActivitySettlementHold hold : releasable) {
            try {
                tx.execute(status -> {
                    CmsSetting setting = cmsSettingMapper.selectCmsSettingById(1L);
                    if (setting == null) {
                        throw new ServiceException("活动结算配置不存在，拒绝补结 holdId=" + hold.getId());
                    }
                    return releaseHeldRegistration(hold, setting, rule);
                });
            } catch (Exception e) {
                logger.error("【结算告警】涉诉暂扣补结失败已回滚,holdId={},请人工核查", hold.getId(), e);
                failures.add("holdId=" + hold.getId() + ": " + e.getMessage());
                failure.addSuppressed(e);
            }
        }
        // R9-46 §4:逐活动失败不能被吞成 SUCCESS。其余活动已各自提交,末端再抛让 Quartz 记 FAIL 并点名。
        if (!failures.isEmpty()) {
            IllegalStateException visible = new IllegalStateException("活动结算失败 " + failures.size()
                    + " 个(成功 " + settledCount + " 个): " + String.join("; ", failures));
            for (Throwable t : failure.getSuppressed()) visible.addSuppressed(t);
            throw visible;
        }

        logger.info("活动结算完成，共结算 {} 个活动", settledCount);
        return settledCount;
    }

    /**
     * 验证活动结算费率(P0-28:读 settlement_rule 小数,不再读 cms_setting 百分数)。
     * rule 缺失 → 拒结整批(绝不静默把平台/设计者份额归零或让商家拿全额)。
     * 平台(platform)+商户(merchant)+设计者(creator)三方之和必须 == 1.0。
     */
    private boolean validateActivityCommissionSettings(SettlementRule rule) {
        if (rule == null) {
            logger.error("活动结算费率缺失(settlement_rule biz_type=activity),拒结整批");
            return false;
        }
        BigDecimal platformRate = rule.getPlatformCommissionRate();
        BigDecimal merchantRate = rule.getMerchantRate();
        BigDecimal designerRate = rule.getCreatorRate();

        if (platformRate == null || merchantRate == null || designerRate == null) {
            logger.error("活动结算费率不完整(platform/merchant/creator 有空值),拒结整批");
            return false;
        }

        BigDecimal totalRate = platformRate.add(merchantRate).add(designerRate);
        if (designerRate.compareTo(BigDecimal.ZERO) != 0) {
            logger.error("H8 要求活动 creator_rate=0，拒绝结算，当前值: {}", designerRate);
            return false;
        }
        if (totalRate.compareTo(BigDecimal.ONE) != 0) {
            // HF-01(A-29): 三方比例之和必须=100%(小数 1.0),否则独立计提会超分/少分,直接拒绝结算防资损
            logger.error("活动结算费率三方之和不为100%，拒绝结算，当前总和: {}", totalRate);
            return false;
        }

        logger.info("活动结算费率验证通过: 平台{}, 商户{}, 设计者{}, 总计{}",
                platformRate, merchantRate, designerRate, totalRate);
        return true;
    }

    /**
     * 结算单个活动
     */
    private boolean settleActivity(CmsActivity activity, CmsSetting setting, SettlementRule rule) {
        CmsTopic topic = activity.getTopicId() == null
                ? null : cmsTopicMapper.selectCmsTopicById(activity.getTopicId());
        if (topic != null && Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
            throw new ServiceException("FREE_EXPLORE_LEGACY_SETTLEMENT_FORBIDDEN: activityId="
                    + activity.getId() + ", topicId=" + activity.getTopicId()
                    + ", productType=" + (topic == null ? null : topic.getProductType()));
        }
        // HF-02(A-29): 原子领取——仅当 is_settlement 由 0 翻 1 成功才继续,防并发/重复触发导致重复入账
        if (cmsActivityMapper.claimActivityForSettlement(activity.getId()) == 0) {
            logger.info("活动 {} 已被结算或正在结算,跳过", activity.getId());
            return false;
        }

        // 1. 计算活动总销售额（已支付且未退款的报名记录，owner_type=2）
        // 客诉期提现闸(9-16 拍板第 3 条):还有未结客诉的报名单只暂扣它自己那部分,其余照放。
        activitySettlementHoldMapper.holdOpenComplaintRegistrations(activity.getId());
        BigDecimal totalSales = cmsRegistrationMapper.selectTotalSalesByActivityId(activity.getId());
        BigDecimal heldSales = activitySettlementHoldMapper.selectHeldSalesByActivityId(activity.getId());
        if (totalSales != null && heldSales != null) {
            totalSales = totalSales.subtract(heldSales);
        }
        if (totalSales == null || totalSales.compareTo(BigDecimal.ZERO) <= 0) {
            logger.info("活动 {} 没有销售额，跳过分润（已通过领取标记为已结算）", activity.getId());
            return true;
        }
        if (activity.getTopicId() != null && (topic == null || topic.getProductType() == null)) {
            throw new ServiceException("NULL_PRODUCT_TYPE_WITH_SALES_FORBIDDEN: activityId="
                    + activity.getId() + ", topicId=" + activity.getTopicId() + ", totalSales=" + totalSales);
        }

        requireUsablePlatformSettlementAccount(setting, rule, activity.getId());

        // 2. 计算各项佣金（含主题设计者，无主题来源时其份额并入商户）
        ActivitySettlementDTO settlement = calculateActivityCommissions(activity, totalSales, rule);

        // 3. 分配商户分润（活动归属商户）
        payActivityCommission(activity.getMemberId(), settlement.getMerchantCommissionAmount(),
                "活动分润 - " + activity.getName(), 3L, activity.getId());
        settlementDebtLedger.offsetFromSettlement(activity.getMemberId(),
                settlement.getMerchantCommissionAmount(), activity.getId());

        // 4. 分配平台分润（打入平台账户）。前置校验失败会抛异常并回滚本活动的原子领取。
        payActivityCommission(setting.getPlatformMemberId(), settlement.getPlatformCommissionAmount(),
                "活动平台分润 - " + activity.getName(), 7L, activity.getId());

        // 5. 同步纯账本:系统1是唯一真实打款方,打款成功后把本次覆盖的报名明细置已结算。
        // 与余额更新同处本活动事务;任一步失败都会连同 is_settlement 领取一起回滚。
        settlementDetailMapper.markActivityRegistrationDetailsSettled(activity.getId());

        // 6. 现金与台账写入完成后才发作品结算积分；已知发布者发奖失败仍回滚本活动全部写入。
        if (topic != null) {
            if (topic.getMemberId() == null) {
                // H8「每主题统一资格 100 积分、全链最多一次」必须 fail-closed:只打日志会让现金落定、
                // 活动被永久标记已结算，积分事后再也补不回来（唯一信号是一条日志）。抛出会连同
                // claimActivityForSettlement 一起回滚，活动下轮可重试且持续告警。
                // 存量对称闸在 migration_20260815_tandianri_h8_rate_convergence.sql 的前置 guard。
                throw new ServiceException("ACTIVITY_SOURCE_TOPIC_PUBLISHER_MISSING: activityId="
                        + activity.getId() + ", topicId=" + topic.getId());
            }
            pointsAwardService.awardPublisherSettlement(topic.getMemberId(), topic.getId());
        }

        // 7. is_settlement 已在步骤 0 的原子领取时置 1（含于本活动事务,失败会一并回滚）

        logger.info("活动结算成功: activityId={}, 总销售额={}, 商户佣金={}, 设计者佣金={}, 平台佣金={}",
                activity.getId(), totalSales, settlement.getMerchantCommissionAmount(),
                settlement.getDesignerCommissionAmount(), settlement.getPlatformCommissionAmount());

        return true;
    }

    @Override
    public BigDecimal estimateMerchantShare(CmsActivity activity, BigDecimal sales) {
        SettlementRule rule = settlementRuleService.getActiveRule(0L, "activity");
        if (activity == null || sales == null || !validateActivityCommissionSettings(rule)) {
            return null;
        }
        return calculateActivityCommissions(activity, sales, rule).getMerchantCommissionAmount();
    }

    /**
     * 补结一张涉诉暂扣的报名单:客诉已关闭 ⇒ 仍是已付未退就按活动同一费率给商家/平台入账,
     * 已退款就作废(这单从没给商家打过钱,也就无需冲销)。与 settleActivity 同一独立事务粒度。
     */
    private boolean releaseHeldRegistration(com.chengyinhub.business.domain.ActivitySettlementHold hold,
                                            CmsSetting setting, SettlementRule rule) {
        // 锁报名行:与退款改 registration_status 串行,避免「补结入账」与「退款判未打款不冲销」交错。
        CmsRegistration registration = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(hold.getRegistrationId());
        boolean stillPaid = registration != null
                && Integer.valueOf(2).equals(registration.getPaymentStatus())
                && Integer.valueOf(2).equals(registration.getRegistrationStatus());
        // 选单在事务外读出,锁行后复查:其间又开了客诉就继续暂扣,下轮再看。
        if (stillPaid && activitySettlementHoldMapper.countOpenComplaintsByRegistrationId(hold.getRegistrationId()) > 0) {
            return false;
        }
        if (!stillPaid) {
            activitySettlementHoldMapper.claimHold(hold.getId(),
                    com.chengyinhub.business.domain.ActivitySettlementHold.VOIDED);
            return false;
        }
        if (activitySettlementHoldMapper.claimHold(hold.getId(),
                com.chengyinhub.business.domain.ActivitySettlementHold.RELEASED) != 1) {
            return false;
        }
        BigDecimal sales = registration.getPayableAmount();
        if (sales == null || sales.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        CmsActivity activity = cmsActivityMapper.selectCmsActivityById(hold.getActivityId());
        if (activity == null) {
            throw new ServiceException("暂扣补结的活动不存在 holdId=" + hold.getId());
        }
        requireUsablePlatformSettlementAccount(setting, rule, activity.getId());
        ActivitySettlementDTO settlement = calculateActivityCommissions(activity, sales, rule);
        payActivityCommission(activity.getMemberId(), settlement.getMerchantCommissionAmount(),
                "活动分润(客诉结案补结) - " + activity.getName(), 3L, activity.getId());
        settlementDebtLedger.offsetFromSettlement(activity.getMemberId(),
                settlement.getMerchantCommissionAmount(), activity.getId());
        payActivityCommission(setting.getPlatformMemberId(), settlement.getPlatformCommissionAmount(),
                "活动平台分润(客诉结案补结) - " + activity.getName(), 7L, activity.getId());
        settlementDetailMapper.markRegistrationDetailsSettled(registration.getId());
        return true;
    }

    private void requireUsablePlatformSettlementAccount(CmsSetting setting, SettlementRule rule, Long activityId) {
        if (rule.getPlatformCommissionRate().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Long memberId = setting == null ? null : setting.getPlatformMemberId();
        // settleActivity 在 TransactionTemplate 中执行。先锁行再复查，使后台禁用/删除与之后的三方入账串行化；
        // 锁持有到本活动事务结束，因此必须在商户、设计者付款前完成。
        if (memberId == null || umsMemberService.lockMemberById(memberId) == null) {
            throw new ServiceException("活动平台收款账户未配置或不可用，拒绝结算 activityId=" + activityId);
        }
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        if (!isUsablePlatformSettlementAccount(member)) {
            throw new ServiceException("活动平台收款账户未配置或不可用，拒绝结算 activityId=" + activityId);
        }
    }

    private boolean isUsablePlatformSettlementAccount(UmsMember member) {
        return member != null
                && Long.valueOf(1L).equals(member.getStatus())
                && Integer.valueOf(0).equals(member.getDelFlag());
    }

    /**
     * 计算活动各项佣金
     */
    private ActivitySettlementDTO calculateActivityCommissions(CmsActivity activity, BigDecimal totalSales, SettlementRule rule) {
        ActivitySettlementDTO settlement = new ActivitySettlementDTO();
        settlement.setActivityId(activity.getId());
        settlement.setActivityName(activity.getName());
        settlement.setTotalSales(totalSales);

        // 设置佣金比例（P0-28:小数,来自 settlement_rule;designer→creator 映射）
        settlement.setPlatformCommissionRate(rule.getPlatformCommissionRate());
        settlement.setMerchantCommissionRate(rule.getMerchantRate());
        settlement.setDesignerCommissionRate(rule.getCreatorRate());

        // 解析主题设计者（活动有主题来源时取主题发布者）
        Long designerMemberId = resolveDesignerMemberId(activity);
        settlement.setDesignerMemberId(designerMemberId);

        // 计算佣金金额（比例为小数,直接相乘,不再 ÷100）
        settlement.setPlatformCommissionAmount(
                totalSales.multiply(settlement.getPlatformCommissionRate()).setScale(2, RoundingMode.HALF_UP)
        );

        BigDecimal merchantAmount = totalSales.multiply(settlement.getMerchantCommissionRate()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal designerAmount = totalSales.multiply(settlement.getDesignerCommissionRate()).setScale(2, RoundingMode.HALF_UP);

        if (designerMemberId == null) {
            // 无主题设计者（独立活动）：设计者份额并入商户
            merchantAmount = merchantAmount.add(designerAmount);
            designerAmount = BigDecimal.ZERO;
        }
        settlement.setMerchantCommissionAmount(merchantAmount);
        settlement.setDesignerCommissionAmount(designerAmount);

        // 验证佣金分配是否合理
        validateActivityCommissionDistribution(settlement, totalSales);

        return settlement;
    }

    /**
     * 解析活动的主题设计者会员ID：活动有 topicId 且主题存在时取主题发布者，否则为空（独立活动）
     */
    private Long resolveDesignerMemberId(CmsActivity activity) {
        if (activity.getTopicId() == null) {
            return null;
        }
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(activity.getTopicId());
        if (topic == null) {
            logger.warn("活动 {} 关联的主题 {} 不存在，按无设计者处理", activity.getId(), activity.getTopicId());
            return null;
        }
        return topic.getMemberId();
    }

    /**
     * 验证活动佣金分配
     */
    private void validateActivityCommissionDistribution(ActivitySettlementDTO settlement, BigDecimal totalSales) {
        BigDecimal distributedAmount = settlement.getPlatformCommissionAmount()
                .add(settlement.getMerchantCommissionAmount())
                .add(settlement.getDesignerCommissionAmount());

        BigDecimal difference = totalSales.subtract(distributedAmount);

        if (difference.abs().compareTo(new BigDecimal("0.01")) > 0) {
            logger.warn("活动佣金分配存在差异: 总销售额={}, 已分配金额={}, 差异={}",
                    totalSales, distributedAmount, difference);
        }
    }

    /**
     * 给指定会员发放一笔活动分润，并写入余额流水
     *
     * @param memberId   收款会员ID（正额分润时必须存在）
     * @param amount     分润金额（<=0 则跳过）
     * @param reason     流水变更原因
     * @param eventType  事件类型：3商户 6设计者 7平台
     * @param activityId 活动ID
     */
    private void payActivityCommission(Long memberId, BigDecimal amount, String reason, Long eventType, Long activityId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (memberId == null) {
            throw new ServiceException("活动现金收款会员缺失，拒绝结算 activityId=" + activityId
                    + ", eventType=" + eventType);
        }

        try {
            UmsMember member = umsMemberService.selectUmsMemberById(memberId);
            if (member == null) {
                throw new ServiceException("活动现金收款会员不存在，拒绝结算 activityId=" + activityId
                        + ", memberId=" + memberId + ", eventType=" + eventType);
            }
            if (Long.valueOf(7L).equals(eventType) && !isUsablePlatformSettlementAccount(member)) {
                throw new ServiceException("活动平台收款账户不可用，拒绝结算 activityId=" + activityId);
            }

            // 记录当前余额
            BigDecimal currentBalance = member.getBalance() != null ? member.getBalance() : BigDecimal.ZERO;

            // 增加会员余额
            if (!umsMemberService.addBalance(memberId, amount)) {
                throw new ServiceException("活动现金余额更新失败，拒绝结算 activityId=" + activityId
                        + ", memberId=" + memberId + ", eventType=" + eventType);
            }

            // 获取增加后的余额
            UmsMember updated = umsMemberService.selectUmsMemberById(memberId);
            if (updated == null || updated.getBalance() == null) {
                throw new ServiceException("活动现金余额回读失败，拒绝结算 activityId=" + activityId
                        + ", memberId=" + memberId + ", eventType=" + eventType);
            }
            BigDecimal afterBalance = updated.getBalance();

            // 创建余额记录
            UmsMemberBalanceDetail balanceDetail = new UmsMemberBalanceDetail();
            balanceDetail.setMemberId(memberId);
            balanceDetail.setCurrentBalance(currentBalance);
            balanceDetail.setAfterBalance(afterBalance);
            balanceDetail.setChangeBalance(amount);
            balanceDetail.setChangeReason(reason);
            balanceDetail.setEventType(eventType);
            balanceDetail.setEventId(activityId);
            balanceDetail.setChangeType(1L); // 1表示收入
            balanceDetail.setDelFlag(0L);
            balanceDetail.setCreateTime(new Date());
            if (umsMemberBalanceDetailService.insertUmsMemberBalanceDetail(balanceDetail) != 1) {
                throw new ServiceException("活动现金流水写入失败，拒绝结算 activityId=" + activityId
                        + ", memberId=" + memberId + ", eventType=" + eventType);
            }

            logger.info("活动分润分配成功: memberId={}, amount={}, reason={}, currentBalance={}, afterBalance={}",
                    memberId, amount, reason, currentBalance, afterBalance);

        } catch (Exception e) {
            logger.error("活动分润分配失败, memberId: {}, activityId: {}", memberId, activityId, e);
            throw new RuntimeException("活动分润分配失败", e);
        }
    }

    /**
     * 查询活动
     *
     * @param id 活动主键
     * @return 活动
     */
    @Override
    public CmsActivity selectCmsActivityById(Long id)
    {
        return cmsActivityMapper.selectCmsActivityById(id);
    }

    @Override
    public CmsActivity selectPublicCmsActivityById(Long id)
    {
        return cmsActivityMapper.selectPublicCmsActivityById(id);
    }

    @Override
    public CmsActivity selectCmsActivityDetailById(Long id, Long viewerMemberId)
    {
        return cmsActivityMapper.selectCmsActivityDetailById(id, viewerMemberId);
    }

    @Override
    public int countPaidRegistrations(Long activityId)
    {
        if (activityId == null) {
            return 0;
        }
        CmsRegistration q = new CmsRegistration();
        q.setOwnerType(2L);
        q.setOwnerId(activityId);
        q.setPaymentStatus(2);
        return cmsRegistrationMapper.countCmsRegistrationList(q);
    }

    /**
     * [W9] 待支付({@code payment_status=0})的报名数 —— 单还活着,钱随时可能进来。
     *
     * <p>⚠️ <b>不按 {@code pay_expire_time} 划线</b>:该字段后端没有强制力(支付口从不校验它,
     * 过期单照样能换到支付参数并支付成功)⇒「窗口已过 = 安全」不成立。弃单的解堵靠
     * {@code orderCloseTask} 把 0 关成 3,不靠这里放行。详见
     * {@code CmsTopicServiceImpl.assertTopicDeletable} 的完整说明与前提。
     */
    @Override
    public int countPendingRegistrations(Long activityId)
    {
        if (activityId == null) {
            return 0;
        }
        CmsRegistration q = new CmsRegistration();
        q.setOwnerType(2L);
        q.setOwnerId(activityId);
        q.setPaymentStatus(0);
        return cmsRegistrationMapper.countCmsRegistrationList(q);
    }

    /**
     * 开卖 = cms_registration 上仍活着的单：payment_status 0 待支付 / 1 支付中 / 2 已支付。
     * 3 支付失败(含关单)与 4 已退款不算；未支付也算开卖。
     */
    @Override
    public int countOpenSaleRegistrations(Long activityId)
    {
        if (activityId == null) {
            return 0;
        }
        int n = 0;
        int[] openSaleStatuses = new int[] {0, 1, 2};
        for (int status : openSaleStatuses) {
            CmsRegistration q = new CmsRegistration();
            q.setOwnerType(2L);
            q.setOwnerId(activityId);
            q.setPaymentStatus(status);
            n += cmsRegistrationMapper.countCmsRegistrationList(q);
        }
        return n;
    }

    /**
     * 已报名 = registration_status=2(与活动详情页 registrationCount 同一判据)。
     */
    @Override
    public int countSignedUpRegistrations(Long activityId)
    {
        if (activityId == null) {
            return 0;
        }
        CmsRegistration q = new CmsRegistration();
        q.setOwnerType(2L);
        q.setOwnerId(activityId);
        q.setRegistrationStatus(2);
        return cmsRegistrationMapper.countCmsRegistrationList(q);
    }

    @Override
    public Map<Long, Integer> countSignedUpRegistrationsByActivityIds(List<Long> activityIds) {
        if (activityIds == null || activityIds.isEmpty()) return java.util.Collections.emptyMap();
        Map<Long, Integer> counts = new java.util.HashMap<>();
        for (Map<String, Object> row : cmsActivityMapper.selectSignedUpRegistrationCounts(activityIds)) {
            counts.put(((Number) row.get("activityId")).longValue(),
                    ((Number) row.get("signupCount")).intValue());
        }
        return counts;
    }

    @Override
    public Map<Long, Integer> countSignedUpRegistrationsByTopicIds(List<Long> topicIds) {
        if (topicIds == null || topicIds.isEmpty()) return java.util.Collections.emptyMap();
        Map<Long, Integer> counts = new java.util.HashMap<>();
        for (Map<String, Object> row : cmsActivityMapper.selectTopicSignedUpRegistrationCounts(topicIds)) {
            counts.put(((Number) row.get("topicId")).longValue(),
                    ((Number) row.get("signupCount")).intValue());
        }
        return counts;
    }

    @Override
    public Map<Long, Integer> countPendingVerificationByActivityIds(List<Long> activityIds) {
        if (activityIds == null || activityIds.isEmpty()) return java.util.Collections.emptyMap();
        Map<Long, Integer> counts = new java.util.HashMap<>();
        for (Map<String, Object> row : cmsActivityMapper.selectPendingVerificationCounts(activityIds)) {
            counts.put(((Number) row.get("activityId")).longValue(),
                    ((Number) row.get("pendingVerifyCount")).intValue());
        }
        return counts;
    }

    /**
     * 查询活动列表
     *
     * @param cmsActivity 活动
     * @return 活动
     */
    @Override
    public List<CmsActivity> selectCmsActivityList(CmsActivity cmsActivity)
    {
        return cmsActivityMapper.selectCmsActivityList(cmsActivity);
    }

    @Override
    public List<CmsActivity> selectPublicCmsActivityList(CmsActivity cmsActivity, PublicActivityScope scope)
    {
        if (scope == null)
        {
            // fail-closed:宁可炸,也不要静默按「什么都不过滤」跑 —— 那正是本次修复要杀的形态。
            throw new IllegalArgumentException("公开活动查询必须显式指定可见性范围");
        }
        // 两道闸在这里统一拨,调用方碰不到 —— 它们曾经是可选 <if>,四个调用方拼出了四套不同的闸。
        cmsActivity.setPublicOnly(scope.excludesClubActivities() ? 1 : null);
        cmsActivity.setIsNew(scope.isLiveOnly() ? 1 : 0);
        return cmsActivityMapper.selectPublicCmsActivityList(cmsActivity);
    }

    /**
     * 新增活动
     *
     * @param cmsActivity 活动
     * @return 结果
     */
    @Override
    public int insertCmsActivity(CmsActivity cmsActivity)
    {
        cmsActivity.setCreateTime(DateUtils.getNowDate());
        return cmsActivityMapper.insertCmsActivity(cmsActivity);
    }

    /**
     * 修改活动
     *
     * @param cmsActivity 活动
     * @return 结果
     */
    @Override
    public int updateCmsActivity(CmsActivity cmsActivity)
    {
        cmsActivity.setUpdateTime(DateUtils.getNowDate());
        return cmsActivityMapper.updateCmsActivity(cmsActivity);
    }

    /**
     * 批量删除活动
     *
     * @param ids 需要删除的活动主键
     * @return 结果
     */
    @Override
    public int deleteCmsActivityByIds(Long[] ids)
    {
        return cmsActivityMapper.deleteCmsActivityByIds(ids);
    }

    /**
     * 删除活动信息
     *
     * @param id 活动主键
     * @return 结果
     */
    @Override
    public int deleteCmsActivityById(Long id)
    {
        return cmsActivityMapper.deleteCmsActivityById(id);
    }

    @Override
    public int selectCmsActivityNumByMemberId(Long member_id){
        return cmsActivityMapper.selectCmsActivityNumByMemberId(member_id);
    }
    /**
     * 活动发布
     * @param request CmsActivityPublishRequest
     * @param memberId  发布用户ID
     * @param username 发布用户昵称
     * @param preReview true=先审后发;false=先发后审
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publishActivity(CmsActivityPublishRequest request, Long memberId,String username, boolean preReview) {
        // 9-16 拍板第 2 条:欠款逾期未补足 ⇒ 限制交易(不能上架新活动)。
        settlementDebtLedger.requireNoOverdueDebt(memberId);
        if (request.getClubId() != null && request.getClubId() > 0) {
            Club club = clubMapper.selectClubByIdForUpdate(request.getClubId());
            if (club == null) {
                throw new IllegalArgumentException("俱乐部已解散或不存在，不能发布活动");
            }
            if (club.getMemberId() == null || !club.getMemberId().equals(memberId)) {
                throw new IllegalArgumentException("只能向自己主理的俱乐部发布活动");
            }
        }
        requirePaidPublisherIdentity(request, memberId);
        // 发布入口建的都是独立活动(topic_id 为空)。
        assertStandalonePaidTicketsCollectible(request, memberId);
        // 1. 构建活动实体
        CmsActivity activity = new CmsActivity();

        activity.setMemberId(memberId);
        activity.setName(request.getName());
        activity.setDescription(request.getDescription());
        activity.setImgUrl(request.getImgUrl());
        activity.setImgArr(request.getImgArr());
        activity.setCategoryIds(request.getCategoryIds());
        activity.setAddressName(request.getAddressName());
        activity.setLongitude(request.getLongitude());
        activity.setLatitude(request.getLatitude());
        activity.setAddress(request.getAddress());

        // 处理开始时间：设置为当天的 00:00:00
        activity.setStartDate(request.getStartDate());

        // 处理结束时间：设置为当天的 23:59:59
        activity.setEndDate(request.getEndDate());

        activity.setTemplateId(request.getTemplateId());

        // M1-3 社群活动:俱乐部主理人发社群活动时挂 club_id(公域列表不出,仅成员可见)
        activity.setClubId(request.getClubId());
        // 分级审核(2026-10-20 拍板,推翻 M0-6「活动一律先审后发」):活动按内容处理,默认先发后审,
        // 直接上架(status=1/publishStatus=1),事后靠举报/抽检/图片回调下架;
        // 高风险类目、未审核商家、总闸 pre_review_all 由 ActivityReviewPolicy 判成 preReview →
        // 仍待审(status=0/publishStatus=0),公域列表不出,审核台通过后 applyModeration 置 1 上架。
        activity.setStatus(preReview ? 0L : 1L);
        activity.setPublishStatus(preReview ? 0L : 1L);
        activity.setDelFlag(0); // 0->未删除

        // 设置创建信息
        //String username = getAppUserName();
        activity.setCreateBy(username);
        activity.setCreateTime(new Date());
        activity.setUpdateBy(username);
        activity.setUpdateTime(new Date());

        // 2. 插入活动记录
        int activityResult = cmsActivityMapper.insertCmsActivity(activity);
        if (activityResult <= 0) {
            throw new RuntimeException("创建活动失败");
        }
        Long activityId = activity.getId();

        // 先审后发的活动建审核任务(best-effort,失败不阻断);先发后审不建阻断单
        if (preReview) {
            try {
                String snap = "{\"bizType\":\"ACTIVITY\",\"bizId\":" + activityId
                        + ",\"name\":\"" + (activity.getName() == null ? "" : activity.getName().replace("\"", "'")) + "\"}";
                auditTaskService.submit(com.chengyinhub.business.domain.AuditTask.BIZ_ACTIVITY, activityId, memberId, "user_publish", snap);
            } catch (Exception ignore) {
                logger.error("活动发布审核任务提交失败 activityId={}", activityId, ignore);
            }
        }

        //3. 处理合作者
        if (request.getCollaborators() != null && !request.getCollaborators().isEmpty()) {
            for (Long collaboratorId : request.getCollaborators()) {
                CmsCollaborators collaborator = new CmsCollaborators();
                collaborator.setDataType(2L);
                collaborator.setDataId(activityId);
                collaborator.setMemberId(memberId);
                collaborator.setCreateTime(new Date());
                cmsCollaboratorsMapper.insertCmsCollaborators(collaborator);
            }
        }

        //4. 处理票务
        if (request.getTickets() != null && !request.getTickets().isEmpty()) {
            for (ActivityTicketRequest ticketRequest : request.getTickets()) {
                OmsTicket ticket = new OmsTicket();
                ticket.setMemberId(memberId);
                ticket.setOwnerType(2L);
                ticket.setOwnerId(activityId);
                ticket.setName(ticketRequest.getName());
                ticket.setPrice(ticketRequest.getPrice());
                ticket.setStartTime(ticketRequest.getStartTime());
                ticket.setEndTime(ticketRequest.getEndTime());
                ticket.setTotalInventory(Convert.toLong(ticketRequest.getTotalStock()));
                ticket.setMode(ticketRequest.getMode());
                ticket.setMeetingPoint(ticketRequest.getMeetingPoint());
                ticket.setTeamSize(ticketRequest.getTeamSize());
                ticket.setRemainingInventory(ticket.getTotalInventory()); // 剩余库存=总库存
                ticket.setDelFlag(0);
                ticket.setCreateBy(username);
                ticket.setCreateTime(new Date());

                OmsTicketWriteValidator.validateNewTicket(ticket);
                // CU-C-48:活动票的场次时间必须落在本活动 start_date–end_date 内。
                OmsTicketWriteValidator.validateTicketWindowWithinActivity(ticket.getStartTime(),
                        ticket.getEndTime(), activity.getStartDate(), activity.getEndDate());
                omsTicketMapper.insertOmsTicket(ticket);
            }
        }

        return activityId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateActivityByOwner(CmsActivityPublishRequest request, Long memberId, String username) {
        if (request == null || request.getId() == null || request.getId() < 1
                || memberId == null || memberId <= 0) {
            throw new ServiceException("参数不合规");
        }
        CmsActivity editable = toEditableActivity(request);
        editable.setId(request.getId());
        editable.setMemberId(memberId);
        editable.setUpdateBy(username);
        editable.setUpdateTime(new Date());
        // 顺序是两条闸的交集,别再动(2026-09-15 集成第二段合并 lifecycle-refund × publish-rules):
        //   ① 免费转商业整单拒必须在任何票务写之前 —— FreeTopicNoCommercialConversionTest 钉死
        //      verify(ticketMapper, never()).update/insertOmsTicket,「事务回滚」不算数,要求压根不写;
        //   ② 票务同步仍先于主表更新 —— 已售锁若拒绝,活动主表一行都还没被写(publish-rules 的 P0)。
        // 两条同时成立只有这一种排法:policy 闸 → 票务同步 → 主表。
        assertNoPaidTicketOnFreeTopic(request);
        requirePaidPublisherIdentity(request, memberId);
        CmsActivity currentForCollect = cmsActivityMapper.selectCmsActivityById(request.getId());
        if (currentForCollect != null && currentForCollect.getTopicId() == null) {
            // PR10 复审:存量收费独立活动不能因收款闸连文字都改不了;编辑只拦「新增收费票/0 元改收费/涨价」。
            assertStandaloneEditKeepsCollectible(request, memberId);
        }
        syncLiveActivityTickets(request, request.getId(), memberId, username);
        int rows = cmsActivityMapper.updateCmsActivityByOwner(editable);
        if (rows <= 0) {
            throw new ServiceException("更新失败");
        }
        return rows;
    }

    /**
     * F-L4-01/F-W2-01：独立活动没有主题内容槽，收款主体只能按发布者判。非平台发布的收费独立活动
     * 下单必被 CmsRegistrationServiceImpl.assertCollectible 拒绝(二清)，发布时就拒，
     * 与商家主办「票价只能是 0」同口径，避免上架一场玩家付不了款的活动。
     */
    private void assertStandalonePaidTicketsCollectible(CmsActivityPublishRequest request, Long memberId) {
        if (request.getTickets() == null) return;
        boolean paid = false;
        for (ActivityTicketRequest ticket : request.getTickets()) {
            if (ticket != null && ticket.getPrice() != null && ticket.getPrice().signum() > 0) {
                paid = true;
                break;
            }
        }
        if (!paid) return;
        if (platformMemberId != null && platformMemberId > 0L && platformMemberId.equals(memberId)) return;
        throw new ServiceException("独立活动暂不支持在线收款(平台正在办理支付资质)，票价只能是 0");
    }

    /**
     * 编辑口径的收款闸:存量已收费且价格不升的票原样放行(玩家下单仍由 assertCollectible 兜底),
     * 只拒绝把收费面扩大的改动 —— 新增收费票、0 元票改收费、在库价基础上涨价。发布仍走全量闸。
     */
    private void assertStandaloneEditKeepsCollectible(CmsActivityPublishRequest request, Long memberId) {
        if (request.getTickets() == null) return;
        if (platformMemberId != null && platformMemberId > 0L && platformMemberId.equals(memberId)) return;
        List<OmsTicket> live = null;
        for (ActivityTicketRequest ticket : request.getTickets()) {
            if (ticket == null || ticket.getPrice() == null || ticket.getPrice().signum() <= 0) continue;
            if (live == null) {
                OmsTicket query = new OmsTicket();
                query.setOwnerType(2L);
                query.setOwnerId(request.getId());
                query.setDelFlag(0);
                live = omsTicketMapper.selectOmsTicketList(query);
                if (live == null) live = java.util.Collections.emptyList();
            }
            OmsTicket existing = findLiveTicket(live, ticket.getId());
            if (existing == null || existing.getPrice() == null || existing.getPrice().signum() <= 0
                    || ticket.getPrice().compareTo(existing.getPrice()) > 0) {
                throw new ServiceException("独立活动暂不支持在线收款(平台正在办理支付资质)，票价只能是 0");
            }
        }
    }

    private void requirePaidPublisherIdentity(CmsActivityPublishRequest request, Long memberId) {
        if (request.getTickets() == null) return;
        for (ActivityTicketRequest ticket : request.getTickets()) {
            if (ticket != null && ticket.getPrice() != null && ticket.getPrice().signum() > 0) {
                String error = memberIdentityService.checkRegistered(memberId);
                if (error != null) throw new ServiceException(error);
                return;
            }
        }
    }

    /**
     * 9-15 拍板:挂在已发布免费主题上的活动,不能新增收费票或把 0 元票改成收费(= 免费转商业)。
     * 活动票不在 CommercialTopicPolicy.evaluate 口径内,所以按请求逐票比对现存价格;原本就收费的票不在此闸。
     */
    private void assertNoPaidTicketOnFreeTopic(CmsActivityPublishRequest request) {
        if (request.getTickets() == null) return;
        CmsActivity current = cmsActivityMapper.selectCmsActivityById(request.getId());
        if (current == null || current.getTopicId() == null) return;
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(current.getTopicId());
        if (topic == null || !commercialTopicPolicy.isConversionProtected(topic)) return;
        OmsTicket query = new OmsTicket();
        query.setOwnerType(2L);
        query.setOwnerId(request.getId());
        query.setDelFlag(0);
        List<OmsTicket> live = omsTicketMapper.selectOmsTicketList(query);
        for (ActivityTicketRequest ticket : request.getTickets()) {
            if (ticket == null || ticket.getPrice() == null || ticket.getPrice().signum() <= 0) continue;
            OmsTicket existing = findLiveTicket(live == null ? java.util.Collections.<OmsTicket>emptyList() : live,
                    ticket.getId());
            if (existing == null || existing.getPrice() == null || existing.getPrice().signum() <= 0) {
                throw new ServiceException(CommercialTopicPolicy.FREE_TO_COMMERCIAL_REJECTED);
            }
        }
    }

    /** 普通编辑只接收活动内容字段，对齐 CmsActivityController.toEditableActivity。 */
    private CmsActivity toEditableActivity(CmsActivityPublishRequest request)
    {
        CmsActivity editable = new CmsActivity();
        editable.setName(request.getName());
        editable.setDescription(request.getDescription());
        editable.setImgUrl(request.getImgUrl());
        editable.setImgArr(request.getImgArr());
        editable.setCategoryIds(request.getCategoryIds());
        editable.setAddressName(request.getAddressName());
        editable.setLongitude(request.getLongitude());
        editable.setLatitude(request.getLatitude());
        editable.setAddress(request.getAddress());
        editable.setStartDate(request.getStartDate());
        editable.setEndDate(request.getEndDate());
        editable.setTemplateId(request.getTemplateId());
        return editable;
    }

    /**
     * 只对 del_flag=0 的活票做原地更新或软删；入参带已删票 id 时忽略该 id 并插入新票。
     *
     * <p>先整体校验再落笔:已售出(有用户下单)的票,时间/集合点/价格/退款规则被锁且不许删;
     * 任何一处越界都在写任何票之前抛出,由调用方事务兜底。未售出的票照常可改可删。
     */
    private void syncLiveActivityTickets(CmsActivityPublishRequest request, Long activityId,
                                         Long memberId, String username) {
        if (request.getTickets() == null) {
            return;
        }
        OmsTicket query = new OmsTicket();
        query.setOwnerType(2L);
        query.setOwnerId(activityId);
        query.setDelFlag(0);
        List<OmsTicket> live = omsTicketMapper.selectOmsTicketList(query);
        if (live == null) {
            live = java.util.Collections.emptyList();
        }
        java.util.Set<Long> soldTicketIds = openSaleTicketIds(activityId);
        assertSoldTicketEditsAllowed(request.getTickets(), live, soldTicketIds);

        java.util.Set<Long> kept = new java.util.HashSet<Long>();
        for (ActivityTicketRequest ticketRequest : request.getTickets()) {
            // CU-C-48:在这批票写下去之前先把窗口挡在外面 —— 与 publishActivity / 后台票务写口
            // 同一把尺子，不然「活动 9.24、票种 10.1」这种自相矛盾的场次还能从编辑路径造成。
            OmsTicketWriteValidator.validateTicketWindowWithinActivity(ticketRequest.getStartTime(),
                    ticketRequest.getEndTime(), request.getStartDate(), request.getEndDate());
            OmsTicket existingLive = findLiveTicket(live, ticketRequest.getId());
            if (existingLive != null) {
                updateLiveTicket(existingLive, ticketRequest, username);
                kept.add(existingLive.getId());
            } else {
                insertLiveTicket(ticketRequest, activityId, memberId, username);
            }
        }
        for (OmsTicket liveTicket : live) {
            if (liveTicket.getId() != null && !kept.contains(liveTicket.getId())) {
                OmsTicket del = new OmsTicket();
                del.setId(liveTicket.getId());
                del.setDelFlag(2);
                omsTicketMapper.updateOmsTicket(del);
            }
        }
    }

    /**
     * 已售锁(2026-09-15 裁决 15 的总控判定):票一旦有用户下单(待支付 0/支付中 1/已支付 2),
     * 时间、集合点、价格、退款规则不能再改,也不能从活动里删掉 —— 玩家按当时的票面成交,
     * 追溯改写票据等于把已成交订单的履约与退款口径一起挪走。只允许改票名等文字。
     *
     * <p>未售出的票不受影响;请求里没带的活票 = 删除意图,已售的那张直接拒。
     */
    private void assertSoldTicketEditsAllowed(List<ActivityTicketRequest> requested, List<OmsTicket> live,
                                              java.util.Set<Long> soldTicketIds) {
        java.util.Set<Long> requestedLiveIds = new java.util.HashSet<Long>();
        for (ActivityTicketRequest ticketRequest : requested) {
            OmsTicket existingLive = findLiveTicket(live, ticketRequest.getId());
            if (existingLive == null) {
                continue;
            }
            requestedLiveIds.add(existingLive.getId());
            if (!soldTicketIds.contains(existingLive.getId())) {
                continue;
            }
            String lockedChange = SoldTicketEditGuard.lockedChange(existingLive, lockedFieldProbe(ticketRequest));
            if (lockedChange != null) {
                throw new ServiceException("该活动已有用户下单，已售出的票（" + ticketNameOf(ticketRequest, existingLive)
                        + "）不能修改" + lockedChange + "；票名等文字信息仍可改");
            }
        }
        for (OmsTicket liveTicket : live) {
            if (liveTicket.getId() != null && soldTicketIds.contains(liveTicket.getId())
                    && !requestedLiveIds.contains(liveTicket.getId())) {
                throw new ServiceException("该活动已有用户下单，已售出的票（" + liveTicket.getName()
                        + "）不能删除；票名等文字信息仍可改");
            }
        }
    }

    /** 只带被锁字段的探测对象;写口 {@link #updateLiveTicket} 的字段映射若变了这里要同步。 */
    private OmsTicket lockedFieldProbe(ActivityTicketRequest ticketRequest) {
        OmsTicket probe = new OmsTicket();
        probe.setStartTime(ticketRequest.getStartTime());
        probe.setEndTime(ticketRequest.getEndTime());
        probe.setMeetingPoint(ticketRequest.getMeetingPoint());
        probe.setGatherLng(ticketRequest.getGatherLng());
        probe.setGatherLat(ticketRequest.getGatherLat());
        probe.setPrice(ticketRequest.getPrice());
        probe.setRefundRule(ticketRequest.getRefundRule());
        return probe;
    }

    private String ticketNameOf(ActivityTicketRequest ticketRequest, OmsTicket existingLive) {
        if (ticketRequest.getName() != null && !ticketRequest.getName().trim().isEmpty()) {
            return ticketRequest.getName();
        }
        return existingLive.getName() == null ? "" : existingLive.getName();
    }

    /** 活动上仍活着的报名(0/1/2)引用到的票 id —— 已售锁的判据。 */
    private java.util.Set<Long> openSaleTicketIds(Long activityId) {
        List<Long> ids = cmsRegistrationMapper.selectOpenSaleTicketIdsByActivity(activityId);
        if (ids == null || ids.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<Long> result = new java.util.HashSet<Long>();
        for (Long id : ids) {
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    private OmsTicket findLiveTicket(List<OmsTicket> live, Long ticketId) {
        if (ticketId == null || ticketId < 1 || live == null) {
            return null;
        }
        for (OmsTicket ticket : live) {
            if (ticketId.equals(ticket.getId())) {
                return ticket;
            }
        }
        return null;
    }

    private void updateLiveTicket(OmsTicket existing, ActivityTicketRequest ticketRequest, String username) {
        OmsTicket patch = new OmsTicket();
        patch.setId(existing.getId());
        patch.setName(ticketRequest.getName());
        patch.setPrice(ticketRequest.getPrice());
        patch.setStartTime(ticketRequest.getStartTime());
        patch.setEndTime(ticketRequest.getEndTime());
        Long newTotal = Convert.toLong(ticketRequest.getTotalStock());
        patch.setTotalInventory(newTotal);
        patch.setRemainingInventory(OmsTicketWriteValidator.remainingKeepingOccupiedSeats(existing, newTotal));
        patch.setMode(ticketRequest.getMode());
        patch.setMeetingPoint(ticketRequest.getMeetingPoint());
        patch.setTeamSize(ticketRequest.getTeamSize());
        patch.setMinPeople(ticketRequest.getMinPeople());
        patch.setMaxPeople(ticketRequest.getMaxPeople());
        patch.setSignupDeadline(ticketRequest.getSignupDeadline());
        patch.setGatherLng(ticketRequest.getGatherLng());
        patch.setGatherLat(ticketRequest.getGatherLat());
        patch.setRefundRule(ticketRequest.getRefundRule());
        patch.setNeedAudit(ticketRequest.getNeedAudit());
        patch.setUpdateBy(username);
        patch.setUpdateTime(new Date());
        OmsTicketWriteValidator.validatePrice(patch.getPrice());
        OmsTicketWriteValidator.validateTicketTimeWindow(patch.getStartTime(), patch.getEndTime());
        omsTicketMapper.updateOmsTicket(patch);
    }

    private void insertLiveTicket(ActivityTicketRequest ticketRequest, Long activityId,
                                  Long memberId, String username) {
        OmsTicket ticket = new OmsTicket();
        ticket.setMemberId(memberId);
        ticket.setOwnerType(2L);
        ticket.setOwnerId(activityId);
        ticket.setName(ticketRequest.getName());
        ticket.setPrice(ticketRequest.getPrice());
        ticket.setStartTime(ticketRequest.getStartTime());
        ticket.setEndTime(ticketRequest.getEndTime());
        ticket.setTotalInventory(Convert.toLong(ticketRequest.getTotalStock()));
        ticket.setMode(ticketRequest.getMode());
        ticket.setMeetingPoint(ticketRequest.getMeetingPoint());
        ticket.setTeamSize(ticketRequest.getTeamSize());
        ticket.setMinPeople(ticketRequest.getMinPeople());
        ticket.setMaxPeople(ticketRequest.getMaxPeople());
        ticket.setSignupDeadline(ticketRequest.getSignupDeadline());
        ticket.setGatherLng(ticketRequest.getGatherLng());
        ticket.setGatherLat(ticketRequest.getGatherLat());
        ticket.setRefundRule(ticketRequest.getRefundRule());
        ticket.setNeedAudit(ticketRequest.getNeedAudit());
        ticket.setRemainingInventory(ticket.getTotalInventory());
        ticket.setDelFlag(0);
        ticket.setCreateBy(username);
        ticket.setCreateTime(new Date());
        OmsTicketWriteValidator.validateNewTicket(ticket);
        omsTicketMapper.insertOmsTicket(ticket);
    }
}
