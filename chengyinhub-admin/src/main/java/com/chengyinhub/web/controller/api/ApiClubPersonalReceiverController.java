package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.payout.EcommercePersonalReceiverService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Owner-only personal receiver consent, separate from checkout seller onboarding. */
@RestController
@RequestMapping("/api/club/personal-receiver")
public class ApiClubPersonalReceiverController extends BaseController {
    private final EcommercePersonalReceiverService service;

    public ApiClubPersonalReceiverController(EcommercePersonalReceiverService service) {
        this.service = service;
    }

    @PostMapping("/bind")
    public AjaxResult bind(@RequestBody BindRequest request) {
        return success(service.bind(getAppUserId(), request.getClubId(), request.getJsCode(), request.getConsent()));
    }

    @GetMapping("/status")
    public AjaxResult status(@RequestParam Long clubId) {
        return success(service.ownerStatus(getAppUserId(), clubId));
    }

    /** Neither member id, appid nor OpenID is accepted from the caller. */
    public static class BindRequest {
        private Long clubId;
        private String jsCode;
        private Boolean consent;
        public Long getClubId() { return clubId; }
        public void setClubId(Long clubId) { this.clubId = clubId; }
        public String getJsCode() { return jsCode; }
        public void setJsCode(String jsCode) { this.jsCode = jsCode; }
        public Boolean getConsent() { return consent; }
        public void setConsent(Boolean consent) { this.consent = consent; }
    }
}
