package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubPayable;
import com.chengyinhub.business.domain.ClubPayableBatch;
import com.chengyinhub.business.domain.TopicEditionFinalization;

import java.util.Date;

public interface ITopicEditionFinalizationService {

    /** FINAL：只消费 F1/F2 的不可变事实；重放返回同一结果，不重复应付。 */
    TopicEditionFinalization finalizeEdition(Long topicId, Date now, Long operatorId);

    /**
     * 撤销：写反向新行，绝不 UPDATE 原 accrual。
     *
     * <p>{@code topicId} 是<b>作用域参数</b>，不是冗余：Controller 拿它过 canEditTopic，
     * service 再拿它和台账行自报的 topic_id 对一遍。裸一个 payableId 就能写负向资金行是不行的。
     */
    ClubPayable reversePayable(Long topicId, Long payableId, Long operatorId, String reason);

    /**
     * 红冲之后按 append-only 重新计提。
     *
     * <p>FINAL 头一期一行、幂等键恒 {@code FINAL:topicId}，所以「重跑 finalizeEdition」永远读回旧头、
     * 也永远撞 {@code uk_club_payable_idem} —— 没有这条方法，红冲只能把钱抵成 0，正确金额进不了台账。
     *
     * <p>做法：按 component 把台账净额与<b>当下重算</b>的事实比，差额追加一条新行（正差 ACCRUAL、
     * 负差 REVERSAL），幂等键带修订序号。FINAL 头是终结快照，<b>不就地 UPDATE</b>。
     */
    java.util.List<ClubPayable> reaccrueAfterReversal(Long topicId, Date now, Long operatorId, String reason);

    /**
     * 后台只读操作面：本期的 FINAL 头 + 应付原始行 + 批次（含付款轴与发票轴）。
     *
     * <p>没有它，红冲 / 更正 / 批次四个动作都要求操作者<b>先连生产库</b>把 id 捞出来 ——
     * 与「后台是这套动作的唯一操作面」直接冲突。返回 {@code finalization / payables / batches}
     * 三个键；每一行都按 {@code topic_id} 收窄并逐行回读核对，绝不跨期。
     */
    java.util.Map<String, Object> editionLedgerView(Long topicId);

    /**
     * 建批次：<b>一期一批次</b>（E4）。topicId 与 clubId 共同定位这张付款单，
     * 收进来的每一行都必须真属于这一期这个俱乐部。
     */
    ClubPayableBatch createBatch(Long topicId, Long clubId, Long operatorId);

    /**
     * 批次确认。
     *
     * <p>★{@code topicId} 是<b>承重的作用域参数</b>，与 {@link #reversePayable} 同形：批次自报的
     * {@code topic_id} 必须等于它，否则一个裸 batchId 就能把别的期的钱确认掉。这四个批次动作的
     * 授权由各自的 {@code @PreAuthorize} 专用权限承担（确认/开票 → clubpayable:confirm，
     * 付款登记 → clubpayable:paid），对象归属由这里的回读承担。
     */
    ClubPayableBatch confirmBatch(Long topicId, Long batchId, Long operatorId);

    /** 付款登记；登记人不得是该批次的确认人。topicId 同 {@link #confirmBatch}，承重。 */
    ClubPayableBatch markBatchPaid(Long topicId, Long batchId, Long operatorId, String paymentReference);

    /** 发票轴：开票。与付款状态互不影响 —— 「已打款未开票」是合法状态。topicId 承重。 */
    ClubPayableBatch markBatchInvoiceIssued(Long topicId, Long batchId, String invoiceNo, Long operatorId);

    /** 发票轴：红冲。必须先 ISSUED 才能 RED。topicId 承重。 */
    ClubPayableBatch markBatchInvoiceRed(Long topicId, Long batchId, String redInvoiceNo, Long operatorId);
}
