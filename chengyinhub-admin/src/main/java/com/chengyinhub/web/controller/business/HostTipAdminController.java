package com.chengyinhub.web.controller.business;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.exception.BountyTipException;
import com.chengyinhub.business.service.IHostTipService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 主持小费(运营只读)。
 *
 * <p><b>为什么只有只读</b>:小费的收款、转付与退款都要走既有受控结算路径,
 * 当前没有受支持的收款通道,因此运营既不能"手动标已付",也不能在这里打款。
 * 任何这样的能力都必须来自真实通道,不能由后台按钮制造。
 *
 * <p>权限串沿用既有 RBAC 机制:超管 {@code *:*:*} 直接可用;未授权普通账号 403。
 *
 * @author chengyinhub
 */
@Tag(name = "主持小费(运营)")
@RestController
@RequestMapping("/business/hosttip")
public class HostTipAdminController extends BaseController
{
    private static final String PERM_AUDIT = "@ss.hasPermi('business:hosttip:audit')";

    private final IHostTipService hostTipService;

    public HostTipAdminController(IHostTipService hostTipService) {
        this.hostTipService = hostTipService;
    }

    @Operation(summary = "查看某个主持收到的小费记录(只读;状态只能由真实通道回执推进)")
    @PreAuthorize(PERM_AUDIT)
    @GetMapping("/intent/list")
    public AjaxResult listByHost(@RequestParam("hostMemberId") Long hostMemberId) {
        return success(hostTipService.listForHost(hostMemberId));
    }

    @ExceptionHandler(BountyTipException.class)
    public AjaxResult handleRejection(BountyTipException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        return result;
    }
}
