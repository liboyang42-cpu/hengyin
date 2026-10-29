package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit -Dtest opt-in. Missing scratch connection configuration is a hard failure; no skip/H2 fallback. */
public class TopicContentCopyMySqlIT {
    private TopicContentCopyMySqlFixture f;
    private String testName;
    private Object outcome = "UNSET";

    @BeforeEach void setup(TestInfo info) throws Exception {
        testName = info.getTestMethod().orElseThrow().getName();
        f = new TopicContentCopyMySqlFixture();
        f.open();
    }

    @AfterEach void cleanup() throws Exception {
        if (f != null) try {
            if (f.before != null) f.receipt(testName, outcome);
        } finally { f.close(); }
    }

    @Test void completeCopyTypeThreeCommitsWholeContentGraphAndMemberSlot() throws Exception {
        long id = f.copy(3, TopicContentCopyMySqlFixture.TARGET);
        assertFullGraph(id, TopicContentCopyMySqlFixture.TARGET, "MEMBER", 2);
        f.assertOriginalUnchanged();
        for (String mapper : List.of("CmsTopicMapper", "CmsTopicChapterMapper", "CmsTopicNodeMapper",
                "CmsMemberTemplateMapper", "NpcProfileMapper", "CmsCollaboratorsMapper", "ProductRoleMapper"))
            assertTrue(f.writes.statements.stream().anyMatch(s -> s.contains("." + mapper + ".")), mapper);
        outcome = id;
    }

    @Test void sameOwnerKeepsCompletionRuleAndSharesOnlyUnreferencedTemplate() throws Exception {
        long id = f.copy(3, TopicContentCopyMySqlFixture.OWNER);
        CmsTopic copy = f.topics.selectCmsTopicById(id);
        assertEquals(f.json(f.topics.selectCmsTopicById(100L).getCompleteRuleJson()), f.json(copy.getCompleteRuleJson()));
        Map<String, Map<String, Object>> nodes = named(f.nodes(id));
        assertNotEquals(701, f.number(nodes.get("node-11").get("template_id")), "NPC references require independent same-owner template");
        assertEquals(702, f.number(nodes.get("node-13").get("template_id")), "unreferenced same-owner template remains shared");
        assertEquals(3, f.db.queryForObject("select count(*) from cms_member_template", Integer.class));
        f.assertOriginalUnchanged();
        outcome = id;
    }

    @Test void officialCopyUsesPlatformSlotAndDoesNotInferSourceFromMemberId() throws Exception {
        f.db.update("update product_role set entity_type='PLATFORM',entity_id=null where topic_id=100");
        f.before = f.snapshot(); // source member_id is deliberately still 1000
        long id = f.service.copyOfficialCmsTopic(100L, Map.of("copyType", 3));
        assertFullGraph(id, 100000L, "PLATFORM", 2);
        f.assertOriginalUnchanged();
        outcome = id;
    }

    @Test void officialMemberParameterRejectsBeforeAnyWrite() {
        rejected(() -> f.service.copyOfficialCmsTopic(100L, Map.of("memberId", 2000L, "copyType", 3)));
        assertTrue(f.writes.statements.isEmpty());
    }

    @Test void nonPlatformOfficialSourceRejectsBeforeAnyWrite() {
        rejected(() -> f.service.copyOfficialCmsTopic(100L, Map.of("copyType", 3)));
        assertTrue(f.writes.statements.isEmpty());
    }

    @Test void missingSourceRejectsWithoutContentWrites() {
        rejected(() -> f.service.copyCmsTopic(999L, Map.of("copyType", 3, "memberId", 2000L)));
        assertTrue(f.writes.statements.isEmpty());
    }

    @Test void deletedSourceRejectsWithoutContentWrites() {
        f.db.update("update cms_topic set del_flag=2 where id=100"); f.before = f.snapshot();
        rejected(() -> f.copy(3, 2000L));
        assertTrue(f.writes.statements.isEmpty());
    }

