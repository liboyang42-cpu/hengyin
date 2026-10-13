package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.IpWorld;
import com.chengyinhub.business.service.IIpWorldService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3 IP世界后台。
 */
@RestController
@RequestMapping("/business/world")
public class IpWorldController extends BaseController {

    @Autowired
    private IIpWorldService ipWorldService;

    @PreAuthorize("@ss.hasPermi('business:world:list')")
    @GetMapping("/list")
    public TableDataInfo list(IpWorld query) {
        startPage();
        return getDataTable(ipWorldService.selectAdminWorldList(query));
    }

    @PreAuthorize("@ss.hasPermi('business:world:edit')")
    @Log(title = "P3 IP世界", businessType = BusinessType.UPDATE)
    @PostMapping
    public AjaxResult save(@RequestBody IpWorld world) {
        return toAjax(ipWorldService.saveWorld(world));
    }
}
