package com.chengyinhub.business.service;

import java.util.Date;
import java.util.List;
import com.chengyinhub.business.domain.LedgerDiscrepancy;

/**
 * 四账对账 Service 合同(P1-6 N块 feature/p1-four-ledger-reconcile)。
 *
 * <p>依据 P1-5《四账映射与守恒规则》(chengyinhub-admin/sql/_四账映射与守恒规则_20260725.sql)
 * 冻结的映射表,对以下三条已批准的守恒式做只读比对,产出差异明细:
 * <ol>
 *   <li>微信交易 vs 报名/订单:{@code sum(微信交易成功金额) == cms_registration.wechat_payment_amount}
 *       (逐笔比对,见 {@link #reconcileWechatBillAgainstRegistrations})</li>
 *   <li>内部资金流水自洽:{@code sum(收入) - sum(支出) == 当前余额快照}
 *       (见 {@link #reconcileMemberBalanceConservation})</li>
 *   <li>商家结算真源自洽(merchant_payable 为真源,settlement_detail/coop_settlement 为辅助,
 *       本轮只对真源做批次冻结完整性校验):{@code merchant_payable_batch.amount_total ==
 *       当前 sum(merchant_payable 净额)}(见 {@link #reconcileMerchantPayableBatchIntegrity})</li>
 * </ol>
 *
 * <p>★本轮范围边界(与任务书一致,不越界):
 * <ul>
 *   <li>只产出差异明细,<b>不做自动补偿/自动重放</b>——"差异可重放"留给后续阶段;</li>
 *   <li>微信交易维度依赖 {@code wx_trade_bill_snapshot} 表,本轮该表大概率是空表(P1-7 才接入
 *       真实下载),{@link #reconcileWechatBillAgainstRegistrations} 对此显式判断并跳过,
 *       不会把"账单未下载"误报成"金额对不上";</li>
 *   <li>settlement_detail/coop_settlement 只是辅助台账,不参与本轮的比对断言(见 P1-5 doc:
 *       它们口径与 merchant_payable 不完全重叠,归一化视图留给后续决定)。</li>
 * </ul>
 */
public interface IFourLedgerReconcileService {

    /**
     * 维度一:微信交易 vs 报名/订单。
     *
     * <p>对指定账单日已支付(且含微信支付部分)的报名订单,逐笔核对
     * {@code wx_trade_bill_snapshot} 中该订单号(biz_type=支付)的金额之和是否等于
     * {@code cms_registration.wechat_payment_amount}。
     *
     * <p>⚠️ 若 {@code wx_trade_bill_snapshot} 在该账单日一行都没有(账单尚未下载,P1-7 前的
     * 常态),本方法返回空列表——"未下载"不是"对不上",不应产出误报差异。
     *
     * @param billDate 账单日期(T日)
     * @return 差异明细(维度="微信交易vs报名订单");若该日账单未下载,返回空列表
     */
    List<LedgerDiscrepancy> reconcileWechatBillAgainstRegistrations(Date billDate);

    /**
     * 维度二:内部资金流水自洽。
     *
     * <p>{@code sum(change_type=1收入) - sum(change_type=2支出) == 当前余额快照(ums_member.balance)}。
     * 超出容差(见实现类 TOLERANCE 常量)视为差异——分润/提现等场景可能有分单元的舍入,不应逐分必较。
     *
     * @param memberId 用户ID
     * @return 单条差异明细的列表(不违反守恒式时返回空列表)
     */
    List<LedgerDiscrepancy> reconcileMemberBalanceConservation(Long memberId);

    /**
     * 维度三:商家结算真源(merchant_payable)批次冻结完整性。
     *
     * <p>批次生成时 {@code merchant_payable_batch.amount_total} 冻结了当时的净额快照;
     * 若该批次生成后,同一 (merchant_member_id, period_ym) 又有新的 REVERSAL 行追加进来
     * (如已打款批次事后被冲正),当前净额会与冻结值产生偏差——这正是需要人工介入的差异
     * (钱可能已经打出去了,但账上欠数变了)。
     *
     * <p>若该商家该期尚未生成批次,不视为差异(还没到结算时点),返回空列表。
     *
     * @param merchantMemberId 商家 memberId
     * @param periodYm 结算期 YYYY-MM
     * @return 单条差异明细的列表(未生成批次或未产生偏差时返回空列表)
     */
    List<LedgerDiscrepancy> reconcileMerchantPayableBatchIntegrity(Long merchantMemberId, String periodYm);
}
