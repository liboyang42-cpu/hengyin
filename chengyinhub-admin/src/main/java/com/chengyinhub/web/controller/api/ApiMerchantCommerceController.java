package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.impl.MerchantCommerceService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@Tag(name = "商家商业化能力")
@RequestMapping("/api/merchant/commerce")
public class ApiMerchantCommerceController extends BaseController {
    @Autowired private MerchantCommerceService commerceService;
    @Autowired private IUmsMemberService memberService;
    @Autowired private IMerchantAccessService merchantAccessService;

    @Operation(summary = "商业化能力与收费开关")
    @PostMapping("/capabilities")
    public AjaxResult capabilities() {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.PROFILE_WRITE);
        Long ownerMemberId = access.getOwnerMemberId();
        UmsMember owner = memberService.selectUmsMemberById(ownerMemberId);
        String role = owner != null && owner.getRole() != null ? owner.getRole() : "merchant";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("selfCheckoutEnabled", commerceService.isSelfCheckoutEnabled());
        result.put("premiumTemplate", commerceService.entitlement(ownerMemberId, role,
                MerchantCommerceService.BIZ_PREMIUM_TEMPLATE, 0));
        result.put("cityNode", commerceService.entitlement(ownerMemberId, role,
                MerchantCommerceService.ENTITLEMENT_CITY_NODE, 0));
        return success(result);
    }

    @Operation(summary = "创建商业化权益订单(收费开关默认关闭)")
    @PostMapping("/order")
    public AjaxResult createOrder(@RequestBody Map<String, Object> body) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.PROFILE_WRITE);
        // 付款人 = 登录人(经手购买的员工或店主);订单主体 = 商家主体,权益落给商家。
        UmsMember payer = memberService.selectUmsMemberById(memberId);
        if (payer == null || payer.getWxOpenId() == null || payer.getWxOpenId().trim().isEmpty()) {
            return error("微信身份未绑定，无法发起支付");
        }
        try {
            return success(commerceService.createOrder(access.getOwnerMemberId(), access.getMerchantId(),
                    payer.getWxOpenId(),
                    body == null || body.get("bizType") == null ? null : String.valueOf(body.get("bizType")),
                    body == null || body.get("requestId") == null ? null : String.valueOf(body.get("requestId"))));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "查询当前商家的商业化权益订单终态")
    @PostMapping("/order/status")
    public AjaxResult orderStatus(@RequestBody Map<String, Object> body) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.PROFILE_WRITE);
        try {
            String orderSn = body == null || body.get("orderSn") == null
                    ? null : String.valueOf(body.get("orderSn"));
            return success(commerceService.orderStatus(
                    access.getOwnerMemberId(), access.getMerchantId(), orderSn));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    private Long currentMemberId() {
        try {
            Long id = getAppUserId();
            return id != null && id > 0 ? id : null;
        } catch (Exception e) {
            return null;
        }
    }
}
