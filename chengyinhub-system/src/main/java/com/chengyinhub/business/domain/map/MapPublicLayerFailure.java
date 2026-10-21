package com.chengyinhub.business.domain.map;

/** 这一页不能按合同发布。其他层仍可返回，游标不前进。 */
public final class MapPublicLayerFailure extends RuntimeException {

    public MapPublicLayerFailure(String code) {
        super(code);
    }
}
