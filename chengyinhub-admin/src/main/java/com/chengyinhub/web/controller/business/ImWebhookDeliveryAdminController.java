package com.chengyinhub.web.controller.business;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.channel.platform.ChannelWebhookAdminService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Back office over the channel-webhook outbox: list deliveries (default: the dead-letter queue) and redrive one.
 * Read is {@code business:audit:list}, redrive is {@code business:audit:review} (the audit workbench permissions).
 * A redrive follows the member retry rules exactly and is written to sys_oper_log through {@link Log}.
 */
@RestController
@Tag(name = "IM Webhook 投递(后台)")
@RequestMapping("/business/im-webhook")
public class ImWebhookDeliveryAdminController extends BaseController
{
    private final ChannelWebhookAdminService deliveries;

    public ImWebhookDeliveryAdminController(ChannelWebhookAdminService deliveries)
    {
        this.deliveries = deliveries;
    }

    @Operation(summary = "投递列表（默认死信 DEAD；state=ALL 看全部）")
    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/deliveries")
    public AjaxResult list(@RequestParam(required = false) String state, @RequestParam(required = false) Long subscriptionId,
                           @RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize)
    {
        return success(deliveries.deliveries(state, subscriptionId, pageNum, pageSize));
    }

    @Operation(summary = "重新投递（死信归零尝试次数；FAILED/UNKNOWN 未超上限可重排）")
    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @Log(title = "IM Webhook 重新投递", businessType = BusinessType.UPDATE)
    @PostMapping("/deliveries/{deliveryId}/redrive")
    public AjaxResult redrive(@PathVariable long deliveryId)
    {
        return success(deliveries.redrive(getUserId(), deliveryId));
    }

    @ExceptionHandler(ChannelRejection.class)
    public ResponseEntity<AjaxResult> failed(ChannelRejection e)
    {
        AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), Map.of());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
