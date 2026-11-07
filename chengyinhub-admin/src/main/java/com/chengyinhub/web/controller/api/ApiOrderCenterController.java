package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.order.OrderCenterQueryApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/order-center")
public class ApiOrderCenterController extends BaseController {
    @Autowired private OrderCenterQueryApplication orderCenterQueryApplication;

    @PostMapping("/detail")
    public AjaxResult detail(@RequestParam(value = "outTradeNo", required = false) String outTradeNo) {
        Long principal;
        try { principal = getAppUserId(); }
        catch (Exception anonymous) { return new AjaxResult(401, "请先登录"); }
        if (principal == null || principal <= 0) return new AjaxResult(401, "请先登录");
        return orderCenterQueryApplication.detail(principal, outTradeNo);
    }
}
