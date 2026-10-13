package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.MemberActivityFundsRow;
import com.chengyinhub.business.mapper.ActivitySettlementHoldMapper;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.mapper.CoopSettlementAdjustmentMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 余额页三段(2026-09-16 晚拍板第 5 条):待结算(活动未结束)→ 客诉期中(X月X日可提现)→ 可提现;
 * 涉诉金额单列「客诉处理中」。
 *
 * <p>钱在哪一段完全由结算闸决定,这里只是把闸的判据原样读出来,不另立口径:
 * <ul>
 *   <li>活动未结束 → 待结算;</li>
 *   <li>已结束未结算 → 客诉期中,可提现日 = max(结束 + 7 天, 最晚一张可退票的退款截止),
 *       与 selectActivitiesForSettlement 的两道闸同源;</li>
 *   <li>有未结客诉的报名单(未结算的,或结算时已暂扣的)→ 客诉处理中;</li>
 *   <li>coop T+7 未打款行 → 客诉期中(可提现日 = payable_time);主题有未结客诉整行冻结 → 客诉处理中
 *       (与 payoutOneDueCoopSettlement 的整主题冻结一致);</li>
 *   <li>可提现 = 余额 − 提现冻结额(与两条提现腿同一个 WithdrawalFrozenAmountResolver)。</li>
 * </ul>
 * 活动侧金额是按当前费率估算的应得份额;费率配置异常时整份不返回数字(amountsKnown=false),不编数。
 */
@Service
public class MemberFundsStageService {

    static final long COMPLAINT_PERIOD_MS = 7L * 24 * 3600 * 1000;

    private final ActivitySettlementHoldMapper holdMapper;
    private final ICmsActivityService activityService;
    private final CoopSettlementMapper coopSettlementMapper;
    private final CoopSettlementAdjustmentMapper adjustmentMapper;
    private final CoopComplaintMapper complaintMapper;
    private final UmsMemberMapper umsMemberMapper;
    private final WithdrawalFrozenAmountResolver frozenAmountResolver;

    public MemberFundsStageService(ActivitySettlementHoldMapper holdMapper, ICmsActivityService activityService,
                                   CoopSettlementMapper coopSettlementMapper,
                                   CoopSettlementAdjustmentMapper adjustmentMapper,
                                   CoopComplaintMapper complaintMapper, UmsMemberMapper umsMemberMapper,
                                   WithdrawalFrozenAmountResolver frozenAmountResolver) {
        this.holdMapper = holdMapper;
        this.activityService = activityService;
        this.coopSettlementMapper = coopSettlementMapper;
        this.adjustmentMapper = adjustmentMapper;
        this.complaintMapper = complaintMapper;
        this.umsMemberMapper = umsMemberMapper;
        this.frozenAmountResolver = frozenAmountResolver;
    }

    public Map<String, Object> stages(Long memberId, Date now) {
        String today = day(now);
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal disputed = BigDecimal.ZERO;
        TreeMap<String, BigDecimal> byDate = new TreeMap<>();
        boolean amountsKnown = true;

        for (MemberActivityFundsRow row : holdMapper.selectUnsettledActivityFundsByMember(memberId)) {
            BigDecimal normal = share(row, row.getNormalSales());
            BigDecimal dispute = share(row, row.getDisputedSales());
            if (normal == null || dispute == null) {
                amountsKnown = false;
                continue;
            }
            if (row.getEndDate() == null || row.getEndDate().after(now)) {
                pending = pending.add(normal).add(dispute);
                continue;
            }
            Date available = new Date(row.getEndDate().getTime() + COMPLAINT_PERIOD_MS);
            if (row.getLastRefundDeadline() != null && row.getLastRefundDeadline().after(available)) {
                available = row.getLastRefundDeadline();
            }
            addOn(byDate, laterDay(day(available), today), normal);
            disputed = disputed.add(dispute);
        }
        for (MemberActivityFundsRow row : holdMapper.selectHeldActivityFundsByMember(memberId)) {
            BigDecimal normal = share(row, row.getNormalSales());
            BigDecimal dispute = share(row, row.getDisputedSales());
            if (normal == null || dispute == null) {
                amountsKnown = false;
                continue;
            }
            // 客诉已结、等结算任务下一轮补结的暂扣单
            addOn(byDate, today, normal);
            disputed = disputed.add(dispute);
        }
        for (CoopSettlement line : coopSettlementMapper.selectByMerchant(memberId)) {
            if (line.getStatus() == null || line.getStatus() != 0) {
                continue;
            }
            BigDecimal adjustments = line.getId() == null ? null : adjustmentMapper.sumExecutedBySettlement(line.getId());
            BigDecimal amount = nz(line.getAmount()).add(nz(adjustments));
            if (amount.signum() <= 0) {
                continue;
            }
            if (line.getTopicId() != null && complaintMapper.countOpenByTopicId(line.getTopicId()) > 0) {
                disputed = disputed.add(amount);
            } else {
                addOn(byDate, laterDay(line.getPayableTime() == null ? today : day(line.getPayableTime()), today),
                        amount);
            }
        }

        UmsMember member = umsMemberMapper.selectUmsMemberById(memberId);
        if (member == null) {
            // 查不到人不是「余额 0」,不许给页面一个确定的 ¥0
            throw new com.chengyinhub.common.exception.ServiceException("账户不存在");
        }
        // balance 列可空:新用户/还没收益,按 0(与 /api/user/info 同口径)
        BigDecimal balance = nz(member.getBalance());
        BigDecimal withdrawable = balance.subtract(frozenAmountResolver.resolve(memberId).total()).max(BigDecimal.ZERO);

        List<Map<String, Object>> complaintPeriod = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : byDate.entrySet()) {
            if (e.getValue().signum() <= 0) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("amount", e.getValue());
            item.put("availableDate", e.getKey());
            complaintPeriod.add(item);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("amountsKnown", amountsKnown);
        d.put("pendingSettlement", pending);
        d.put("complaintPeriod", complaintPeriod);
        d.put("disputed", disputed);
        d.put("withdrawable", withdrawable);
        return d;
    }

    private BigDecimal share(MemberActivityFundsRow row, BigDecimal sales) {
        if (sales == null || sales.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        CmsActivity activity = new CmsActivity();
        activity.setId(row.getActivityId());
        activity.setTopicId(row.getTopicId());
        return activityService.estimateMerchantShare(activity, sales);
    }

    private static void addOn(TreeMap<String, BigDecimal> byDate, String date, BigDecimal amount) {
        byDate.put(date, nz(byDate.get(date)).add(amount));
    }

    private static String laterDay(String a, String b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static String day(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd").format(date);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
