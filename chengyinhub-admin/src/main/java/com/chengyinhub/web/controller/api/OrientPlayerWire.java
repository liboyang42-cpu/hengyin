package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.OrientScoreAppeal;
import org.springframework.beans.BeanWrapperImpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** 玩家 JSON 边界保留 ID 精度,不改变共享业务对象或管理员计分。 */
final class OrientPlayerWire {
    private OrientPlayerWire() { }

    static Object response(Object value) {
        return response(value, false);
    }

    private static Object response(Object value, boolean dimensionTotals) {
        if (value instanceof OrientScoreAppeal) {
            BeanWrapperImpl bean = new BeanWrapperImpl(value);
            Map<String, Object> fields = new LinkedHashMap<>();
            for (var property : bean.getPropertyDescriptors()) {
                if (!"class".equals(property.getName()) && property.getReadMethod() != null) {
                    fields.put(property.getName(), bean.getPropertyValue(property.getName()));
                }
            }
            return response(fields);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                boolean exactNumber = dimensionTotals || key instanceof String name
                        && (name.equals("id") || name.endsWith("Id")
                        || name.endsWith("Centi") && !name.equals("deltaCenti"));
                result.put(key, exactNumber && item instanceof Number ? item.toString()
                        : response(item, "byDimension".equals(key)));
            });
            return result;
        }
        if (value instanceof Iterable<?> items) {
            ArrayList<Object> result = new ArrayList<>();
            items.forEach(item -> result.add(response(item)));
            return result;
        }
        return value;
    }
}
