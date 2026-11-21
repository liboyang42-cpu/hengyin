package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.appleiap.WorkshopAppleIapService;
import com.chengyinhub.business.modcommerce.ModCommerceService;
import com.chengyinhub.business.platformpass.PlatformPassAppleService;
import com.chengyinhub.business.platformpass.PlatformPassException;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * R6a:App Store Server Notifications V2 的唯一入口(App Store Connect 里一个 App 只能填一个地址)。
 *
 * <p>通行证商品的通知由 {@link PlatformPassAppleService} 处理;其余原样转给 MOD 工坊的内购处理
 * ({@code /api/workshop/v2/apple-iap/notifications} 保持不变)。两边都自行用 Apple SDK 验签,本类不信任请求里的任何字段。
 * 只要没处理完就回非 2xx,让 Apple 重投;不会对未落库的退款回 200。
 */
@RestController
@RequestMapping("/api/apple-iap")
public class ApiAppleNotificationController {
    private final PlatformPassAppleService pass;
    private final ObjectProvider<WorkshopAppleIapService> workshop;
    /** R6c: workshop template products listed in mod_commerce_offer (dispatch by product id). */
    private final ObjectProvider<ModCommerceService> templates;

    public ApiAppleNotificationController(PlatformPassAppleService pass, ObjectProvider<WorkshopAppleIapService> workshop) {
        this(pass, workshop, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ApiAppleNotificationController(PlatformPassAppleService pass, ObjectProvider<WorkshopAppleIapService> workshop,
                                          ObjectProvider<ModCommerceService> templates) {
        this.pass = pass;
        this.workshop = workshop;
        this.templates = templates;
    }

    public record Notification(String signedPayload) { }

    @Anonymous
    @PostMapping("/notifications")
    public ResponseEntity<AjaxResult> notification(@RequestBody(required = false) Notification command) {
        String raw = command == null ? null : command.signedPayload();
        if (raw == null || raw.isBlank()) return ResponseEntity.status(400).body(new AjaxResult(400, "APPLE_SIGNED_DATA_INVALID"));
        try {
            var handled = pass.notificationIfPass(raw);
            if (handled.isPresent()) return ResponseEntity.ok(AjaxResult.success(handled.get()));
            ModCommerceService template = templates == null ? null : templates.getIfAvailable();
            var templateHandled = template == null ? java.util.Optional.<Map<String, Object>>empty() : template.notificationIfTemplate(raw);
            if (templateHandled.isPresent()) return ResponseEntity.ok(AjaxResult.success(templateHandled.get()));
            WorkshopAppleIapService other = workshop.getIfAvailable();
            if (other == null) return ResponseEntity.status(503).body(new AjaxResult(503, "APPLE_NOTIFICATION_UNROUTED"));
            Map<String, Object> result = other.notification(raw);
            return ResponseEntity.ok(AjaxResult.success(result));
        } catch (PlatformPassException e) {
            int code = e.getHttpStatus() >= 400 ? e.getHttpStatus() : 503;
            return ResponseEntity.status(code).body(new AjaxResult(code, e.getReasonCode()));
        } catch (ServiceException e) {
            int code = e.getCode() == null || e.getCode() < 400 ? 503 : e.getCode();
            return ResponseEntity.status(code).body(new AjaxResult(code, e.getMessage()));
        }
    }
}
