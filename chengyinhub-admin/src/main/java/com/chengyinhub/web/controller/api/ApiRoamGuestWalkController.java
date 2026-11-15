package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.guestwalk.GuestWalkPolicy;
import com.chengyinhub.business.guestwalk.GuestWalkService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * R4 guest-walk (GRO-12): {@code POST /api/roam/guest-walk} saves the one walk made on this install before sign-in.
 * Signed in only (the route is not on the anonymous list). Body and rules: {@link GuestWalkPolicy}; credit:
 * {@link GuestWalkService}. Refusals carry {@code reason} (stable code) and {@code retryable}.
 */
@RestController
@Tag(name = "漫游")
@RequestMapping("/api/roam")
public class ApiRoamGuestWalkController extends BaseController {

    @Autowired
    private GuestWalkService guestWalkService;

    /** Package-visible so tests can inject an identity. */
    Long uid() {
        try {
            return getAppUserId();
        } catch (Exception e) {
            return 0L;
        }
    }

    @Operation(summary = "保存登录前的那一次漫游(每台设备、每个账号各一次)")
    @PostMapping("/guest-walk")
    @RateLimiter(time = 60, count = 6, limitType = LimitType.MEMBER)
    public AjaxResult save(@RequestBody(required = false) String body) {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) return error("请先登录");
        try {
            return success(guestWalkService.save(memberId, body, System.currentTimeMillis()));
        } catch (GuestWalkPolicy.Rejected rejected) {
            return error(rejected.getMessage()).put("reason", "INVALID").put("retryable", false);
        } catch (GuestWalkService.Refused refused) {
            return error(refused.getMessage()).put("reason", refused.code()).put("retryable", refused.retryable());
        } catch (DuplicateKeyException raced) {
            // Two uploads of the same walk raced; the loser's transaction rolled back and its retry reads the result.
            return error("正在保存,请稍后再试").put("reason", "IN_PROGRESS").put("retryable", true);
        }
    }
}
