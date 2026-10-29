package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Review admission before a business object becomes public; existing callers retain ownership checks. */
@Component
public class AppMediaPublicationGuard {
    @Autowired private AppMediaReferenceGuard references;
    @Autowired private CmsTopicMapper topics;
    @Autowired private CmsTopicChapterMapper chapters;
    @Autowired private CmsTopicNodeMapper nodes;
    @Autowired private CmsMemberTemplateMapper templates;
    @Autowired private CmsTemplateLibraryMapper libraries;
    private final ObjectMapper json = new ObjectMapper();
    /** Absolute http(s) URL anywhere, or a protocol-relative //host at the start; slashes may be backslashes. */
    private static final java.util.regex.Pattern EXTERNAL_LINK =
            java.util.regex.Pattern.compile("(?i)https?:[/\\\\]{2}|^\\s*[/\\\\]{2}");

    private static final String[] TEMPLATE_MEDIA_FIELDS = {"imgUrl", "medalImg", "storyImg", "audioUrl", "questionImg",
            "questionAudio", "questionOptionMediaJson", "advancedConfigJson", "storyJson"};

    public void requireTemplate(Long owner, Object template, boolean publish) {
        requireFields(owner, template, publish, adoptedSourceMedia(template), TEMPLATE_MEDIA_FIELDS);
    }

    public void requireTopic(Long owner, CmsTopic topic, boolean publish) {
        requireFields(owner, topic, publish, Set.of(), "imgUrl", "imgArr", "audioUrl", "finishMedalImg",
                "gameModuleConfigJson", "themeRuntimeConfigJson", "publishedThemeRuntimeConfigJson", "journeyStoryJson");
    }

    public void requireChapter(Long owner, Object chapter, boolean publish) {
        requireFields(owner, chapter, publish, Set.of(), "audioUrl", "imgArr", "blocksJson");
    }

    public void requireNode(Long owner, Object node, boolean publish) {
        requireFields(owner, node, publish, Set.of(), "imgUrl");
    }

    public void requireTopicPublication(Long topicId) {
        CmsTopic topic = topics.selectCmsTopicById(topicId);
        if (topic == null) throw new ServiceException("主题不存在，不能审核媒体", 422);
        Long owner = topic.getMemberId();
        requireTopic(owner, topic, true);
        CmsTopicChapter chapterQuery = new CmsTopicChapter(); chapterQuery.setTopicId(topicId);
        java.util.List<CmsTopicChapter> chapterRows = chapters.selectCmsTopicChapterList(chapterQuery);
        if (chapterRows != null) for (CmsTopicChapter row : chapterRows) requireChapter(owner, row, true);
        CmsTopicNode nodeQuery = new CmsTopicNode(); nodeQuery.setTopicId(topicId);
        java.util.List<CmsTopicNode> nodeRows = nodes.selectCmsTopicNodeList(nodeQuery);
        if (nodeRows != null) for (CmsTopicNode row : nodeRows) {
            requireNode(owner, row, true);
            if (row.getTemplateId() != null && row.getTemplateId() > 0) {
                CmsMemberTemplate template = templates.selectCmsMemberTemplateById(row.getTemplateId());
                if (template == null) throw new ServiceException("节点模板不存在，不能公开", 422);
                requireTemplate(template.getMemberId(), template, true);
            }
        }
    }

    /**
     * 业主口径(2026-10-29 V-11/V-13):公开模板自带的图片(封面与玩法素材)谁采用谁就能直接用。
     * 采用稿(originalTemplateId)沿用来源库模板里原样存在的引用;已登记媒体仍走原审核事实与归属校验。
     */
    private Set<String> adoptedSourceMedia(Object template) {
        if (!(template instanceof CmsMemberTemplate adopted) || adopted.getOriginalTemplateId() == null
                || adopted.getOriginalTemplateId() <= 0) return Set.of();
        CmsTemplateLibrary source = libraries.selectCmsTemplateLibraryById(adopted.getOriginalTemplateId());
        if (source == null || !Integer.valueOf(0).equals(source.getDelFlag()) || !Long.valueOf(1L).equals(source.getStatus()))
            return Set.of();
        Set<String> inherited = new HashSet<>();
        BeanWrapper properties = new BeanWrapperImpl(source);
        for (String field : TEMPLATE_MEDIA_FIELDS) {
            if (!properties.isReadableProperty(field)) continue;
            Object value = properties.getPropertyValue(field);
            if (value instanceof String text && !text.trim().isEmpty()) collectReferences(text.trim(), inherited);
        }
        return inherited;
    }

