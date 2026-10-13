package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.vo.SpoilerAccessSnapshot;
import com.chengyinhub.business.service.ISpoilerProtectionService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@Tag(name = "剧透保护")
@RequestMapping("/api/spoiler")
public class ApiSpoilerController extends BaseController {
    @Autowired private ISpoilerProtectionService spoilerProtectionService;

    @Operation(summary = "读取当前用户剧透偏好与已揭晓节点")
    @PostMapping("/state")
    public AjaxResult state() {
        Long memberId = memberId();
        if (memberId == null) return error("请先登录");
        SpoilerAccessSnapshot state = spoilerProtectionService.snapshot(
                memberId, ISpoilerProtectionService.CONTENT_NODE);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revealAll", state.isRevealAll());
        out.put("revealedNodeIds", state.getRevealedContentIds());
        return success(out);
    }

    @Operation(summary = "更新全部揭晓偏好")
    @PostMapping("/preference")
    public AjaxResult preference(@RequestBody Map<String, Object> body) {
        Long memberId = memberId();
        if (memberId == null) return error("请先登录");
        if (body == null || !(body.get("revealAll") instanceof Boolean)) return error("缺少 revealAll");
        boolean revealAll = (Boolean) body.get("revealAll");
        return spoilerProtectionService.updatePreference(memberId, revealAll)
                ? success("设置成功") : error("设置失败");
    }

    @Operation(summary = "主动揭晓一个节点内容")
    @PostMapping("/reveal")
    public AjaxResult reveal(@RequestBody Map<String, Object> body) {
        Long memberId = memberId();
        if (memberId == null) return error("请先登录");
        try {
            Long contentId = body == null || body.get("contentId") == null
                    ? null : Long.valueOf(String.valueOf(body.get("contentId")));
            String requestId = body == null || body.get("requestId") == null
                    ? null : String.valueOf(body.get("requestId"));
            spoilerProtectionService.reveal(memberId, ISpoilerProtectionService.CONTENT_NODE,
                    contentId, requestId);
            return success("已揭晓");
        } catch (NumberFormatException e) {
            return error("剧透保护目标无效");
        } catch (ServiceException e) {
            AjaxResult error = new AjaxResult(e.getCode(), e.getMessage());
            error.put("errorCode", e.getDetailMessage());
            return error;
        }
    }

    private Long memberId() {
        try {
            Long value = getAppUserId();
            return value != null && value > 0 ? value : null;
        } catch (Exception e) {
            return null;
        }
    }
}
