package com.chengyinhub.framework.security.matcher;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.Assert;

/**
 * Preserves the existing case-sensitive servletPath + pathInfo whitelist grammar.
 * Security 7 removes AntPathRequestMatcher; PathPattern cannot express the existing
 * / ** / *.html static-resource patterns. Do not widen these to an anonymous prefix.
 * The security firewall still validates each request before authorization.
 */
public final class LegacyAntRequestMatcher implements RequestMatcher
{
    private final String pattern;
    private final HttpMethod method;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public LegacyAntRequestMatcher(String pattern, HttpMethod method)
    {
        Assert.hasText(pattern, "Pattern must not be empty");
        this.pattern = pattern;
        this.method = method;
        this.matcher.setTrimTokens(false);
        this.matcher.setCaseSensitive(true);
    }

    @Override
    public boolean matches(HttpServletRequest request)
    {
        if (method != null && !method.matches(request.getMethod()))
        {
            return false;
        }
        String path = request.getServletPath();
        if (request.getPathInfo() != null)
        {
            path += request.getPathInfo();
        }
        if ("/**".equals(pattern) || "**".equals(pattern))
        {
            return true;
        }
        return matcher.match(pattern, path);
    }
}
