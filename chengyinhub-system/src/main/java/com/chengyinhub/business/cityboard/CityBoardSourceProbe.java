package com.chengyinhub.business.cityboard;

import org.springframework.stereotype.Component;

/**
 * 外部真源探测。生产实现一律 UNAVAILABLE：POI、商户、主题角色、私人 home、
 * 虚拟经营资格和地点许可都不在本组，不能用夹具判成存在。
 */
@Component
public class CityBoardSourceProbe {

    public enum Verdict {
        FOUND, NOT_FOUND, DENIED, UNAVAILABLE
    }

    public Verdict probe(String domain, String source, String id) {
        return Verdict.UNAVAILABLE;
    }
}
