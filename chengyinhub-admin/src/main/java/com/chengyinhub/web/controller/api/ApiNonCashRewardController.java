package com.chengyinhub.web.controller.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.chengyinhub.business.service.rewardpledge.CashPayoutSwitch;
import com.chengyinhub.business.service.rewardpledge.PledgeRejection;
import com.chengyinhub.business.service.rewardpledge.RewardPledgeService;
import com.chengyinhub.business.service.rewardpledge.RewardPledgeService.Claim;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

/**
 * Player claim and read-back of non-cash (coupon-backed) rewards. The coupon credential stays on /api/coupon/qr-token.
 * Two app clients read this prefix: RewardPledgeClient (no query: list is an array, detail is the award view) and
 * NonCashRewardService ({@code limit}/{@code cursor} page, detail with the frozen context reference).
 */
@RestController
@RequestMapping("/api/rewards/noncash")
public class ApiNonCashRewardController extends BaseController {
    private final RewardPledgeService pledges;

    public ApiNonCashRewardController(RewardPledgeService pledges) {
        this.pledges = pledges;
    }

    @PostMapping("/claims")
    public AjaxResult claim(@RequestBody(required = false) Claim claim) {
        Long me = member();
        if (me == null) return signedOut();
        if (claim == null) return AjaxResult.error(400, "INVALID_INPUT");
        claim.ownerId = me;
        return run(() -> pledges.claim(claim));
    }

    @PostMapping("/claims/recover")
    public AjaxResult recover(@RequestBody(required = false) Map<String, String> body) {
        Long me = member();
        if (me == null) return signedOut();
        return run(() -> pledges.recover(me, body == null ? null : body.get("requestId")));
    }

    @GetMapping
    public AjaxResult list(@RequestParam(value = "limit", required = false) Integer limit,
                           @RequestParam(value = "cursor", required = false) String cursor) {
        Long me = member();
        if (me == null) return signedOut();
        if (limit == null && cursor == null) return run(() -> pledges.listMine(me));
        if (limit == null) return AjaxResult.error(400, "INVALID_INPUT");
        return run(() -> pledges.pageMine(me, limit, cursor));
    }

    @GetMapping("/{awardId}")
    public AjaxResult detail(@PathVariable String awardId,
                             @RequestParam(value = "contextType", required = false) String contextType,
                             @RequestParam(value = "contextId", required = false) String contextId,
                             @RequestParam(value = "releaseId", required = false) String releaseId,
                             @RequestParam(value = "instanceId", required = false) String instanceId) {
        Long me = member();
        if (me == null) return signedOut();
        if (contextType == null && contextId == null && releaseId == null && instanceId == null) {
            return run(() -> pledges.detail(me, awardId));
        }
        return run(() -> pledges.nonCashDetail(me, awardId, contextType, contextId, releaseId, instanceId));
    }

    @GetMapping("/subjects/{subjectId}")
    public AjaxResult catalog(@PathVariable String subjectId) {
        Long me = member();
        if (me == null) return signedOut();
        return run(() -> pledges.catalog(me, subjectId));
    }

    @GetMapping("/versions/{versionId}")
    public AjaxResult offer(@PathVariable String versionId,
                            @RequestParam(value = "branch", required = false) String branch,
                            @RequestParam(value = "subjectId", required = false) String subjectId) {
        Long me = member();
        if (me == null) return signedOut();
        return run(() -> pledges.offer(me, versionId, branch, subjectId));
    }

    @PostMapping("/cases")
    public AjaxResult openCase(@RequestBody(required = false) Map<String, String> body) {
        Long me = member();
        if (me == null) return signedOut();
        if (body == null) return AjaxResult.error(400, "INVALID_INPUT");
        return run(() -> pledges.openCase(me, body.get("versionId"), body.get("awardId"), body.get("reason")));
    }

    @GetMapping("/cases/{caseId}")
    public AjaxResult readCase(@PathVariable String caseId) {
        Long me = member();
        if (me == null) return signedOut();
        return run(() -> pledges.readCase(me, caseId));
    }

    private Long member() {
        try {
            Long id = getAppUserId();
            return id == null || id <= 0 ? null : id;
        } catch (Exception signedOut) {
            return null;
        }
    }

    static AjaxResult signedOut() {
        return AjaxResult.error(401, "请先登录");
    }

    static AjaxResult run(java.util.concurrent.Callable<Object> work) {
        try {
            return AjaxResult.success(work.call());
        } catch (PledgeRejection rejected) {
            return rejection(rejected.code);
        } catch (RuntimeException failed) {
            throw failed;
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    /** Cash closed is the release switch's FEATURE_CLOSED; the message stays the code the app matches on. */
    static AjaxResult rejection(String code) {
        AjaxResult result = AjaxResult.error(status(code), code);
        result.put("errorCode", "CASH_CLOSED".equals(code) ? CashPayoutSwitch.FEATURE_CLOSED : code);
        return result;
    }

    static int status(String code) {
        if ("NOT_FOUND".equals(code)) return 404;
        if ("FORBIDDEN".equals(code) || "CASH_CLOSED".equals(code)) return 403;
        if ("INVALID_INPUT".equals(code) || "NOT_CONFIRM".equals(code)) return 400;
        return 409;
    }
}
