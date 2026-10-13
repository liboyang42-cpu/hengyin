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
import java.util.Iterator;
import java.util.Map;

/** Review admission before a business object becomes public; existing callers retain ownership checks. */
@Component
public class AppMediaPublicationGuard {
    @Autowired private AppMediaReferenceGuard references;
    @Autowired private CmsTopicMapper topics;
    @Autowired private CmsTopicChapterMapper chapters;
    @Autowired private CmsTopicNodeMapper nodes;
    @Autowired private CmsMemberTemplateMapper templates;
    private final ObjectMapper json = new ObjectMapper();

    public void requireTemplate(Long owner, Object template, boolean publish) {
        requireFields(owner, template, publish, "imgUrl", "medalImg", "storyImg", "audioUrl", "questionImg", "questionAudio",
                "questionOptionMediaJson", "advancedConfigJson", "storyJson");
    }

    public void requireTopic(Long owner, CmsTopic topic, boolean publish) {
        requireFields(owner, topic, publish, "imgUrl", "imgArr", "audioUrl", "finishMedalImg",
                "gameModuleConfigJson", "themeRuntimeConfigJson", "publishedThemeRuntimeConfigJson", "journeyStoryJson");
    }

    public void requireChapter(Long owner, Object chapter, boolean publish) {
        requireFields(owner, chapter, publish, "audioUrl", "imgArr", "blocksJson");
    }

    public void requireNode(Long owner, Object node, boolean publish) {
        requireFields(owner, node, publish, "imgUrl");
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

    private void requireFields(Long owner, Object bean, boolean publish, String... fields) {
        if (bean == null) throw new ServiceException("媒体所属内容不存在", 422);
        BeanWrapper properties = new BeanWrapperImpl(bean);
        for (String field : fields) {
            if (!properties.isReadableProperty(field) || !properties.isWritableProperty(field)) continue;
            String value = (String) properties.getPropertyValue(field);
            if (value == null || value.trim().isEmpty()) continue;
            String safe;
            if (AppMediaSchema.MEDIA_JSON_FIELDS.contains(field) || "journeyStoryJson".equals(field)) safe = requireJson(owner, value, publish);
            else safe = AppMediaSchema.MULTI_MEDIA_FIELDS.contains(field)
                    ? references.requireAttachments(actor(owner), value, publish) : requireMedia(owner, value, publish);
            properties.setPropertyValue(field, safe);
        }
    }

    public String requireMedia(Long owner, String reference, boolean publish) {
        if (reference == null || reference.trim().isEmpty()) return reference;
        if (AppMediaSchema.isBundledMerchantGameAudio(reference)) return reference;
        if (com.chengyinhub.business.support.NpcAvatar.isPreset(reference)) return reference;
        if ((reference.startsWith("/images/") || reference.startsWith("/assets/"))
                && !reference.contains("..") && !reference.contains("%")) return reference;
        return references.requireAttachment(actor(owner), reference, publish);
    }

    private static Long actor(Long owner) {
        return owner != null && owner > 0 ? owner : com.chengyinhub.common.utils.SecurityUtils.getUserId();
    }

    public String requireJson(Long owner, String value, boolean publish) {
        if (value == null || value.trim().isEmpty()) return value;
        try {
            JsonNode tree = json.readTree(value);
            JsonNode safe = requireTree(owner, tree, "", publish);
            return safe.equals(tree) ? value : json.writeValueAsString(safe);
        } catch (IOException invalid) { throw new ServiceException("媒体配置格式不正确", 422); }
    }

    private JsonNode requireTree(Long owner, JsonNode value, String path, boolean publish) {
        if (value == null) return json.getNodeFactory().nullNode();
        String field = path.substring(path.lastIndexOf('.') + 1);
        if (value.isTextual()) {
            String text = value.textValue();
            boolean albumUrl = path.equals("album.images.url") || path.endsWith(".album.images.url");
            if (text.matches("media-asset:[1-9][0-9]{0,18}") || albumUrl || AppMediaSchema.MEDIA_FIELDS.contains(field)) {
                if (text.trim().isEmpty()) return value;
                if ((text.startsWith("/images/") || text.startsWith("/assets/")) && !text.contains("..") && !text.contains("%")) return value;
                return json.getNodeFactory().textNode(requireMedia(owner, text, publish));
            }
            return value;
        }
        if (value.isArray()) {
            ArrayNode safe = json.createArrayNode();
            for (JsonNode child : value) safe.add(requireTree(owner, child, path, publish));
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
                safe.set(child.getKey(), requireTree(owner, child.getValue(), blockMedia ? "imageUrl" : childPath, publish));
            }
            return safe;
        }
        return value;
    }
}
