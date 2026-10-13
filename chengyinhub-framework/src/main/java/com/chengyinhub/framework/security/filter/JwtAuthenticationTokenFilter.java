package com.chengyinhub.framework.security.filter;

import java.io.IOException;
import java.util.Collection;
import javax.annotation.Resource;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.framework.web.service.AppTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.framework.web.service.TokenService;

/**
 * token过滤器 验证token有效性
 * 
 * @author ruoyi
 */
@Component
public class JwtAuthenticationTokenFilter extends OncePerRequestFilter
{
    @Autowired
    private TokenService tokenService;

    @Resource
    private AppTokenService appTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        //前台APP/小程序登录判断
        if (request.getRequestURI().startsWith(Constants.APP_URI)) {
            //logger.info("前台接口请求:{}", request.getRequestURI());
            LoginAppUser loginAppUser = appTokenService.getLoginAppUser(request);
            if (StringUtils.isNotNull(loginAppUser) && StringUtils.isNull(SecurityUtils.getAuthentication())) {
                if (appTokenService.verifyToken(loginAppUser)) {
                    auth(loginAppUser, loginAppUser.getAuthorities(), request);
                }
            }

        } else {
            //管理后台登录判断
            LoginUser loginUser = tokenService.getLoginUser(request);
            if (StringUtils.isNotNull(loginUser) && StringUtils.isNull(SecurityUtils.getAuthentication()))
            {
                tokenService.verifyToken(loginUser);
                UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
                authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authenticationToken);
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * 验证token
     *
     * @param obj         登录信息
     * @param authorities 已授予的权限集合
     * @param request     网络请求
     */
    private void auth(Object obj, Collection<? extends GrantedAuthority> authorities, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(obj, null, authorities);
        authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
    }
}
