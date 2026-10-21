package com.chengyinhub.web.controller.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.chengyinhub.business.service.rewardpledge.PledgeRejection;
import com.chengyinhub.business.service.rewardpledge.RewardPledgeService;
import com.chengyinhub.business.service.rewardpledge.RewardPledgeService.Claim;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

/** Player claim and read-back. The coupon credential stays on /api/coupon/qr-token. */
@RestController
@RequestMapping("/api/rewards/noncash")
public class ApiNonCashRewardController extends BaseController {
    private final RewardPledgeService pledges;

    public ApiNonCashRewardController(RewardPledgeService pledges) {
        this.pledges = pledges;
    }

    @PostMapping("/claims")
    public AjaxResult claim(@RequestBody Claim claim) {
        claim.ownerId = getAppUserId();
        return run(() -> pledges.claim(claim));
    }

    @PostMapping("/claims/recover")
    public AjaxResult recover(@RequestBody Map<String, String> body) {
        return run(() -> pledges.recover(getAppUserId(), body.get("requestId")));
    }

    @GetMapping
    public AjaxResult list() {
        long owner = getAppUserId();
        return run(() -> pledges.listMine(owner));
    }

    @GetMapping("/{awardId}")
    public AjaxResult detail(@PathVariable String awardId) {
        long owner = getAppUserId();
        return run(() -> pledges.detail(owner, awardId));
    }

    @PostMapping("/cases")
    public AjaxResult openCase(@RequestBody Map<String, String> body) {
        long owner = getAppUserId();
        return run(() -> pledges.openCase(owner, body.get("versionId"), body.get("awardId"), body.get("reason")));
    }

    private AjaxResult run(java.util.concurrent.Callable<Object> work) {
        try {
            return AjaxResult.success(work.call());
        } catch (PledgeRejection rejected) {
            int code = "NOT_FOUND".equals(rejected.code) ? 404 : "INVALID_INPUT".equals(rejected.code) ? 400 : 409;
            return AjaxResult.error(code, rejected.code);
        } catch (RuntimeException failed) {
            throw failed;
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }
}
