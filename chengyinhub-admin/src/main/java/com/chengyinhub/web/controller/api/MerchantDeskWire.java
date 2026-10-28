package com.chengyinhub.web.controller.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** 商家工作台的 Map/List JSON 边界；只转换标识，不改计数、版本、价格或共享 Mapper 结果。 */
final class MerchantDeskWire {
    private MerchantDeskWire() { }

    static Object response(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                boolean identifier = key instanceof String name && (name.equals("id") || name.endsWith("Id"));
                copy.put(key, identifier && item instanceof Number ? item.toString() : response(item));
            });
            return copy;
        }
        if (value instanceof Iterable<?> rows) {
            ArrayList<Object> copy = new ArrayList<>();
            rows.forEach(row -> copy.add(response(row)));
            return copy;
        }
        return value;
    }
}
