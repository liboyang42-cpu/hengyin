package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.externalgame.ExternalGameOpsService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * G3: whether a catalogue game can be opened, so the app explains a closed game (taken down by an operator, not
 * listed, unknown) instead of failing on the catalogue read. The operator's internal reason is never returned.
 */
@RestController
@Tag(name = "外部游戏可用状态")
public class ApiExternalGameAvailabilityController extends AppGapController {
    private final ExternalGameOpsService ops;

    public ApiExternalGameAvailabilityController(ExternalGameOpsService ops) { this.ops = ops; }

    @Anonymous
    @RateLimiter(time = 60, count = 120, limitType = LimitType.IP)
    @Operation(summary = "外部游戏可用状态（LISTED / TAKEN_DOWN / NOT_LISTED / UNKNOWN_GAME）")
    @GetMapping("/api/external-game/catalog/{gameId}/availability")
    public AjaxResult availability(@PathVariable String gameId) {
        return success(ops.availability(gameId));
    }
}
