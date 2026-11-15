package com.chengyinhub.web.controller.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.MerchantReferralBounty;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 拓展人看自己的激活赏金(R9 BE-13)。只读:不触发激活、不入账、不提现。
 * 只回本人作为拓展人的记录,商家只露会员 id;快照、追回原因与审核人不下发。
 */
@Tag(name = "拓展人赏金")
@RestController
@RequestMapping("/api/referral/bounty")
public class ApiReferralBountyController extends BaseController
{
    private final MerchantReferralBountyService bountyService;

    public ApiReferralBountyController(MerchantReferralBountyService bountyService)
    {
        this.bountyService = bountyService;
    }

    @Operation(summary = "我的拓展赏金")
    @GetMapping("/mine")
    public AjaxResult mine()
    {
        Long memberId = null;
        try { memberId = getAppUserId(); } catch (Exception ignored) { }
        if (memberId == null || memberId <= 0) return AjaxResult.error(HttpStatus.UNAUTHORIZED, "请先登录");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MerchantReferralBounty b : bountyService.mine(memberId)) rows.add(project(b));
        return success(rows);
    }

    static Map<String, Object> project(MerchantReferralBounty b)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", b.getId());
        row.put("merchantMemberId", b.getMerchantMemberId());
        row.put("state", state(b.getStatus()));
        row.put("windowExpiresAt", b.getWindowExpiresAt());
        row.put("activatedAt", b.getActivatedAt());
        row.put("amount", b.getBountyAmount());
        row.put("paidAt", b.getPaidAt());
        row.put("frozenUntil", b.getFrozenUntil());
        return row;
    }

    /** 稳定的英文状态键,App/小程序据此显示本地化文案。 */
    static String state(Integer status)
    {
        if (status == null) return "UNKNOWN";
        switch (status)
        {
            case MerchantReferralBounty.STATUS_PENDING: return "WAITING_FOR_ACTIVATION";
            case MerchantReferralBounty.STATUS_PAID: return "PAID";
            case MerchantReferralBounty.STATUS_CLAWED_BACK: return "CLAWED_BACK";
            case MerchantReferralBounty.STATUS_EXPIRED: return "EXPIRED";
            case MerchantReferralBounty.STATUS_AWAITING_REVIEW: return "IN_REVIEW";
            case MerchantReferralBounty.STATUS_REJECTED: return "REJECTED";
            default: return "UNKNOWN";
        }
    }
}
