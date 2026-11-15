package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.citygame.CityGameException;
import com.chengyinhub.business.citygame.MemberAttributeService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Attributes v2 (six permanent attributes, titles, archetype) for the signed-in member only. */
@RestController
@RequestMapping("/api/member")
public class ApiMemberAttributeController extends BaseController {
    private final MemberAttributeService service;

    public ApiMemberAttributeController(MemberAttributeService service) {
        this.service = service;
    }

    private long member() {
        try {
            return getAppUserId();
        } catch (Exception e) {
            throw new CityGameException(401, "UNAUTHORIZED", "请先登录");
        }
    }

    @GetMapping("/attributes")
    public AjaxResult attributes() {
        return success(service.view(member()));
    }

    @GetMapping("/attributes/ledger")
    public AjaxResult entries(@RequestParam(required = false) String key,
                              @RequestParam(required = false) Long cursor,
                              @RequestParam(required = false) Integer limit) {
        return success(service.entries(member(), key, cursor, limit));
    }

    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 20, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @PostMapping("/titles/equip")
    public AjaxResult equip(@RequestBody Map<String, Object> body) {
        Object code = body == null ? null : body.get("titleCode");
        return success(service.equip(member(), code == null ? null : String.valueOf(code)));
    }

    @ExceptionHandler(CityGameException.class)
    public ResponseEntity<AjaxResult> failed(CityGameException e, HttpServletRequest request) {
        AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), e.getData());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
