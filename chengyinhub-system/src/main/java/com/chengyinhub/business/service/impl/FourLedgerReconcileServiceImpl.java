package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.LedgerDiscrepancy;
import com.chengyinhub.business.domain.MerchantPayableBatch;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.WxTradeBillSnapshot;
import com.chengyinhub.business.domain.WxBillImport;
import com.chengyinhub.business.mapper.FourLedgerReconcileMapper;
import com.chengyinhub.business.mapper.MerchantPayableBatchMapper;
import com.chengyinhub.business.mapper.WxTradeBillSnapshotMapper;
import com.chengyinhub.business.mapper.WxBillImportMapper;
import com.chengyinhub.common.config.WxPayConfig;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.business.service.IFourLedgerReconcileService;
import com.chengyinhub.business.service.IMerchantPayableService;
import com.chengyinhub.business.service.IUmsMemberService;

/**
 * 四账对账 Service 实现(P1-6 N块 feature/p1-four-ledger-reconcile)。
 *
 * <p>只读:全部方法只查询、比对、产出 {@link LedgerDiscrepancy},不写任何库表,不做自动补偿/重放。
 * 三条守恒式与范围边界见 {@link IFourLedgerReconcileService} 类注释,依据 P1-5《四账映射与守恒规则》。
 */
@Service
public class FourLedgerReconcileServiceImpl implements IFourLedgerReconcileService {

    /** 金额比对容差(元)。分润/汇总场景可能有分单元舍入,超出此值才判定为差异。 */
    static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    @Autowired
    private FourLedgerReconcileMapper reconcileMapper;

    @Autowired
    private WxTradeBillSnapshotMapper wxTradeBillSnapshotMapper;

    @Autowired
    private WxBillImportMapper wxBillImportMapper;

    @Autowired
    private WxPayConfig wxPayConfig;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private IMerchantPayableService merchantPayableService;

    @Autowired
    private MerchantPayableBatchMapper merchantPayableBatchMapper;

    @Override
    public List<LedgerDiscrepancy> reconcileWechatBillAgainstRegistrations(Date billDate) {
        List<LedgerDiscrepancy> discrepancies = new ArrayList<LedgerDiscrepancy>();
        if (billDate == null) {
            return discrepancies;
        }

        String merchantId = wxPayConfig.getMerchantId();
        if (merchantId == null || merchantId.isBlank()) {
            throw new ServiceException("微信商户未配置，账单待核实", 409);
        }
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate billDay = billDate instanceof java.sql.Date sqlDate ? sqlDate.toLocalDate()
                : java.time.Instant.ofEpochMilli(billDate.getTime()).atZone(zone).toLocalDate();
        Date day = Date.from(billDay.atStartOfDay(zone).toInstant());
        WxBillImport imported = wxBillImportMapper.selectImport(merchantId, "TRADE", day);
        if (imported == null || !"SUCCESS".equals(imported.getStatus())
                || imported.getRowCount() == null || imported.getRowCount() < 0
                || imported.getSourceHash() == null || imported.getSourceHash().isBlank()) {
            throw new ServiceException("交易账单尚未完整导入，待核实", 409);
        }

        List<CmsRegistration> registrations = reconcileMapper.selectPaidWechatRegistrationsByBillDate(day);
        for (CmsRegistration r : registrations) {
            BigDecimal expected = nz(r.getWechatPaymentAmount());
            BigDecimal actual = nz(wxTradeBillSnapshotMapper.sumAmountByOutTradeNoAndBizType(
                    r.getRegistrationNo(), WxTradeBillSnapshot.BIZ_TYPE_PAYMENT));
            if (diverges(expected, actual)) {
                discrepancies.add(new LedgerDiscrepancy(
                        "微信交易vs报名订单", r.getRegistrationNo(), expected, actual,
                        actual.signum() == 0
                                ? "微信账单中未找到该商户订单号的支付成功记录"
                                : "微信账单金额与报名订单记录的微信支付金额不一致"));
            }
        }
        return discrepancies;
    }

    @Override
    public List<LedgerDiscrepancy> reconcileMemberBalanceConservation(Long memberId) {
        List<LedgerDiscrepancy> discrepancies = new ArrayList<LedgerDiscrepancy>();
        if (memberId == null) {
            return discrepancies;
        }

        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        if (member == null) {
            return discrepancies;
        }

        BigDecimal income = nz(reconcileMapper.sumBalanceIncomeByMember(memberId));
        BigDecimal expense = nz(reconcileMapper.sumBalanceExpenseByMember(memberId));
        BigDecimal expected = income.subtract(expense);
        BigDecimal actual = nz(member.getBalance());

        if (diverges(expected, actual)) {
            discrepancies.add(new LedgerDiscrepancy(
                    "内部资金流水自洽", String.valueOf(memberId), expected, actual,
                    "余额明细流水净额(收入-支出)与用户当前余额快照不一致"));
        }
        return discrepancies;
    }

    @Override
    public List<LedgerDiscrepancy> reconcileMerchantPayableBatchIntegrity(Long merchantMemberId, String periodYm) {
        List<LedgerDiscrepancy> discrepancies = new ArrayList<LedgerDiscrepancy>();
        if (merchantMemberId == null || periodYm == null) {
            return discrepancies;
        }

        MerchantPayableBatch batch = merchantPayableBatchMapper.selectByMerchantAndPeriod(merchantMemberId, periodYm);
        if (batch == null) {
            // 尚未生成批次,不视为差异——还没到结算时点。
            return discrepancies;
        }

        BigDecimal frozen = nz(batch.getAmountTotal());
        BigDecimal currentNet = nz(merchantPayableService.netPayable(merchantMemberId, periodYm));

        if (diverges(frozen, currentNet)) {
            discrepancies.add(new LedgerDiscrepancy(
                    "商家结算真源批次完整性", merchantMemberId + ":" + periodYm, frozen, currentNet,
                    "批次(状态=" + batch.getStatus() + ")生成时冻结的净额与当前应付台账净额不一致,"
                            + "可能是批次生成后又发生了冲正/计提,需要人工核实(尤其若批次已 PAID)"));
        }
        return discrepancies;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean diverges(BigDecimal expected, BigDecimal actual) {
        return actual.subtract(expected).abs().compareTo(TOLERANCE) > 0;
    }
}
