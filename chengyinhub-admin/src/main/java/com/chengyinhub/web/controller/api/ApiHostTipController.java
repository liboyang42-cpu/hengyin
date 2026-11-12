package com.chengyinhub.web.controller.api;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.dto.HostTipConfigRequest;
import com.chengyinhub.business.domain.dto.HostTipPrepareRequest;
import com.chengyinhub.business.exception.BountyTipException;
import com.chengyinhub.business.service.IHostTipService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 主持小费(顾客与主持共用入口)。
 *
 * <p><b>通道未启用时</b>{@code /prepare} 返回 HTTP 501 + {@code reasonCode=CHANNEL_NOT_ENABLED}:
 * 意向单已记录,但没有任何收款发生。客户端必须按 {@code payable=false} 渲染,
 * <b>不得</b>显示"已支付"或给任何余额加数。
 *
 * <p>本入口不加余额、不写分账、不改原订单;退款与打款都在既有受控结算路径里。
 *
 * @author chengyinhub
 */
@Tag(name = "主持小费")
@RestController
@RequestMapping("/api/host-tip")
public class ApiHostTipController extends BaseController
{
    private final IHostTipService hostTipService;

    public ApiHostTipController(IHostTipService hostTipService) {
        this.hostTipService = hostTipService;
    }

    @Operation(summary = "配置我的小费(只能配置自己;100% 归主持,平台不抽成)")
    @PostMapping("/config")
    public AjaxResult upsertConfig(@RequestBody(required = false) HostTipConfigRequest request) {
        return success(hostTipService.upsertConfig(requireAppMemberId(), request));
    }

    @Operation(summary = "我的小费配置")
    @GetMapping("/config/mine")
    public AjaxResult myConfigs() {
        return success(hostTipService.listMyConfigs(requireAppMemberId()));
    }

    @Operation(summary = "顾客自愿给主持一笔小费(通道未启用时返回 501,不产生收款)")
    @PostMapping("/prepare")
    public AjaxResult prepare(@RequestBody(required = false) HostTipPrepareRequest request) {
        return success(hostTipService.prepare(requireAppMemberId(), request));
    }

    @Operation(summary = "取消自己未支付的小费")
    @PostMapping("/intent/{id}/cancel")
    public AjaxResult cancel(@PathVariable("id") Long id) {
        return success(hostTipService.cancel(requireAppMemberId(), id));
    }

    @Operation(summary = "我付出的小费记录")
    @GetMapping("/intent/mine")
    public AjaxResult listMine() {
        return success(hostTipService.listMine(requireAppMemberId()));
    }

    @Operation(summary = "我收到的小费记录(只读)")
    @GetMapping("/intent/received")
    public AjaxResult listReceived() {
        return success(hostTipService.listForHost(requireAppMemberId()));
    }

    @ExceptionHandler(BountyTipException.class)
    public AjaxResult handleRejection(BountyTipException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        return result;
    }

    private long requireAppMemberId() {
        Long memberId = getAppUserId();
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("请先登录", HttpStatus.UNAUTHORIZED);
        }
        return memberId;
    }
}
