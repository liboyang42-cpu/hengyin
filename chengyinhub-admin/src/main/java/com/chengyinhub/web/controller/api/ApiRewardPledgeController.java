package com.chengyinhub.web.controller.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.service.rewardpledge.PledgeRejection;
import com.chengyinhub.business.service.rewardpledge.RewardPledgeService;
import com.chengyinhub.business.service.rewardpledge.RewardPledgeService.Draft;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

/** Merchant pledge and staff fulfilment. Login is the actor. Missing policy stays closed. */
@RestController
@RequestMapping("/api/reward-pledge")
public class ApiRewardPledgeController extends BaseController {
    private final RewardPledgeService pledges;
    private final IMerchantAccessService access;

    public ApiRewardPledgeController(RewardPledgeService pledges, IMerchantAccessService access) {
        this.pledges = pledges;
        this.access = access;
    }

    @PostMapping("/drafts")
    public AjaxResult draft(@RequestBody Draft draft) {
        MerchantAccessContext ctx = store(MerchantPermission.COUPON_MANAGE);
        draft.storeId = ctx.getMerchantId();
        draft.merchantMemberId = ctx.getOwnerMemberId();
        return run(() -> pledges.draft(draft));
    }

    @PostMapping("/{pledgeId}/submit")
    public AjaxResult submit(@PathVariable String pledgeId) {
        MerchantAccessContext ctx = store(MerchantPermission.COUPON_MANAGE);
        return run(() -> pledges.submit(ctx.getOwnerMemberId(), pledgeId));
    }

    @PostMapping("/{pledgeId}/confirm")
    public AjaxResult confirm(@PathVariable String pledgeId) {
        MerchantAccessContext ctx = store(MerchantPermission.COUPON_MANAGE);
        return run(() -> pledges.confirm(ctx.getOwnerMemberId(), pledgeId));
    }

    @PostMapping("/{pledgeId}/revise")
    public AjaxResult revise(@PathVariable String pledgeId, @RequestBody Draft draft) {
        MerchantAccessContext ctx = store(MerchantPermission.COUPON_MANAGE);
        draft.storeId = ctx.getMerchantId();
        draft.merchantMemberId = ctx.getOwnerMemberId();
        return run(() -> pledges.revise(ctx.getOwnerMemberId(), pledgeId, draft));
    }

    @PostMapping("/{pledgeId}/freeze")
    public AjaxResult freeze(@PathVariable String pledgeId, @RequestBody Map<String, String> body) {
        long reviewer = getAppUserId();
        return run(() -> pledges.freeze(reviewer, pledgeId, body.get("ruleVersion")));
    }

    @PostMapping("/versions/{versionId}/withdraw")
    public AjaxResult withdraw(@PathVariable String versionId) {
        MerchantAccessContext ctx = store(MerchantPermission.COUPON_MANAGE);
        return run(() -> pledges.withdraw(ctx.getOwnerMemberId(), versionId));
    }

    @PostMapping("/versions/{versionId}/hold")
    public AjaxResult hold(@PathVariable String versionId) {
        MerchantAccessContext ctx = store(MerchantPermission.COUPON_MANAGE);
        return run(() -> pledges.hold(ctx.getOwnerMemberId(), versionId));
    }

    @GetMapping("/{pledgeId}")
    public AjaxResult read(@PathVariable String pledgeId) {
        MerchantAccessContext ctx = store(MerchantPermission.COUPON_MANAGE);
        return run(() -> pledges.readPledge(ctx.getMerchantId(), pledgeId));
    }

    @GetMapping("/{pledgeId}/stats")
    public AjaxResult stats(@PathVariable String pledgeId) {
        MerchantAccessContext ctx = store(MerchantPermission.COUPON_MANAGE);
        return run(() -> pledges.stats(ctx.getMerchantId(), pledgeId));
    }

    @PostMapping("/awards/{awardId}/return-to-pool")
    public AjaxResult returnToPool(@PathVariable String awardId) {
        MerchantAccessContext ctx = store(MerchantPermission.COUPON_MANAGE);
        return run(() -> pledges.returnToPool(ctx.getOwnerMemberId(), awardId, System.currentTimeMillis()));
    }

    @PostMapping("/compensations")
    public AjaxResult compensate(@RequestBody Map<String, String> body) {
        MerchantAccessContext ctx = store(MerchantPermission.COUPON_MANAGE);
        RewardPledgeService.Claim claim = new RewardPledgeService.Claim();
        claim.versionId = body.get("versionId");
        claim.requestId = body.get("requestId");
        return run(() -> pledges.compensate(ctx.getOwnerMemberId(), claim, body.get("originalAwardId"), body.get("compensationKey")));
    }

    @PostMapping("/fulfilment/preview")
    public AjaxResult preview(@RequestBody Map<String, String> body) {
        MerchantAccessContext ctx = store(MerchantPermission.VERIFY);
        return run(() -> pledges.preview(ctx.getMerchantId(), body.get("awardId")));
    }

    @PostMapping("/fulfilment/redeem")
    public AjaxResult redeem(@RequestBody Map<String, String> body) {
        if (!"true".equals(body.get("confirm"))) return AjaxResult.error(400, "NOT_CONFIRM");
        MerchantAccessContext ctx = store(MerchantPermission.VERIFY);
        return run(() -> pledges.fulfil(ctx.getMerchantId(), ctx.getPrincipalMemberId(), ctx.getRoleCode(), body.get("awardId")));
    }

    @PostMapping("/fulfilment/expire")
    public AjaxResult expire(@RequestBody Map<String, String> body) {
        store(MerchantPermission.VERIFY);
        return run(() -> pledges.expire(body.get("awardId"), System.currentTimeMillis()));
    }

    private MerchantAccessContext store(String permission) {
        MerchantAccessContext ctx = access.require(getAppUserId(), permission);
        if (ctx == null || ctx.getMerchantId() == null) throw new PledgeRejection("FORBIDDEN");
        return ctx;
    }

    private AjaxResult run(java.util.concurrent.Callable<Object> work) {
        try {
            return AjaxResult.success(work.call());
        } catch (PledgeRejection rejected) {
            return AjaxResult.error(code(rejected.code), rejected.code);
        } catch (RuntimeException failed) {
            throw failed;
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static int code(String error) {
        if ("NOT_FOUND".equals(error)) return 404;
        if ("FORBIDDEN".equals(error)) return 403;
        if ("INVALID_INPUT".equals(error) || "NOT_CONFIRM".equals(error)) return 400;
        return 409;
    }
}
