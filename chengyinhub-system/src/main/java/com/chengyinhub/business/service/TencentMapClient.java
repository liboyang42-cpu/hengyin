package com.chengyinhub.business.service;

import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.http.HttpUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 腾讯位置服务的唯一服务端出口，避免 Key 下发或散落在各业务服务中。 */
@Service
public class TencentMapClient {

    @Value("${tencent.map.key:}")
    private String mapKey;

    @Value("${tencent.map.enabled:false}")
    private boolean enabled;

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
