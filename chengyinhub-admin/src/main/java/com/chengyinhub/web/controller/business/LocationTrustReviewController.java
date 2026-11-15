package com.chengyinhub.web.controller.business;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.mapper.LocationTrustMapper;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

/**
 * P8 review trail (read-only): newest location writes that were flagged, withheld or refused.
 * No menu/UI yet; reachable by operators holding {@code business:locationTrust:list} (admin has all).
 */
@RestController
@RequestMapping("/business/locationTrust")
public class LocationTrustReviewController extends BaseController {

    @Autowired
    private LocationTrustMapper mapper;

    @PreAuthorize("@ss.hasPermi('business:locationTrust:list')")
    @GetMapping("/flagged")
    public AjaxResult flagged(Long memberId, String kind, Integer limit) {
        int n = limit == null ? 100 : Math.max(1, Math.min(500, limit));
        return success(mapper.selectFlagged(memberId, kind, n));
    }
}
