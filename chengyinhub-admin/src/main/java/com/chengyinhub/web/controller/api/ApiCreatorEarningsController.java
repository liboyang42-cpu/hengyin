package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.creatorearnings.CreatorEarningsPolicy;
import com.chengyinhub.business.creatorearnings.CreatorEarningsService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * R12 creator earnings, own-member reads only (GAM-10, MON-06).
 *
 * <ul>
 *   <li>/api/creator-earnings/mine[/ledger]: my items, uses, verified plays and points.</li>
 *   <li>/api/mod-settlement/mine/income[/{orderKey}[/revisions]]: intended creator share of each paid workshop sale,
 *       in the app's {@code mod-creator-income-*-v1} shape. It is an allocation record, not money paid.</li>
 *   <li>/api/mod-settlement/mine and /qualification: no payout has ever been made and no payout qualification
 *       exists, so the list is empty and the qualification is absent. Payout writes stay closed (503) in
 *       {@code ApiAppGapsWorkshopController}.</li>
 * </ul>
 */
@RestController
@Tag(name = "App 创作者收益")
public class ApiCreatorEarningsController extends BaseController {

    private final CreatorEarningsService earnings;

    public ApiCreatorEarningsController(CreatorEarningsService earnings) {
        this.earnings = earnings;
    }

    /** Signed-in member id, or null. */
    Long member() {
        try {
            Long id = getAppUserId();
            return id != null && id > 0 ? id : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static AjaxResult login() { return AjaxResult.error(401, "请先登录"); }

    @GetMapping("/api/creator-earnings/mine")
    public AjaxResult mine() {
        Long me = member();
        if (me == null) return login();
        return AjaxResult.success(earnings.mine(me));
    }

    @GetMapping("/api/creator-earnings/mine/ledger")
    public AjaxResult ledger(@RequestParam(value = "before", required = false) Long before) {
        Long me = member();
        if (me == null) return login();
        if (before != null && before <= 0) return AjaxResult.error(400, "INVALID_CURSOR");
        return AjaxResult.success(earnings.ledger(me, before));
    }

    @GetMapping("/api/mod-settlement/mine")
    public AjaxResult settlements(@RequestParam(value = "limit", required = false) Integer limit,
                                  @RequestParam(value = "before", required = false) String before) {
        Long me = member();
        if (me == null) return login();
        if (limit != null && (limit < 1 || limit > 100)) return AjaxResult.error(400, "INVALID_LIMIT");
        // Payouts are not built and real money is off: there is no settlement record to list.
        return AjaxResult.success(List.of());
    }

    @GetMapping("/api/mod-settlement/mine/qualification")
    public AjaxResult qualification() {
        Long me = member();
        if (me == null) return login();
        return AjaxResult.success((Object) null);
    }

    @GetMapping("/api/mod-settlement/mine/income")
    public AjaxResult income(@RequestParam(value = "before", required = false) String before) {
        Long me = member();
        if (me == null) return login();
        if (before != null && !CreatorEarningsPolicy.isIncomeOrderKey(before)) return AjaxResult.error(400, "INVALID_CURSOR");
        return AjaxResult.success(earnings.incomePage(me, before));
    }

    @GetMapping("/api/mod-settlement/mine/income/{orderKey}")
    public AjaxResult incomeDetail(@PathVariable("orderKey") String orderKey) {
        Long me = member();
        if (me == null) return login();
        Map<String, Object> detail = CreatorEarningsPolicy.isIncomeOrderKey(orderKey) ? earnings.incomeDetail(me, orderKey) : null;
        return detail == null ? AjaxResult.error(404, "NOT_FOUND") : AjaxResult.success(detail);
    }

    @GetMapping("/api/mod-settlement/mine/income/{orderKey}/revisions")
    public AjaxResult incomeRevisions(@PathVariable("orderKey") String orderKey,
                                      @RequestParam(value = "asOf", required = false) Long asOf,
                                      @RequestParam(value = "before", required = false) Long before) {
        Long me = member();
        if (me == null) return login();
        if (asOf == null || !CreatorEarningsPolicy.isIncomeOrderKey(orderKey)) return AjaxResult.error(404, "NOT_FOUND");
        Map<String, Object> page = earnings.incomeRevisions(me, orderKey, asOf, before);
        return page == null ? AjaxResult.error(404, "NOT_FOUND") : AjaxResult.success(page);
    }

    /** No settlement record exists for anyone yet. */
    @GetMapping("/api/mod-settlement/mine/{id:ms_[0-9a-f]{64}}")
    public AjaxResult settlement(@PathVariable("id") String id) {
        Long me = member();
        if (me == null) return login();
        return AjaxResult.error(404, "NOT_FOUND");
    }
}
