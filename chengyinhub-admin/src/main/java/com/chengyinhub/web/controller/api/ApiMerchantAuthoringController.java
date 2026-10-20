package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.domain.dto.ai.MerchantAuthoringConfirmation;
import com.chengyinhub.business.service.ai.MerchantAuthoringRecordService;
import com.chengyinhub.business.domain.dto.ai.MerchantAuthoringFactsReference;
import com.chengyinhub.business.service.ai.MerchantAuthoringFactsService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.exception.ServiceException;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Owned facts and explicit new-draft confirmation; no shop, cash checkout or publish operation. */
@org.springframework.context.annotation.Profile("w05-merchant-authoring")
@RestController
@RequestMapping("/api/merchant/authoring")
public class ApiMerchantAuthoringController extends BaseController {
    @Autowired private MerchantAuthoringFactsService facts;
    @Autowired private MerchantAuthoringRecordService records;

    private Long principal() {
        org.springframework.security.core.Authentication auth = com.chengyinhub.common.utils.SecurityUtils.getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new ServiceException("请先登录", 401);
        com.chengyinhub.common.core.domain.model.LoginAppUser login = getLoginAppUser();
        if (login == null || login.getAppUser() == null || login.getAppUser().getUserId() == null
                || login.getAppUser().getUserId() <= 0) throw new ServiceException("请先登录", 401);
        return login.getAppUser().getUserId();
    }

    @PostMapping("/draft")
    public AjaxResult draft(@RequestBody MerchantAuthoringConfirmation request) {
        try {
            Long principal = principal();
            if (principal == null || principal <= 0) return new AjaxResult(401, "请先登录");
            return success(records.confirm(principal, request));
        } catch (ServiceException e) { return new AjaxResult(e.getCode(), e.getMessage()); }
    }

    @PostMapping("/catalog")
    public AjaxResult catalog(@RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum) {
        if (pageNum == null || pageNum <= 0 || pageNum > Integer.MAX_VALUE / 20 + 1) return new AjaxResult(400, "分页参数不合法");
        try {
            Long principal = principal();
            if (principal == null || principal <= 0) return new AjaxResult(401, "请先登录");
            List<PmsProduct> rows = facts.listMine(principal, pageNum);
            TableDataInfo page = getDataTable(rows);
            page.setRows(facts.projectCatalog(principal, rows));
            return success(page);
        } catch (ServiceException e) { return new AjaxResult(e.getCode(), e.getMessage()); }
    }
    @PostMapping("/product")
    public AjaxResult product(@RequestBody MerchantAuthoringFactsReference request) {
        try {
            Long principal = principal();
            if (principal == null || principal <= 0) return new AjaxResult(401, "请先登录");
            if (request == null) return new AjaxResult(400, "商品事实参数不合法");
            return success(facts.product(principal, request.getProductId()));
        } catch (ServiceException e) { return new AjaxResult(e.getCode(), e.getMessage()); }
    }
    @PostMapping("/facts")
    public AjaxResult facts(@RequestBody MerchantAuthoringFactsReference request) {
        try {
            Long principal = principal();
            if (principal == null || principal <= 0) return new AjaxResult(401, "请先登录");
            if (request == null) return new AjaxResult(400, "商品事实参数不合法");
            return success(facts.facts(principal, request.getProductId(), request.getSkuId()));
        } catch (ServiceException e) { return new AjaxResult(e.getCode(), e.getMessage()); }
    }
}
