package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.impl.UserInvoiceProcessingService;
import com.chengyinhub.business.service.impl.UserInvoiceRefundReconciliationJob;
import com.chengyinhub.business.service.compliance.ComplianceFeatureSwitches;
import com.chengyinhub.business.service.invoice.UserInvoiceException;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.function.Supplier;

/** Dedicated invoice permissions AND issuer scope are required. No finance-role fallback or export. */
@RestController
@RequestMapping("/business/userInvoice")
public class UserInvoiceProcessingController extends BaseController {
    private final UserInvoiceProcessingService service;
    private final ComplianceFeatureSwitches switches;
    private final UserInvoiceRefundReconciliationJob reconciliation;
    public UserInvoiceProcessingController(UserInvoiceProcessingService service, ComplianceFeatureSwitches switches, UserInvoiceRefundReconciliationJob reconciliation) {
        this.service=service; this.switches=switches; this.reconciliation=reconciliation;
    }
    @GetMapping("/list")
    @PreAuthorize("@ss.hasPermi('business:userInvoice:list')")
    public AjaxResult list(@RequestParam(required=false) Long before, HttpServletResponse response) {
        return run(response,()->service.list(getUserId(),before));
    }
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('business:userInvoice:query')")
    public AjaxResult detail(@PathVariable Long id,HttpServletResponse response) {
        return run(response,()->service.detail(getUserId(),id));
    }
    /** D6:刷新会暂停申请并写审计,属处理动作,要求 process 权限而非查看权限。 */
    @PostMapping("/{id}/refresh-refund-facts")
    @PreAuthorize("@ss.hasPermi('business:userInvoice:process')")
    public AjaxResult refreshRefundFacts(@PathVariable Long id,HttpServletResponse response) {
        return run(response,()->reconciliation.refreshApplication(getUserId(),id));
    }
    @PostMapping("/{id}/tax-number")
    @PreAuthorize("@ss.hasPermi('business:userInvoice:taxView')")
    public AjaxResult taxNumber(@PathVariable Long id,@RequestBody Map<String,String> body,HttpServletResponse response) {
        return run(response,()->service.revealTaxNumber(getUserId(),id,body.get("requestId")));
    }
    @GetMapping("/{id}/requests/{requestId}")
    @PreAuthorize("@ss.hasPermi('business:userInvoice:query')")
    public AjaxResult receipt(@PathVariable Long id,@PathVariable String requestId,HttpServletResponse response) {
        return run(response,()->service.receipt(getUserId(),id,requestId));
    }
    @PostMapping("/{id}/requests/{requestId}/close")
    @PreAuthorize("@ss.hasPermi('business:userInvoice:process')")
    public AjaxResult closeCommand(@PathVariable Long id,@PathVariable String requestId,
                                   @RequestBody Map<String,String> body,HttpServletResponse response) {
        return run(response,()-> {
            if("RECONCILE".equals(body.get("action"))) throw new UserInvoiceException(UserInvoiceException.ARGUMENT_INVALID,"请使用核对请求封存入口");
            return service.closeUnconfirmed(getUserId(),id,requestId,body.get("action"));
        });
    }
    @PostMapping("/{id}/requests/{requestId}/close-reconcile")
    @PreAuthorize("@ss.hasPermi('business:userInvoice:reconcile')")
    public AjaxResult closeReconcile(@PathVariable Long id,@PathVariable String requestId,HttpServletResponse response) {
        return run(response,()->service.closeUnconfirmed(getUserId(),id,requestId,"RECONCILE"));
    }
    @PostMapping("/{id}/commands")
    @PreAuthorize("@ss.hasPermi('business:userInvoice:process')")
    public AjaxResult command(@PathVariable Long id,@RequestBody UserInvoiceProcessingService.Command command,HttpServletResponse response) {
        return run(response,()-> {
            if ("RECONCILE".equals(command.action())) throw new UserInvoiceException(UserInvoiceException.ARGUMENT_INVALID,"请使用核对入口");
            return service.command(getUserId(),id,command);
        });
    }
    @PostMapping("/{id}/reconcile")
    @PreAuthorize("@ss.hasPermi('business:userInvoice:reconcile')")
    public AjaxResult reconcile(@PathVariable Long id,@RequestBody UserInvoiceProcessingService.Command command,HttpServletResponse response) {
        return run(response,()-> {
            if(!"RECONCILE".equals(command.action())) throw new UserInvoiceException(UserInvoiceException.ARGUMENT_INVALID,"核对动作不合法");
            return service.command(getUserId(),id,command);
        });
    }
    private AjaxResult run(HttpServletResponse response,Supplier<Object> action) {
        response.setHeader("Cache-Control","no-store"); response.setHeader("Pragma","no-cache");
        if(!switches.userInvoiceOn()) return AjaxResult.error("发票功能未启用").put("errorCode","INVOICE_FEATURE_OFF");
        try {return AjaxResult.success(action.get());}
        catch(UserInvoiceException e) {return AjaxResult.error(e.getMessage()).put("errorCode",e.getErrorCode());}
    }
}
