package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IAccountDeregistrationService;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.ip.TrustedProxyIpResolver;
import com.chengyinhub.framework.web.service.SmsVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/** 账号注销：预检、申请、撤销和状态查询均只作用于当前登录用户。 */
@RestController
@Tag(name = "账号注销")
@RequestMapping("/api/user/deregister")
public class ApiAccountDeregistrationController extends BaseController {

    @Autowired
    private IAccountDeregistrationService deregistrationService;
    @Autowired
    private IUmsMemberService umsMemberService;
    @Autowired
    private SmsVerificationService smsVerificationService;
    @Autowired
    private IConsentRecordService consentRecordService;
    @Autowired
    private ComplianceProperties complianceProperties;

    @Operation(summary = "注销前置条件检查")
    @PostMapping("/precheck")
    public AjaxResult precheck() {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        return success(deregistrationService.precheck(memberId));
    }

    @Operation(summary = "提交账号注销申请")
    @PostMapping("/apply")
    public AjaxResult apply(String smscode, String requestId, HttpServletRequest request) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        ConsentRecord latestConsent = consentRecordService.latest(memberId,
                "account_cancellation_notice", "account_cancel");
        String currentVersion = complianceProperties.versionOf("account_cancellation_notice");
        if (latestConsent == null || !"AGREE".equals(latestConsent.getEventType())
                || currentVersion == null || !currentVersion.equals(latestConsent.getDocVersion())) {
            return error("请先同意账号注销须知");
        }
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        if (member == null || StringUtils.isEmpty(member.getPhone())) {
            return error("请先绑定手机号后再申请注销");
        }
        if (StringUtils.isEmpty(requestId) || requestId.length() > 64) {
            return error("请求标识不合法");
        }
        if (!smsVerificationService.verify(
                member.getPhone(), smscode, TrustedProxyIpResolver.resolve(request))) {
            return error("验证码输入有误");
        }
        return success(deregistrationService.apply(memberId));
    }

    @Operation(summary = "撤销冷静期内的注销申请")
    @PostMapping("/cancel")
    public AjaxResult cancel() {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        try {
            return success(deregistrationService.cancel(memberId));
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "查询账号注销状态")
    @GetMapping("/status")
    public AjaxResult status() {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        return success(deregistrationService.status(memberId));
    }

    private Long currentMemberId() {
        try {
            return getAppUserId();
        } catch (Exception e) {
            return null;
        }
    }
}
