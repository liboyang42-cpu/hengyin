package com.chengyinhub.business.service.ai.npc;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/** Strict structural whitelist for untrusted DRAFT content, not a privacy classifier or approval. */
final class MerchantNpcPublicFactsDraft {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    final String json;
    final String hash;
    MerchantNpcPublicFactsDraft(String input) {
        try {
            if (input == null || input.getBytes(StandardCharsets.UTF_8).length > 16000) throw invalid();
            JsonNode root = JSON.readTree(input);
            fields(root, "schemaVersion", "products", "hours", "promotions", "faq", "neverSay");
            if (!root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").canConvertToInt() || root.path("schemaVersion").asInt() != 1) throw invalid();
            for (JsonNode p : array(root, "products", 40)) {
                fields(p,"name","priceMinor","currency"); text(p,"name",120);
                if (!p.path("priceMinor").isIntegralNumber() || !p.path("priceMinor").canConvertToLong()
                    || p.path("priceMinor").asLong()<0 || p.path("priceMinor").asLong()>100000000) throw invalid();
                if (!text(p,"currency",3).matches("[A-Z]{3}")) throw invalid();
            }
            Set<String> days = new HashSet<>();
            for (JsonNode h : array(root,"hours",7)) {
                fields(h,"day","opens","closes","timeZone");
                if (!h.path("day").isIntegralNumber() || !h.path("day").canConvertToInt() || h.path("day").asInt()<1 || h.path("day").asInt()>7
                    || !days.add(h.path("day").toString())) throw invalid();
                LocalTime opens=LocalTime.parse(text(h,"opens",5));
                LocalTime closes=LocalTime.parse(text(h,"closes",5));
                if (!opens.isBefore(closes)) throw invalid(); // overnight schedules require a future explicit schema
                ZoneId.of(text(h,"timeZone",64));
            }
            for (JsonNode p : array(root,"promotions",20)) {
                fields(p,"title","startsAt","endsAt"); text(p,"title",300);
                Instant start=Instant.parse(text(p,"startsAt",30)), end=Instant.parse(text(p,"endsAt",30));
                if (!start.isBefore(end)) throw invalid();
            }
            for (JsonNode f : array(root,"faq",30)) {
                fields(f,"question","answer"); text(f,"question",200); text(f,"answer",600);
            }
            for (JsonNode n : array(root,"neverSay",30)) checkText(n,200);
            json=JSON.writeValueAsString(sorted(root));
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder(); for(byte b:digest) hex.append(String.format(Locale.ROOT,"%02x",b & 255));
            hash=hex.toString();
        } catch (Exception e) { throw invalid(); }
    }
    private static JsonNode sorted(JsonNode n) {
        if(n.isObject()) {
            ObjectNode out=JSON.createObjectNode(); List<String> names=new ArrayList<>();
            n.fieldNames().forEachRemaining(names::add); Collections.sort(names);
            for(String name:names) out.set(name,sorted(n.get(name))); return out;
        }
        if(n.isArray()) { ArrayNode out=JSON.createArrayNode(); for(JsonNode v:n) out.add(sorted(v)); return out; }
        return n;
    }
    private static void fields(JsonNode n,String... names) {
        if(n==null || !n.isObject() || n.size()!=names.length) throw invalid();
        for(String name:names) if(!n.hasNonNull(name)) throw invalid();
    }
    private static JsonNode array(JsonNode root,String name,int max) {
        JsonNode n=root.get(name); if(n==null || !n.isArray() || n.size()>max) throw invalid(); return n;
    }
    private static String text(JsonNode n,String field,int max) { return checkText(n.get(field),max); }
    private static String checkText(JsonNode n,int max) {
        if(n==null || !n.isTextual() || n.asText().trim().isEmpty() || n.asText().length()>max) throw invalid();
        String s=n.asText();
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i); if(Character.isISOControl(c)) throw invalid();
            if(Character.isHighSurrogate(c)) {
                if(i+1>=s.length() || !Character.isLowSurrogate(s.charAt(i+1))) throw invalid();
                i++;
            } else if(Character.isLowSurrogate(c)) throw invalid();
        }
        return s;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid public-facts draft schema"); }
}
