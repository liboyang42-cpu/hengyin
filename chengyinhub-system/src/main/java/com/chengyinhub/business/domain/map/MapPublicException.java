package com.chengyinhub.business.domain.map;

/** 公开地图的确定性拒绝。message 是稳定代码，不带坐标或 SQL。 */
public final class MapPublicException extends RuntimeException {

    public MapPublicException(String code) {
        super(code);
    }
}