    private void collectReferences(String value, Set<String> inherited) {
        inherited.add(value);
        if (value.startsWith("{") || value.startsWith("[")) {
            try { collectTree(json.readTree(value), inherited); } catch (IOException notJson) { /* 整串已收录 */ }
        }
    }

    private static void collectTree(JsonNode value, Set<String> inherited) {
        if (value == null) return;
        if (value.isTextual() && !value.textValue().trim().isEmpty()) inherited.add(value.textValue().trim());
        for (JsonNode child : value) collectTree(child, inherited);
    }

    private void requireFields(Long owner, Object bean, boolean publish, Set<String> inherited, String... fields) {
        if (bean == null) throw new ServiceException("媒体所属内容不存在", 422);
        BeanWrapper properties = new BeanWrapperImpl(bean);
        for (String field : fields) {
            if (!properties.isReadableProperty(field) || !properties.isWritableProperty(field)) continue;
            String value = (String) properties.getPropertyValue(field);
            if (value == null || value.trim().isEmpty()) continue;
            String safe;
            if (AppMediaSchema.MEDIA_JSON_FIELDS.contains(field) || "journeyStoryJson".equals(field)) safe = requireJson(owner, value, publish, inherited);
            else if (AppMediaSchema.MULTI_MEDIA_FIELDS.contains(field))
                safe = inheritedAsIs(value, inherited) ? value : references.requireAttachments(actor(owner), value, publish);
            else safe = requireMedia(owner, value, publish, inherited);
            properties.setPropertyValue(field, safe);
        }
    }

    public String requireMedia(Long owner, String reference, boolean publish) {
        return requireMedia(owner, reference, publish, Set.of());
    }

    private String requireMedia(Long owner, String reference, boolean publish, Set<String> inherited) {
        if (reference == null || reference.trim().isEmpty()) return reference;
        if (inheritedAsIs(reference, inherited)) return reference;
        if (AppMediaSchema.isBundledMerchantGameAudio(reference)) return reference;
        if (com.chengyinhub.business.support.NpcAvatar.isPreset(reference)) return reference;
        if ((reference.startsWith("/images/") || reference.startsWith("/assets/"))
                && !reference.contains("..") && !reference.contains("%")) return reference;
        return references.requireAttachment(actor(owner), reference, publish);
    }

    private boolean inheritedAsIs(String reference, Set<String> inherited) {
        String value = reference.trim();
        return inherited.contains(value) && !references.isRegistered(value);
    }

    private static Long actor(Long owner) {
        return owner != null && owner > 0 ? owner : com.chengyinhub.common.utils.SecurityUtils.getUserId();
    }

    public String requireJson(Long owner, String value, boolean publish) {
        return requireJson(owner, value, publish, Set.of());
    }

    private String requireJson(Long owner, String value, boolean publish, Set<String> inherited) {
        if (value == null || value.trim().isEmpty()) return value;
        try {
            JsonNode tree = json.readTree(value);
            JsonNode safe = requireTree(owner, tree, "", publish, inherited);
            return safe.equals(tree) ? value : json.writeValueAsString(safe);
        } catch (IOException invalid) { throw new ServiceException("媒体配置格式不正确", 422); }
    }

    private JsonNode requireTree(Long owner, JsonNode value, String path, boolean publish, Set<String> inherited) {
        if (value == null) return json.getNodeFactory().nullNode();
        String field = path.substring(path.lastIndexOf('.') + 1);
        if (value.isTextual()) {
            String text = value.textValue();
            boolean albumUrl = path.equals("album.images.url") || path.endsWith(".album.images.url");
            if (text.matches("media-asset:[1-9][0-9]{0,18}") || albumUrl || AppMediaSchema.MEDIA_FIELDS.contains(field)) {
                if (text.trim().isEmpty()) return value;
                if ((text.startsWith("/images/") || text.startsWith("/assets/")) && !text.contains("..") && !text.contains("%")) return value;
                return json.getNodeFactory().textNode(requireMedia(owner, text, publish, inherited));
            }
            // F-L5C-16: no URL review channel exists, so an external link cannot become public (fail-closed).
            if (publish && EXTERNAL_LINK.matcher(text).find()) throw new ServiceException("暂不支持外部链接，请删除后再发布", 422);
            return value;
        }
        if (value.isArray()) {
            ArrayNode safe = json.createArrayNode();
            for (JsonNode child : value) safe.add(requireTree(owner, child, path, publish, inherited));
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
                safe.set(child.getKey(), requireTree(owner, child.getValue(), blockMedia ? "imageUrl" : childPath, publish, inherited));
            }
            return safe;
        }
        return value;
    }
}
