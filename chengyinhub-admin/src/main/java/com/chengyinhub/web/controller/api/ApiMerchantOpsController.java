package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IMerchantDetailAggregationService;
import com.chengyinhub.business.service.IMerchantPrerequisiteService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchant/ops")
public class ApiMerchantOpsController extends BaseController {
    private final IMerchantPrerequisiteService prerequisiteService;
    private final IMerchantDetailAggregationService detailService;

    public ApiMerchantOpsController(IMerchantPrerequisiteService prerequisiteService,
                                    IMerchantDetailAggregationService detailService) {
        this.prerequisiteService = prerequisiteService;
        this.detailService = detailService;
    }

    @GetMapping("/prerequisites")
    public AjaxResult prerequisites() {
        try {
            return success(prerequisiteService.calculate(getAppUserId()));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
    }

    @Anonymous
    @GetMapping("/public-detail")
    public AjaxResult publicDetail(@RequestParam("merchantId") Long merchantId) {
        try {
            return success(detailService.getPublicDetail(merchantId));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
    }
}
