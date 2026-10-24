package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.support.GameplayTemplateRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [W4闭环] 复制主题时,节点引用的私有模板(cms_member_template)必须跟着换归属:
 * 副本节点若原样沿用源作者的 templateId,新归属在编辑器保存时会被 assertTemplateOwned
 * 以「不能引用他人的模板」拒掉 —— 官方主题模板 /use 后必然要改店铺位置,等于模板不可用。
 */
class TopicCopyTemplateOwnershipTest {

    private static final Long SOURCE_TOPIC = 41L;
    private static final Long NEW_TOPIC = 42L;
    private static final Long SOURCE_OWNER = 100000L;
    private static final Long NEW_OWNER = 20088L;
    private static final Long SOURCE_TEMPLATE = 7001L;
    private static final Long LIBRARY_TEMPLATE = 3001L;

    private CmsTopicMapper topicMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsMemberTemplateMapper memberTemplateMapper;
    private IProductRoleService productRoleService;
    private CmsTopicServiceImpl service;
    private final List<CmsTopicNode> insertedNodes = new ArrayList<>();
    private final List<CmsMemberTemplate> insertedTemplates = new ArrayList<>();

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        memberTemplateMapper = mock(CmsMemberTemplateMapper.class);
        productRoleService = mock(IProductRoleService.class);
        service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "cmsMemberTemplateMapper", memberTemplateMapper);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "sysCategoryService", mock(ISysCategoryService.class));

        CmsTopic original = new CmsTopic();
        original.setId(SOURCE_TOPIC);
        original.setMemberId(SOURCE_OWNER);
        original.setProductType(ProductType.FREE_EXPLORE);
        when(topicMapper.selectCmsTopicById(SOURCE_TOPIC)).thenReturn(original);
        doAnswer(invocation -> {
            ((CmsTopic) invocation.getArgument(0)).setId(NEW_TOPIC);
            return 1;
        }).when(topicMapper).insertCmsTopic(any(CmsTopic.class));

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(6L);
        chapter.setTopicId(SOURCE_TOPIC);
        chapter.setRecruitEnabled(0);
        chapter.setMaxMerchant(0);
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Arrays.asList(chapter));
        doAnswer(invocation -> {
            ((CmsTopicChapter) invocation.getArgument(0)).setId(60L);
            return 1;
        }).when(chapterMapper).insertCmsTopicChapter(any(CmsTopicChapter.class));

        // 两个节点共用同一个源模板 —— 断言只深拷贝一次
        CmsTopicNode nodeA = templatedNode(501L, 6L);
        CmsTopicNode nodeB = templatedNode(502L, 6L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(nodeA, nodeB));
        AtomicLong nodeIds = new AtomicLong(900L);
        doAnswer(invocation -> {
            CmsTopicNode inserted = invocation.getArgument(0);
            inserted.setId(nodeIds.incrementAndGet());
            insertedNodes.add(inserted);
            return 1;
        }).when(nodeMapper).insertCmsTopicNode(any(CmsTopicNode.class));

        CmsMemberTemplate sourceTemplate = new CmsMemberTemplate();
        sourceTemplate.setId(SOURCE_TEMPLATE);
        sourceTemplate.setOriginalTemplateId(LIBRARY_TEMPLATE);
        sourceTemplate.setMemberId(SOURCE_OWNER);
        sourceTemplate.setTitle("隐藏菜单");
        sourceTemplate.setStatus(1L);
        sourceTemplate.setDraftStatus(1);
        sourceTemplate.setIsSync(1);
        when(memberTemplateMapper.selectCmsMemberTemplateById(SOURCE_TEMPLATE)).thenReturn(sourceTemplate);
        doAnswer(invocation -> {
            CmsMemberTemplate inserted = invocation.getArgument(0);
            inserted.setId(8001L);
            insertedTemplates.add(inserted);
            return 1;
        }).when(memberTemplateMapper).insertCmsMemberTemplate(any(CmsMemberTemplate.class));
    }

    private static CmsTopicNode templatedNode(Long id, Long chapterId) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setChapterId(chapterId);
        node.setName("节点" + id);
        node.setTemplateId(SOURCE_TEMPLATE);
        return node;
    }

    private Long copyToNewOwner() {
        Map<String, Object> params = new HashMap<>();
        params.put("copyType", 2);
        params.put("memberId", NEW_OWNER);
        return com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, params);
    }

    private String installSlotBindings() {
        ObjectMapper json = new ObjectMapper();
        ObjectNode advanced = json.createObjectNode().put("schemaVersion", 1);
        ObjectNode rules = advanced.putObject("interactionRules");
        ArrayNode slots = rules.putArray("resourceSlots");
        slots.addObject().put("key", "material").put("kind", "ITEM").put("label", "物资").put("required", true);
        rules.putArray("resourceCosts").addObject().put("itemId", "material").put("quantity", 1);
        memberTemplateMapper.selectCmsMemberTemplateById(SOURCE_TEMPLATE).setAdvancedConfigJson(advanced.toString());
        ObjectNode config = json.createObjectNode().put("schemaVersion", 1).put("capacity", 2);
        config.putArray("items").addObject().put("id", "supply").put("kind", "ITEM");
        ObjectNode bindings = config.putObject("templateBindings");
        for (String node : Arrays.asList("501", "502")) {
            ObjectNode binding = bindings.putObject(node).put("templateId", SOURCE_TEMPLATE).put("slotDigest", GameplayTemplateRules.slotDigest(slots));
            binding.putObject("values"); binding.putObject("items").put("material", "supply");
        }
        topicMapper.selectCmsTopicById(SOURCE_TOPIC).setThemeRuntimeConfigJson(config.toString());
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        when(memberTemplateMapper.updateCmsMemberTemplate(any(CmsMemberTemplate.class))).thenReturn(1);
        return config.toString();
    }

    @Test
    void copiedResourceBindingsFollowNewTemplateIdentityAndKeepDigest() throws Exception {
        String original = installSlotBindings();
        copyToNewOwner();
        ArgumentCaptor<CmsTopic> patches = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(patches.capture());
        com.fasterxml.jackson.databind.JsonNode copied = new ObjectMapper().readTree(patches.getValue().getThemeRuntimeConfigJson());
        com.fasterxml.jackson.databind.JsonNode source = new ObjectMapper().readTree(original);
        for (String node : Arrays.asList("901", "902")) {
            assertEquals(8001L, copied.path("templateBindings").path(node).path("templateId").asLong());
            assertEquals(source.path("templateBindings").path("501").path("slotDigest"), copied.path("templateBindings").path(node).path("slotDigest"));
            assertEquals("supply", copied.path("templateBindings").path(node).path("items").path("material").asText());
        }
        assertEquals(original, topicMapper.selectCmsTopicById(SOURCE_TOPIC).getThemeRuntimeConfigJson());
    }

    @Test
    void sameOwnerForcedTemplateCopyAlsoUpdatesResourceBindings() throws Exception {
        installSlotBindings();
        CmsMemberTemplate template = memberTemplateMapper.selectCmsMemberTemplateById(SOURCE_TEMPLATE);
        ObjectNode advanced = (ObjectNode) new ObjectMapper().readTree(template.getAdvancedConfigJson());
        advanced.putArray("requires").addObject().put("var", "sys.passed.501").put("op", "EQ").put("value", 1);
        template.setAdvancedConfigJson(advanced.toString());
        Map<String, Object> params = new HashMap<>(); params.put("copyType", 2); params.put("memberId", SOURCE_OWNER);
        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, params);
        ArgumentCaptor<CmsTopic> patches = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(patches.capture());
        assertEquals(8001L, new ObjectMapper().readTree(patches.getValue().getThemeRuntimeConfigJson()).path("templateBindings").path("901").path("templateId").asLong());
    }

    @Test
    void sameOwnerSharedTemplateKeepsBindingIdentity() throws Exception {
        installSlotBindings();
        Map<String, Object> params = new HashMap<>(); params.put("copyType", 2); params.put("memberId", SOURCE_OWNER);
        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, params);
        ArgumentCaptor<CmsTopic> patches = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(patches.capture());
        assertEquals(SOURCE_TEMPLATE.longValue(), new ObjectMapper().readTree(patches.getValue().getThemeRuntimeConfigJson()).path("templateBindings").path("901").path("templateId").asLong());
        verify(memberTemplateMapper, never()).insertCmsMemberTemplate(any());
    }

    @Test
    void copyCannotRepairAForgedBindingTemplateIdentity() throws Exception {
        ObjectNode config = (ObjectNode) new ObjectMapper().readTree(installSlotBindings());
        config.with("templateBindings").with("501").put("templateId", 999L);
        topicMapper.selectCmsTopicById(SOURCE_TOPIC).setThemeRuntimeConfigJson(config.toString());
        String error = assertThrows(IllegalArgumentException.class, this::copyToNewOwner).getMessage();
        assertTrue(error.contains("501") && error.contains("模板身份"), error);
    }

    @Test
    void copy_deepCopiesForeignTemplateToNewOwner_onceForSharedTemplate() {
        copyToNewOwner();

        assertEquals(1, insertedTemplates.size(), "同一源模板只深拷贝一次");
        CmsMemberTemplate copied = insertedTemplates.get(0);
        assertEquals(NEW_OWNER, copied.getMemberId(), "模板副本归属必须是新归属(否则 W4 拦编辑保存)");
        assertEquals(LIBRARY_TEMPLATE, copied.getOriginalTemplateId(),
                "original_template_id 必须继承公共模板库溯源,不能写会员模板 id");
        assertEquals(Long.valueOf(1L), copied.getStatus(), "保留审核通过态,否则 W4 以「尚未通过审核」拦引用");
        assertEquals(Integer.valueOf(1), copied.getDraftStatus(), "保留已发布态,否则 W4 以「草稿模板」拦引用");
        assertEquals(Integer.valueOf(0), copied.getIsSync(), "副本不得带进公共模板库");

        assertEquals(2, insertedNodes.size());
        for (CmsTopicNode node : insertedNodes) {
            assertEquals(Long.valueOf(8001L), node.getTemplateId(),
                    "副本节点必须指向深拷贝后的模板,不能沿用源作者的 " + SOURCE_TEMPLATE);
            assertNotEquals(SOURCE_TEMPLATE, node.getTemplateId());
        }
    }

    @Test
    void copy_keepsTemplateId_whenOwnerUnchanged() {
        Map<String, Object> params = new HashMap<>();
        params.put("copyType", 2);
        params.put("memberId", SOURCE_OWNER);
        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, params);

        assertTrue(insertedTemplates.isEmpty(), "归属未变不深拷贝模板");
        assertEquals(2, insertedNodes.size());
        for (CmsTopicNode node : insertedNodes) {
            assertEquals(SOURCE_TEMPLATE, node.getTemplateId());
        }
    }

    @Test
    void copy_writesZeroLineage_whenSourceHasNoLibraryLineage() {
        CmsMemberTemplate sourceWithoutLibraryLineage = new CmsMemberTemplate();
        sourceWithoutLibraryLineage.setId(SOURCE_TEMPLATE);
        sourceWithoutLibraryLineage.setMemberId(SOURCE_OWNER);
        sourceWithoutLibraryLineage.setStatus(1L);
        sourceWithoutLibraryLineage.setDraftStatus(1);
        when(memberTemplateMapper.selectCmsMemberTemplateById(SOURCE_TEMPLATE))
                .thenReturn(sourceWithoutLibraryLineage);

        copyToNewOwner();

        assertEquals(Long.valueOf(0L), insertedTemplates.get(0).getOriginalTemplateId(),
                "无公共模板库溯源时必须显式写 0,不能写 null 或会员模板 id");
    }

    @Test
    void copy_remapsUnlockAfterNodeId_toCopiedNodes() {
        // 501 ← 502 的前置引用:副本里必须翻译成新节点 id,不能指回源主题
        CmsTopicNode nodeA = templatedNode(501L, 6L);
        CmsTopicNode nodeB = templatedNode(502L, 6L);
        nodeB.setUnlockAfterNodeId(501L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(nodeA, nodeB));
        List<CmsTopicNode> patches = new ArrayList<>();
        when(nodeMapper.updateCmsTopicNode(any(CmsTopicNode.class))).thenAnswer(invocation -> {
            patches.add(invocation.getArgument(0));
            return 1;
        });

        copyToNewOwner();

        // 插入时不带 unlock(引用未知),二遍 patch 补映射
        assertEquals(1, patches.size(), "只有带前置的节点补一刀 patch");
        CmsTopicNode patch = patches.get(0);
        Long copiedA = insertedNodes.get(0).getId();
        Long copiedB = insertedNodes.get(1).getId();
        assertEquals(copiedB, patch.getId(), "patch 打在副本 B 上");
        assertEquals(copiedA, patch.getUnlockAfterNodeId(), "前置指向副本 A,不是源 501");
        assertNotEquals(Long.valueOf(501L), patch.getUnlockAfterNodeId());
    }

    @Test
    void copy_failsClosed_whenUnlockPatchUpdatesNoRow() {
        CmsTopicNode nodeA = templatedNode(501L, 6L);
        CmsTopicNode nodeB = templatedNode(502L, 6L);
        nodeB.setUnlockAfterNodeId(501L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(nodeA, nodeB));
        when(nodeMapper.updateCmsTopicNode(any(CmsTopicNode.class))).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class, this::copyToNewOwner);

        assertTrue(error.getMessage().contains("节点前置解锁写入失败"),
                "0 行更新必须用明确业务语义失败,不能静默留下已解锁副本");
        assertTrue(error.getMessage().contains("nodeId=902"), "错误必须点名失败的副本节点");
    }

    @Test
    void copy_withoutMemberId_keepsLegacyBehavior() {
        // P0-35:无法判定归属的复制不猜归属,也不动 templateId
        Map<String, Object> params = new HashMap<>();
        params.put("copyType", 2);
        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, params);

        verify(memberTemplateMapper, never()).insertCmsMemberTemplate(any(CmsMemberTemplate.class));
        assertEquals(2, insertedNodes.size());
        for (CmsTopicNode node : insertedNodes) {
            assertEquals(SOURCE_TEMPLATE, node.getTemplateId());
        }
    }

    @Test
    void copyFailsClosedWhenRouteUsesSourceTopicTicketIds() {
        CmsTopic constrained = new CmsTopic();
        constrained.setId(SOURCE_TOPIC);
        constrained.setMemberId(SOURCE_OWNER);
        constrained.setProductType(ProductType.ORIENTEERING);
        constrained.setRouteMode("BRANCH_GRAPH");
        constrained.setRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":501," +
                "\"terminalNodeIds\":[502],\"edges\":[{\"id\":\"go\"," +
                "\"fromNodeId\":501,\"toNodeId\":502," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]," +
                "\"fallbacks\":[{\"fromNodeId\":501,\"toNodeId\":502}]," +
                "\"nodeRequirements\":[{\"nodeId\":502,\"allowedTicketIds\":[77]}]}");
        when(topicMapper.selectCmsTopicById(SOURCE_TOPIC)).thenReturn(constrained);
        when(topicMapper.updateRouteDefinition(eq(NEW_TOPIC), eq("BRANCH_GRAPH"), any(String.class),
                eq(1L), eq(1L), any(Date.class))).thenReturn(1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, this::copyToNewOwner);

        assertTrue(error.getMessage().contains("allowedTicketIds"), error.getMessage());
        assertTrue(error.getMessage().contains("重新选择票种"), error.getMessage());
    }
}
