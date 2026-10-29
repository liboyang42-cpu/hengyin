package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IClubTopicSettingService;
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

import java.util.Map;

@RestController
@Tag(name = "俱乐部主题设置")
@RequestMapping("/api/club/topic-setting")
public class ApiClubTopicSettingController extends BaseController {
    @Autowired private IClubTopicSettingService clubTopicSettingService;

    @Operation(summary = "俱乐部对本主题的开关与章节招商概览(club:activity:read)")
    @PostMapping("/detail")
    public AjaxResult detail(@RequestBody(required = false) Map<String, Object> body) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (body == null) return error("缺少俱乐部或主题参数");
        try {
            return success(clubTopicSettingService.detail(principal, asLong(body.get("clubId")),
                    asLong(body.get("topicId"))));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @Operation(summary = "保存三个开关(club:activity:manage)")
    @PostMapping("/save")
    public AjaxResult save(@RequestBody(required = false) Map<String, Object> body) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (body == null) return error("缺少参数");
        try {
            return success(clubTopicSettingService.save(principal, asLong(body.get("clubId")),
                    asLong(body.get("topicId")), asBool(body.get("coopOpen")),
                    asBool(body.get("pinned")), asBool(body.get("memberOnly"))));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @Operation(summary = "结束主题:停售 + 逐场取消并全额退款(club:activity:manage)")
    @PostMapping("/end")
    public AjaxResult end(@RequestBody(required = false) Map<String, Object> body) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (body == null) return error("缺少参数");
        try {
            Long epoch=cancellationEpoch(body);
            String reason=body.get("reason")==null?null:String.valueOf(body.get("reason"));
            return success(epoch==null?clubTopicSettingService.endTopic(principal,appUsername(principal),
                    asLong(body.get("clubId")),asLong(body.get("topicId")),reason)
                    :clubTopicSettingService.endTopic(principal,appUsername(principal),asLong(body.get("clubId")),
                    asLong(body.get("topicId")),reason,epoch));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    private Long cancellationEpoch(Map<String,Object> body) {
        if(!body.containsKey("cancellationEpoch")) return null;
        Object value=body.get("cancellationEpoch");
        if(value==null || !(value instanceof String || value instanceof Number)
                || !value.toString().matches("0|[1-9][0-9]{0,18}")) throw new ServiceException("取消轮次无效",400);
        try {return Long.valueOf(value.toString());}
        catch(NumberFormatException invalid) {throw new ServiceException("取消轮次无效",400);}
    }

    private Long principal() {
        try { return getAppUserId(); } catch (Exception e) { return null; }
    }

    private String appUsername(Long principal) {
        try {
            String username = getAppUserName();
            return username == null || username.trim().isEmpty() ? String.valueOf(principal) : username;
        } catch (Exception ignored) {
            return String.valueOf(principal);
        }
    }

    private static Long asLong(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number) return ((Number) raw).longValue();
        try { return Long.valueOf(String.valueOf(raw).trim()); } catch (NumberFormatException e) { return null; }
    }

    /** 只认真正的布尔或 "true"/"false"。别的形状一律 null,由服务层拒绝而不是当成 false 关掉开关。 */
    private static Boolean asBool(Object raw) {
        if (raw instanceof Boolean) return (Boolean) raw;
        String s = raw == null ? "" : String.valueOf(raw).trim().toLowerCase();
        if ("true".equals(s)) return Boolean.TRUE;
        if ("false".equals(s)) return Boolean.FALSE;
        return null;
    }

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }
}
