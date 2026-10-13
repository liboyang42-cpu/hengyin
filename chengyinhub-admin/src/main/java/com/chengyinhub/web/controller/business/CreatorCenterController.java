package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CreatorProfile;
import com.chengyinhub.business.domain.dto.CreatorReviewDTO;
import com.chengyinhub.business.service.ICreatorCenterService;
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
 * P3 创作者后台。
 */
@RestController
@RequestMapping("/business/creator")
public class CreatorCenterController extends BaseController {

    @Autowired
    private ICreatorCenterService creatorCenterService;

    @PreAuthorize("@ss.hasPermi('business:creator:list')")
    @GetMapping("/list")
    public TableDataInfo list(CreatorProfile query) {
        startPage();
        return getDataTable(creatorCenterService.selectAdminCreatorList(query));
    }

    @PreAuthorize("@ss.hasPermi('business:creator:review')")
    @Log(title = "P3创作者审核", businessType = BusinessType.UPDATE)
    @PostMapping("/review")
    public AjaxResult review(@RequestBody CreatorReviewDTO request) {
        return toAjax(creatorCenterService.review(request, getUserId()));
    }
}
