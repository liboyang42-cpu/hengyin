package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IMinorGuardianService;
import com.chengyinhub.business.service.IMinorProtectionService;
import com.chengyinhub.business.service.compliance.ComplianceFeatureSwitches;
import com.chengyinhub.business.service.minor.AgeBand;
import com.chengyinhub.business.service.minor.MinorGuardianException;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DOC2 F7 未成年模式（会员侧）：政策版本、本人年龄段、监护授权发起/确认/撤回/列表。
 * 开关 feature.flag.minorMode 缺省关闭。监护证明只认双方本人账号确认，不收短信验证码、证件或生物特征。
 */
@Tag(name = "未成年模式")
@RestController
@RequestMapping("/api/minor")
public class ApiMinorGuardianController extends BaseController {
    static final String FEATURE_OFF = "MINOR_FEATURE_OFF";

    private final IMinorGuardianService guardianService;
    private final IMinorProtectionService protectionService;
    private final ComplianceFeatureSwitches switches;

    public ApiMinorGuardianController(IMinorGuardianService guardianService,
                                      IMinorProtectionService protectionService,
                                      ComplianceFeatureSwitches switches) {
        this.guardianService = guardianService;
        this.protectionService = protectionService;
        this.switches = switches;
    }

    @Operation(summary = "未成年人保护政策版本与规则摘要")
    @PostMapping("/policy")
    public AjaxResult policy() {
        return run(memberId -> guardianService.policy());
    }

    @Operation(summary = "本人年龄段与是否需要监护人")
    @PostMapping("/status")
    public AjaxResult status() {
        return run(memberId -> {
            AgeBand band = protectionService.ageBand(memberId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ageBand", band.name());
            data.put("guardianRequired", band != AgeBand.ADULT);
            data.put("orienteeringGateOn", switches.minorOrienteeringGateOn());
            return data;
        });
    }

    @Operation(summary = "监护人发起授权（返回一次性邀请码）")
    @PostMapping("/guardian/invite")
    public AjaxResult invite(@RequestBody(required = false) Map<String, Object> body) {
        String requestId = text(body, "requestId");
        String purpose = text(body, "purpose");
        String relation = text(body, "relation");
        String policyVersion = text(body, "policyVersion");
        Integer validDays = toInt(body == null ? null : body.get("validDays"));
        return run(memberId -> guardianService.createInvite(memberId, requestId, purpose, relation,
                policyVersion, validDays));
    }

    @Operation(summary = "被监护人确认授权")
    @PostMapping("/guardian/accept")
    public AjaxResult accept(@RequestBody(required = false) Map<String, Object> body) {
        String token = text(body, "inviteToken");
        String policyVersion = text(body, "policyVersion");
        return run(memberId -> guardianService.accept(memberId, token, policyVersion));
    }

    @Operation(summary = "撤回授权（监护人或被监护人本人）")
    @PostMapping("/guardian/revoke")
    public AjaxResult revoke(@RequestBody(required = false) Map<String, Object> body) {
        Long id = toLong(body == null ? null : body.get("id"));
        return run(memberId -> guardianService.revoke(memberId, id));
    }

    @Operation(summary = "我的监护授权")
    @PostMapping("/guardian/list")
    public AjaxResult list() {
        return run(memberId -> guardianService.listMine(memberId));
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    private interface MemberCall {
        Object apply(Long memberId);
    }

    private AjaxResult run(MemberCall call) {
        if (!switches.minorModeOn()) return coded("未成年模式暂未开放", FEATURE_OFF);
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        try {
            return AjaxResult.success(call.apply(memberId));
        } catch (MinorGuardianException e) {
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

    private static Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && s.trim().matches("\\d{1,18}")) return Long.valueOf(s.trim());
        return null;
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && s.trim().matches("\\d{1,4}")) return Integer.valueOf(s.trim());
        return null;
    }
}
