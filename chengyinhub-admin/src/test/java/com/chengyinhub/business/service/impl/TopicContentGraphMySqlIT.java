package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.service.support.GameplayTemplateRules;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.system.domain.SysCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicit -Dtest opt-in, hard controlled-storage prerequisites, ordinary non-skipped @Test cases. */
class TopicContentGraphMySqlIT {
    private TopicContentGraphMySqlFixture f;
    @BeforeEach void open() throws Exception { f=new TopicContentGraphMySqlFixture();f.open(); }
    @AfterEach void close(TestInfo info) throws Exception {
        if(f!=null)try { if(f.before!=null)f.receipt(info.getTestMethod().orElseThrow().getName()); }
        finally { f.close(); }
    }

    private void seedAlternativeHistory() {
        for (String type : List.of("QUIZ", "SCAN", "PHOTO")) {
            f.db.update("insert into orient_task_alternative(topic_id,task_id,alternative_task_type,prompt_text,answer_hash,enabled,create_by,create_time,update_time) "
                    + "values(100,11,?,?,?,?,42,'2026-01-02 03:04:05','2026-01-02 03:04:05')",
                    type, "authored-" + type, type.equals("PHOTO") ? null : "a".repeat(64), type.equals("QUIZ") ? 0 : 1);
        }
        f.db.update("insert into orient_task_alternative(topic_id,task_id,alternative_task_type,prompt_text,enabled,create_by,create_time,update_time) "
                + "values(100,12,'PHOTO','deleted node',0,42,now(),now()),(101,21,'PHOTO','foreign node',1,77,now(),now()),"
                + "(100,99,'PHOTO','historical orphan',1,42,now(),now())");
        f.db.update("insert into orient_task_attempt(member_id,activity_id,topic_id,play_instance_id,task_id,alternative_task_type,idempotency_key,request_fingerprint,status,create_time,update_time) "
                + "values(81,0,100,'history',11,'QUIZ','history-attempt',?,1,now(),now())", "b".repeat(64));
        f.db.update("insert into orient_task_completion(member_id,activity_id,topic_id,play_instance_id,task_id,completed_via,source_attempt_id,completed_time) "
                + "values(81,0,100,'history',11,'QUIZ',1,now())");
        f.mark();
    }

    private void assertAlternativeCopy() {
        long newId = f.number(f.topicNodes(100).stream().filter(row -> "first".equals(row.get("name"))).findFirst().orElseThrow().get("id"));
        List<Map<String,Object>> old = f.db.queryForList("select alternative_task_type,prompt_text,answer_hash,enabled,create_by,create_time,update_time from orient_task_alternative where task_id=11 order by alternative_task_type");
        assertEquals(old, f.db.queryForList("select alternative_task_type,prompt_text,answer_hash,enabled,create_by,create_time,update_time from orient_task_alternative where task_id=? order by alternative_task_type", newId));
        assertEquals(1, f.db.queryForObject("select count(*) from orient_task_attempt where task_id=11", Integer.class));
        assertEquals(1, f.db.queryForObject("select count(*) from orient_task_completion where task_id=11", Integer.class));
        assertEquals(0, f.db.queryForObject("select count(*) from orient_task_attempt where task_id=?", Integer.class, newId));
        assertEquals(0, f.db.queryForObject("select count(*) from orient_task_completion where task_id=?", Integer.class, newId));
        f.earlier("selectAlternativesForTopicRebuild", "deleteCmsTopicNodeById");
        f.earlier("insertCmsTopicNode", "insertAlternative");
        assertTrue(f.txFacts.stream().allMatch(Boolean::booleanValue));
        f.protectedRowsUnchanged();
    }

    @Test void v1AlternativeRebuildCopiesConfigurationWithoutChangingHistory() {
        seedAlternativeHistory();
        assertFalse(f.orientSwitches.altTaskOn());
        f.success("v1-alternative-rebuild", () -> f.update(f.update(false, 1), false));
        assertAlternativeCopy();
    }

    @Test void v2AlternativeRebuildCopiesConfigurationWithoutChangingHistory() {
        seedAlternativeHistory();
        assertFalse(f.orientSwitches.altTaskOn());
        f.success("v2-alternative-rebuild", () -> f.update(f.update(true, 1), true));
        assertAlternativeCopy();
    }

    @Test void forgedOldIdsNeverCopyForeignOrHistoricalAlternativeConfig() {
        seedAlternativeHistory();
        f.db.update("update cms_topic set route_mode='LINEAR',route_graph_json=null where id=100"); f.mark();
        TopicUpdateDTO dto = f.update(true, 1);
        dto.getChapters().get(0).getNodes().get(0).setId(21L);
        dto.getChapters().get(0).getNodes().get(1).setId(99L);
        // The third node's server unlock still refers to removed 11, so remove it explicitly in this scratch fixture.
        f.db.update("update cms_topic_node set unlock_after_node_id=null where id=13"); f.mark();
        f.success("untrusted-old-ids", () -> f.update(dto, true));
        for (Map<String,Object> node : f.topicNodes(100)) {
            assertEquals(0, f.db.queryForObject("select count(*) from orient_task_alternative where task_id=?", Integer.class, node.get("id")));
        }
        f.protectedRowsUnchanged();
    }

