package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * FINAL 之前的<b>事实门</b>（审计 2「暂结与终结动作合同」）。
 *
 * <p>契约里 final 是「先重算、<b>跑事实门和守恒断言</b>，再允许质量、票源与额外工时应付生成」。
 * 事实表逐条：
 * <ul>
 *   <li>期已结束、仍有退款窗内票 → 终结<b>拒绝</b>（{@code EDITION_FINALIZATION_BLOCKED_REFUND_WINDOW}）</li>
 *   <li>未结补履约/投诉 → 终结<b>拒绝</b>（{@code EDITION_FINALIZATION_BLOCKED_CASE}）</li>
 * </ul>
 *
 * <p>为什么这道门必须在：FINAL 一期只能一次（{@code uk_topic_edition_finalization}），
 * 而定责结果晚到时台账已经落了三条应付并可能进了批次。旧结算腿早就有客诉冻结这道闸
 * （{@code CmsTopicServiceImpl} 那处「有未结工单 → 暂缓放款」），新腿此前对它零调用。
 *
 * <p><b>已知边界（不假装有）</b>：本仓 {@code oms_ticket.refund_rule} 是自由文本，
 * <b>没有</b>可计算的逐票退款窗。所以「仍有退款窗内票」这一条落成两个可观测的事实：
 * ①本期履约窗（开售冻结的 {@code fulfillment_end_at}）还没结束；②该期还有未落定的退款单。
 * 逐票退款截止真正可算之前，这道门覆盖不到「窗内但还没人提退款」那一格 —— 它是
 * EXTERNAL_GATE，不是本类假装挡住了。
 */
@Component
public class EditionFinalFactGate {

    private final CoopComplaintMapper complaintMapper;
    private final RefundApplicationMapper refundMapper;

    public EditionFinalFactGate(CoopComplaintMapper complaintMapper, RefundApplicationMapper refundMapper) {
        this.complaintMapper = complaintMapper;
        this.refundMapper = refundMapper;
    }

    /**
     * @param fulfillmentEndAt 开售冻结的履约窗结束瞬间；缺它一律拒绝（缺事实不放行）
     */
    public void assertFinalizable(Long topicId, Date now, Date fulfillmentEndAt) {
        if (topicId == null || now == null) {
            throw new IllegalArgumentException("EDITION_FINAL_ARGUMENT_REQUIRED");
        }
        // 缺冻结事实不放行：null 的履约窗结束意味着这一期的条款是历史遗留/被改坏的，不是「随便结」。
        if (fulfillmentEndAt == null || now.before(fulfillmentEndAt)) {
            throw new IllegalStateException("EDITION_FINALIZATION_BLOCKED_EDITION_RUNNING");
        }
        if (refundMapper.countUnsettledRefundsByTopic(topicId) > 0) {
            throw new IllegalStateException("EDITION_FINALIZATION_BLOCKED_REFUND_WINDOW");
        }
        if (complaintMapper.countOpenByTopicId(topicId) > 0) {
            throw new IllegalStateException("EDITION_FINALIZATION_BLOCKED_CASE");
        }
    }
}
