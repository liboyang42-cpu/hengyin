package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.marketing.MarketingRevenuePostingTask;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.mapper.MarketingRevenuePostingTaskMapper;
import com.chengyinhub.business.service.IMarketingChannelRevenueReversal;
import com.chengyinhub.business.service.MarketingChannelRefundFactByIdReader;
import com.chengyinhub.common.utils.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 渠道退款完成 → 净额冲销入队（typed，独立于微信退款链路）。
 *
 * <p>调用方（唯一）：{@code MarketingProductFulfillmentServiceImpl.consumeVerifiedChannelRefund}，
 * 在服务端已确认渠道退款、且与撤销准入**同一事务**内调用。本类只插入一条可重试任务，
 * 不计算金额、不读同单未提交数据、不改准入状态。
 *
 * <p><b>事实来源</b>：{@link MarketingChannelRefundFactByIdReader}（M8 实现）按不可变主键
 * 回读 {@code marketing_channel_refund_verification}。调用方传来的 order/provider/refundNo/hash
 * 只用于核对；金额与币种在消费时再回读一次，队列里不存金额。
 *
 * <p><b>为什么只入一条任务</b>：唯一键 {@code (task_kind, ref_id)} 用
 * {@code KIND_CHANNEL_REVERSE + 渠道退款事实 id}。一条渠道退款事实对应一条任务；
 * 多作品包（N 行注册单）由消费方按范围逐条冲销，避免每行一个任务把同一退款重复计一次。
 *
 * <p><b>部分退款</b>：当前没有既定撤权/冲销口径，明确拒绝且不假装成功；
 * 退款事实在 M8 侧以 REJECTED/原因码持久留存，供人工对账处理。
 */
@Service
public class ChannelRevenueReversalServiceImpl implements IMarketingChannelRevenueReversal {

    private static final Logger log = LoggerFactory.getLogger(ChannelRevenueReversalServiceImpl.class);

    private final MarketingChannelRefundFactByIdReader refundFactReader;
    private final MarketingRevenuePostingTaskMapper taskMapper;

    public ChannelRevenueReversalServiceImpl(MarketingChannelRefundFactByIdReader refundFactReader,
                                             MarketingRevenuePostingTaskMapper taskMapper) {
        this.refundFactReader = refundFactReader;
        this.taskMapper = taskMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long enqueueChannelReversal(String channelOrderNo, String providerKey, String channelRefundNo,
                                       long refundFactId, String refundFactHash) {
        if (isBlank(channelOrderNo) || isBlank(providerKey) || isBlank(channelRefundNo)) {
            throw MarketingRevenueException.badRequest("CHANNEL_REFUND_KEY_REQUIRED",
                    "渠道退款入队缺少订单号/provider/退款单号");
        }
        if (refundFactId <= 0) {
            throw MarketingRevenueException.badRequest("CHANNEL_FACT_REQUIRED", "缺少渠道退款事实标识");
        }
        if (isBlank(refundFactHash)) {
            throw MarketingRevenueException.badRequest("CHANNEL_FACT_HASH_REQUIRED", "缺少渠道退款事实哈希");
        }
        MarketingChannelRefundFactByIdReader.Fact fact = refundFactReader.requireVerifiedById(refundFactId);
        if (fact == null) {
            // 未验证/未确认：可重试，不按调用方声称的金额入队。
            throw MarketingRevenueException.conflict("CHANNEL_REFUND_FACT_UNVERIFIED",
                    "渠道退款事实未通过服务端验证，冲销任务暂不入队");
        }
        if (!fact.confirmed) {
            throw MarketingRevenueException.conflict("CHANNEL_REFUND_NOT_CONFIRMED",
                    "渠道退款尚未确认完成，不得冲销");
        }
        // 重复退款单/变更载荷：只有全部字段与原事实一致才允许入队（重放会命中幂等键，不双记）。
        if (!channelOrderNo.trim().equals(fact.parentOrderNo)) {
            throw MarketingRevenueException.conflict("CHANNEL_REFUND_ORDER_CONFLICT",
                    "渠道退款事实与原订单不一致");
        }
        if (!providerKey.trim().equals(fact.providerKey)) {
            throw MarketingRevenueException.conflict("CHANNEL_REFUND_PROVIDER_CONFLICT",
                    "渠道退款事实与 provider 不一致");
        }
        if (!channelRefundNo.trim().equals(fact.channelRefundNo)) {
            throw MarketingRevenueException.conflict("CHANNEL_REFUND_NO_CONFLICT",
                    "同一退款单号对应了不同的渠道退款事实");
        }
        if (!refundFactHash.trim().equals(fact.factHash)) {
            throw MarketingRevenueException.conflict("CHANNEL_REFUND_HASH_CONFLICT",
                    "渠道退款事实哈希与入队参数不一致");
        }
        if (!fact.fullRefund) {
            throw MarketingRevenueException.conflict("PARTIAL_CHANNEL_REFUND_UNSUPPORTED",
                    "渠道部分退款未定义撤权与冲销口径，保留待人工处理");
        }
        List<Long> scope = fact.registrationIds;
        if (scope == null || scope.isEmpty()) {
            throw MarketingRevenueException.conflict("CHANNEL_REFUND_SCOPE_EMPTY",
                    "渠道退款事实没有可冲销的注册行范围，保留待人工核对");
        }

        Date now = DateUtils.getNowDate();
        MarketingRevenuePostingTask task = new MarketingRevenuePostingTask();
        task.setTaskKind(MarketingRevenuePostingTask.KIND_CHANNEL_REVERSE);
        task.setRefId(Long.valueOf(refundFactId));
        task.setOrderId(fact.registrationId == null ? scope.get(0) : fact.registrationId);
        task.setStatus(MarketingRevenuePostingTask.STATUS_PENDING);
        task.setAttempts(0);
        task.setNextAttemptAt(now);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        taskMapper.enqueue(task);
        MarketingRevenuePostingTask stored = taskMapper.selectByKindAndRef(
                MarketingRevenuePostingTask.KIND_CHANNEL_REVERSE, Long.valueOf(refundFactId));
        if (stored == null) {
            throw new IllegalStateException("渠道冲销任务入队后读不到 factId=" + refundFactId);
        }
        log.info("[M8] 渠道退款冲销入队 factId={} orderNo={} provider={} refundNo={} scope={} taskId={}",
                refundFactId, channelOrderNo, providerKey, channelRefundNo, scope.size(), stored.getId());
        return stored.getId();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
