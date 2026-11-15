package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.account.apple.AppleIdentityReconfirmationService;
import com.chengyinhub.business.account.apple.AppleTokenRevocationService;
import com.chengyinhub.common.exception.ErrorCode;

import com.chengyinhub.business.service.IAccountDeregistrationService;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.DeregistrationStatusVO;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.enums.LimitType;
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
    @Autowired
    private com.chengyinhub.business.appgaps.DeregistrationResultService deregistrationResultService;
    /** FLOW-03: Apple-only accounts (no phone, no WeChat openid) confirm deletion with a fresh Sign in with Apple. */
    @Autowired(required = false)
    private AppleIdentityReconfirmationService appleReconfirmationService;
    @Autowired(required = false)
    private AppleTokenRevocationService appleTokenRevocationService;

    @Operation(summary = "注销前置条件检查")
    @PostMapping("/precheck")
    public AjaxResult precheck() {
        Long memberId = currentMemberId();
        if (memberId == null) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        return success(deregistrationService.precheck(memberId));
    }

    @Operation(summary = "提交账号注销申请")
    @PostMapping("/apply")
    public AjaxResult apply(String smscode, String wxcode, String appleIdentityToken, String appleAuthorizationCode,
                            String requestId, HttpServletRequest request) {
        Long memberId = currentMemberId();
        if (memberId == null) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        ConsentRecord latestConsent = consentRecordService.latest(memberId,
                "account_cancellation_notice", "account_cancel");
        String currentVersion = complianceProperties.versionOf("account_cancellation_notice");
        if (latestConsent == null || !"AGREE".equals(latestConsent.getEventType())
                || currentVersion == null || !currentVersion.equals(latestConsent.getDocVersion())) {
            return error("请先同意账号注销须知");
        }
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        if (member == null) return refuse(ErrorCode.NOT_FOUND, "账号不存在");
        if (StringUtils.isEmpty(requestId) || requestId.length() > 64) {
            return refuse(ErrorCode.BAD_REQUEST, "请求标识不合法");
        }
        // F-R2S-07:首次提交已生效但回包丢失时,验证码已被原子消费;重试先回放已存在的 PENDING,
        // 不再要求重新验证(只返回本人状态,不产生新的写入)。
        DeregistrationStatusVO existing = deregistrationService.status(memberId);
        if (existing != null && "PENDING".equals(existing.getStatus())) {
            existing.setResultToken(deregistrationResultService.issue(memberId, requestId));
            return success(existing);
        }
        String trustedIp = TrustedProxyIpResolver.resolve(request);
        boolean appleConfirmed = false;
        if (member.getPhone() != null && !member.getPhone().trim().isEmpty()) {
            // A supplied WeChat code or Apple token cannot bypass the bound-phone SMS check.
            if (!smsVerificationService.verify(member.getPhone(), smscode, trustedIp)) return error("验证码输入有误");
        } else if (isAppleOnly(member)) {
            // FLOW-03: no phone and no WeChat openid, so the only owner proof is a fresh Sign in with Apple.
            AppleIdentityReconfirmationService.Result apple = appleReconfirmationService.verify(
                    memberId, member.getAppleId(), appleIdentityToken, trustedIp);
            if (apple == AppleIdentityReconfirmationService.Result.UNAVAILABLE) {
                return error("Apple 身份确认暂时不可用，请稍后重试");
            }
            if (apple != AppleIdentityReconfirmationService.Result.CONFIRMED) {
                return error("Apple 身份确认失败，请重新确认本人");
            }
            appleConfirmed = true;
        } else if (!wechatReconfirmationService.verify(memberId, member.getWxOpenId(), wxcode, trustedIp)) {
            return error("微信身份确认失败，请重新确认本人");
        }
        DeregistrationStatusVO applied = deregistrationService.apply(memberId);
        if (applied != null && "PENDING".equals(applied.getStatus())) {
            // Apply revokes every session; the token is the only way to read the result afterwards.
            applied.setResultToken(deregistrationResultService.issue(memberId, requestId));
            if (appleConfirmed && appleTokenRevocationService != null) {
                // Best effort: exchange the code now (it expires in 5 minutes) so the token can be revoked on erasure.
                appleTokenRevocationService.prepare(memberId, appleAuthorizationCode);
            }
        }
        return success(applied);
    }

    /** The WeChat-or-SMS signature used by existing Java callers and tests; only the six-argument method is HTTP mapped. */
    public AjaxResult apply(String smscode, String wxcode, String requestId, HttpServletRequest request) {
        return apply(smscode, wxcode, null, null, requestId, request);
    }

    /** Existing Java callers use the SMS-only signature; only the six-argument method is HTTP mapped. */
    public AjaxResult apply(String smscode, String requestId, HttpServletRequest request) {
        return apply(smscode, null, null, null, requestId, request);
    }

    private boolean isAppleOnly(UmsMember member) {
        return appleReconfirmationService != null
                && (member.getWxOpenId() == null || member.getWxOpenId().trim().isEmpty())
                && member.getAppleId() != null && !member.getAppleId().trim().isEmpty();
    }

    @Operation(summary = "撤销冷静期内的注销申请")
    @PostMapping("/cancel")
    public AjaxResult cancel() {
        Long memberId = currentMemberId();
        if (memberId == null) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        try {
            DeregistrationStatusVO cancelled = deregistrationService.cancel(memberId);
            if (appleTokenRevocationService != null) appleTokenRevocationService.discard(memberId);
            return success(cancelled);
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @RateLimiter(time = 60, count = 30, limitType = LimitType.IP)
    @Operation(summary = "注销申请后的结果回读(会话已被撤销时凭申请回执里的 resultToken)")
    @GetMapping("/result")
    public org.springframework.http.ResponseEntity<AjaxResult> result(String resultToken) {
        try {
            return org.springframework.http.ResponseEntity.ok(success(deregistrationResultService.result(currentMemberId(), resultToken)));
        } catch (com.chengyinhub.business.appgaps.AppGapException e) {
            AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage());
            body.put("errorCode", e.getErrorCode());
            return org.springframework.http.ResponseEntity.status(e.getStatus()).body(body);
        }
    }

    @Operation(summary = "查询账号注销状态")
    @GetMapping("/status")
    public AjaxResult status() {
        Long memberId = currentMemberId();
        if (memberId == null) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
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
