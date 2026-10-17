package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IAccountDeregistrationService;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.DeregistrationStatusVO;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.ip.TrustedProxyIpResolver;
import com.chengyinhub.framework.web.service.SmsVerificationService;
import com.chengyinhub.framework.web.service.WechatReconfirmationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

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
    private WechatReconfirmationService wechatReconfirmationService;
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
    public AjaxResult apply(String smscode, String wxcode, String requestId, HttpServletRequest request) {
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
        if (member == null) return error("账号不存在");
        if (StringUtils.isEmpty(requestId) || requestId.length() > 64) {
            return error("请求标识不合法");
        }
        // F-R2S-07:首次提交已生效但回包丢失时,验证码已被原子消费;重试先回放已存在的 PENDING,
        // 不再要求重新验证(只返回本人状态,不产生新的写入)。
        DeregistrationStatusVO existing = deregistrationService.status(memberId);
        if (existing != null && "PENDING".equals(existing.getStatus())) {
            return success(existing);
        }
        String trustedIp = TrustedProxyIpResolver.resolve(request);
        if (member.getPhone() != null && !member.getPhone().trim().isEmpty()) {
            // A supplied WeChat code cannot bypass the bound-phone SMS check.
            if (!smsVerificationService.verify(member.getPhone(), smscode, trustedIp)) return error("验证码输入有误");
        } else if (!wechatReconfirmationService.verify(memberId, member.getWxOpenId(), wxcode, trustedIp)) {
            return error("微信身份确认失败，请重新确认本人");
        }
        return success(deregistrationService.apply(memberId));
    }

    /** Existing Java callers use the SMS-only signature; only the four-argument method is HTTP mapped. */
    public AjaxResult apply(String smscode, String requestId, HttpServletRequest request) {
        return apply(smscode, null, requestId, request);
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
