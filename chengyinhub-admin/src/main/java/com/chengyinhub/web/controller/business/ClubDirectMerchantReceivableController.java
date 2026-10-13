package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.impl.ClubDirectMerchantReceivableService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.Map;

/** 沿用现有财务读写权限并在服务层校验主题范围；俱乐部直付不进平台月结。 */
@RestController
@RequestMapping("/business/club-direct-merchant")
public class ClubDirectMerchantReceivableController extends BaseController {
    private final ClubDirectMerchantReceivableService service;

    public ClubDirectMerchantReceivableController(ClubDirectMerchantReceivableService service) { this.service = service; }

    @PreAuthorize("@ss.hasPermi('business:payablebatch:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam Long topicId) {
        try { return success(service.listForAdmin(topicId)); }
        catch (IllegalStateException e) { return error(e.getMessage()); }
    }

    @PreAuthorize("@ss.hasPermi('business:payablebatch:edit')")
    @Log(title = "俱乐部直付事实登记", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/record")
    public AjaxResult record(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            Object refund = body.get("refund");
            if (refund != null && !(refund instanceof Boolean)) return error("退回标识必须是布尔值");
            boolean done = service.record(id, number(body.get("topicId")), number(body.get("payerClubId")),
                    number(body.get("merchantMemberId")), new BigDecimal(String.valueOf(body.get("amount"))),
                    getUsername(), body.get("voucherNo") == null ? null : String.valueOf(body.get("voucherNo")),
                    Boolean.TRUE.equals(refund));
            return done ? success("已记录付款事实") : error("账目状态已变化或该事实已记录，请刷新");
        } catch (IllegalStateException | IllegalArgumentException e) { return error(e.getMessage()); }
    }

    private Long number(Object value) { return value == null ? null : Long.valueOf(String.valueOf(value)); }
}
