package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/** Opt-in author wire projection; does not change runtime/entity serialization. */
public final class TemplateAdoptionWire {
    private TemplateAdoptionWire() {}
    public static JsonNode strings(Object value, String field) {
        return strings(value, field, new ObjectMapper().findAndRegisterModules());
    }
    public static JsonNode strings(Object value, String field, ObjectMapper mapper) {
        JsonNode node = mapper.valueToTree(value);
        visit(node, field); return node;
    }
    private static void visit(JsonNode node, String field) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>(); node.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = node.get(name);
                if (name.equals(field) && child.isIntegralNumber()) ((ObjectNode) node).put(name, child.asText());
                else visit(child, field);
            }
        } else if (node.isArray()) node.forEach(child -> visit(child, field));
    }
}
