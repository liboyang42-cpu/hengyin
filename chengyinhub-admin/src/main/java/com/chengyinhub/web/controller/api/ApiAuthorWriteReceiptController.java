package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.support.AuthorWriteReceiptService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/author-write")
public class ApiAuthorWriteReceiptController extends BaseController {
    private final AuthorWriteReceiptService receipts;
    public ApiAuthorWriteReceiptController(AuthorWriteReceiptService receipts) { this.receipts = receipts; }
    @GetMapping("/receipt")
    public AjaxResult receipt(@RequestParam String operation, @RequestParam String requestId) {
        return success(receipts.receipt(getAppUserId(), operation, requestId));
    }
}
