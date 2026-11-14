package com.chengyinhub.web.controller.business;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantgap.MerchantOfferWithdrawalAdminService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Back-office, read-only ledger of merchant chapter-supply exit receipts (merchant_offer_withdrawal_request).
 * The exit is merchant self-service with an immutable receipt; there is nothing to decide here, only to read
 * ({@code business:audit:list}).
 */
@RestController
@Tag(name = "商家供给退出回执(后台)")
@RequestMapping("/business/offer-withdrawal")
public class MerchantOfferWithdrawalAdminController extends BaseController
{
    private final MerchantOfferWithdrawalAdminService receipts;

    public MerchantOfferWithdrawalAdminController(MerchantOfferWithdrawalAdminService receipts)
    {
        this.receipts = receipts;
    }

    @Operation(summary = "供给退出回执列表（只读）")
    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) Long merchantId, @RequestParam(required = false) Long offerId,
                           @RequestParam(required = false) String outcome, @RequestParam(required = false) Integer pageNum,
                           @RequestParam(required = false) Integer pageSize)
    {
        return success(receipts.list(merchantId, offerId, outcome, pageNum, pageSize));
    }

    @ExceptionHandler(MerchantGapException.class)
    public ResponseEntity<AjaxResult> failed(MerchantGapException e)
    {
        AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), e.getData());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