    @Test void zeroTopicInsertRejectsBeforeChildren() {
        f.writes.zero = u -> u.statement().endsWith("CmsTopicMapper.insertCmsTopic");
        rejected(() -> f.copy(3, 2000L));
        assertEquals(1, f.writes.statements.size());
    }

    @Test void secondChapterFailureRollsBackTopicSlotAndBothChapters() {
        f.writes.fail = u -> u.statement().endsWith("CmsTopicChapterMapper.insertCmsTopicChapter")
                && f.db.queryForObject("select count(*) from cms_topic_chapter where topic_id<>100", Integer.class) == 2;
        rejected(() -> f.copy(3, 2000L));
    }

    @Test void nodeFailureRollsBackClonedTemplateAndAllEarlierRows() {
        f.writes.fail = u -> u.statement().endsWith("CmsTopicNodeMapper.insertCmsTopicNode");
        rejected(() -> f.copy(3, 2000L));
        assertTrue(f.writes.statements.stream().anyMatch(s -> s.endsWith("CmsMemberTemplateMapper.insertCmsMemberTemplate")));
    }

    @Test void npcFailureRollsBackNodeTemplateAndContentSlot() {
        f.writes.fail = u -> u.statement().endsWith("NpcProfileMapper.insertNodeNpc");
        rejected(() -> f.copy(3, 2000L));
    }

    @Test void runtimePatchFailureRollsBackCompleteChildren() {
        f.writes.fail = u -> u.statement().endsWith("CmsTopicMapper.updateCmsTopic")
                && u.parameter() instanceof CmsTopic topic && topic.getThemeRuntimeConfigJson() != null;
        rejected(() -> f.copy(3, 2000L));
    }

    @Test void graphPatchFailureRollsBackChildrenAndRuntimePatch() {
        f.writes.fail = u -> u.statement().endsWith("CmsTopicMapper.updateRouteDefinition");
        rejected(() -> f.copy(3, 2000L));
    }

    @Test void blockPatchFailureRollsBackCopiedGraph() {
        f.writes.fail = u -> u.statement().endsWith("CmsTopicChapterMapper.updateCmsTopicChapter");
        rejected(() -> f.copy(3, 2000L));
    }

    @Test void storyPatchFailureRollsBackEveryContentTable() {
        f.writes.fail = u -> u.statement().endsWith("CmsTopicMapper.updateCmsTopic")
                && u.parameter() instanceof CmsTopic topic && topic.getJourneyStoryJson() != null;
        rejected(() -> f.copy(3, 2000L));
    }

    @Test void officialMissingPersistedSlotRollsBackInsertedTopic() {
        f.db.update("update product_role set entity_type='PLATFORM',entity_id=null where topic_id=100"); f.before = f.snapshot();
        f.writes.zero = u -> u.statement().endsWith("ProductRoleMapper.insertProductRole");
        rejected(() -> f.service.copyOfficialCmsTopic(100L, Map.of("copyType", 3)));
    }

    @Test void memberSlotZeroRowsRemainsAnExplicitUnfixedLegacyRisk() throws Exception {
        f.writes.zero = u -> u.statement().endsWith("ProductRoleMapper.insertProductRole");
        long id = f.copy(3, 2000L);
        assertEquals(3, f.nodes(id).size());
        assertNull(f.roles.activeContentSlot(id), "mechanical move must not quietly tighten the MEMBER readback rule");
        f.assertOriginalUnchanged(); outcome = id;
    }

    @Test void collaboratorZeroRowsRemainsAnExplicitUnfixedLegacyRisk() throws Exception {
        f.writes.zero = u -> u.statement().endsWith("CmsCollaboratorsMapper.insertCmsCollaborators");
        long id = f.copy(3, 2000L);
        assertEquals(3, f.nodes(id).size());
        assertEquals(0, f.db.queryForObject("select count(*) from cms_collaborators where data_id=?", Integer.class, id));
        f.assertOriginalUnchanged(); outcome = id;
    }

