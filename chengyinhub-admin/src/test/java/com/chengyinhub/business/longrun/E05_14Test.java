package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.impl.PlayEncounterServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Real public Encounter consumer; mocks only lookup/proof boundaries. */
class E05_14Test {
    private PlayEncounterServiceImpl service;
    private TopicRouteSession run;
    private static final String LIVE = "{\"qa\":{\"enabled\":true},\"branch\":{\"enabled\":true,"
            + "\"steps\":[{\"terminal\":true,\"outcomeCode\":\"LIVE_LATE\",\"outcomeLabel\":\"late edit\"}]}}";

    @BeforeEach
    void setup() {
        service = new PlayEncounterServiceImpl();
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        ICmsMemberTemplateService templates = mock(ICmsMemberTemplateService.class);
        TopicRouteSessionMapper sessions = mock(TopicRouteSessionMapper.class);
        IShopDayProofService proof = mock(IShopDayProofService.class);
        ReflectionTestUtils.setField(service, "cmsTopicNodeService", nodes);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateService", templates);
        ReflectionTestUtils.setField(service, "topicRouteSessionMapper", sessions);
        ReflectionTestUtils.setField(service, "shopDayProofService", proof);
        ReflectionTestUtils.setField(service, "npcProfileService", mock(NpcProfileService.class));
        ReflectionTestUtils.setField(service, "playerNodeProgressService", mock(IPlayerNodeProgressService.class));
        ReflectionTestUtils.setField(service, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        CmsTopicNode node = new CmsTopicNode(); node.setId(101L); node.setTopicId(73L); node.setTemplateId(501L);
        when(nodes.selectCmsTopicNodeById(101L)).thenReturn(node);
        CmsMemberTemplate template = new CmsMemberTemplate(); template.setId(501L); template.setAdvancedConfigJson(LIVE);
        when(templates.selectCmsMemberTemplateById(501L)).thenReturn(template);
        run = new TopicRouteSession(); run.setId(9001L); run.setMemberId(71L); run.setTopicId(73L);
        run.setActivityId(0L); run.setVersion(3);
        run.setStateJson("{\"status\":\"ACTIVE\",\"variables\":{},\"tags\":[],\"completedNodeIds\":[]}");
        when(sessions.selectByOwner(0L, 73L, 71L, "default")).thenReturn(run);
        when(proof.hasArrived(71L, 0L, 101L)).thenReturn(true);
    }

    @Test
    void presentInvalidPlayablesRejectInsteadOfExposingLateLiveActions() {
        for (String invalid : new String[]{"null", "[]", "\"broken\"", "1", "false"}) {
            snapshot("{\"playables\":" + invalid + ",\"branchOutcomes\":[]}", "[]");
            ServiceException error = assertThrows(ServiceException.class, this::resolve, invalid);
            assertEquals("玩法配置不可用", error.getMessage(), invalid);
        }
    }

    @Test
    void presentInvalidBranchOutcomesRejectInsteadOfExposingLateLiveDeclarations() {
        for (String invalid : new String[]{"null", "{}", "\"broken\"", "1", "false"}) {
            snapshot("{\"playables\":{},\"branchOutcomes\":" + invalid + "}", "[]");
            ServiceException error = assertThrows(ServiceException.class, this::resolve, invalid);
            assertEquals("玩法配置不可用", error.getMessage(), invalid);
        }
    }

    @Test
    void missingKeysKeepLegacyLiveFallbackInAnExistingFrozenNode() {
        snapshot("{}", "[]");
        Map<String, Object> result = resolve();
        assertTrue(((List<?>) result.get("allowedActions")).contains("qa"));
        assertTrue(codes(result).contains("LIVE_LATE"));
    }

    @Test
    void validExplicitEmptyKeysDoNotUseLiveButStillMergeFrozenGraphDestinations() {
        snapshot("{\"playables\":{},\"branchOutcomes\":[]}",
                "[{\"fromNodeId\":101,\"toNodeId\":102,\"trigger\":{\"outcomeCode\":\"GRAPH_ONLY\"}}]");
        Map<String, Object> result = resolve();
        assertTrue(((List<?>) result.get("allowedActions")).isEmpty());
        assertEquals(Collections.singletonList("GRAPH_ONLY"), codes(result));
        Map<?, ?> contract = (Map<?, ?>) result.get("outcomeContract");
        Map<?, ?> row = (Map<?, ?>) ((List<?>) contract.get("outcomes")).get(0);
        assertEquals(Collections.singletonList(102L), row.get("nextNodeIds"));
        assertNull(row.get("label"));
    }

    private void snapshot(String block, String edges) {
        run.setConfigSnapshotJson("{\"nodeJourney\":{\"101\":" + block + "},\"edges\":" + edges + "}");
    }
    private Map<String, Object> resolve() { return service.resolve(71L, 73L, 0L, 101L); }
    private List<?> codes(Map<String, Object> view) {
        return (List<?>) ((Map<?, ?>) view.get("outcomeContract")).get("outcomeCodes");
    }
}
