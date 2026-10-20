package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 服务端总开关。冻结的主题局关闭后不得降级成旧玩法继续结算。 */
@Component
public class ThemeRuntimeFeaturePolicy {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Value("${chengyin.theme-runtime.enabled:false}")
    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void requireEnabled() {
        if (!enabled) throw new ServiceException("主题运行时暂未开放");
    }

    /** 在解析或写入新配置前检查；非空但不合法的配置也不能绕过开关。 */
    public void requireConfiguredEnabled(String configJson) {
        if (isConfigured(configJson)) requireEnabled();
    }

    /** 只读已冻结配置，不读取当前草稿或发布配置，不推进状态。 */
    public void requireSessionEnabled(TopicRouteSession session) {
        if (hasFrozenTheme(session)) requireEnabled();
    }

    public static boolean isConfigured(String configJson) {
        return configJson != null && !configJson.trim().isEmpty();
    }

    /** 空旧快照保留旧路径；损坏的非空快照不能被当作未配置的旧局。 */
    public static boolean hasFrozenTheme(TopicRouteSession session) {
        if (session == null) return false;
        if (ThemeFirstIdentity.usesNamespace(session.getPlayInstanceId())) {
            // A reserved FIRST token without the persisted owner-bound origin cannot become legacy.
            return ThemeFirstIdentity.isFirst(session);
        }
        if (!isConfigured(session.getConfigSnapshotJson())) return false;
        try {
            JsonNode snapshot = JSON.readTree(session.getConfigSnapshotJson());
            if (snapshot == null || !snapshot.isObject()) throw invalidSnapshot();
            JsonNode config = snapshot.get("themeConfig");
            if (config == null) return false;
            if (!config.isObject()) throw invalidSnapshot();
            return true;
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalidSnapshot();
        }
    }

    private static ServiceException invalidSnapshot() {
        return new ServiceException("本局冻结配置不可读取，请重新进入");
    }
}
