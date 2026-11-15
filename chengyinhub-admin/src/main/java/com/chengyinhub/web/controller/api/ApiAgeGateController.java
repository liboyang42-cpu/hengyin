package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.compliance.MemberAgeAttestationService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.ip.IpUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * P1b SEC-14：注册年龄确认（所有登录方式共用，登录成功后由客户端提交）。
 * 政策见 {@code app.agePolicy}；未满 18 岁的回答被拒绝且不落库。
 */
@Tag(name = "年龄确认")
@RestController
@RequestMapping("/api/compliance/age")
public class ApiAgeGateController extends BaseController {

    private final MemberAgeAttestationService service;

    public ApiAgeGateController(MemberAgeAttestationService service) {
        this.service = service;
    }

    @Operation(summary = "本人年龄确认状态")
    @PostMapping("/status")
    public AjaxResult status() {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        try {
            return success(service.status(memberId));
        } catch (RuntimeException e) {
            return error("年龄确认状态暂不可用");
        }
    }

    @Operation(summary = "确认已满 18 周岁（adult=false 时拒绝）")
    @PostMapping("/confirm")
    public AjaxResult confirm(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        Boolean adult = strictBoolean(body == null ? null : body.get("adult"));
        if (adult == null) return error("请选择是否已满 18 周岁");
        Object source = body.get("source");
        try {
            return success(service.confirm(memberId, adult, source == null ? null : String.valueOf(source),
                    IpUtils.getIpAddr(request)));
        } catch (MemberAgeAttestationService.UnderAgeException e) {
            AjaxResult result = error(e.getMessage());
            result.put("errorCode", MemberAgeAttestationService.UNDER_AGE);
            return result;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            return error("年龄确认暂不可用，请稍后重试");
        }
    }

    /** 只认 JSON true/false 或字符串 "true"/"false"；其它（含缺失）视为没回答。 */
    static Boolean strictBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            if ("true".equals(s.trim())) return Boolean.TRUE;
            if ("false".equals(s.trim())) return Boolean.FALSE;
        }
        return null;
    }

    Long currentMemberId() {
        try {
            Long memberId = getAppUserId();
            return memberId != null && memberId > 0 ? memberId : null;
        } catch (Exception e) {
            return null;
        }
    }
}
