package com.chengyinhub.framework.security.handle;

import java.io.IOException;
import java.io.Serializable;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson2.JSON;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.ServletUtils;

/**
 * 认证失败处理类 返回未授权
 * 
 * @author ruoyi
 */
@Component
public class AuthenticationEntryPointImpl implements AuthenticationEntryPoint, Serializable
{
    private static final long serialVersionUID = -8970718410437077606L;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException
    {
        int code = HttpStatus.UNAUTHORIZED;
        // 稳定人话文案,不暴露请求路径/鉴权术语/堆栈(终审 X17:原样带 URI 的 401 文案会直出到用户屏)
        String msg = "登录状态已失效，请重新登录";
        String body = JSON.toJSONString(AjaxResult.error(code, msg));
        if (request.getRequestURI().startsWith(Constants.APP_URI))
        {
            response.setStatus(code);
            response.setContentType("application/json");
            response.setCharacterEncoding("utf-8");
            response.getWriter().print(body);
            return;
        }
        ServletUtils.renderString(response, body);
    }
}
