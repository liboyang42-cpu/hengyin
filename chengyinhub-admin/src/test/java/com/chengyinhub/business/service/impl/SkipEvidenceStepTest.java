package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 拍照作证据步时 SKIP 随主玩法一起跳过;纯拍照站仍按现场凭证拒绝跳过。 */
class SkipEvidenceStepTest {
    private static final String LADDER = "\"hintLadder\":[{\"level\":1,\"type\":\"TEXT\",\"content\":\"提示\"},{\"level\":2,\"type\":\"SKIP\"}]";
    private static final String QA = "\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"prompt\":\"示例站点\",\"answerText\":\"ABC\"}";
    private static final String PHOTO = "\"photoCheck\":{\"enabled\":true,\"prompt\":\"拍一张示例站点\"}";

    private final AdvancedGameRuntimeServiceImpl service = new AdvancedGameRuntimeServiceImpl();

    private void skippable(String json) throws Exception {
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        CmsTopicNode node = new CmsTopicNode(); node.setId(5L); node.setTopicId(1L);
        when(nodes.selectCmsTopicNodeById(5L)).thenReturn(node);
        ReflectionTestUtils.setField(service, "nodeMapper", nodes);
        ReflectionTestUtils.setField(service, "templateMapper", mock(CmsMemberTemplateMapper.class));
        JsonNode config = new ObjectMapper().readTree(json);
        ReflectionTestUtils.invokeMethod(service, "requireSkippable", config, 5L);
    }

    @Test
    void qaWithPhotoEvidenceStepCanBeSkipped() throws Exception {
        skippable("{\"schemaVersion\":1," + LADDER + "," + QA + "," + PHOTO + "}");
    }

    @Test
    void photoOnlyStationStillRefusesSkip() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> skippable("{\"schemaVersion\":1," + LADDER + "," + PHOTO + "}"));
        assertEquals("该节点依赖现场凭证，不能跳过", refused.getMessage());
    }

    @Test
    void scanBesideQaStillRefusesSkip() {
        assertThrows(IllegalArgumentException.class,
                () -> skippable("{\"schemaVersion\":1," + LADDER + "," + QA + ",\"scan\":{\"enabled\":true}}"));
    }
}
