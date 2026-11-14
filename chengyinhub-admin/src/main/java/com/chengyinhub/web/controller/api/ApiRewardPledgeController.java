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

/** Merchant pledge, reviewer freeze and staff fulfilment. Login is the actor, the store comes from the merchant
 * access context (never the body). Missing policy stays closed. */
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
    public AjaxResult draft(@RequestBody(required = false) Draft draft) {
        if (draft == null) return AjaxResult.error(400, "INVALID_INPUT");
        return store(MerchantPermission.COUPON_MANAGE, ctx -> {
            draft.storeId = ctx.getMerchantId();
            draft.merchantMemberId = ctx.getOwnerMemberId();
            draft.principalMemberId = ctx.getPrincipalMemberId() == null ? 0L : ctx.getPrincipalMemberId();
            return pledges.draft(draft);
        });
    }

    @PostMapping("/{pledgeId}/submit")
    public AjaxResult submit(@PathVariable String pledgeId) {
        return store(MerchantPermission.COUPON_MANAGE, ctx -> pledges.submit(ctx.getMerchantId(), principal(ctx), pledgeId));
    }

    @PostMapping("/{pledgeId}/confirm")
    public AjaxResult confirm(@PathVariable String pledgeId) {
        return store(MerchantPermission.COUPON_MANAGE, ctx -> pledges.confirm(ctx.getMerchantId(), principal(ctx), pledgeId));
    }

    @PostMapping("/{pledgeId}/revise")
    public AjaxResult revise(@PathVariable String pledgeId, @RequestBody(required = false) Draft draft) {
        if (draft == null) return AjaxResult.error(400, "INVALID_INPUT");
        return store(MerchantPermission.COUPON_MANAGE, ctx -> {
            draft.storeId = ctx.getMerchantId();
            draft.merchantMemberId = ctx.getOwnerMemberId();
            return pledges.revise(ctx.getMerchantId(), pledgeId, draft);
        });
    }

    @PostMapping("/{pledgeId}/freeze")
    public AjaxResult freeze(@PathVariable String pledgeId, @RequestBody(required = false) Map<String, String> body) {
        Long reviewer = member();
        if (reviewer == null) return ApiNonCashRewardController.signedOut();
        return ApiNonCashRewardController.run(() -> pledges.freeze(reviewer, pledgeId, body == null ? null : body.get("ruleVersion")));
    }

    @PostMapping("/versions/{versionId}/withdraw")
    public AjaxResult withdraw(@PathVariable String versionId) {
        return store(MerchantPermission.COUPON_MANAGE, ctx -> pledges.withdraw(ctx.getMerchantId(), principal(ctx), versionId));
    }

    @PostMapping("/versions/{versionId}/hold")
    public AjaxResult hold(@PathVariable String versionId) {
        return store(MerchantPermission.COUPON_MANAGE, ctx -> pledges.hold(ctx.getMerchantId(), principal(ctx), versionId));
    }

    @GetMapping("/pledges")
    public AjaxResult owned() {
        return store(MerchantPermission.COUPON_MANAGE, ctx -> pledges.ownedPledges(ctx.getMerchantId()));
    }

    @GetMapping("/review")
    public AjaxResult reviewQueue() {
        Long reviewer = member();
        if (reviewer == null) return ApiNonCashRewardController.signedOut();
        return ApiNonCashRewardController.run(() -> pledges.reviewQueue(reviewer));
    }

    @GetMapping("/review/{versionId}")
    public AjaxResult reviewVersion(@PathVariable String versionId) {
        Long reviewer = member();
        if (reviewer == null) return ApiNonCashRewardController.signedOut();
        return ApiNonCashRewardController.run(() -> pledges.reviewVersion(reviewer, versionId));
    }

    @GetMapping("/ops/{op}/{key}")
    public AjaxResult readOp(@PathVariable String op, @PathVariable String key) {
        String permission = "expire".equals(op) ? MerchantPermission.VERIFY : MerchantPermission.COUPON_MANAGE;
        return store(permission, ctx -> pledges.readOp(ctx.getMerchantId(), op, key));
    }

    @GetMapping("/{pledgeId}")
    public AjaxResult read(@PathVariable String pledgeId) {
        return store(MerchantPermission.COUPON_MANAGE, ctx -> pledges.readPledge(ctx.getMerchantId(), pledgeId));
    }

    @GetMapping("/{pledgeId}/stats")
    public AjaxResult stats(@PathVariable String pledgeId) {
        return store(MerchantPermission.COUPON_MANAGE, ctx -> pledges.stats(ctx.getMerchantId(), pledgeId));
    }

    @PostMapping("/awards/{awardId}/return-to-pool")
    public AjaxResult returnToPool(@PathVariable String awardId) {
        return store(MerchantPermission.COUPON_MANAGE,
                ctx -> pledges.returnToPool(ctx.getMerchantId(), principal(ctx), awardId, System.currentTimeMillis()));
    }

    @PostMapping("/compensations")
    public AjaxResult compensate(@RequestBody(required = false) Map<String, String> body) {
        if (body == null) return AjaxResult.error(400, "INVALID_INPUT");
        return store(MerchantPermission.COUPON_MANAGE, ctx -> {
            RewardPledgeService.Claim claim = new RewardPledgeService.Claim();
            claim.versionId = body.get("versionId");
            claim.requestId = body.get("requestId");
            return pledges.compensate(ctx.getMerchantId(), principal(ctx), claim, body.get("originalAwardId"), body.get("compensationKey"));
        });
    }

    @GetMapping("/fulfilment/awards")
    public AjaxResult storeAwards() {
        return store(MerchantPermission.VERIFY, ctx -> pledges.storeAwards(ctx.getMerchantId()));
    }

    @PostMapping("/fulfilment/preview")
    public AjaxResult preview(@RequestBody(required = false) Map<String, String> body) {
        return store(MerchantPermission.VERIFY, ctx -> pledges.preview(ctx.getMerchantId(), body == null ? null : body.get("awardId")));
    }

    @PostMapping("/fulfilment/resolve")
    public AjaxResult resolve(@RequestBody(required = false) Map<String, String> body) {
        return store(MerchantPermission.VERIFY, ctx -> pledges.resolve(ctx.getMerchantId(), body == null ? null : body.get("code")));
    }

    @PostMapping("/fulfilment/redeem")
    public AjaxResult redeem(@RequestBody(required = false) Map<String, String> body) {
        if (body == null || !"true".equals(body.get("confirm"))) return ApiNonCashRewardController.rejection("NOT_CONFIRM");
        return store(MerchantPermission.VERIFY,
                ctx -> pledges.fulfil(ctx.getMerchantId(), principal(ctx), ctx.getRoleCode(), body.get("awardId")));
    }

    @PostMapping("/fulfilment/expire")
    public AjaxResult expire(@RequestBody(required = false) Map<String, String> body) {
        return store(MerchantPermission.VERIFY,
                ctx -> pledges.expire(ctx.getMerchantId(), principal(ctx), body == null ? null : body.get("awardId"), System.currentTimeMillis()));
    }

    private interface StoreWork {
        Object run(MerchantAccessContext ctx);
    }

    private AjaxResult store(String permission, StoreWork work) {
        Long me = member();
        if (me == null) return ApiNonCashRewardController.signedOut();
        MerchantAccessContext ctx;
        try {
            ctx = access.require(me, permission);
        } catch (RuntimeException denied) {
            return ApiNonCashRewardController.rejection("FORBIDDEN");
        }
        if (ctx == null || ctx.getMerchantId() == null || ctx.getOwnerMemberId() == null) {
            return ApiNonCashRewardController.rejection("FORBIDDEN");
        }
        return ApiNonCashRewardController.run(() -> work.run(ctx));
    }

    private static long principal(MerchantAccessContext ctx) {
        return ctx.getPrincipalMemberId() != null ? ctx.getPrincipalMemberId() : ctx.getOwnerMemberId();
    }

    private Long member() {
        try {
            Long id = getAppUserId();
            return id == null || id <= 0 ? null : id;
        } catch (Exception signedOut) {
            return null;
        }
    }
}
