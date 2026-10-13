package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.service.impl.ClubDirectMerchantReceivableService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.web.bind.annotation.*;

/** 登录商家读本商家，登录俱乐部主理人读本俱乐部；不提供付款、提现或状态写入口。 */
@RestController
@RequestMapping("/api/club-direct-merchant")
public class ApiClubDirectMerchantReceivableController extends BaseController {
    private final ClubDirectMerchantReceivableService service;
    private final IMerchantAccessService merchantAccess;

    public ApiClubDirectMerchantReceivableController(ClubDirectMerchantReceivableService service,
            IMerchantAccessService merchantAccess) { this.service = service; this.merchantAccess = merchantAccess; }

    @GetMapping("/merchant")
    public AjaxResult merchant(@RequestParam(required = false) Long topicId) {
        try {
            Long owner = merchantAccess.require(getAppUserId(), MerchantPermission.FINANCE_READ).getOwnerMemberId();
            return success(service.listForMerchant(topicId, owner));
        } catch (ServiceException e) { return new AjaxResult(e.getCode(), e.getMessage()); }
        catch (IllegalStateException e) { return error(e.getMessage()); }
    }

    @GetMapping("/club")
    public AjaxResult club(@RequestParam(required = false) Long topicId, @RequestParam Long clubId) {
        try { return success(service.listForClub(topicId, clubId, getAppUserId())); }
        catch (IllegalStateException e) { return error(e.getMessage()); }
    }
}
