package com.chengyinhub.framework.config;

import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.common.utils.SecurityUtils;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.jackson2.autoconfigure.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit media fields share the review gate in bean, map and array HTTP output. */
@Configuration
public class AppMediaOutputConfiguration {
    static final Set<String> MEDIA_FIELDS = com.chengyinhub.business.service.support.AppMediaSchema.MEDIA_FIELDS;
    static final Set<String> MEDIA_JSON_FIELDS = com.chengyinhub.business.service.support.AppMediaSchema.MEDIA_JSON_FIELDS;
    private static final Set<String> MULTI_MEDIA_FIELDS = com.chengyinhub.business.service.support.AppMediaSchema.MULTI_MEDIA_FIELDS;
    private static final String CACHE_ATTRIBUTE = AppMediaOutputConfiguration.class.getName() + ".cache";
    private final AppMediaReferenceGuard media;
    // Internal tree parsing deliberately does not register this HTTP serializer recursively.
    private final ObjectMapper json = new ObjectMapper();

    public AppMediaOutputConfiguration(AppMediaReferenceGuard media) { this.media = media; }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer reviewedMediaOutput() {
        return builder -> { builder.serializerByType(String.class, new JsonSerializer<String>() {
            @Override public void serialize(String value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                List<String> names = new ArrayList<>();
                for (JsonStreamContext context = gen.getOutputContext(); context != null; context = context.getParent()) {
                    if (context.getCurrentName() != null) names.add(context.getCurrentName());
                }
                Collections.reverse(names);
                String path = String.join(".", names);
                String field = names.isEmpty() ? null : names.get(names.size() - 1);
                Object parent = gen.getOutputContext().getCurrentValue();
                boolean blockMedia = "url".equals(field) && (parent instanceof com.chengyinhub.business.domain.dto.ChapterBlockDTO
                        || parent instanceof com.chengyinhub.business.domain.dto.ChapterBlockDTO.DreamImageDTO
                        || path.endsWith(".blocks.images.url") || path.endsWith(".blocks.url") || path.equals("blocks.url")
                        || path.equals("blocks.images.url"));
                // 此公开端点的顶层 JPEG 由服务端生成，不是待审核的用户媒体。
                ServletRequestAttributes request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes
                        ? (ServletRequestAttributes) RequestContextHolder.getRequestAttributes() : null;
                boolean serverCaptcha = "img".equals(path) && parent instanceof com.chengyinhub.common.core.domain.AjaxResult
                        && Boolean.TRUE.equals(((Map<?, ?>) parent).get("captchaEnabled"))
                        && Integer.valueOf(200).equals(((Map<?, ?>) parent).get("code"))
                        && request != null && "/captchaImage".equals(request.getRequest().getServletPath())
                        && value.length() <= 262144 && value.startsWith("/9j/");
                gen.writeString(serverCaptcha ? value : project(value, isAlbumImagePath(path) || blockMedia ? "imageUrl" : field));
            }
        });
            builder.serializerByType(JsonNode.class, new JsonSerializer<JsonNode>() {
                @Override public void serialize(JsonNode value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                    // TreeNode serializers normally bypass String serializers, so use the same explicit tree schema.
                    gen.writeRawValue(json.writeValueAsString(projectTree(value, "")));
                }
            });
        };
    }

    String project(String value, String field) {
        if (value == null) return null;
        if (value.matches("media-asset:[1-9][0-9]{0,18}")) return visible(value);
        if (field != null && MEDIA_JSON_FIELDS.contains(field)) {
            try {
                JsonNode tree = json.readTree(value);
                if (tree == null) return value;
                JsonNode safe = projectTree(tree, "imagesJson".equals(field) ? "imageUrls" : "galleryJson".equals(field) ? "gallery" : "");
                return safe.equals(tree) ? value : json.writeValueAsString(safe);
            } catch (IOException invalidMediaJson) { return ""; }
        }
        if (field == null || !MEDIA_FIELDS.contains(field)) return value;
        if (MULTI_MEDIA_FIELDS.contains(field) && value.trim().startsWith("[")) {
            // 门店相册等多图字段存 JSON 字符串数组:逐张投影后保持数组形状,不按分隔符切碎。
            try {
                JsonNode tree = json.readTree(value);
                if (tree == null || !tree.isArray()) return "";
                return json.writeValueAsString(projectTree(tree, "imageUrl"));
            } catch (IOException invalidMediaJson) { return ""; }
        }
        String[] references = MULTI_MEDIA_FIELDS.contains(field) ? value.split("[;,]", -1) : new String[]{value};
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < references.length; i++) {
            String reference = references[i].trim();
            // App-bundled resources are not user uploads; all network references require registry facts.
            String safe = (com.chengyinhub.business.support.NpcAvatar.isPreset(reference)
                    || com.chengyinhub.business.service.support.AppMediaSchema.isBundledMerchantGameAudio(reference)) ? reference : (reference.startsWith("/images/") || reference.startsWith("/assets/")) && !reference.contains("..") && !reference.contains("%") ? reference
                    : (reference.isEmpty() ? "" : visible(reference));
            if (i > 0) result.append(';');
            result.append(safe == null ? "" : safe);
        }
        return result.toString();
    }

    private static boolean isAlbumImagePath(String path) {
        return path.equals("album.images.url") || path.endsWith(".album.images.url");
    }

    private JsonNode projectTree(JsonNode value, String path) {
        String field = path.substring(path.lastIndexOf('.') + 1);
        if (value.isTextual()) return json.getNodeFactory().textNode(project(value.textValue(), isAlbumImagePath(path) ? "imageUrl" : field));
        if (value.isArray()) {
            ArrayNode safe = json.createArrayNode();
            for (JsonNode child : value) safe.add(projectTree(child, path));
            return safe;
        }
        if (value.isObject()) {
            ObjectNode safe = json.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> child = fields.next();
                String childPath = path.isEmpty() ? child.getKey() : path + "." + child.getKey();
                if ("images".equals(child.getKey()) && "dream".equals(value.path("type").asText())) childPath = "blocks.images";
                boolean blockMedia = "url".equals(child.getKey()) && ("image".equals(value.path("type").asText())
                        || "audio".equals(value.path("type").asText()) || path.endsWith("blocks.images"));
                safe.set(child.getKey(), projectTree(child.getValue(), blockMedia ? "imageUrl" : childPath));
            }
            return safe;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private String visible(String reference) {
        Long viewer = null;
        try { viewer = SecurityUtils.getLoginAppUser().getAppUser().getUserId(); } catch (Exception anonymousOrSystem) { }
        ServletRequestAttributes request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes
                ? (ServletRequestAttributes) RequestContextHolder.getRequestAttributes() : null;
        Map<String, String> cache = null;
        String key = String.valueOf(viewer) + "|" + reference;
        if (request != null) {
            cache = (Map<String, String>) request.getRequest().getAttribute(CACHE_ATTRIBUTE);
            if (cache == null) {
                cache = new HashMap<>();
                request.getRequest().setAttribute(CACHE_ATTRIBUTE, cache);
            }
            if (cache.containsKey(key)) return cache.get(key);
        }
        String safe = media.visibleUrl(reference, viewer);
        if (safe == null) safe = "";
        if (cache != null) {
            cache.put(key, safe);
            // A DTO may have projected this reference before Jackson reaches it. Preserve that signature.
            if (!safe.isEmpty()) cache.put(String.valueOf(viewer) + "|" + safe, safe);
        }
        return safe;
    }
}
