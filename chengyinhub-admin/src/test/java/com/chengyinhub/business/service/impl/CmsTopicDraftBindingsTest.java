package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.support.GameplayTemplateRules;
import com.chengyinhub.business.service.support.ThemeRuntimeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CmsTopicDraftBindingsTest {
    private final ObjectMapper json = new ObjectMapper();
    private ICmsTopicService service;
    private CmsTopicMapper topics;
    private JdbcTemplate jdbc;
    private CmsTopic stored;

    @BeforeEach void setup() throws Exception {
        CmsTopicServiceImpl target = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(target, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(target, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        for (Field field : CmsTopicServiceImpl.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive() || field.getType().getName().startsWith("java.")) continue;
            field.setAccessible(true);
            if (field.get(target) == null) ReflectionTestUtils.setField(target, field.getName(), mock(field.getType()));
        }
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wireRelocatedCollaborators(target, publicationField -> org.mockito.Mockito.mock(publicationField.getType()));
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table saved_topic (id bigint primary key, config clob)");
        jdbc.execute("create table saved_node (id bigint primary key, name varchar(100), template_id bigint)");
        topics = (CmsTopicMapper) ReflectionTestUtils.getField(target, "cmsTopicMapper");
        when(topics.insertCmsTopic(any())).thenAnswer(call -> {
            stored = call.getArgument(0); stored.setId(700L); stored.setLifecycle(0);
            assertNoClientKeys(stored.getThemeRuntimeConfigJson());
            return jdbc.update("insert into saved_topic values (?, ?)", 700L, stored.getThemeRuntimeConfigJson());
        });
        when(topics.selectCmsTopicById(anyLong())).thenAnswer(call -> {
            if (stored == null) return null;
            CmsTopic copy = new CmsTopic(); BeanUtils.copyProperties(stored, copy);
            copy.setThemeRuntimeConfigJson(jdbc.queryForObject("select config from saved_topic where id=700", String.class));
            return copy;
        });
        when(topics.selectCmsTopicByIdForUpdate(anyLong())).thenAnswer(call -> topics.selectCmsTopicById(call.getArgument(0)));
        when(topics.selectTopicIsDraft(anyLong())).thenReturn(1);
        when(topics.updateCmsTopic(any())).thenAnswer(call -> {
            CmsTopic patch = call.getArgument(0);
            assertNoClientKeys(patch.getThemeRuntimeConfigJson());
            if (patch.getThemeRuntimeConfigJson() != null) jdbc.update("update saved_topic set config=? where id=700", patch.getThemeRuntimeConfigJson());
            return 1;
        });
        CmsTopicChapterMapper chapters = (CmsTopicChapterMapper) ReflectionTestUtils.getField(target, "cmsTopicChapterMapper");
        AtomicLong chapterIds = new AtomicLong(800);
        when(chapters.insertCmsTopicChapter(any())).thenAnswer(call -> { ((CmsTopicChapter) call.getArgument(0)).setId(chapterIds.incrementAndGet()); return 1; });
        when(chapters.updateCmsTopicChapter(any())).thenReturn(1);
        CmsTopicNodeMapper nodes = (CmsTopicNodeMapper) ReflectionTestUtils.getField(target, "cmsTopicNodeMapper");
        AtomicLong nodeIds = new AtomicLong(1000);
        when(nodes.insertCmsTopicNode(any())).thenAnswer(call -> {
            CmsTopicNode node = call.getArgument(0); node.setId(nodeIds.incrementAndGet());
            return jdbc.update("insert into saved_node values (?, ?, ?)", node.getId(), node.getName(), node.getTemplateId());
        });
        when(nodes.selectCmsTopicNodeList(any())).thenAnswer(call -> jdbc.query("select * from saved_node order by id", (row, index) -> {
            CmsTopicNode node = new CmsTopicNode(); node.setId(row.getLong("id")); node.setName(row.getString("name")); node.setTemplateId(row.getLong("template_id")); node.setTopicId(700L); return node;
        }));
        when(nodes.deleteCmsTopicNodeById(anyLong())).thenAnswer(call -> jdbc.update("delete from saved_node where id=?", (Long) call.getArgument(0)));
        CmsMemberTemplateMapper templates = (CmsMemberTemplateMapper) com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(target, "cmsMemberTemplateMapper");
        CmsMemberTemplate template = new CmsMemberTemplate(); template.setId(501L); template.setMemberId(42L); template.setStatus(1L); template.setDraftStatus(1);
        when(templates.selectCmsMemberTemplateById(501L)).thenReturn(template);
        ReflectionTestUtils.setField(target, "commercialTopicPolicy",
                new com.chengyinhub.business.service.support.CommercialTopicPolicy(
                        (OmsTicketMapper) ReflectionTestUtils.getField(target, "omsTicketMapper"), chapters,
                        (MerchantChapterOfferMapper) ReflectionTestUtils.getField(target, "merchantChapterOfferMapper"), topics));
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(target);
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        service = (ICmsTopicService) factory.getProxy();
    }

    private void assertNoClientKeys(String raw) {
        if (raw != null && !raw.isEmpty()) ThemeRuntimeConfig.parseDraft(raw);
    }
    private NodeDTO node(String key, Long id) {
        NodeDTO node = new NodeDTO(); node.setClientNodeKey(key); node.setId(id); node.setName(key); node.setTemplateId(501L);
        node.setAddress("地点"); node.setLongitude("121.4"); node.setLatitude("31.2"); node.setNodeTime(0); return node;
    }
    private <T extends TopicCreateDTO> T request(T request, NodeDTO... nodes) {
        request.setName("主题"); request.setSaveDraft(1); request.setProductType(2); request.setPublishMode("pro");
        ChapterDTO chapter = new ChapterDTO(); chapter.setName("章节"); chapter.setNodes(new ArrayList<>(Arrays.asList(nodes)));
        request.setChapters(Collections.singletonList(chapter));
        return request;
    }
    private String config(String... keys) {
        ObjectNode config = json.createObjectNode().put("schemaVersion", 1).put("capacity", 10);
        config.putArray("items").addObject().put("id", "supply").put("kind", "ITEM");
        config.withArray("items").addObject().put("id", "spare").put("kind", "ITEM");
        ObjectNode bindings = config.putObject("templateBindings");
        com.fasterxml.jackson.databind.node.ArrayNode slots = json.createArrayNode();
        slots.addObject().put("key", "material").put("kind", "ITEM").put("label", "物资").put("required", true);
        String digest = GameplayTemplateRules.slotDigest(slots);
        int index = 0;
        for (String key : keys) {
            ObjectNode binding = bindings.putObject(key).put("templateId", 501L).put("slotDigest", digest);
            binding.putObject("values"); binding.putObject("items").put("material", index++ == 0 ? "supply" : "spare");
        }
        return config.toString();
    }
    private ObjectNode saved() { return ThemeRuntimeConfig.parseDraft(topics.selectCmsTopicById(700L).getThemeRuntimeConfigJson()); }
    private void create() {
        TopicCreateDTO request = request(new TopicCreateDTO(), node("first", null), node("second", null));
        request.setThemeRuntimeConfig(config("client:first", "client:second"));
        assertEquals(700L, com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).createTopic(request, 42L, "author"));
    }

    @Test void createPersistsTwoBindingsUsingGeneratedIdsOnly() {
        create();
        assertEquals(2, saved().path("templateBindings").size());
        assertTrue(saved().path("templateBindings").has("1001")); assertTrue(saved().path("templateBindings").has("1002"));
    }
    @Test void v2BlockFlowUsesTheSameClientMapping() {
        TopicCreateDTO request = request(new TopicCreateDTO(), node("first", null), node("second", null));
        request.setProductType(1); request.setThemeRuntimeConfig(config("client:first", "client:second"));
        List<ChapterBlockDTO> blocks = new ArrayList<>();
        for (int index = 0; index < 2; index++) { ChapterBlockDTO block = new ChapterBlockDTO(); block.setType("node"); block.setNodeIndex(index); blocks.add(block); }
        request.getChapters().get(0).setSchemaVersion(1); request.getChapters().get(0).setBlocks(blocks);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).createTopicV2(request, 42L, "author");
        assertTrue(saved().path("templateBindings").has("1001")); assertTrue(saved().path("templateBindings").has("1002"));
    }
    @Test void danglingClientKeyRollsBackTopicAndNodes() {
        TopicCreateDTO request = request(new TopicCreateDTO(), node("first", null), node("second", null)); request.setThemeRuntimeConfig(config("client:missing"));
        String error = assertThrows(IllegalArgumentException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).createTopic(request, 42L, "author")).getMessage();
        assertTrue(error.contains("client:missing"), error);
        assertEquals(0, jdbc.queryForObject("select count(*) from saved_topic", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from saved_node", Integer.class));
    }
    @Test void updateWithoutConfigKeepsRealBindingsAcrossReorder() {
        create();
        TopicUpdateDTO request = request(new TopicUpdateDTO(), node("second", 1002L), node("first", 1001L)); request.setId(700L);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(request, 42L, "author");
        assertTrue(saved().path("templateBindings").has("1003")); assertTrue(saved().path("templateBindings").has("1004"));
        assertFalse(saved().path("templateBindings").has("1001"));
        assertEquals("spare", saved().path("templateBindings").path("1003").path("items").path("material").asText());
        assertEquals("supply", saved().path("templateBindings").path("1004").path("items").path("material").asText());
    }
    @Test void updateCanBindANewNodeAlongsideRealNodeBindings() {
        create();
        TopicUpdateDTO request = request(new TopicUpdateDTO(), node("first", 1001L), node("third", null)); request.setId(700L);
        request.setThemeRuntimeConfig(config("1001", "client:third"));
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(request, 42L, "author");
        assertTrue(saved().path("templateBindings").has("1003")); assertTrue(saved().path("templateBindings").has("1004"));
    }
    @Test void existingNodeCannotBeReboundUsingAClientAlias() {
        create(); String before = saved().toString();
        TopicUpdateDTO request = request(new TopicUpdateDTO(), node("first", 1001L), node("second", 1002L)); request.setId(700L);
        request.setThemeRuntimeConfig(config("client:first", "1002"));
        assertThrows(IllegalArgumentException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(request, 42L, "author"));
        assertEquals(before, saved().toString());
        assertEquals(2, jdbc.queryForObject("select count(*) from saved_node where id in (1001,1002)", Integer.class));
    }
    @Test void deletingBoundNodeRejectsUnlessBindingIsExplicitlyRemoved() {
        create(); String before = saved().toString();
        TopicUpdateDTO request = request(new TopicUpdateDTO(), node("first", 1001L)); request.setId(700L);
        assertThrows(IllegalArgumentException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(request, 42L, "author"));
        assertEquals(before, saved().toString());
        request.setThemeRuntimeConfig(config("1001")); com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(request, 42L, "author");
        assertEquals(1, saved().path("templateBindings").size());
    }
    @Test void duplicateClientKeysRollBackAndCopyStillUsesRealIds() {
        TopicCreateDTO request = request(new TopicCreateDTO(), node("same", null), node("same", null)); request.setThemeRuntimeConfig(config("client:same"));
        assertThrows(IllegalArgumentException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).createTopic(request, 42L, "author"));
        assertEquals(0, jdbc.queryForObject("select count(*) from saved_node", Integer.class));
        ObjectNode copy = ThemeRuntimeConfig.parseDraft(ThemeRuntimeConfig.remapNodes(config("1001"), Collections.singletonMap(1001L, 2001L)));
        assertTrue(copy.path("templateBindings").has("2001")); assertFalse(copy.path("templateBindings").has("1001"));
    }
    @Test void persistedParserStillRejectsClientKeysAndOwnershipIsUnchanged() {
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(config("client:first")));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseForNodeSave(config("client:")));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseForNodeSave(config("client: first")));
        create();
        TopicUpdateDTO request = request(new TopicUpdateDTO(), node("first", 1001L)); request.setId(700L);
        assertThrows(RuntimeException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(request, 99L, "other"));
        assertEquals(2, saved().path("templateBindings").size());
    }
}
