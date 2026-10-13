package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.List;
import com.chengyinhub.business.domain.MerchantClawbackApplication;

/**
 * M块:商家违规倒扣(2026-07-25 拍板)。
 *
 * <p><b>解决什么</b>:{@code CoopComplaintMapper} 建单目前只挡
 * {@code CmsTopicServiceImpl.payoutDueCoopSettlements} 的打款<b>前</b>放款,打款<b>后</b>
 * 完全没有倒扣路径。本服务补上「已打款结算款」的事后追回。
 *
 * <p><b>两条互斥路径,发起时由系统自动判定,不让运营去猜</b>:
 * <ul>
 *   <li>默认:商家有未来未打款批次({@code merchant_payable_batch} 的 PENDING/CONFIRMED,
 *       取 period_ym 最小的一个)时,从其 {@code amount_total} 里直接扣减,不碰余额。</li>
 *   <li>兜底:商家没有任何未来可冲抵的批次(已停止合作/全部已 PAID)时,直接扣
 *       {@code ums_member.balance}(允许扣成负数,由对账脚本兜底发现)。</li>
 * </ul>
 *
 * <p><b>必须人工介入</b>:倒扣金额由运营核实投诉后手动填写,不是系统计算;
 * 走「发起 → 复核确认」两步,复核人须≠发起人 —— 与
 * {@code MerchantPayableServiceImpl.markBatchPaid} 的 maker-checker 同型。
 */
public interface IMerchantClawbackService {

    /**
     * 发起倒扣申请。系统按商家当前是否有未来未打款批次自动判定结算路径,
     * 不接受调用方指定路径 —— 避免运营凭感觉选错路径。
     *
     * @param merchantMemberId 被追回的商家
     * @param reason           违规/投诉依据说明(必填)
     * @param amount           倒扣金额,必须 &gt; 0(人工核实后填写,本服务不计算)
     * @param operator         发起人(sys_user.user_name)
     * @return 新建的申请,状态为 PENDING_REVIEW
     */
    MerchantClawbackApplication initiate(Long merchantMemberId, String reason, BigDecimal amount, String operator);

    /**
     * 复核通过并执行:PENDING_REVIEW → EXECUTED。
     *
     * <p>★双人复核:复核人必须≠发起人,否则单个后台账号可以自审自批把钱扣了。
     *
     * <p>执行动作按 {@code settlePath} 分流:OFFSET_BATCH 扣批次 amount_total(CAS,
     * 目标批次执行时已不是 PENDING/CONFIRMED 则整体回滚,申请仍留在待复核态,交由运营
     * 重新处理);BALANCE 直接扣会员余额(允许扣负)。
     *
     * @return false = 该申请已不是待复核态(已执行或已驳回)
     * @throws IllegalStateException 复核人与发起人为同一人,或申请/目标不存在,或目标批次
     *         执行时状态已变化(需运营驳回后另行处理)
     */
    boolean confirmExecute(Long id, String operator);

    /**
     * 驳回:PENDING_REVIEW → REJECTED。不移动任何资金。
     *
     * @return false = 该申请已不是待复核态
     */
    boolean reject(Long id, String operator, String rejectReason);

    MerchantClawbackApplication selectById(Long id);

    List<MerchantClawbackApplication> listByMerchant(Long merchantMemberId);

    /** @param status 可选过滤,null/空 = 全部 */
    List<MerchantClawbackApplication> list(Long merchantMemberId, String status);
}