    @Test void deletedNodeAlternativeStaysHistoricalAndDoesNotAttachToAnotherNode() {
        seedAlternativeHistory();
        f.db.update("update cms_topic set route_mode='LINEAR',route_graph_json=null where id=100"); f.mark();
        TopicUpdateDTO dto = f.update(true, 1); dto.getChapters().get(0).getNodes().remove(1);
        dto.getChapters().get(0).getBlocks().remove(2);
        f.success("deleted-node-alternative", () -> f.update(dto, true));
        assertAlternativeCopy();
        assertEquals(1, f.db.queryForObject("select count(*) from orient_task_alternative where task_id=12", Integer.class));
        assertEquals(0, f.db.queryForObject("select count(*) from orient_task_alternative where task_id>=20000 and prompt_text='deleted node'", Integer.class));
    }

    @Test void alternativeCopyZeroRowWriteRollsBackBothRebuildPathsAndEarlierCopies() {
        seedAlternativeHistory();
        for (boolean compiled : List.of(false, true)) {
            java.util.concurrent.atomic.AtomicInteger copies = new java.util.concurrent.atomic.AtomicInteger();
            f.zeroAfter = call -> call.is("insertAlternative") && copies.incrementAndGet() == 2;
            assertEquals("替代完成方式重建保存失败", f.failure("alternative-zero-" + compiled,
                    () -> f.update(f.update(compiled, 1), compiled)).getMessage());
            assertEquals(2, copies.get()); f.unchanged();
        }
        f.zeroAfter = call -> false;
        f.failBefore = call -> call.is("selectAlternativesForTopicRebuild");
        f.failure("alternative-read-error", () -> f.update(f.update(true, 1), true)); f.unchanged();
    }

    @Test void alternativeCopyRejectsDestinationOutsideActualNewTopicNodes() {
        seedAlternativeHistory();
        com.chengyinhub.business.mapper.OrientMapper mapper = f.sql.getMapper(com.chengyinhub.business.mapper.OrientMapper.class);
        for (Set<Long> newIds : List.of(Set.of(21L), Set.<Long>of())) {
            com.chengyinhub.business.service.support.TopicAuthoredGraphIds ids =
                    new com.chengyinhub.business.service.support.TopicAuthoredGraphIds(null, List.of(), Map.of(), Map.of(),
                            Map.of(11L, 21L), newIds, Map.of());
            assertEquals("替代完成方式节点重映射失败", f.failure("foreign-destination-" + newIds.size(),
                    () -> f.tx.execute(status -> {
                        org.springframework.test.util.ReflectionTestUtils.invokeMethod(f.facade, "copyRebuiltNodeAlternatives",
                                100L, mapper.selectAlternativesForTopicRebuild(100L), ids);
                        return null;
                    })).getMessage());
            f.unchanged();
        }
    }

    @Test void featureOffMissingAlternativeTableKeepsOriginalCitySave() {
        assertFalse(f.orientSwitches.altTaskOn());
        f.db.execute("rename table orient_task_alternative to orient_task_alternative_unavailable");
        try {
            f.success("off-missing-table", () -> f.update(f.update(true, 1), true));
            assertEquals(-1, f.index("selectAlternativesForTopicRebuild"));
            assertEquals(-1, f.index("insertAlternative"));
        } finally {
            f.db.execute("rename table orient_task_alternative_unavailable to orient_task_alternative");
        }
        assertEquals(3, f.topicNodes(100).size());
        assertTrue(f.topicNodes(100).stream().allMatch(row -> f.number(row.get("id")) >= 20000));
        // Availability is not cached false: the next save must see and preserve the newly available data.
        long nodeId = f.number(f.topicNodes(100).get(0).get("id"));
        f.db.update("insert into orient_task_alternative(topic_id,task_id,alternative_task_type,prompt_text,enabled,create_by,create_time,update_time) "
                + "values(100,?,'PHOTO','after recovery',0,42,now(),now())", nodeId);
        TopicUpdateDTO next = f.update(true, 1);
        for (int i = 0; i < 2; i++) next.getChapters().get(i).setId(f.number(f.topicChapters(100).get(i).get("id")));
        for (int i = 0; i < 3; i++) {
            NodeDTO node = i < 2 ? next.getChapters().get(0).getNodes().get(i) : next.getChapters().get(1).getNodes().get(0);
            node.setId(f.number(f.topicNodes(100).get(i).get("id")));
        }
        f.success("off-table-recovered", () -> f.update(next, true));
        long current = f.number(f.topicNodes(100).get(0).get("id"));
        assertEquals(1, f.db.queryForObject("select count(*) from orient_task_alternative where task_id=? and prompt_text='after recovery'", Integer.class, current));
    }

    @Test void featureOnMissingAlternativeTableRejectsAndRollsBackWholeSave() {
        when(f.orientSwitches.altTaskOn()).thenReturn(true);
        f.db.execute("rename table orient_task_alternative to orient_task_alternative_unavailable");
        try {
            assertEquals("替代完成方式配置表不存在", f.failure("on-missing-table",
                    () -> f.update(f.update(true, 1), true)).getMessage());
            assertEquals(-1, f.index("deleteCmsTopicNodeById"));
            assertEquals(-1, f.index("selectAlternativesForTopicRebuild"));
        } finally {
            f.db.execute("rename table orient_task_alternative_unavailable to orient_task_alternative");
        }
        f.unchanged();
    }

    @Test void v1CreatePersistsLegacyGraphInSubmittedOrder() {
        long id=f.success("create-v1",()->f.create(f.create(false,1),false));
        assertEquals(2,f.topicChapters(id).size());assertEquals(3,f.topicNodes(id).size());
        assertTrue(f.topicChapters(id).stream().allMatch(row -> row.get("blocks")==null));
        assertEquals(List.of("first","second","third"),f.topicNodes(id).stream().map(row -> row.get("name")).toList());
        f.earlier("insertCmsTopicChapter","insertCmsTopicNode");f.earlier("D:xp.autoAllocate","insertCmsCollaborators");
        f.earlier("insertCmsCollaborators","D:ticket.selectOmsTicketList");f.originalRowsUnchanged();
    }

