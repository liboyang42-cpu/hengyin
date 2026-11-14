package com.chengyinhub.web.controller.api.appgaps;

import com.chengyinhub.business.appgaps.SupportTicketReads;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** App 客服工单只读（本人）。 */
@RestController
@Tag(name = "App 客服工单")
public class ApiAppGapsSupportController extends BaseController {
    private final SupportTicketReads reads;

    public ApiAppGapsSupportController(SupportTicketReads reads) { this.reads = reads; }

    private Long member() { try { return getAppUserId(); } catch (Exception e) { return null; } }

    @PostMapping("/api/support/tickets/list")
    public AjaxResult list(@RequestBody(required = false) Map<String, Object> body) {
        Long member = member();
        if (member == null) return AjaxResult.error(401, "请先登录");
        try { return AjaxResult.success(reads.list(member, body)); }
        catch (ServiceException e) { return AjaxResult.error(e.getCode() == null ? 400 : e.getCode(), e.getMessage()); }
    }

    @PostMapping("/api/support/tickets/detail")
    public AjaxResult detail(@RequestBody(required = false) Map<String, Object> body) {
        Long member = member();
        if (member == null) return AjaxResult.error(401, "请先登录");
        try { return AjaxResult.success(reads.detail(member, body)); }
        catch (ServiceException e) { return AjaxResult.error(e.getCode() == null ? 400 : e.getCode(), e.getMessage()); }
    }
}