    @Test void defaultBasicCopyKeepsExistingDefaultsWithoutInventingContentSlot() {
        long id = f.service.copyCmsTopic(100L, null);
        CmsTopic copy = f.topics.selectCmsTopicById(id);
        assertEquals(Long.valueOf(100000), copy.getMemberId());
        assertEquals("LINEAR", copy.getRouteMode());
        assertEquals(0, f.nodes(id).size()); assertEquals(0, f.chapters(id).size());
        assertNull(f.roles.activeContentSlot(id), "known no-owner legacy path is not fixed in this move");
        f.assertOriginalUnchanged(); outcome = id;
    }

    @Test void malformedCopyTypeKeepsExistingBasicConversion() {
        long id = f.service.copyCmsTopic(100L, Map.of("copyType", "bad", "memberId", 2000L));
        assertEquals(0, f.nodes(id).size()); assertNotNull(f.roles.activeContentSlot(id));
        f.assertOriginalUnchanged(); outcome = id;
    }

    @Test void callerRollbackAfterFullCopyRollsBackWholeGraph() {
        rejected(() -> f.tx.execute(status -> {
            long id = f.copy(3, 2000L);
            assertEquals(3, f.nodes(id).size());
            throw new IllegalStateException("fixture caller rollback");
        }));
    }

    @Test void transferOriginalOfflineFailureRollsBackRealCopyAndClubBinding() {
        f.writes.fail = u -> u.statement().endsWith("CmsTopicMapper.updateCmsTopic")
                && u.parameter() instanceof CmsTopic topic && Long.valueOf(100).equals(topic.getId())
                && Integer.valueOf(0).equals(topic.getUserStatus());
        rejected(() -> f.service.transferToClub(100L, 2000L, 77L));
        assertTrue(f.writes.statements.stream().anyMatch(s -> s.endsWith("NpcProfileMapper.insertNodeNpc")), "must not replace the real copy with a stub");
        verifyNoInteractions(f.square);
    }

    @Test void transferSquareFailureRollsBackRealCopyAndOriginalVisibility() {
        doThrow(new IllegalStateException("fixture square failure")).when(f.square).syncTopicPostVisibility(100L, false);
        rejected(() -> f.service.transferToClub(100L, 2000L, 77L));
        verify(f.square).syncTopicPostVisibility(100L, false);
    }

    @Test void transferSuccessKeepsOuterAlgorithmAndDoesNotCopyCollaborators() {
        long id = f.service.transferToClub(100L, 2000L, 77L);
        assertEquals(Long.valueOf(77), f.topics.selectCmsTopicById(id).getClubId());
        assertEquals(Integer.valueOf(0), f.topics.selectCmsTopicById(100L).getUserStatus());
        assertEquals(3, f.nodes(id).size()); assertEquals(2, f.chapters(id).size());
        assertNotNull(f.roles.activeContentSlot(id));
        assertEquals(0, f.db.queryForObject("select count(*) from cms_collaborators where data_id=?", Integer.class, id));
        verify(f.square).syncTopicPostVisibility(100L, false);
        outcome = id;
    }

    @Test void repeatedCopyCreatesIndependentGraphsRatherThanInventedIdempotency() throws Exception {
        long first = f.copy(3, 2000L); assertFullGraph(first, 2000L, "MEMBER", 2);
        long second = f.copy(3, 2000L); assertNotEquals(first, second);
        Set<Object> ids = new HashSet<>(), codes = new HashSet<>(), templates = new HashSet<>();
        for (long topic : new long[]{first, second}) for (Map<String, Object> node : f.nodes(topic)) {
            assertTrue(ids.add(node.get("id"))); assertTrue(codes.add(node.get("checkin_code")));
            templates.add(node.get("template_id"));
        }
        assertEquals(6, ids.size()); assertEquals(4, templates.size());
        f.assertOriginalUnchanged(); outcome = List.of(first, second);
    }

