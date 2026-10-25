package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

/** Shared content read owner for authoring and publication; no independent transaction. */
@Component
public final class TopicRouteOutcomeReader {
    private static final ObjectMapper ROUTE_JSON = new ObjectMapper();
    private final CmsTopicNodeMapper cmsTopicNodeMapper;
    private final CmsMemberTemplateMapper cmsMemberTemplateMapper;
    public TopicRouteOutcomeReader(CmsTopicNodeMapper nodes, CmsMemberTemplateMapper templates) {
        this.cmsTopicNodeMapper = nodes;
        this.cmsMemberTemplateMapper = templates;
    }
    private static <T> List<T> nullToEmpty(List<T> rows) {
        return rows == null ? Collections.<T>emptyList() : rows;
    }

    public Map<Long, Set<String>> declaredRouteOutcomes(Long topicId) {
        CmsTopicNode probe = new CmsTopicNode(); probe.setTopicId(topicId); probe.setStatus(1L);
        return declaredRouteOutcomes(nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(probe)), node ->
                node.getTemplateId() == null ? null : cmsMemberTemplateMapper.selectCmsMemberTemplateById(node.getTemplateId()));
    }

    public Map<Long, Set<String>> declaredRouteOutcomes(TopicStateKeyRefService.CurrentContent content) {
        if (content == null || !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("正式路线结果声明必须加入主题写事务");
        return declaredRouteOutcomes(content.activeNodes(), node -> content.templates().get(node.getTemplateId()));
    }

    private Map<Long, Set<String>> declaredRouteOutcomes(List<CmsTopicNode> nodes,
            java.util.function.Function<CmsTopicNode, CmsMemberTemplate> templateAt) {
        Map<Long, Set<String>> result = new LinkedHashMap<Long, Set<String>>();
        for (CmsTopicNode node : nodes) {
            Set<String> values = new LinkedHashSet<String>();
            CmsMemberTemplate template = templateAt.apply(node);
            boolean advancedGameplay = false;
            if (template != null && StringUtils.isNotBlank(template.getAdvancedConfigJson())) {
                try {
                    JsonNode config = ROUTE_JSON.readTree(template.getAdvancedConfigJson());
                    JsonNode branch = config.path("branch");
                    if (branch.path("enabled").asBoolean(false)) {
                        advancedGameplay = true;
                        for (JsonNode step : branch.path("steps")) {
                            if (step.path("terminal").asBoolean(false)) {
                                String code = step.path("outcomeCode").asText("").trim();
                                if (!code.isEmpty()) values.add("ADVANCED_RESULT:" + code);
                            }
                        }
                    } else if (config.path("diceRoll").path("enabled").asBoolean(false)
                            && "d20".equals(config.path("diceRoll").path("mode").asText())) {
                        advancedGameplay = true;
                        values.add("ADVANCED_RESULT:D20_SUCCESS");
                        values.add("ADVANCED_RESULT:D20_FAILURE");
                    }
                } catch (Exception e) { throw new IllegalArgumentException("高级玩法 outcomeCode 声明无效", e); }
            }
            if (!advancedGameplay && template != null
                    && Integer.valueOf(ValidationMethod.PREFERENCE_GROUP).equals(template.getValidationMethod())) {
                try {
                    Iterator<String> names = ROUTE_JSON.readTree(template.getPreferenceJson()).path("results").fieldNames();
                    while (names.hasNext()) values.add("PREFERENCE_RESULT:" + names.next());
                } catch (Exception e) { throw new IllegalArgumentException("偏好玩法 outcomeCode 声明无效", e); }
            } else if (!advancedGameplay) values.add("CHOICE:COMPLETED");
            result.put(node.getId(), values);
        }
        Map<Long, Set<String>> frozen = new LinkedHashMap<>();
        result.forEach((node, values) -> frozen.put(node, Collections.unmodifiableSet(new LinkedHashSet<>(values))));
        return Collections.unmodifiableMap(frozen);
    }
}
