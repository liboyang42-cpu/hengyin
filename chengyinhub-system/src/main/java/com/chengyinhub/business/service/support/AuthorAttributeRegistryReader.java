package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collections;

/** Author-only versioned read; one transaction holds the same topic lock as registry writers. */
@Service
public class AuthorAttributeRegistryReader {
    private final CmsTopicMapper topics;
    private final ThemeStateKeyService registry;
    private static final ObjectMapper JSON = new ObjectMapper();
    public AuthorAttributeRegistryReader(CmsTopicMapper topics, ThemeStateKeyService registry) {
        this.topics = topics; this.registry = registry;
    }
    @Transactional(rollbackFor = Exception.class)
    public ObjectNode read(Long topicId, Long ownerId) {
        CmsTopic topic = topics.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag()) || ownerId == null
                || !ownerId.equals(topic.getMemberId())) throw new ServiceException("主题不存在或无权读取", 403);
        ObjectNode binding = registry.readBinding(topicId, Collections.emptyList());
        ObjectNode out = JSON.createObjectNode(); out.put("topicId", topicId.toString());
        out.put("configVersion", Long.toString(topic.getConfigVersion() == null ? 1L : topic.getConfigVersion()));
        out.put("registryVersion", AuthorAttributeBinding.version(binding.get("registryVersion")));
        var definitions = out.putArray("definitions");
        for (ThemeStateKey key : registry.listByTopic(topicId)) {
            ObjectNode row = definitions.addObject(); row.put("stateKey", key.getStateKey()); row.put("keyType", key.getKeyType());
            row.put("scope", key.getScope() == null ? "run" : key.getScope()); row.put("label", key.displayLabel());
            row.put("initialValue", key.getInitialValue() == null ? 0 : key.getInitialValue());
            row.put("minValue", key.effectiveMin()); row.put("maxValue", key.effectiveMax()); row.put("visible", key.shownOnCard());
            row.put("displayType", key.getDisplayType() == null ? "number" : key.getDisplayType());
            row.put("persistScope", key.getPersistScope() == null ? "run" : key.getPersistScope());
            row.put("color", key.getColor()); row.put("icon", key.getIcon());
            if (key.getSortOrder() == null) row.putNull("sortOrder"); else row.put("sortOrder", key.getSortOrder());
            row.put("rulesJson", key.getRulesJson());
        }
        return out;
    }
}
