package com.chengyinhub.framework.interceptor;

import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.common.constant.Constants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UriUtils;

/** Local byte access must enforce the same review fact as JSON media projections. */
@Component
public class AppMediaVisibilityInterceptor implements HandlerInterceptor {
    private final AppMediaAssetMapper mapper;

    public AppMediaVisibilityInterceptor(AppMediaAssetMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String key;
        if (path.equals("/common/download/resource")) {
            key = request.getParameter("resource");
            if (key == null || key.indexOf(Constants.RESOURCE_PREFIX) < 0) return true;
            key = Constants.RESOURCE_PREFIX + com.chengyinhub.common.utils.file.FileUtils.stripPrefix(key);
        } else {
            key = UriUtils.decode(path, StandardCharsets.UTF_8);
        }
        // A resource handler resolves dot segments before reading bytes; use that same canonical key.
        key = java.nio.file.Paths.get(key).normalize().toString().replace('\\', '/');
        Map<String, Object> asset = mapper.selectByObjectKey(key);
        if (asset != null && !"APPROVED".equals(asset.get("reviewState"))) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setHeader("Cache-Control", "no-store");
            return false;
        }
        // Do not cache revocable public media in a browser/proxy beyond the current response.
        if (asset != null) response.setHeader("Cache-Control", "no-store");
        return true;
    }
}
