package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Typed intent boundary only. Existing quest/arrival/proof/cost engines remain the business authority. */
public final class ThemeQuestActionContract {
    public static final String PROOF_FIELD = "__themeQuestRequestV1";
    private static final Set<String> KINDS = Set.of("QUEST_START", "QUEST_RESOLVE", "QUEST_CANCEL");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonGenerator.Feature.ESCAPE_NON_ASCII)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ThemeQuestActionContract() { }

    public static boolean supports(String kind) { return kind != null && KINDS.contains(kind); }

    public static Map<String,Object> arguments(String kind, JsonNode payload) {
        return parameters(kind,payload,true);
    }

    public static Map<String,Object> previewArguments(JsonNode payload) {
        if (payload==null || !payload.isObject() || !payload.path("operation").isTextual()
                || !"QUEST_RESOLVE".equals(payload.path("operation").textValue())) throw invalid();
        Set<String> fields=Set.of("operation","contentId","choiceKey","costOption","discardRewards");
        payload.fieldNames().forEachRemaining(field -> { if(!fields.contains(field))throw invalid(); });
        ObjectNode resolve=(ObjectNode)payload.deepCopy();resolve.remove("operation");
        return parameters("QUEST_RESOLVE",resolve,false);
    }

    private static Map<String,Object> parameters(String kind,JsonNode payload,boolean requirePreview) {
        if (!supports(kind) || payload == null || !payload.isObject()) throw invalid();
        Set<String> allowed = "QUEST_RESOLVE".equals(kind)
                ? Set.of("contentId", "choiceKey", "costOption", "discardRewards", "previewVersion") : Set.of("contentId", "costOption");
        payload.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw invalid(); });
        Map<String,Object> arguments = new LinkedHashMap<>();
        arguments.put("contentId",text(payload,"contentId",96));
        if (payload.has("choiceKey")) arguments.put("choiceKey",text(payload,"choiceKey",96));
        if (payload.has("costOption")) {
            JsonNode option = payload.get("costOption");
            if (!option.isIntegralNumber() || !option.canConvertToInt() || option.intValue()<0) throw invalid();
            arguments.put("costOption",option.intValue());
        }
        // Legacy resolve may omit a preview. This new typed flow requires a server preview and explicit confirmation.
        if ("QUEST_RESOLVE".equals(kind) && requirePreview) arguments.put("previewVersion",text(payload,"previewVersion",128));
        if (payload.has("discardRewards")) {
            JsonNode rows=payload.get("discardRewards");
            if (!rows.isArray() || rows.size()>200) throw invalid();
            Set<String> seen=new HashSet<>();
            com.fasterxml.jackson.databind.node.ArrayNode canonicalRows=JSON.createArrayNode();
            for (JsonNode row:rows) {
                if (!row.isObject() || row.size()!=2 || !row.has("itemId") || !row.has("quantity")) throw invalid();
                String id=text(row,"itemId",96);JsonNode quantity=row.get("quantity");
                if (!quantity.isIntegralNumber() || !quantity.canConvertToInt() || quantity.intValue()<1 || !seen.add(id)) throw invalid();
                canonicalRows.addObject().put("itemId",id).put("quantity",quantity.intValue());
            }
            arguments.put("discardRewards",canonicalRows);
        }
        return arguments;
    }

    public static String fingerprint(Long actor,Long topic,Long activity,Long session,Integer version,String actionId,String kind,JsonNode payload) {
        context(actor,topic,activity,session,version,actionId);arguments(kind,payload);
        ObjectNode canonical=JSON.createObjectNode();
        for (String field:List.of("contentId","choiceKey","costOption","discardRewards","previewVersion")) if (payload.has(field)) {
            if (!"discardRewards".equals(field)) canonical.set(field,payload.get(field).deepCopy());
            else for (JsonNode row:payload.get(field)) canonical.withArray(field).addObject().put("itemId",row.path("itemId").textValue()).put("quantity",row.path("quantity").intValue());
            if ("discardRewards".equals(field) && payload.get(field).isEmpty()) canonical.putArray(field);
        }
        try {
            String encoded=JSON.writeValueAsString(Arrays.asList("THEME_QUEST",1,actor,topic,activity,session,version,actionId,kind,canonical));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException("分型任务请求摘要不可用",impossible); }
    }

    public static void context(Long actor,Long topic,Long activity,Long session,Integer version,String actionId) {
        if (actor==null || actor<=0 || topic==null || topic<=0 || activity==null || activity<0 || session==null || session<=0
                || version==null || version<0 || version==Integer.MAX_VALUE || actionId==null || !actionId.matches("[A-Za-z0-9:_-]{1,80}")) throw invalid();
    }

    public static JsonNode readStored(String response) throws java.io.IOException { return JSON.readTree(response); }

    private static String text(JsonNode object,String field,int max) {
        JsonNode value=object.get(field);
        if (value==null || !value.isTextual() || value.textValue().isEmpty() || value.textValue().length()>max) throw invalid();
        return value.textValue();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("分型任务意图不正确"); }
}
