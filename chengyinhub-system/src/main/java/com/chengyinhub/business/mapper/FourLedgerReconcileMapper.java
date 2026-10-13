package com.chengyinhub.business.mapper;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CmsRegistration;

/**
 * 四账对账只读查询 Mapper(P1-6 N块新建,不改动 K/L/M 已有 mapper)。
 *
 * <p>★全部只读,不含 insert/update/delete——四账对账本轮只产出差异明细,不做自动补偿/重放。
 * 刻意不复用 {@code CmsRegistrationMapper}/{@code UmsMemberBalanceDetailMapper} 已有方法之外的
 * 新查询,避免修改这两个文件(它们不在 N 块独占文件清单里,改了容易和其他分支冲突)。
 * 能复用的既有只读方法(如 {@code IMerchantPayableService.netPayable}、
 * {@code MerchantPayableBatchMapper.selectByMerchantAndPeriod}、
 * {@code IUmsMemberService.selectUmsMemberById})直接在 Service 层注入复用,不在此重复造轮子。
 */
public interface FourLedgerReconcileMapper {

    /**
     * 某日已支付且含微信支付部分的报名订单(paymentStatus=已支付,paymentTime 落在 billDate 当天)。
     *
     * <p>只填充 id / registrationNo / paymentStatus / paymentType / payableAmount /
     * wechatPaymentAmount / pointPaymentAmount / paymentTime 六个字段,其余字段为 null
     * ——本查询只服务对账,不是完整订单详情查询。
     */
    List<CmsRegistration> selectPaidWechatRegistrationsByBillDate(@Param("billDate") Date billDate);

    /** 用户余额收入流水之和(change_type=1),该用户一行都没有时返回 null。 */
    BigDecimal sumBalanceIncomeByMember(@Param("memberId") Long memberId);

    /** 用户余额支出流水之和(change_type=2),该用户一行都没有时返回 null。 */
    BigDecimal sumBalanceExpenseByMember(@Param("memberId") Long memberId);
}
