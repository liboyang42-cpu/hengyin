package com.chengyinhub.business.service;

import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.http.HttpUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 腾讯位置服务的唯一服务端出口，避免 Key 下发或散落在各业务服务中。 */
@Service
public class TencentMapClient {

    private static final Logger log = LoggerFactory.getLogger(TencentMapClient.class);

    @Value("${tencent.map.key:}")
    private String mapKey;

    @Value("${tencent.map.enabled:false}")
    private boolean enabled;

    /** 启动时提示：未启用或缺 Key 时 /api/map/reverse-geocode 只会返回 manualInputRequired。绝不打印 Key。 */
    @PostConstruct
    public void warnIfNotReady() {
        if (!enabled) {
            log.warn("腾讯位置服务未启用(tencent.map.enabled=false)：首页城市定位将回退为手动选择城市。"
                    + "请在应用机设置 CHENGYIN_TENCENT_MAP_ENABLED=true 与 CHENGYIN_TENCENT_MAP_KEY");
        } else if (StringUtils.isEmpty(mapKey)) {
            log.warn("腾讯位置服务已启用但未配置 Key(tencent.map.key 为空)：首页城市定位将回退为手动选择城市。"
                    + "请设置 CHENGYIN_TENCENT_MAP_KEY");
        }
    }

    public boolean isReady() {
        return enabled && !StringUtils.isEmpty(mapKey);
    }

    public String get(String endpoint) {
        if (!isReady()) {
            return null;
        }
        String separator = endpoint.contains("?") ? "&" : "?";
        return request(endpoint + separator + "key=" + mapKey);
    }

    /** 保持 HTTP 调用可替换，以便离线验证配置失败和错误处理。 */
    protected String request(String url) {
        return HttpUtils.sendGetWithRetry(url);
    }
}
