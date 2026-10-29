package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.HashMap;

/** Pure template-runtime identity rewrite shared by content creation, rebuilding and copying. */
public final class TopicTemplateRuleIdentityRemapper {
    private static final ObjectMapper ROUTE_JSON = new ObjectMapper();
    private TopicTemplateRuleIdentityRemapper() { }

    public static String remap(String raw,Map<Long,Long> nodes) {
        if(raw==null || raw.trim().isEmpty())return raw;
        try {
            com.fasterxml.jackson.databind.node.ObjectNode config=(com.fasterxml.jackson.databind.node.ObjectNode)ROUTE_JSON.readTree(raw);
            Map<String,String> keys=new HashMap<>();
            for(JsonNode facility:config.path("gameplayRuntime").path("facilities"))for(Map.Entry<Long,Long> mapping:nodes.entrySet()) {
                String oldKey="tpl_node_"+mapping.getKey(),newKey="tpl_node_"+mapping.getValue();
                if(oldKey.equals(facility.path("facilityKey").asText()) && oldKey.equals(facility.path("settlementSlot").asText()) && facility.path("operationNodeId").asLong()==mapping.getValue()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode)facility).put("facilityKey",newKey).put("settlementSlot",newKey);keys.put(oldKey,newKey);
                }
            }
            for(JsonNode quest:config.path("gameplayRuntime").path("quests"))if(keys.containsKey(quest.path("facilityKey").asText()))((com.fasterxml.jackson.databind.node.ObjectNode)quest).put("facilityKey",keys.get(quest.path("facilityKey").asText()));
            com.chengyinhub.business.service.support.GameplayTemplateRules.remapToolIdentities(config,nodes);
            return com.chengyinhub.business.service.support.ThemeRuntimeConfig.parseDraft(config.toString()).toString();
        }catch(java.io.IOException invalid){throw new IllegalArgumentException("模板运行身份复制失败",invalid);}
    }
}