    @Test void v2CreateCommitsOpeningEndingAndAllReferences() throws Exception {
        TopicCreateDTO dto=f.create(true,1);
        ChapterDTO opening=f.chapter(5,"opening",true);opening.setOpening(true);
        ChapterDTO ending=f.chapter(30,"ending",true);ending.setEnding(f.json("{\"when\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":13}]}"));
        ChapterDTO fallback=f.chapter(40,"fallback",true);fallback.setEnding(f.json("{\"fallback\":true}"));
        dto.getChapters().add(0,opening);dto.getChapters().add(ending);dto.getChapters().add(fallback);
        dto.setJourneyStory("{\"schemaVersion\":1,\"stages\":[{\"chapterId\":10}],\"future\":{\"kept\":true}}");
        dto.setRouteMode("BRANCH_GRAPH");dto.setRouteGraphJson(f.graphJson());
        dto.setThemeRuntimeConfig("{\"schemaVersion\":1,\"capacity\":2,\"items\":[{\"id\":\"supply\",\"kind\":\"ITEM\"}]}");
        long id=f.success("full-v2-create",()->f.create(dto,true));
        assertEquals(5,f.topicChapters(id).size());assertEquals(3,f.topicNodes(id).size());
        CmsTopic actual=f.topics.selectCmsTopicById(id);JsonNode story=f.json(actual.getJourneyStoryJson());
        assertEquals(f.number(f.topicChapters(id).get(0).get("id")),story.path("openingChapterId").asLong());
        assertEquals(2,story.path("ending").path("endings").size());assertTrue(story.path("future").path("kept").asBoolean());
        assertTrue(story.path("stages").get(0).path("chapterId").asLong()>=10000);
        assertEquals(3,f.json(actual.getRouteGraphJson()).path("terminalNodeIds").get(0).asLong()-f.number(f.topicNodes(id).get(0).get("id"))+1);
        f.originalRowsUnchanged();
    }

    @Test void syntheticFreeThemesKeepTwoChapterGameAndMediaBindingsAfterRebuild() {
        f.db.update("update cms_member_template set question_img=? where id=701", "/fixture/first-original.jpg");
        f.db.update("insert into cms_member_template(id,member_id,title,status,draft_status,publish_status,check_status,is_sync,del_flag,question_img) "
                + "values(703,42,'second-owned-game',1,1,1,1,0,'0','/fixture/second-original.jpg')");
        f.mark();
        String fixture = "two-chapter";
        for (boolean compiled : List.of(false, true)) {
            TopicCreateDTO create = f.create(compiled, 2);
            create.setName("synthetic-free-" + fixture + "-" + compiled);
            create.getChapters().get(1).getNodes().add(f.node(14L, "fourth"));
            for (int chapter = 0; chapter < 2; chapter++) {
                List<NodeDTO> nodes = create.getChapters().get(chapter).getNodes();
                for (int station = 0; station < nodes.size(); station++) {
                    NodeDTO node = nodes.get(station);
                    node.setTemplateId(chapter == 0 ? 701L : 703L);
                    node.setAddress("fixture-" + chapter + "-" + station);
                    node.setLongitude("121." + (4100 + chapter * 100 + station));
                    node.setLatitude("31." + (2100 + chapter * 100 + station));
                }
                if (compiled) create.getChapters().set(chapter, f.chapter(chapter == 0 ? 10 : 20,
                        chapter == 0 ? "first" : "second", true, nodes.toArray(new NodeDTO[0])));
            }
            long topic = f.success("free-create-" + fixture + "-" + compiled, () -> f.create(create, true));
            List<Map<String,Object>> chapters = f.topicChapters(topic);
            List<Map<String,Object>> nodes = f.topicNodes(topic);
            assertEquals(2, chapters.size());
            assertEquals(4, nodes.size());
            List<Map<String,Object>> before = f.db.queryForList(
                    "select c.name as chapter_name,n.name as node_name,n.address,n.longitude,n.latitude,t.question_img from cms_topic_node n "
                            + "join cms_topic_chapter c on c.id=n.chapter_id and c.topic_id=n.topic_id "
                            + "join cms_member_template t on t.id=n.template_id where n.topic_id=? order by n.address", topic);
            assertEquals(4, before.size());
            for (int chapter = 0; chapter < 2; chapter++) {
                long chapterId = f.number(chapters.get(chapter).get("id"));
                assertEquals(2, nodes.stream().filter(row -> f.number(row.get("chapter_id")) == chapterId).count());
                assertEquals(1, nodes.stream().filter(row -> f.number(row.get("chapter_id")) == chapterId)
                        .map(row -> row.get("template_id")).distinct().count());
            }
            assertEquals(2, nodes.stream().map(row -> row.get("template_id")).distinct().count());
            assertEquals(List.of("/fixture/first-original.jpg", "/fixture/first-original.jpg",
                    "/fixture/second-original.jpg", "/fixture/second-original.jpg"),
                    before.stream().map(row -> row.get("question_img")).toList());
            TopicUpdateDTO update = f.fill(new TopicUpdateDTO(), compiled, 2);
            update.setId(topic);
            update.setName(create.getName());
            update.setChapters(create.getChapters());
            for (int chapter = 0; chapter < 2; chapter++) {
                ChapterDTO dto = update.getChapters().get(chapter);
                dto.setId(f.number(chapters.get(chapter).get("id")));
                for (int station = 0; station < 2; station++) dto.getNodes().get(station)
                        .setId(f.number(nodes.get(chapter * 2 + station).get("id")));
            }
            f.success("free-rebuild-" + fixture + "-" + compiled, () -> f.update(update, true));
            assertEquals(2, f.topicChapters(topic).size());
            assertEquals(4, f.topicNodes(topic).size());
            assertEquals(before, f.db.queryForList(
                    "select c.name as chapter_name,n.name as node_name,n.address,n.longitude,n.latitude,t.question_img from cms_topic_node n "
                            + "join cms_topic_chapter c on c.id=n.chapter_id and c.topic_id=n.topic_id "
                            + "join cms_member_template t on t.id=n.template_id where n.topic_id=? order by n.address", topic));
            assertEquals(2, f.db.queryForObject(
                    "select count(distinct template_id) from cms_topic_node where topic_id=?", Integer.class, topic));
            assertEquals(2, f.db.queryForObject("select product_type from cms_topic where id=?", Integer.class, topic));
            assertEquals(1, f.db.queryForObject("select is_draft from cms_topic where id=?", Integer.class, topic));
            assertEquals(0, f.db.queryForObject(
                    "select count(*) from cms_topic_node where topic_id=? and merchant_member_id is not null", Integer.class, topic));
            f.originalRowsUnchanged();
        }
    }

    @Test void v2FreeExploreWithoutBlocksKeepsLegacyWriter() {
        long id=f.success("free-without-blocks",()->f.create(f.create(false,2),true));
        assertEquals(3,f.topicNodes(id).size());assertTrue(f.topicChapters(id).stream().allMatch(row -> row.get("blocks")==null));f.originalRowsUnchanged();
    }

    @Test void v2FreeExploreWithBlocksUsesCompiledWriter() {
        long id=f.success("free-with-blocks",()->f.create(f.create(true,2),true));
        assertEquals(3,f.topicNodes(id).size());assertTrue(f.topicChapters(id).stream().allMatch(row -> row.get("blocks")!=null));f.originalRowsUnchanged();
    }

    @Test void v1RebuildDeletesFlowBeforeCollaborators() {
        f.success("v1-rebuild",()->f.update(f.update(false,1),false));
        f.earlier("deleteCmsTopicNodeById","deleteCmsCollaboratorsById");f.earlier("deleteCmsTopicChapterById","deleteCmsCollaboratorsById");
        f.earlier("deleteCmsCollaboratorsById","insertCmsTopicChapter");f.earlier("D:xp.autoAllocate","insertCmsCollaborators");
        assertEquals(3,f.topicNodes(100).size());assertTrue(f.topicNodes(100).stream().allMatch(row -> f.number(row.get("id"))>=20000));
    }

    @Test void v2InvalidCompiledReplacementKeepsOldGraphAfterRollback() {
        // Grammar passes the earlier statistics compile; the write-phase template eligibility guard fails.
        f.db.update("update cms_member_template set member_id=77 where id=701");f.mark();
        Throwable error=f.failure("foreign-template-after-collab-delete",()->f.update(f.update(true,1),true));
        assertTrue(error.getMessage().contains("不能引用他人的模板"));assertTrue(f.index("deleteCmsCollaboratorsById")>=0);
        assertEquals(-1,f.index("deleteCmsTopicChapterById"));assertEquals(-1,f.index("deleteCmsTopicNodeById"));f.unchanged();
    }

    @Test void v2CompiledReplacementRebindsEveryIdentity() throws Exception {
        f.success("v2-rebuild",()->f.update(f.update(true,1),true));
        f.earlier("deleteCmsCollaboratorsById","deleteCmsTopicNodeById");f.earlier("deleteCmsTopicNodeById","insertCmsTopicChapter");
        assertEquals(3,f.topicNodes(100).size());
        Map<String,Map<String,Object>> byName=new LinkedHashMap<>();for(Map<String,Object> row:f.topicNodes(100))byName.put(row.get("name").toString(),row);
        assertEquals(0L,f.number(byName.get("second").get("status")));
        assertEquals(byName.get("first").get("id"),byName.get("third").get("unlock_after_node_id"));
        CmsTopic actual=f.topics.selectCmsTopicById(100L);assertEquals(8L,actual.getConfigVersion());
        assertEquals(byName.get("first").get("id"),f.json(actual.getRouteGraphJson()).path("startNodeId").longValue());
        assertTrue(f.json(actual.getJourneyStoryJson()).path("stages").get(0).path("chapterId").asLong()>=10000);
    }

    @Test void v2NonCompiledReplacementKeepsLegacyDeleteOrder() {
        f.db.update("update cms_topic set product_type=2,route_mode='LINEAR',route_graph_json=null where id=100");f.mark();
        f.success("v2-legacy-rebuild",()->f.update(f.update(false,2),true));
        f.earlier("deleteCmsTopicChapterById","deleteCmsCollaboratorsById");f.earlier("deleteCmsCollaboratorsById","insertCmsTopicChapter");
        assertTrue(f.topicChapters(100).stream().allMatch(row -> row.get("blocks")==null));
    }

    @Test void repeatedTemplateUseClonesOnlyOncePerRebuild() {
        f.db.update("update cms_member_template set advanced_config_json=? where id=701","{\"when\":[{\"var\":\"sys.outcome.11\"}]}");f.mark();
        f.success("cloned-sys-node-template",()->f.update(f.update(true,1),true));
        assertEquals(3,f.db.queryForObject("select count(*) from cms_member_template",Integer.class));
        Set<Object> ids=new HashSet<>();for(Map<String,Object> row:f.topicNodes(100))ids.add(row.get("template_id"));
        assertEquals(1,ids.size());assertFalse(ids.contains(701L));
        assertTrue(f.db.queryForObject("select advanced_config_json from cms_member_template where id>702",String.class).contains("sys.outcome.20000"));
    }

    @Test void foreignTemplateRejectsAndRollsBackExistingWrites() {
        f.db.update("update cms_member_template set member_id=77 where id=701");f.mark();
        Throwable error=f.failure("v1-template-reject-after-chapter",()->f.create(f.create(false,1),false));
        assertTrue(error.getMessage().contains("不能引用他人的模板"));assertTrue(f.index("insertCmsTopicChapter")>=0);f.unchanged();
        f.tx.execute(status->{
            f.db.update("update cms_member_template set member_id=42,del_flag='2' where id=701");
            assertTrue(f.failure("deleted-template",()->f.create(f.create(false,1),false)).getMessage().contains("已删除"));
            f.db.update("update cms_member_template set del_flag='0',status=0 where id=701");
            assertTrue(f.failure("unapproved-template",()->f.create(f.create(false,1),false)).getMessage().contains("还在审核中")); // P-15 (89ab0186) 用户可读文案
            status.setRollbackOnly();return null;
        });f.unchanged();
    }

    @Test void privateDraftTemplateKeepsOriginalBundledEligibility() {
        f.db.update("update cms_member_template set status=0,draft_status=0,publish_status=0,check_status=0 where id=701");f.mark();
        TopicCreateDTO simple=f.create(true,1);simple.setSaveDraft(0);simple.setPublishMode("simple");
        Throwable error=f.failure("simple-private-template-rejected",()->f.create(simple,true));
        assertTrue(error.getMessage().contains("还在审核中，审核通过后才能用于发布"), error.getMessage()); // P-15 (89ab0186)f.unchanged();
        long id=f.success("private-template-allowed-in-draft-bundle",()->f.create(f.create(true,1),true));
        assertEquals(3,f.topicNodes(id).size());f.originalRowsUnchanged();
    }

    @Test void omittedStoryKeepsOriginalContentAndRemapsChapterIds() throws Exception {
        f.success("omitted-story",()->f.update(f.update(true,1),true));
        JsonNode story=f.json(f.topics.selectCmsTopicById(100L).getJourneyStoryJson());
        assertEquals(11L,story.path("future").path("nodeId").asLong());
        assertEquals(f.number(f.topicChapters(100).get(0).get("id")),story.path("stages").get(0).path("chapterId").asLong());
    }

    @Test void hiddenAndUnlockRelationshipsSurviveReordering() {
        TopicUpdateDTO missing=f.update(true,1);missing.getChapters().set(0,f.chapter(10,"first",true,f.node(12L,"second")));
        assertTrue(f.failure("missing-unlock",()->f.update(missing,true)).getMessage().contains("前置解锁引用不在本次提交里"));f.unchanged();
        TopicUpdateDTO dto=f.update(true,1);Collections.swap(dto.getChapters().get(0).getNodes(),0,1);
        f.success("hidden-and-unlock",()->f.update(dto,true));
        Map<String,Map<String,Object>> byName=new LinkedHashMap<>();for(Map<String,Object> row:f.topicNodes(100))byName.put(row.get("name").toString(),row);
        assertEquals(0L,f.number(byName.get("second").get("status")));assertEquals(1L,f.number(byName.get("first").get("status")));
        assertEquals(byName.get("first").get("id"),byName.get("third").get("unlock_after_node_id"));
    }

    @Test void runtimeBindingsUseCorrectOldAndCreatedClientMaps() throws Exception {
        ObjectNode advanced=TopicContentGraphMySqlFixture.JSON.createObjectNode().put("schemaVersion",1);
        JsonNode slots=f.json("[{\"key\":\"material\",\"kind\":\"ITEM\",\"label\":\"material\",\"required\":true}]");
        ObjectNode rules=advanced.putObject("interactionRules");rules.set("resourceSlots",slots);
        rules.putArray("resourceCosts").addObject().put("itemId","material").put("quantity",1);
        // Resource costs need a real supported completion kind, not only a resource-slot declaration.
        // The unchanged publication gate still rejects the original template without a proof bridge.
        f.db.update("update cms_member_template set advanced_config_json=? where id=701",advanced.toString());f.mark();
        TopicUpdateDTO dto=f.update(true,1);dto.getChapters().get(1).getNodes().get(0).setId(null);
        dto.setRouteMode("LINEAR");dto.setConfigVersion(7L);
        ObjectNode runtime=TopicContentGraphMySqlFixture.JSON.createObjectNode().put("schemaVersion",1).put("capacity",3);
        runtime.putArray("items").addObject().put("id","supply").put("kind","ITEM");
        ObjectNode bindings=runtime.putObject("templateBindings");
        for(String key:List.of("11","12","client:third")) {
            ObjectNode binding=bindings.putObject(key).put("templateId",701).put("slotDigest",GameplayTemplateRules.slotDigest(slots));
            binding.putObject("values");binding.putObject("items").put("material","supply");
        }
        dto.setThemeRuntimeConfig(runtime.toString());
        TopicUpdateDTO unsupported=f.update(true,1);unsupported.getChapters().get(1).getNodes().get(0).setId(null);
        unsupported.setRouteMode("LINEAR");unsupported.setConfigVersion(7L);unsupported.setThemeRuntimeConfig(runtime.toString());
        assertTrue(f.failure("resource-costs-without-proof",()->f.update(unsupported,true))
                .toString().contains("尚无通用结算完成证明"));f.unchanged();
        // This fixture's nodes have GPS coordinates. Method 5 is the existing GPS completion bridge.
        assertEquals(1,f.db.update("update cms_member_template set validation_method=5 where id=701"));f.mark();
        f.success("runtime-bindings",()->f.update(dto,true));
        JsonNode stored=f.json(f.topics.selectCmsTopicById(100L).getThemeRuntimeConfigJson());
        assertEquals(3,stored.path("templateBindings").size());assertFalse(stored.toString().contains("client:"));
        for(Map<String,Object> row:f.topicNodes(100))assertTrue(stored.path("templateBindings").has(row.get("id").toString()));
    }

    @Test void duplicateIdentityRejectsAtOriginalPoint() {
        TopicCreateDTO dto=f.create(false,1);dto.getChapters().get(0).getNodes().get(1).setClientNodeKey("first");
        Throwable error=f.failure("duplicate-client-key",()->f.create(dto,false));
        assertTrue(error.getMessage().contains("clientNodeKey 重复"));assertTrue(f.index("insertCmsTopicNode")>=0);f.unchanged();
        TopicCreateDTO ids=f.create(false,1);ids.getChapters().get(0).getNodes().get(1).setId(11L);
        assertTrue(f.failure("duplicate-old-id",()->f.create(ids,false)).getMessage().contains("节点 id 重复"));f.unchanged();
        TopicCreateDTO longKey=f.create(false,1);longKey.getChapters().get(0).getNodes().get(0).setClientNodeKey("x".repeat(65));
        assertTrue(f.failure("long-client-key",()->f.create(longKey,false)).getMessage().contains("最多 64 位"));f.unchanged();
    }

    @Test void outcomesReaderKeepsAdvancedPreferenceAndDefaultBranches() throws Exception {
        f.db.update("update cms_topic_node set status=1 where id=12");
        f.db.update("update cms_member_template set advanced_config_json=? where id=701","{\"branch\":{\"enabled\":true,\"steps\":[{\"terminal\":true,\"outcomeCode\":\"PASS\"}]},\"diceRoll\":{\"enabled\":true,\"mode\":\"d20\"}}");f.mark();
        Object advanced=f.success("advanced-precedence",f::outcomes);assertTrue(advanced.toString().contains("ADVANCED_RESULT:PASS"));assertFalse(advanced.toString().contains("D20_SUCCESS"));
        // Keep all original protected rows intact: alternate reader configurations live in the same rollback-only transaction.
        f.tx.execute(status -> {
            f.db.update("update cms_member_template set advanced_config_json=? where id=701","{\"diceRoll\":{\"enabled\":true,\"mode\":\"d20\"}}");
            Object d20=f.success("d20-reader",f::outcomes);assertTrue(d20.toString().contains("D20_SUCCESS"));
            f.db.update("update cms_member_template set advanced_config_json=null,validation_method=?,preference_json=? where id=701",ValidationMethod.PREFERENCE_GROUP,"{\"results\":{\"LEFT\":{},\"RIGHT\":{}}}");
            Object preference=f.success("preference-reader",f::outcomes);assertTrue(preference.toString().contains("PREFERENCE_RESULT:LEFT"));
            f.db.update("update cms_member_template set validation_method=1 where id=701");
            Object plain=f.success("default-reader",f::outcomes);assertTrue(plain.toString().contains("CHOICE:COMPLETED"));
            f.db.update("update cms_member_template set advanced_config_json='broken' where id=701");
            f.failure("invalid-reader-json",f::outcomes);status.setRollbackOnly();return null;
        });
        f.unchanged();
    }

    @Test void chapterGuardPreservesRevshareShortCircuitReads() {
        ChapterDTO dto=f.chapter(10,"guard",false);f.success("perk-no-owner-read",()->{f.guardCall("assertChapterShapeForTopic",dto,100L);return "OK";});
        assertEquals(-1,f.index("selectCmsTopicById"));assertEquals(-1,f.index("ProductRoleMapper"));assertEquals(-1,f.index("D:category"));
        dto.setTermsMode("REVSHARE");dto.setMaxPerHeadFee(new BigDecimal("5.00"));
        Throwable error=f.failure("revshare-owner-read",()->f.guardCall("assertChapterShapeForTopic",dto,100L));
        assertTrue(error.getMessage().contains("归属俱乐部"));f.earlier("selectCmsTopicById","ProductRoleMapper");f.unchanged();
    }

    @Test void chapterGuardKeepsAudioRecruitmentAndMethodOrder() {
        ChapterDTO dto=f.chapter(10,"guard",false);dto.setAudioUrl("x".repeat(513));
        assertTrue(f.failure("audio-first",()->f.guardCall("assertChapterShapeForTopic",dto,100L)).getMessage().contains("512"));
        assertEquals(-1,f.index("D:category"));
        dto.setAudioUrl(null);dto.setRecruitEnabled(1);dto.setCategoryId(8);
        assertTrue(f.failure("category-reject",()->f.guardCall("assertChapterShapeForTopic",dto,100L)).getMessage().contains("品类"));
        SysCategory category=new SysCategory();category.setStatus(1);category.setType(1L);category.setParentId(0L);category.setDelFlag(0);
        doAnswer(invocation->{f.trace.add("D:category.selectSysCategoryById");return category;}).when(f.categories).selectSysCategoryById(8L);dto.setAllowedValidationMethods("6");
        assertTrue(f.failure("method-after-category",()->f.guardCall("assertChapterShapeForTopic",dto,100L)).getMessage().contains("验证方式"));f.unchanged();
        dto.setAllowedValidationMethods("1,5");dto.setMaxNodeXp(-1);
        assertTrue(f.failure("negative-node-xp",()->f.guardCall("assertChapterShapeForTopic",dto,100L)).getMessage().contains("不能为负"));f.unchanged();
    }

    @Test void chapterGuardKeepsFeeAndPerkShape() {
        f.db.update("update cms_topic set product_type=2,club_id=900 where id=100");f.db.update("update product_role set entity_type='CLUB',entity_id=900 where id=81");f.mark();
        ChapterDTO dto=f.chapter(10,"guard",false);dto.setTermsMode("REVSHARE");dto.setMaxPerHeadFee(new BigDecimal("5.00"));
        f.success("club-direct-fee",()->{f.guardCall("assertChapterShapeForTopic",dto,100L);return "OK";});
        dto.setMaxPerHeadFee(new BigDecimal("0.00"));assertTrue(f.failure("zero-fee",()->f.guardCall("assertChapterShapeForTopic",dto,100L)).getMessage().contains("正数"));
        dto.setMaxPerHeadFee(new BigDecimal("1.001"));assertTrue(f.failure("fee-scale",()->f.guardCall("assertChapterShapeForTopic",dto,100L)).getMessage().contains("两位"));
        dto.setTermsMode("PERK");dto.setMaxPerHeadFee(new BigDecimal("1.00"));assertTrue(f.failure("perk-with-fee",()->f.guardCall("assertChapterShapeForTopic",dto,100L)).getMessage().contains("仅计酬"));
        dto.setMaxPerHeadFee(null);dto.setPerkMinValue(BigDecimal.ZERO);assertTrue(f.failure("zero-perk",()->f.guardCall("assertChapterShapeForTopic",dto,100L)).getMessage().contains("大于0"));f.unchanged();
    }

    @Test void invalidGeneratedIdentityRejectsAndRollsBack() {
        for(String method:List.of("insertCmsTopicChapter","insertCmsTopicNode")) {
            f.zeroAfter=call->call.is(method);
            Throwable error=f.failure(method,()->f.create(f.create(false,1),false));
            assertTrue(error.getMessage().contains("未生成有效"));f.unchanged();
        }
        f.zeroAfter=call->false;
        for(String method:List.of("insertCmsTopicChapter","insertCmsTopicNode")) {
            f.clearIdentityAfter=call->call.is(method);
            assertTrue(f.failure(method+"-missing-id",()->f.create(f.create(false,1),false)).getMessage().contains("未生成有效"));f.unchanged();
        }
    }

    @Test void secondChapterFailureRestoresOriginalGraphAndCollaborators() {
        f.failBefore=call->call.is("insertCmsTopicChapter")&&++f.inserts==2;
        f.failure("second-chapter",()->f.update(f.update(true,1),true));assertTrue(f.index("deleteCmsCollaboratorsById")>=0);f.unchanged();
    }

    @Test void blocksUpdateFailureRestoresDeletedAndInsertedRows() {
        f.failBefore=call->call.is("updateCmsTopicChapter")&&call.parameter() instanceof CmsTopicChapter c&&c.getBlocksJson()!=null;
        f.failure("blocks-update",()->f.update(f.update(true,1),true));assertTrue(f.index("insertCmsTopicNode")>=0);f.unchanged();
    }

    @Test void templatePatchFailureRestoresCloneAndNodeBinding() {
        f.db.update("update cms_member_template set advanced_config_json=? where id=701","{\"when\":[{\"var\":\"sys.outcome.11\"}]}");f.mark();
        for(String method:List.of("updateCmsMemberTemplate","updateCmsTopicNode")) {
            f.failBefore=call->call.is(method)&&(!method.equals("updateCmsTopicNode")
                    ||call.parameter() instanceof CmsTopicNode node&&node.getTemplateId()!=null);
            f.failure(method,()->f.update(f.update(true,1),true));f.unchanged();
        }
    }

    @Test void storyPatchFailureRollsBackWholeReplacement() {
        f.failBefore=call->call.is("updateCmsTopic")&&call.parameter() instanceof CmsTopic t&&t.getName()==null&&t.getJourneyStoryJson()!=null;
        f.failure("story-update",()->f.update(f.update(true,1),true));f.unchanged();
    }

    @Test void runtimePatchFailureKeepsCreateAndRebuildMessages() {
        f.failBefore=call->call.is("updateCmsTopic")&&call.parameter() instanceof CmsTopic t&&t.getName()==null&&t.getThemeRuntimeConfigJson()!=null;
        TopicCreateDTO create=f.create(false,1);create.setThemeRuntimeConfig("{\"schemaVersion\":1,\"capacity\":2}");
        assertTrue(f.failure("create-runtime",()->f.create(create,false)).getMessage().contains("fixture-mapper:updateCmsTopic"));f.unchanged();
        // Returning zero exercises the original distinct business messages rather than a mapper exception.
        f.failBefore=call->false;f.zeroAfter=call->call.is("updateCmsTopic")&&call.parameter() instanceof CmsTopic t&&t.getName()==null&&t.getThemeRuntimeConfigJson()!=null;
        assertEquals("主题运行配置保存失败",f.failure("create-runtime-zero",()->f.create(create,false)).getMessage());f.unchanged();
        TopicUpdateDTO update=f.update(true,1);update.setThemeRuntimeConfig("{\"schemaVersion\":1,\"capacity\":2}");
        assertEquals("主题运行配置重映射失败",f.failure("rebuild-runtime-zero",()->f.update(update,true)).getMessage());f.unchanged();
    }

    @Test void routeVersionConflictRollsBackAllEarlierWrites() {
        TopicUpdateDTO dto=f.update(true,1);dto.setRouteMode("BRANCH_GRAPH");dto.setRouteGraphJson(f.graphJson());dto.setConfigVersion(6L);
        assertTrue(f.failure("expected-version",()->f.update(dto,true)).getMessage().contains("路线配置已更新"));f.unchanged();
        dto.setConfigVersion(7L);f.zeroAfter=call->call.is("updateRouteDefinition");
        assertTrue(f.failure("route-cas-zero",()->f.update(dto,true)).getMessage().contains("路线配置已更新"));f.unchanged();
    }

    @Test void interactionCompilerKeepsWriteAndNoopBehavior() {
        f.success("real-compiler-null-noop",()->{f.graphCall("persistTemplateInteractionRules",100L);return "OK";});
        assertTrue(f.statements.isEmpty());
        f.trace.add("SETUP:existing-runtime");
        f.db.update("update cms_topic set theme_runtime_config_json=? where id=100","{\"schemaVersion\":1,\"capacity\":2}");
        f.success("real-compiler-equal-noop",()->{f.graphCall("persistTemplateInteractionRules",100L);return "OK";});assertTrue(f.statements.isEmpty());
        // A controlled runtime return isolates the writer's changed-value/rowcount branch; the first two scenarios run the real compiler.
        var controlled=spy(f.runtime);doReturn("{\"schemaVersion\":1,\"capacity\":3}").when(controlled).compileTemplateRules(any());f.runtime(controlled);
        f.success("controlled-compiler-change",()->{f.graphCall("persistTemplateInteractionRules",100L);return "OK";});
        assertTrue(f.topics.selectCmsTopicById(100L).getThemeRuntimeConfigJson().contains("3"));
        f.zeroAfter=call->call.is("updateCmsTopic");
        doReturn("{\"schemaVersion\":1,\"capacity\":4}").when(controlled).compileTemplateRules(any());
        assertEquals("模板使用规则保存失败",f.failure("interaction-zero",()->f.graphCall("persistTemplateInteractionRules",100L)).getMessage());
        assertTrue(f.topics.selectCmsTopicById(100L).getThemeRuntimeConfigJson().contains("3"));
        f.runtime(null);f.success("runtime-dependency-absent",()->{f.graphCall("persistTemplateInteractionRules",100L);return "OK";});
    }

    @Test void xpFailureStillContinuesAtOriginalPosition() {
        f.boundaryFailure="xp";long id=f.success("xp-failure-continues",()->f.create(f.create(false,1),false));
        assertEquals(3,f.topicNodes(id).size());f.earlier("D:xp.autoAllocate","insertCmsCollaborators");f.earlier("insertCmsCollaborators","D:ticket.selectOmsTicketList");f.originalRowsUnchanged();
    }

    @Test void ticketParticipantFailureRollsBackCompletedGraph() {
        f.boundaryFailure="ticket";f.failure("ticket-participant",()->f.update(f.update(true,1),true));
        f.earlier("insertCmsTopicNode","THROW:ticket");f.earlier("insertCmsCollaborators","THROW:ticket");f.unchanged();
    }

    @Test void reviewParticipantFailureRollsBackCompletedV2Graph() {
        f.boundaryFailure="review";TopicUpdateDTO dto=f.update(true,1);dto.setSaveDraft(0);
        assertEquals("fixture-review",f.failure("review-participant",()->f.update(dto,true)).getMessage());
        f.earlier("D:ticket.selectOmsTicketList","D:bundle.freezeForSubmission");f.earlier("insertCmsTopicNode","THROW:review");f.unchanged();
    }

    @Test void draftSaveKeepsVisibilityAndExternalActionsUnchanged() {
        long id=f.success("private-draft-create",()->f.create(f.create(true,1),true));
        assertEquals(1,f.topics.selectTopicIsDraft(id));CmsTopic topic=f.topics.selectCmsTopicById(id);
        assertEquals(0,topic.getStatus());assertEquals(0,topic.getUserStatus());assertEquals(0L,topic.getPublishedConfigVersion());
        assertEquals(-1,f.index("D:bundle.freezeForSubmission"));assertEquals(-1,f.index("publishRouteSnapshot"));
        f.noExternalActions();f.originalRowsUnchanged();
        f.success("private-draft-edit",()->f.update(f.update(true,1),true));
        assertEquals(1,f.topics.selectTopicIsDraft(100L));assertEquals(0,f.topics.selectCmsTopicById(100L).getStatus());
        assertEquals(-1,f.index("D:bundle.freezeForSubmission"));assertEquals(-1,f.index("publishRouteSnapshot"));f.noExternalActions();
    }

    @Test void callerRollbackAfterFullGraphRestoresAllContent() {
        f.failure("caller-rollback",()->f.tx.execute(status->{f.update(f.update(true,1),true);throw new IllegalStateException("fixture-caller");}));
        assertFalse(f.txFacts.isEmpty());assertTrue(f.txFacts.stream().allMatch(Boolean::booleanValue));f.unchanged();
    }
}
