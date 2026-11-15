package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.collection.CollectionAdminService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S2 collection model admin page 分账收款 (menu from {@code migration_20261115_collection_model.sql}; permissions granted
 * to no role). Model state, per-seller collection status (sub-merchant bound or not), split ledger, failures queue and
 * refused sales. The operations act only through the existing profit-sharing service, whose switches still apply.
 */
@RestController
@RequestMapping("/business/collection")
public class CollectionModelAdminController extends BaseController {

    private final CollectionAdminService admin;

    public CollectionModelAdminController(CollectionAdminService admin) {
        this.admin = admin;
    }

    @PreAuthorize("@ss.hasPermi('business:collection:query')")
    @GetMapping("/overview")
    public AjaxResult overview() {
        return success(admin.overview());
    }

    @PreAuthorize("@ss.hasPermi('business:collection:query')")
    @GetMapping("/sellers")
    public AjaxResult sellers(@RequestParam(required = false) String sellerType, @RequestParam(required = false) Boolean bound,
                              @RequestParam(required = false) Integer limit) {
        return success(admin.sellers(sellerType, bound, limit));
    }

    @PreAuthorize("@ss.hasPermi('business:collection:query')")
    @GetMapping("/ledger")
    public AjaxResult ledger(@RequestParam(required = false) String sellerType, @RequestParam(required = false) Long sellerId,
                             @RequestParam(required = false) String state, @RequestParam(required = false) Integer limit) {
        return success(admin.ledger(sellerType, sellerId, state, limit));
    }

    @PreAuthorize("@ss.hasPermi('business:collection:query')")
    @GetMapping("/failures")
    public AjaxResult failures(@RequestParam(required = false) Integer limit) {
        return success(admin.failures(limit));
    }

    @PreAuthorize("@ss.hasPermi('business:collection:query')")
    @GetMapping("/refusals")
    public AjaxResult refusals(@RequestParam(required = false) Integer limit) {
        return success(admin.refusals(limit));
    }

    @PreAuthorize("@ss.hasPermi('business:collection:edit')")
    @PostMapping("/orders/{registrationNo}/replan")
    public AjaxResult replan(@PathVariable String registrationNo) {
        return success(admin.replan(registrationNo));
    }

    @PreAuthorize("@ss.hasPermi('business:collection:edit')")
    @PostMapping("/returns/{id}/retry")
    public AjaxResult retryReturn(@PathVariable Long id) {
        return success(admin.retryReturn(id));
    }

    @PreAuthorize("@ss.hasPermi('business:collection:edit')")
    @PostMapping("/plan-due")
    public AjaxResult planDue() {
        return success(admin.planDue());
    }
}
