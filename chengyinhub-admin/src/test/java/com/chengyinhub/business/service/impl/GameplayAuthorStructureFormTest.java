package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.support.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameplayAuthorStructureFormTest {
    @Test void nativeFormBuiltTeamWorldConfigCompilesAndFreezesWithRealNodes() throws Exception {
        // 夹具由 gameplay-structure-form.js 的原生表单 handlers 生成，前端测试同时断言文件未漂移。
        Path fixture = Paths.get("../chengyinhub-xcx/tests/unit/fixtures/gameplay-structure-form.json");
        if (!Files.exists(fixture)) fixture = Paths.get("chengyinhub-xcx/tests/unit/fixtures/gameplay-structure-form.json");
        ObjectMapper json = new ObjectMapper();
        JsonNode form = json.readTree(Files.readAllBytes(fixture));
        List<CmsMemberTemplate> templates = new ArrayList<>();
        for (JsonNode row : form.path("templates")) {
            CmsMemberTemplate template = new CmsMemberTemplate(); template.setId(row.path("id").asLong());
            template.setValidationMethod(row.path("validationMethod").asInt()); template.setAdvancedConfigJson(row.path("advancedConfigJson").asText());
            templates.add(template);
        }
        List<CmsTopicChapter> chapters = new ArrayList<>(); List<CmsTopicNode> nodes = new ArrayList<>();
        for (JsonNode row : form.path("chapters")) {
            CmsTopicChapter chapter = new CmsTopicChapter(); chapter.setId(row.path("id").asLong()); chapter.setTopicId(77L);
            chapter.setName(row.path("name").asText()); chapter.setSortId((long) chapters.size()); chapters.add(chapter);
            for (JsonNode source : row.path("nodes")) {
                CmsTopicNode node = new CmsTopicNode(); node.setId(source.path("id").asLong()); node.setChapterId(chapter.getId());
                node.setTopicId(77L); node.setName(source.path("name").asText()); node.setTemplateId(source.path("templateId").asLong());
                node.setSortId((long) nodes.size()); node.setStatus(1L); node.setMerchantMemberId(30L); nodes.add(node);
            }
        }
        CmsTopic topic = new CmsTopic(); topic.setId(77L); topic.setName(form.path("name").asText()); topic.setRouteMode("LINEAR");
        topic.setJourneyRulesJson(form.path("journeyRules").toString()); topic.setThemeRuntimeConfigJson(form.path("runtime").toString());
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class); when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(nodes);
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class); when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(templates);
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class); when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(chapters);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class); when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(new CmsRegistration()));
        TopicRouteRuntimeServiceImpl service = new TopicRouteRuntimeServiceImpl(mock(TopicRouteSessionMapper.class), mock(CmsTopicMapper.class), nodeMapper,
                templateMapper, mock(PlayerNodeProgressMapper.class), null, registrations, null, mock(IShopDayProofService.class), () -> 1L, () -> 10, Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        JsonNode compiled = json.readTree(service.compileTemplateRules(topic));
        assertEquals("TEAM", compiled.path("gameplayRuntime").path("runtimeScope").asText());
        assertEquals("WORLD_SHARED", compiled.path("gameplayRuntime").path("worldMode").asText());
        assertEquals(form.path("runtime").path("gameplayRuntime").path("acts"), compiled.path("gameplayRuntime").path("acts"));
        assertTrue(compiled.path("gameplayRuntime").path("acts").get(0).path("hostUnlock").asBoolean());
        assertEquals("guide_202", compiled.path("gameplayRuntime").path("quests").get(1).path("guideKey").asText());
        assertEquals(201, compiled.path("gameplayRuntime").path("quests").get(1).path("approvedFallbackNodeIds").get(0).asInt());
        assertEquals("tpl_node_201", compiled.path("gameplayRuntime").path("quests").get(0).path("facilityKey").asText());
        assertEquals(1, compiled.path("gameplayRuntime").path("facilities").get(0).path("hpCost").asInt());
        assertEquals("MAJORITY_ACCEPTANCE", compiled.path("gameplayRuntime").path("votePolicy").asText());
        assertEquals(2, compiled.path("gameplayRuntime").path("plans").size());
        for (JsonNode plan : compiled.path("gameplayRuntime").path("plans")) {
            assertEquals(203, plan.path("operationNodeId").asInt()); assertTrue(plan.path("terminal").asBoolean());
            assertTrue(form.path("runtime").path("endings").findValuesAsText("id").contains(plan.path("endingKey").asText()));
        }
        topic.setThemeRuntimeConfigJson(compiled.toString());
        JsonNode frozen = json.readTree(service.freezePublication(topic, null));
        assertEquals("TEAM", frozen.path("themeConfig").path("gameplayRuntime").path("runtimeScope").asText());
        assertEquals(form.path("runtime").path("endings"), frozen.path("themeConfig").path("endings"));
        assertFalse(frozen.path("themeConfig").has("templateBindings"));
        Map<Long,Long> copiedNodes = new HashMap<>(); copiedNodes.put(201L,301L); copiedNodes.put(202L,302L); copiedNodes.put(203L,303L);
        JsonNode copied = json.readTree(ThemeRuntimeConfig.remapAll(compiled.toString(), copiedNodes, Collections.singletonMap(101L,201L), null));
        assertEquals(302, copied.path("gameplayRuntime").path("quests").get(1).path("nodeId").asInt());
        assertEquals(301, copied.path("gameplayRuntime").path("quests").get(1).path("approvedFallbackNodeIds").get(0).asInt());
        assertEquals(form.path("runtime").path("endings"), copied.path("endings"));
    }
}
