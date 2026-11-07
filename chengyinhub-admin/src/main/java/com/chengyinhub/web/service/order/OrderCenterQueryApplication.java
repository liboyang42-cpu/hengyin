package com.chengyinhub.web.service.order;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.ClubMembershipInvoice;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.mapper.ClubMembershipMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.IClubMembershipService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.NotActiveMerchantException;
import com.chengyinhub.business.service.impl.MerchantCommerceService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.service.registration.RegistrationQueryApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/** 微信入口只解析指定支付尝试；所有状态均来自已授权的本地业务记录。 */
@Service
@Transactional(readOnly = true)
public class OrderCenterQueryApplication {
    @Autowired private IWechatPayService wechatPayService;
    @Autowired private CmsRegistrationMapper registrationMapper;
    @Autowired private RegistrationQueryApplication registrationQueryApplication;
    @Autowired private ClubMembershipMapper membershipMapper;
    @Autowired private IClubMembershipService membershipService;
    @Autowired private IMerchantAccessService merchantAccessService;
    @Autowired private MerchantCommerceService commerceService;
    @Autowired private ICoopInviteService coopInviteService;

    public AjaxResult detail(Long principal, String outTradeNo) {
        if (principal == null || principal <= 0) return new AjaxResult(401, "请先登录");
        if (outTradeNo == null || !outTradeNo.matches("[A-Za-z0-9_|*\\-]{6,32}")) {
            return new AjaxResult(400, "订单编号不合法");
        }
        try {
            String businessNo = wechatPayService.resolveBusinessOrderNo(outTradeNo);
            if (businessNo == null || businessNo.isBlank() || businessNo.startsWith("WXP")) {
                return new AjaxResult(404, "订单不存在");
            }
            if (businessNo.startsWith("CLM")) return membership(principal, businessNo);
            if (commerceService.supportsOutTradeNo(businessNo)) return commerce(principal, businessNo);
            if (businessNo.startsWith("DEP")) return deposit(principal, businessNo);
            return registration(principal, businessNo);
        } catch (ServiceException denied) {
            return new AjaxResult(denied.getCode() == null ? 403 : denied.getCode(), denied.getMessage());
        } catch (IllegalArgumentException | NotActiveMerchantException denied) {
            return new AjaxResult(403, "无权查看该订单");
        }
    }

    private AjaxResult registration(Long principal, String number) {
        CmsRegistration location = registrationMapper.selectByRegistrationNo(number);
        if (location == null) return new AjaxResult(404, "订单不存在");
        if (!principal.equals(location.getMemberId())) return new AjaxResult(403, "无权查看该订单");
        AjaxResult authorized = registrationQueryApplication.getRegistrationDetail(principal, decimalId(location.getId()));
        if (!Integer.valueOf(200).equals(authorized.get("code"))) return authorized;
        if (!(authorized.get("data") instanceof CmsRegistration row)
                || !number.equals(row.getRegistrationNo())) return new AjaxResult(404, "订单不存在");
        return snapshot("REGISTRATION", row.getId(), number, "报名订单",
                registrationStatus(row.getPaymentStatus()), row.getPayableAmount());
    }

    private AjaxResult membership(Long principal, String number) {
        ClubMembershipInvoice location = membershipMapper.selectInvoiceByOutTradeNo(number);
        if (location == null) return new AjaxResult(404, "订单不存在");
        ClubMembershipInvoice row = membershipService.invoiceReadOnly(principal, location.getClubId(), location.getId());
        if (row == null || !number.equals(row.getOutTradeNo())) return new AjaxResult(404, "订单不存在");
        String status = switch (row.getStatus() == null ? "" : row.getStatus()) {
            case "CREATED" -> "待支付";
            case "PAYING" -> "支付中";
            case "PAID" -> "已支付";
            case "CLOSED" -> "已关闭";
            default -> "状态未知";
        };
        return snapshot("CLM", row.getId(), number,
                row.getPlanNameSnapshot() == null || row.getPlanNameSnapshot().isBlank() ? "俱乐部会费" : row.getPlanNameSnapshot(),
                status, row.getAmountFen() == null ? null : BigDecimal.valueOf(row.getAmountFen(), 2));
    }

    private AjaxResult commerce(Long principal, String number) {
        MerchantAccessContext access = merchantAccessService.require(principal, MerchantPermission.PROFILE_WRITE);
        OmsOrder row = commerceService.readOwnedOrder(access.getOwnerMemberId(), access.getMerchantId(), number);
        String title = switch (row.getBizType() == null ? "" : row.getBizType()) {
            case MerchantCommerceService.BIZ_PREMIUM_TEMPLATE -> "城瘾高级模板权益";
            case MerchantCommerceService.BIZ_PROMOTION_SLOT -> "城瘾推广位权益";
            case MerchantCommerceService.BIZ_CUSTOM_EVENT -> "城瘾活动定制权益";
            default -> "商家权益订单";
        };
        String status = row.getStatus() == null ? "状态未知" : switch (commerceService.statusOf(row)) {
            case "success" -> "已支付";
            case "failed" -> "已关闭";
            case "pending" -> "待支付";
            default -> "状态未知";
        };
        return snapshot("COMMERCE", row.getId(), row.getOrderSn(), title, status, row.getPayAmount());
    }

    private AjaxResult deposit(Long principal, String number) {
        CoopInvite row = coopInviteService.readOwnedDeposit(principal, number);
        if (row == null || !number.equals(row.getDepositOutTradeNo())) return new AjaxResult(404, "订单不存在");
        String status = row.getDepositStatus() == null ? "状态未知" : switch (row.getDepositStatus()) {
            case 0 -> "无需缴纳";
            case 1 -> "支付中";
            case 2 -> "已缴冻结";
            case 3 -> "已扣除";
            case 4 -> "已退还";
            case 5 -> "应缴未缴";
            default -> "状态未知";
        };
        return snapshot("DEP", row.getId(), number, "城市定向合作保证金", status, row.getDepositAmount());
    }

    private AjaxResult snapshot(String kind, Long id, String number, String title, String status, BigDecimal amount) {
        if (amount == null || amount.signum() < 0) return new AjaxResult(409, "订单金额暂不可确认");
        String amountText;
        try { amountText = amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(); }
        catch (ArithmeticException invalid) { return new AjaxResult(409, "订单金额暂不可确认"); }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", kind);
        data.put("id", decimalId(id));
        data.put("orderNo", number);
        data.put("title", title);
        data.put("statusText", status);
        data.put("amountText", amountText);
        return AjaxResult.success(data);
    }

    private String decimalId(Long id) {
        if (id == null || id <= 0) throw new ServiceException("订单记录不可用", 409);
        return id.toString();
    }

    private String registrationStatus(Integer status) {
        return status == null ? "状态未知" : switch (status) {
            case 0 -> "待支付";
            case 1 -> "支付中";
            case 2 -> "已支付";
            case 3 -> "支付失败";
            case 4 -> "已退款";
            default -> "状态未知";
        };
    }
}
