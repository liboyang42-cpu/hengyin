package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.exception.ErrorCode;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.push.PushDeviceService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * App 远程推送设备登记（已登录会员）。
 * <pre>
 * POST /api/push/device/register   {"platform":"IOS","token":"hex","environment":"SANDBOX|PRODUCTION","bundleId":"..","locale":"zh-Hans"}
 *      → data {"deviceId":1,"enabled":true}
 * POST /api/push/device/unregister {"token":"hex"} → data {"enabled":false}（只停用本人名下的 token，幂等）
 * </pre>
 */
@RestController
@Tag(name = "推送设备")
@RequestMapping("/api/push/device")
public class ApiPushDeviceController extends BaseController
{
    @Autowired
    private PushDeviceService devices;

    @Operation(summary = "登记推送设备")
    @PostMapping("/register")
    public AjaxResult register(@RequestBody(required = false) Map<String, Object> body)
    {
        Long principal = principal();
        if (principal == null) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        try
        {
            return success(devices.register(principal, body));
        }
        catch (ServiceException e)
        {
            return serviceError(e);
        }
    }

    @Operation(summary = "注销推送设备")
    @PostMapping("/unregister")
    public AjaxResult unregister(@RequestBody(required = false) Map<String, Object> body)
    {
        Long principal = principal();
        if (principal == null) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        try
        {
            return success(devices.unregister(principal, body));
        }
        catch (ServiceException e)
        {
            return serviceError(e);
        }
    }

    private Long principal()
    {
        try { return getAppUserId(); } catch (Exception ignored) { return null; }
    }

    private AjaxResult serviceError(ServiceException e)
    {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }
}