    @Test void foreignFlatCouponInWholeTopicCopyRollsBackTheCompleteGraph() throws Exception {
        f.installCouponOwnership();
        f.db.update("insert into sms_coupon(id,member_id,del_flag) values(901,1000,0)");
        f.db.update("update cms_member_template set coupon_id=901 where id=701"); f.before=f.snapshot();
        rejected(()->f.copy(3,TopicContentCopyMySqlFixture.TARGET));
        assertEquals(1,f.db.queryForObject("select count(*) from sms_coupon where id=901 and member_id=1000",Integer.class));
    }

    @Test void foreignConditionalCouponCannotBypassTheCopyFunnelAndItsTransaction() throws Exception {
        f.installCouponOwnership();
        f.db.update("insert into sms_coupon(id,member_id,del_flag) values(902,1000,0)");
        f.db.update("update cms_member_template set reward_rules_json=? where id=701", "[{\"outcomeCode\":\"COMPLETED\",\"couponId\":902}]");
        f.before=f.snapshot(); rejected(()->f.copy(3,TopicContentCopyMySqlFixture.TARGET));
        assertEquals(1,f.db.queryForObject("select count(*) from sms_coupon where id=902 and member_id=1000",Integer.class));
    }

    @Test void sameOwnerForcedNpcCopyPreservesItsOwnedCouponAndOriginalLibraryLineage() throws Exception {
        f.installCouponOwnership();
        f.db.update("insert into sms_coupon(id,member_id,del_flag) values(903,1000,0)");
        String rules="[{\"outcomeCode\":\"COMPLETED\",\"couponId\":903}]";
        f.db.update("update cms_member_template set coupon_id=903,reward_rules_json=? where id=701",rules); f.before=f.snapshot();
        long topic=f.copy(3,TopicContentCopyMySqlFixture.OWNER);
        long template=f.number(named(f.nodes(topic)).get("node-11").get("template_id"));
        assertNotEquals(701,template);var copy=f.templates.selectCmsMemberTemplateById(template);
        assertEquals(TopicContentCopyMySqlFixture.OWNER,copy.getMemberId());assertEquals(301L,copy.getOriginalTemplateId());
        assertEquals(903L,copy.getCouponId());assertEquals(f.json(rules),f.json(copy.getRewardRulesJson()));
        f.assertOriginalUnchanged();assertEquals(f.before.get("sms_coupon"),f.snapshot().get("sms_coupon"));outcome=topic;
    }

    private void rejected(Executable command) {
        RuntimeException exception = assertThrows(RuntimeException.class, command);
        Throwable root = exception;
        while (root.getCause() != null) root = root.getCause();
        outcome = Map.of("exception", root.getClass().getName(), "message", Objects.toString(root.getMessage(), ""));
        f.assertUnchanged();
    }

