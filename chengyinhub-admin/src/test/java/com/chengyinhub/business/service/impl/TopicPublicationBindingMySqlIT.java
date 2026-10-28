package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.TopicBundleReviewService;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;

import java.time.Clock;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL/current mapper reads and the real bundle/compiler, using the existing isolated
 * content-graph fixture. These cases do not replace full AuditTask/HTTP/moderation acceptance. */
class TopicPublicationBindingMySqlIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void approvedTemplateRestoredAfterTheReviewReadViewIsFrozenFromItsCurrentLockedVersion() throws Exception {
        try (var f = fixture()) {
            TopicBundleReviewService bundle = bundle(f);
            String submitted = bundle.freezeForSubmission(100L, 42L).getSnapshotJson();
            f.db.update("update cms_member_template set question_answer='B' where id=701");
            ExecutorService editor = Executors.newSingleThreadExecutor();
            try {
                String published = f.tx.execute(tx -> {
                    assertEquals("B", f.templateMapper.selectCmsMemberTemplateById(701L).getQuestionAnswer(),
                            "establish the old RR view before the editor restores approved A");
                    restoreOnAnotherConnection(f, editor);
                    bundle.applyDecision(100L, 42L, submitted, true);
                    // No bundled-draft UPDATE repairs the old read view: this was already approved.
                    ((TopicPublicationService) f.get("topicPublicationService")).publishApprovedRouteSnapshot(100L);
                    return f.topics.selectCmsTopicByIdForUpdate(100L).getPublishedRouteGraphJson();
                });
                assertEquals("A", answers(published).path("11").path("questionAnswer").asText());
                assertEquals("A", answers(published).path("13").path("questionAnswer").asText());
                assertFalse(answers(published).has("12"), "the inactive node stays outside the runtime snapshot");
                assertEquals(JSON.readTree(published), JSON.readTree(f.topics.selectCmsTopicById(100L).getPublishedRouteGraphJson()));
                assertEquals(1L, f.db.queryForObject("select published_config_version from cms_topic where id=100", Long.class));
            } finally {
                editor.shutdownNow();
                assertTrue(editor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void submissionInsideAnOlderReadViewUsesTheSameCurrentRowsAsPublication() throws Exception {
        try (var f = fixture()) {
            String expected = bundle(f).freezeForSubmission(100L, 42L).getSnapshotJson();
            f.db.update("update cms_member_template set question_answer='B' where id=701");
            ExecutorService editor = Executors.newSingleThreadExecutor();
            try {
                String submitted = f.tx.execute(tx -> {
                    assertEquals("B", f.templateMapper.selectCmsMemberTemplateById(701L).getQuestionAnswer());
                    restoreOnAnotherConnection(f, editor);
                    return bundle(f).freezeForSubmission(100L, 42L).getSnapshotJson();
                });
                assertEquals(JSON.readTree(expected), JSON.readTree(submitted));
                assertEquals("A", JSON.readTree(submitted).path("bundledTemplates").get(0).path("content").path("questionAnswer").asText());
            } finally {
                editor.shutdownNow();
                assertTrue(editor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void editedTemplateAfterSubmissionIsRejectedBeforeAnySnapshotOrVersionWrite() throws Exception {
        try (var f = fixture()) {
            String submitted = bundle(f).freezeForSubmission(100L, 42L).getSnapshotJson();
            f.db.update("update cms_member_template set question_answer='B' where id=701");
            assertThrows(IllegalStateException.class, () -> f.tx.execute(tx -> {
                bundle(f).applyDecision(100L, 42L, submitted, true);
                String frozen = compiler(f).freezePublication(f.topics.selectCmsTopicByIdForUpdate(100L), null);
                return f.topics.publishRouteSnapshot(100L, frozen, new java.util.Date());
            }));
            assertEquals(0L, f.db.queryForObject("select published_config_version from cms_topic where id=100", Long.class));
            assertNull(f.topics.selectCmsTopicById(100L).getPublishedRouteGraphJson());
            assertEquals("B", f.templateMapper.selectCmsMemberTemplateById(701L).getQuestionAnswer());
        }
    }

    @Test
    void reviewedChildLocksHoldThroughFreezingAndAnEditorCommitsOnlyAfterThePublisher() throws Exception {
        try (var f = fixture()) {
            String submitted = bundle(f).freezeForSubmission(100L, 42L).getSnapshotJson();
            ExecutorService editor = Executors.newSingleThreadExecutor();
            CountDownLatch updateStarted = new CountDownLatch(1);
            @SuppressWarnings("unchecked") Future<Integer>[] update = new Future[1];
            try {
                String frozen = f.tx.execute(tx -> {
                    bundle(f).applyDecision(100L, 42L, submitted, true);
                    update[0] = editor.submit(() -> {
                        updateStarted.countDown();
                        return new JdbcTemplate(f.manager.getDataSource()).update(
                                "update cms_member_template set question_answer='B' where id=701");
                    });
                    try {
                        assertTrue(updateStarted.await(5, TimeUnit.SECONDS));
                        assertThrows(TimeoutException.class, () -> update[0].get(200, TimeUnit.MILLISECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(e);
                    }
                    String snapshot = compiler(f).freezePublication(f.topics.selectCmsTopicByIdForUpdate(100L), null);
                    assertEquals(1, f.topics.publishRouteSnapshot(100L, snapshot, new java.util.Date()));
                    return snapshot;
                });
                assertEquals(1, update[0].get(10, TimeUnit.SECONDS));
                assertEquals("A", answers(frozen).path("11").path("questionAnswer").asText());
                assertEquals("B", f.templateMapper.selectCmsMemberTemplateById(701L).getQuestionAnswer());
                assertEquals("A", answers(f.topics.selectCmsTopicById(100L).getPublishedRouteGraphJson()).path("11").path("questionAnswer").asText());
            } finally {
                editor.shutdownNow();
                assertTrue(editor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void repeatedOpeningOfThePublishedVersionDoesNotReadNewTemplateAnswersOrChapterContent() throws Exception {
        try (var f = fixture()) {
            String published = f.tx.execute(tx -> {
                String snapshot = compiler(f).freezePublication(f.topics.selectCmsTopicByIdForUpdate(100L), null);
                assertEquals(1, f.topics.publishRouteSnapshot(100L, snapshot, new java.util.Date()));
                return snapshot;
            });
            CmsTopic versionOne = f.topics.selectCmsTopicById(100L);
            String original = compiler(f).freezePublishedJourney(versionOne);
            f.db.update("update cms_member_template set question_answer='B' where id=701");
            f.db.update("update cms_topic_chapter set name='edited after publication' where topic_id=100");
            f.db.update("update cms_topic_node set name='edited station' where topic_id=100");
            assertEquals(JSON.readTree(original), JSON.readTree(compiler(f).freezePublishedJourney(versionOne)));
            assertEquals(answers(published), answers(original));
            assertEquals("A", answers(original).path("11").path("questionAnswer").asText());
            assertEquals(1L, f.db.queryForObject("select published_config_version from cms_topic where id=100", Long.class));
        }
    }

    @Test
    void missingReferencedCurrentTemplateRollsBackWithoutPublishingAPartialPackage() throws Exception {
        try (var f = fixture()) {
            f.db.update("delete from cms_member_template where id=701");
            assertThrows(IllegalStateException.class, () -> f.tx.execute(tx -> {
                String snapshot = compiler(f).freezePublication(f.topics.selectCmsTopicByIdForUpdate(100L), null);
                return f.topics.publishRouteSnapshot(100L, snapshot, new java.util.Date());
            }));
            assertEquals(0L, f.db.queryForObject("select published_config_version from cms_topic where id=100", Long.class));
            assertNull(f.topics.selectCmsTopicById(100L).getPublishedRouteGraphJson());
        }
    }

    @Test
    void declaredMetadataRestoredAfterAnOldReadViewUsesTheOriginalCurrentHashAndFreeze() throws Exception {
        try (var f = fixture()) {
            var keys = f.sql.getMapper(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class);
            var writer = new com.chengyinhub.business.service.support.ThemeStateKeyService(keys);
            var key = new com.chengyinhub.business.domain.ThemeStateKey();
            key.setStateKey("counter.energy"); key.setKeyType("counter"); key.setScope("run"); key.setLabel("体力"); key.setInitialValue(3);
            f.tx.executeWithoutResult(tx -> writer.register(100L, java.util.Collections.singletonList(key)));
            String submitted = bundle(f).freezeForSubmission(100L, 42L).getSnapshotJson();
            var changed = new com.chengyinhub.business.domain.ThemeStateKey(); changed.setLabel("体力"); changed.setInitialValue(4);
            f.tx.executeWithoutResult(tx -> writer.updateDefinition(100L, "counter.energy", changed));
            ExecutorService editor = Executors.newSingleThreadExecutor();
            try {
                String frozen = f.tx.execute(tx -> {
                    assertEquals(4, keys.selectByTopicId(100L).get(0).getInitialValue());
                    try {
                        editor.submit(() -> f.tx.execute(other -> {
                            var restored = new com.chengyinhub.business.domain.ThemeStateKey(); restored.setLabel("体力"); restored.setInitialValue(3);
                            return writer.updateDefinition(100L, "counter.energy", restored);
                        })).get(10, TimeUnit.SECONDS);
                    } catch (Exception e) { throw new IllegalStateException(e); }
                    bundle(f).applyDecision(100L, 42L, submitted, true);
                    ((TopicPublicationService) f.get("topicPublicationService")).publishApprovedRouteSnapshot(100L);
                    return f.topics.selectCmsTopicByIdForUpdate(100L).getPublishedRouteGraphJson();
                });
                JsonNode actual = JSON.readTree(frozen);
                assertEquals(1, actual.path("stateKeySnapshotVersion").asInt());
                assertEquals(3, actual.path("stateKeys").get(0).path("initialValue").asInt());
                assertEquals(JSON.readTree(submitted).path("topic").path("content").path("stateKeyDeclarations"), actual.path("stateKeys"));
                assertTrue(actual.path("stateKeys").get(0).path("minValue").isNull());
            } finally {
                editor.shutdownNow(); assertTrue(editor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void declarationAddedAfterSubmissionRollsBackWithTheOriginalTransaction() throws Exception {
        try (var f = fixture()) {
            String submitted = bundle(f).freezeForSubmission(100L, 42L).getSnapshotJson();
            var writer = new com.chengyinhub.business.service.support.ThemeStateKeyService(
                    f.sql.getMapper(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class));
            assertThrows(IllegalStateException.class, () -> f.tx.execute(tx -> {
                var key = new com.chengyinhub.business.domain.ThemeStateKey();
                key.setStateKey("clue.h_abcdef123456"); key.setKeyType("flag"); key.setScope("run"); key.setLabel("地图"); key.setInitialValue(0);
                writer.register(100L, java.util.Collections.singletonList(key));
                bundle(f).assertDeclarationsUnchanged(100L, submitted);
                return true;
            }));
            assertEquals(0, f.db.queryForObject("select count(*) from theme_state_key where topic_id=100", Integer.class));
            assertEquals(0L, f.db.queryForObject("select published_config_version from cms_topic where id=100", Long.class));
        }
    }

    @Test
    void directCreatedOrdinaryAndNonTemplateContentFreezeWithoutEnablingStateFeatures() throws Exception {
        for (boolean withTemplate : new boolean[]{true, false}) {
            try (var f = fixture()) {
                if (!withTemplate) f.db.update("update cms_topic_node set template_id=null where topic_id=100");
                TopicPublicationService publication = (TopicPublicationService) f.get("topicPublicationService");
                Integer publishedRows = f.tx.execute(tx -> publication.persistCreatedRouteSnapshot(100L));
                assertEquals(Integer.valueOf(1), publishedRows);
                JsonNode frozen = JSON.readTree(f.topics.selectCmsTopicById(100L).getPublishedRouteGraphJson());
                assertEquals(1, frozen.path("runtimeSnapshotVersion").asInt());
                assertEquals(1, frozen.path("stateKeySnapshotVersion").asInt());
                assertFalse(frozen.has("contentSnapshotOnly"));
                assertTrue(frozen.path("journeyRules").has("checkEnabled"));
                assertTrue(frozen.path("journeyRules").has("stateEnabled"));
                assertFalse(frozen.path("journeyRules").path("checkEnabled").asBoolean());
                assertFalse(frozen.path("journeyRules").path("stateEnabled").asBoolean());
                assertTrue(frozen.path("themeConfig").path("gameplayRuntime").isMissingNode());
                JsonNode answer = frozen.path("nodeAnswers").path("11");
                assertEquals(1, answer.path("contentSnapshotVersion").asInt());
                if (withTemplate) {
                    assertEquals(701L, answer.path("templateId").asLong());
                    assertEquals("A", answer.path("questionAnswer").asText());
                } else {
                    assertTrue(answer.path("templateId").isNull());
                    assertTrue(answer.path("templatePresentation").isEmpty());
                }
            }
        }
    }

    private static TopicContentGraphMySqlFixture fixture() throws Exception {
        TopicContentGraphMySqlFixture f = new TopicContentGraphMySqlFixture();
        try {
            f.open();
            f.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            f.db.update("update cms_topic set is_draft=0,status=1,check_status=1,route_mode='LINEAR',"
                    + "journey_story_json=null,journey_rules_json=null,theme_runtime_config_json=null,"
                    + "published_theme_runtime_config_json=null,published_config_version=0,published_route_graph_json=null where id=100");
            f.db.update("update cms_topic_node set latitude='31.240100',longitude='121.490100',address='old dock' where topic_id=100");
            f.db.update("update cms_member_template set validation_method=1,question_name='door number',question_answer='A' where id=701");
            return f;
        } catch (Throwable error) {
            f.close(); throw error;
        }
    }

    private static TopicBundleReviewService bundle(TopicContentGraphMySqlFixture f) {
        TopicBundleReviewService bundle = new TopicBundleReviewService(f.topics, f.chapters, f.nodes, f.templateMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(bundle, "themeStateKeyMapper",
                f.sql.getMapper(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class));
        return bundle;
    }
    private static TopicRoutePublicationCompiler compiler(TopicContentGraphMySqlFixture f) {
        return new TopicRoutePublicationCompiler(f.nodes, f.templateMapper, f.chapters,
                f.sql.getMapper(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class),
                Clock.systemUTC(), JSON, new TopicRouteConfigValidator(), f.topics);
    }
    private static JsonNode answers(String snapshot) throws Exception {
        return JSON.readTree(snapshot).path(NodeJourneySnapshot.NODE_ANSWERS_KEY);
    }
    private static void restoreOnAnotherConnection(TopicContentGraphMySqlFixture f, ExecutorService editor) {
        try {
            assertEquals(1, editor.submit(() -> new JdbcTemplate(f.manager.getDataSource()).update(
                    "update cms_member_template set question_answer='A' where id=701")).get(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }
}
