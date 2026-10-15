package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.MemberIdentityRequest;
import com.chengyinhub.business.service.impl.MemberIdentityService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.ip.IpUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 发布者实名登记(RUN-52 改判后口径)。
 *
 * <p>刻意<b>不加</b> {@code @Log}:RuoYi 的操作日志会把入参整份 JSON 落进 sys_oper_log,
 * 挂上它就等于把明文身份证号抄进一张没有任何加密与脱敏的表里。
 *
 * <p>也没有「查详情」接口 —— 前端只允许知道登记过没有(见 {@link #status()}),
 * 姓名与身份证号从这条调用栈出去不了。
 */
@RestController
@Tag(name = "发布者实名登记")
@RequestMapping("/api/publisher/identity")
public class ApiMemberIdentityController extends BaseController {

    @Autowired
    private MemberIdentityService memberIdentityService;

    @Operation(summary = "登记发布者实名(姓名+身份证号)")
    @RateLimiter(time = 60, count = 5, limitType = LimitType.MEMBER)
    @PostMapping
    public AjaxResult register(@RequestBody MemberIdentityRequest body, HttpServletRequest request) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        try {
            memberIdentityService.register(memberId, body.getRealName(), body.getIdCard(),
                    Boolean.TRUE.equals(body.getConsent()), body.getSource(),
                    IpUtils.getIpAddr(request));
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
        Map<String, Object> data = new HashMap<>();
        data.put("registered", true);
        return AjaxResult.success("实名信息已登记", data);
    }

    @Operation(summary = "查询是否已登记实名(只回状态,不回显任何填写值)")
    @PostMapping("/status")
    public AjaxResult status() {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("registered", memberIdentityService.isRegistered(memberId));
        return AjaxResult.success(data);
    }
}