    private Map<String, Map<String, Object>> named(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) assertNull(result.put(row.get("name").toString(), row));
        return result;
    }

    private void assertFullGraph(long id, long owner, String slotType, int collaborators) throws Exception {
        CmsTopic copy = f.topics.selectCmsTopicById(id);
        assertNotEquals(100L, id); assertEquals(Long.valueOf(owner), copy.getMemberId());
        assertEquals(Integer.valueOf(0), copy.getStatus()); assertEquals(Integer.valueOf(0), copy.getUserStatus());
        assertEquals("BRANCH_GRAPH", copy.getRouteMode()); assertEquals(Long.valueOf(1), copy.getConfigVersion());
        assertEquals(Long.valueOf(0), copy.getPublishedConfigVersion());
        assertNull(copy.getPublishedRouteGraphJson()); assertNull(copy.getPublishedThemeRuntimeConfigJson());
        assertNull(copy.getSelfPlayPrice()); assertNull(copy.getFeePlatformRate());
        assertNull(copy.getCompleteRuleJson()); // these fixtures cross the original member owner
        assertEquals(slotType, f.roles.activeContentSlot(id).getEntityType());
        assertEquals("PLATFORM".equals(slotType) ? null : Long.valueOf(owner), f.roles.activeContentSlot(id).getEntityId());
        Map<String, Map<String, Object>> chapters = named(f.chapters(id)), nodes = named(f.nodes(id));
        assertEquals(2, chapters.size()); assertEquals(3, nodes.size());
        assertEquals("PERK", chapters.get("two").get("terms_mode")); assertNull(chapters.get("two").get("max_per_head_fee"));
        assertEquals(0, f.number(chapters.get("one").get("circle_enabled")));
        long n11 = f.number(nodes.get("node-11").get("id")), n12 = f.number(nodes.get("node-12").get("id"));
        long n13 = f.number(nodes.get("node-13").get("id"));
        assertEquals(n11, f.number(nodes.get("node-12").get("unlock_after_node_id")));
        assertEquals(n12, f.number(nodes.get("node-13").get("unlock_after_node_id")));
        assertEquals(f.number(chapters.get("one").get("id")), f.number(nodes.get("node-11").get("chapter_id")));
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> node : nodes.values()) {
            String code = node.get("checkin_code").toString();
            assertTrue(code.matches("[a-f0-9]{32}")); assertTrue(codes.add(code)); assertEquals(7, f.number(node.get("xp_value")));
        }
        long template = f.number(nodes.get("node-11").get("template_id"));
        assertNotEquals(701, template); assertEquals(template, f.number(nodes.get("node-12").get("template_id")));
        Map<String, Object> templateRow = f.db.queryForMap("select * from cms_member_template where id=?", template);
        assertEquals(owner, f.number(templateRow.get("member_id"))); assertEquals(301, f.number(templateRow.get("original_template_id")));
        assertEquals(0, f.number(templateRow.get("is_sync"))); assertEquals(1, f.number(templateRow.get("draft_status")));
        Map<String, Object> npc = f.db.queryForMap("select * from npc_profile where scope_type=4 and scope_id=?", n11);
        long npcId = f.number(npc.get("id")); assertNotEquals(501, npcId);
        assertEquals(2, f.number(npc.get("voice_status"))); assertEquals("fixture-ready-voice", npc.get("voice_id"));
        Map<String, Object> pending = f.db.queryForMap("select * from npc_profile where scope_type=4 and scope_id=?", n12);
        assertEquals(0, f.number(pending.get("voice_status"))); assertNull(pending.get("voice_id"));
        JsonNode advanced = f.json(templateRow.get("advanced_config_json"));
        assertEquals(npcId, advanced.path("enter").path("npcId").asLong()); assertTrue(advanced.path("outcome").path("npcId").isNull());
        JsonNode graph = f.json(copy.getRouteGraphJson()); assertEquals(n11, graph.path("startNodeId").asLong());
        assertEquals(n13, graph.path("terminalNodeIds").get(0).asLong());
        JsonNode runtime = f.json(copy.getThemeRuntimeConfigJson());
        assertEquals(template, runtime.path("templateBindings").path(Long.toString(n11)).path("templateId").asLong());
        assertEquals(f.json(f.before.get("cms_topic").get(0).get("theme_runtime_config_json")).path("templateBindings").path("11").path("slotDigest"),
                runtime.path("templateBindings").path(Long.toString(n11)).path("slotDigest"));
        JsonNode blocks = f.json(chapters.get("one").get("blocks")); assertEquals(3, blocks.size());
        assertEquals(n11, blocks.get(1).path("nodeId").asLong()); assertEquals(npcId, blocks.get(2).path("npcId").asLong());
        JsonNode story = f.json(copy.getJourneyStoryJson()); assertEquals(npcId, story.path("call").path("npcId").asLong());
        assertEquals(f.number(chapters.get("one").get("id")), story.path("stages").get(0).path("chapterId").asLong());
        assertTrue(story.path("ending").path("epilogues").get(1).path("npcId").isNull());
        assertEquals(collaborators, f.db.queryForObject("select count(*) from cms_collaborators where data_id=?", Integer.class, id));
    }
}
