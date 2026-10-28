package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.support.MerchantReviewReplyReceiptService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/merchant/reviews/reply")
public class ApiMerchantReviewReplyReceiptController extends BaseController {
    @Autowired private MerchantReviewReplyReceiptService service;

    public record Request(Long reviewId, String action, String requestId) { }

    @PostMapping("/receipt")
    public AjaxResult receipt(@RequestBody(required = false) Request request) {
        if (request == null) return new AjaxResult(400, "回复回读参数缺失");
        try {
            return success(service.receipt(getAppUserId(), request.reviewId(), request.action(), request.requestId()));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
    }
}
