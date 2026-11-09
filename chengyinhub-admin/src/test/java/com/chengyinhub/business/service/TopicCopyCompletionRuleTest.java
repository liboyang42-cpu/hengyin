package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsCollaboratorsMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.impl.TopicContentCopyServiceImpl;
import com.chengyinhub.business.service.support.TopicCompletionRulePolicy;
import com.chengyinhub.common.constant.ProductType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TopicCopyCompletionRuleTest {
    private static final long SOURCE = 990047L, COPY = 990100L, OWNER = 100000L, ADOPTER = 20088L;
    private static final String COUNT_RULE = "{\"nodeCompletion\":{\"mode\":\"AT_LEAST\",\"requiredCount\":3}}";
    private final TopicContentCopyServiceImpl service = new TopicContentCopyServiceImpl();
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
    private final List<CmsTopicNode> originals = new ArrayList<>(), copies = new ArrayList<>();
    private final List<CmsTopicChapter> copiedChapters = new ArrayList<>();
    private CmsTopic source;
    private CmsTopic inserted;
    private CmsTopic rulePatch;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);
        ReflectionTestUtils.setField(service, "productRoleService", mock(IProductRoleService.class));
        ReflectionTestUtils.setField(service, "platformMemberId", OWNER);
        CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapters);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateMapper", mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(service, "cmsCollaboratorsMapper", mock(CmsCollaboratorsMapper.class));
        ReflectionTestUtils.setField(service, "omsTicketMapper", mock(OmsTicketMapper.class));
        ICmsMemberTemplateService games = mock(ICmsMemberTemplateService.class);
        when(games.copyForContentOwner(anyLong(), anyLong(), anyBoolean()))
                .thenAnswer(i -> (Long) i.getArgument(0) + 1000L);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateService", games);
        source = new CmsTopic(); source.setId(SOURCE); source.setMemberId(OWNER);
        source.setProductType(ProductType.ORIENTEERING); source.setCompleteRuleJson(COUNT_RULE);
        when(topics.selectCmsTopicById(SOURCE)).thenReturn(source);
        when(topics.insertCmsTopic(any())).thenAnswer(i -> {
            inserted = i.getArgument(0); inserted.setId(COPY); return 1;
        });
        // Questo 地点合同:副本整包复制后经唯一写口落合同并回读(owner 在 SQL 里再判一次)。
        when(topics.updateTopicLocationContract(eq(COPY), any(), any(), any(), any())).thenAnswer(i -> {
            inserted.setContentContractJson(i.getArgument(2)); return 1;
        });
        when(topics.selectCmsTopicById(COPY)).thenAnswer(i -> inserted);
        when(topics.updateCmsTopic(any())).thenAnswer(i -> {
            CmsTopic patch = i.getArgument(0);
            if (patch.getCompleteRuleJson() != null) rulePatch = patch;
            return 1;
        });
        List<CmsTopicChapter> sourceChapters = new ArrayList<>();
        for (int chapter = 0; chapter < 2; chapter++) {
            CmsTopicChapter row = new CmsTopicChapter(); row.setId(197L + chapter);
            row.setTopicId(SOURCE); row.setRecruitEnabled(0); row.setMaxMerchant(0);
            row.setTermsMode("TRAFFIC"); sourceChapters.add(row);
            for (int offset = 0; offset < 2; offset++) {
                CmsTopicNode node = new CmsTopicNode(); node.setId(126L + chapter * 2 + offset);
                node.setTopicId(SOURCE); node.setChapterId(row.getId()); node.setTemplateId(80L + chapter);
                originals.add(node);
            }
        }
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(sourceChapters);
        when(chapters.insertCmsTopicChapter(any())).thenAnswer(i -> {
            CmsTopicChapter row = i.getArgument(0); row.setId(500L + copiedChapters.size());
            copiedChapters.add(row); return 1;
        });
        when(nodes.selectCmsTopicNodeList(any())).thenAnswer(i ->
                Long.valueOf(SOURCE).equals(((CmsTopicNode) i.getArgument(0)).getTopicId()) ? originals : copies);
        when(nodes.insertCmsTopicNode(any())).thenAnswer(i -> {
            CmsTopicNode row = i.getArgument(0); row.setId(1000L + copies.size()); copies.add(row); return 1;
        });
    }

    private Long copy(long owner, int copyType) {
        copies.clear(); copiedChapters.clear(); rulePatch = null;
        return service.copyCmsTopic(SOURCE, Map.of("memberId", owner, "copyType", copyType));
    }

    @Test
    void adoptedCityKeepsThreeOfFourAcrossTwoChaptersWithIndependentGameBindings() {
        assertEquals(COPY, copy(ADOPTER, 3));
        assertEquals(ProductType.ORIENTEERING, inserted.getProductType());
        assertEquals(ADOPTER, inserted.getMemberId());
        assertEquals(0, inserted.getUserStatus());
        assertEquals(COUNT_RULE, rulePatch.getCompleteRuleJson());
        assertEquals(List.of(500L, 500L, 501L, 501L), copies.stream().map(CmsTopicNode::getChapterId).toList());
        assertEquals(List.of(1080L, 1080L, 1081L, 1081L), copies.stream().map(CmsTopicNode::getTemplateId).toList());
        TopicCompletionRulePolicy policy = new TopicCompletionRulePolicy();
        assertFalse(policy.isCompleted(rulePatch.getCompleteRuleJson(), copies.size(), 2));
        assertTrue(policy.isCompleted(rulePatch.getCompleteRuleJson(), copies.size(), 3));
        assertEquals(COUNT_RULE, source.getCompleteRuleJson());
    }

    @Test
    void crossOwnerCopiesOnlyCountFieldsEvenInsideNodeCompletion() throws Exception {
        source.setCompleteRuleJson("{\"nodeCompletion\":{\"mode\":\"AT_LEAST\",\"requiredCount\":3,"
                + "\"couponId\":99},\"bingo\":{\"enabled\":false},\"minimumMerchants\":2,\"unknown\":true}");
        copy(ADOPTER, 2);
        JsonNode rule = new ObjectMapper().readTree(rulePatch.getCompleteRuleJson());
        assertEquals(1, rule.size()); assertEquals(2, rule.path("nodeCompletion").size());
        assertEquals(COUNT_RULE, rule.toString());
    }

    @Test
    void sameOwnerRetainsExistingWholeRule() {
        String rule = COUNT_RULE.substring(0, COUNT_RULE.length() - 1) + ",\"bingo\":{\"enabled\":false}}";
        source.setCompleteRuleJson(rule); copy(OWNER, 2);
        assertEquals(rule, rulePatch.getCompleteRuleJson());
    }

    @Test
    void allModeCannotCarryAnUnknownNestedRequiredCountPayload() {
        source.setCompleteRuleJson("{\"nodeCompletion\":{\"mode\":\"ALL\",\"requiredCount\":{\"couponId\":99}}}");
        copy(ADOPTER, 2);
        assertEquals("{\"nodeCompletion\":{\"mode\":\"ALL\"}}", rulePatch.getCompleteRuleJson());
    }

    @Test
    void freeExploreAcrossOwnersDoesNotAcquireNodeCompletion() {
        source.setProductType(ProductType.FREE_EXPLORE);
        source.setCompleteRuleJson("{\"minimumMerchants\":2}"); copy(ADOPTER, 2);
        assertNull(rulePatch);
    }

    @Test
    void countRuleCannotTravelWithBasicInfoOnlyOrAbsentNodeRule() {
        copy(ADOPTER, 1); assertNull(rulePatch); assertTrue(copies.isEmpty());
        source.setCompleteRuleJson("{\"minimumMerchants\":2}"); copy(ADOPTER, 2);
        assertNull(rulePatch);
    }

    @Test
    void malformedOrImpossibleCountFailsInsteadOfChangingPromise() {
        source.setCompleteRuleJson("[]"); assertThrows(IllegalArgumentException.class, () -> copy(ADOPTER, 2));
        source.setCompleteRuleJson(COUNT_RULE.replace(":3", ":5"));
        assertThrows(IllegalArgumentException.class, () -> copy(ADOPTER, 2));
        assertNull(rulePatch);
    }

    @Test
    void incompleteReadbackOrFailedRuleWriteAbortsCopy() {
        doReturn(originals, originals, List.of()).when(nodes).selectCmsTopicNodeList(any());
        assertThrows(IllegalStateException.class, () -> copy(ADOPTER, 2));
        doReturn(originals).when(nodes).selectCmsTopicNodeList(any());
        doReturn(0).when(topics).updateCmsTopic(any());
        assertThrows(IllegalStateException.class, () -> copy(ADOPTER, 2));
    }
}
