package com.chengyinhub.web.interceptor;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.vo.DeregistrationStatusVO;
import com.chengyinhub.business.service.IAccountDeregistrationService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 冷静期内只允许账号注销流程和退出登录，集中限制其他小程序接口。 */
public class AccountDeregistrationInterceptor implements HandlerInterceptor {
    private final IAccountDeregistrationService deregistrationService;

    public AccountDeregistrationInterceptor(IAccountDeregistrationService deregistrationService) {
        this.deregistrationService = deregistrationService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isAllowed(request.getRequestURI())) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginAppUser)) {
            return true;
        }
        LoginAppUser loginAppUser = (LoginAppUser) authentication.getPrincipal();
        DeregistrationStatusVO status = deregistrationService.status(loginAppUser.getUserId());
        if (!"PENDING".equals(status.getStatus())) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSON.toJSONString(AjaxResult.error("账号正在注销冷静期，仅可撤销注销或退出登录")));
        return false;
    }

    private boolean isAllowed(String uri) {
        return uri != null && (uri.startsWith("/api/user/deregister/") || "/api/logout".equals(uri));
    }
}
