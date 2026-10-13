package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.MerchantCustomerProfile;
import com.chengyinhub.business.service.IMerchantCrmService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/business/crm")
public class MerchantCrmController extends BaseController {
    @Autowired
    private IMerchantCrmService merchantCrmService;

    @PreAuthorize("@ss.hasPermi('business:crm:list')")
    @GetMapping("/list")
    public TableDataInfo list(MerchantCustomerProfile query) {
        startPage();
        List<MerchantCustomerProfile> rows = merchantCrmService.selectCustomerList(query);
        return getDataTable(rows);
    }

    @PreAuthorize("@ss.hasPermi('business:crm:list')")
    @GetMapping("/summary")
    public AjaxResult summary(@RequestParam Long merchantId) {
        return success(merchantCrmService.getSummary(merchantId));
    }

    @PreAuthorize("@ss.hasPermi('business:crm:aggregate')")
    @Log(title = "商家CRM增量对账", businessType = BusinessType.UPDATE)
    @PostMapping("/aggregate")
    public AjaxResult aggregate(@RequestParam("ownerMemberId") Long ownerMemberId) {
        return success(merchantCrmService.aggregateMerchantCustomers(ownerMemberId));
    }
}
