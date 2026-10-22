package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.UserInvoiceTitle;
import com.chengyinhub.business.service.IUserInvoiceService;
import com.chengyinhub.business.service.compliance.ComplianceFeatureSwitches;
import com.chengyinhub.business.service.invoice.UserInvoiceException;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * DOC2 F7 用户发票（会员侧）。开关 feature.flag.userInvoice 缺省关闭；
 * 当前登录会员只从 token 取，所有读写都按它做归属过滤，body 里的 memberId 一概不认。
 */
@Tag(name = "用户发票")
@RestController
@RequestMapping("/api/invoice")
public class ApiUserInvoiceController extends BaseController {
    static final String FEATURE_OFF = "INVOICE_FEATURE_OFF";

    private final IUserInvoiceService invoiceService;
    private final ComplianceFeatureSwitches switches;

    public ApiUserInvoiceController(IUserInvoiceService invoiceService, ComplianceFeatureSwitches switches) {
        this.invoiceService = invoiceService;
        this.switches = switches;
    }

    @Operation(summary = "我的发票抬头（税号掩码）")
    @PostMapping("/titles")
    public AjaxResult titles() {
        return run(memberId -> invoiceService.listTitles(memberId));
    }

    @Operation(summary = "新建或修改发票抬头（仅本人）")
    @PostMapping("/titles/save")
    public AjaxResult saveTitle(@RequestBody(required = false) Map<String, Object> body) {
        UserInvoiceTitle input = new UserInvoiceTitle();
        input.setId(toLong(body, "id"));
        input.setTitleType(text(body, "titleType"));
        input.setTitleName(text(body, "titleName"));
        input.setTaxNo(text(body, "taxNo"));
        input.setIsDefault(Boolean.TRUE.equals(body == null ? null : body.get("isDefault")) ? 1 : 0);
        return run(memberId -> invoiceService.saveTitle(memberId, input));
    }

    @Operation(summary = "删除发票抬头（仅本人）")
    @PostMapping("/titles/delete")
    public AjaxResult deleteTitle(@RequestBody(required = false) Map<String, Object> body) {
        Long id = toLong(body, "id");
        return run(memberId -> {
            invoiceService.deleteTitle(memberId, id);
            return null;
        });
    }

    @Operation(summary = "可开票订单（已支付、未退款、未开过票）")
    @PostMapping("/orders/eligible")
    public AjaxResult eligibleOrders() {
        return run(memberId -> invoiceService.listInvoiceableOrders(memberId));
    }

    @Operation(summary = "合并多笔订单申请发票（requestId 幂等）")
    @PostMapping("/apply")
    public AjaxResult apply(@RequestBody(required = false) Map<String, Object> body) {
        String requestId = text(body, "requestId");
        Long titleId = toLong(body, "titleId");
        List<Long> orderIds = toLongList(body == null ? null : body.get("orderIds"));
        return run(memberId -> invoiceService.apply(memberId, requestId, titleId, orderIds));
    }

    @Operation(summary = "我的发票申请")
    @PostMapping("/list")
    public AjaxResult list() {
        return run(memberId -> invoiceService.listApplications(memberId));
    }

    @Operation(summary = "发票申请详情（仅本人）")
    @PostMapping("/detail")
    public AjaxResult detail(@RequestBody(required = false) Map<String, Object> body) {
        Long id = toLong(body, "id");
        return run(memberId -> invoiceService.applicationDetail(memberId, id));
    }

    @Operation(summary = "撤回未开具的发票申请（仅本人），订单可重新申请")
    @PostMapping("/cancel")
    public AjaxResult cancel(@RequestBody(required = false) Map<String, Object> body) {
        Long id = toLong(body, "id");
        return run(memberId -> invoiceService.cancel(memberId, id));
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    private interface MemberCall {
        Object apply(Long memberId);
    }

    private AjaxResult run(MemberCall call) {
        if (!switches.userInvoiceOn()) return coded("发票功能暂未开放", FEATURE_OFF);
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        try {
            return AjaxResult.success(call.apply(memberId));
        } catch (UserInvoiceException e) {
            return coded(e.getMessage(), e.getErrorCode());
        }
    }

    private AjaxResult coded(String message, String errorCode) {
        AjaxResult result = error(message);
        result.put("errorCode", errorCode);
        return result;
    }

    private Long currentMemberId() {
        try {
            Long memberId = getAppUserId();
            return memberId != null && memberId > 0 ? memberId : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long toLong(Map<String, Object> body, String key) {
        return toLong(body == null ? null : body.get(key));
    }

    private static Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && s.trim().matches("\\d{1,18}")) return Long.valueOf(s.trim());
        return null;
    }

    /** 非法元素原样转成 null，交给服务层统一拒绝，不在这里静默丢弃。 */
    private static List<Long> toLongList(Object value) {
        if (!(value instanceof Collection<?> items)) return null;
        List<Long> out = new ArrayList<>();
        for (Object item : items) out.add(toLong(item));
        return out;
    }
}
