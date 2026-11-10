package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.payout.EcommerceSubMerchantService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 商家「收款账户」：只读当前商家自己的二级商户进件状态。需要 FINANCE_READ，商家 id 只取自登录身份。 */
@RestController
@Tag(name = "商家收款账户")
@RequestMapping("/api/merchant")
public class ApiMerchantPayoutAccountController extends BaseController {
    @Autowired private IMerchantAccessService merchantAccessService;
    @Autowired private EcommerceSubMerchantService subMerchantService;

    @Operation(summary = "当前商家的收款账户（电商收付通二级商户）状态")
    @PostMapping("/payout-account")
    public AjaxResult payoutAccount() {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try {
            MerchantAccessContext access = merchantAccessService.require(principal, MerchantPermission.FINANCE_READ);
            return success(subMerchantService.ownerView(EcommerceSubMerchantService.OWNER_MERCHANT, access.getMerchantId()));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    private Long principal() {
        try { return getAppUserId(); } catch (Exception e) { return null; }
    }

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }
}
