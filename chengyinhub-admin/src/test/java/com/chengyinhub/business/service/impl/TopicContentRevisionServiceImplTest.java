package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicContentRevision;
import com.chengyinhub.business.domain.dto.TopicContentRevisionWrite;
import com.chengyinhub.business.domain.dto.TopicContentRevisionWriteResult;
import com.chengyinhub.business.mapper.CmsTopicContentRevisionMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * BE-06 修订索引的服务合同(单元级,不连数据库)。
 *
 * <p>这里证的是「发布 → 一条不可改写修订」的语义本身:精确 payload、hash 自证、
 * 公共 manifest 不含答案、发布意图号幂等、内容 hash 不是幂等键、事务外拒写。
 * 唯一键/并发/真实字节回读由 {@code TopicContentRevisionMySqlIT} 证(真库,未在本机跑)。
 */
class TopicContentRevisionServiceImplTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TOPIC = 7L;
    private static final long OWNER = 42L;

    /** 与编译器真实冻结形态同构:含答案/判题配置/商家归属/旅程数值规则等 secret。 */
    private static final String FROZEN_A = "{"
            + "\"runtimeSnapshotVersion\":1,\"routeMode\":\"BRANCH_GRAPH\","
            + "\"openingChapter\":{\"chapterId\":3,\"name\":\"序章\"},"
            + "\"routeStations\":[{\"nodeId\":11,\"nodeName\":\"站一\",\"chapterId\":3,"
            + "\"scanReady\":true,\"merchantMemberId\":88}],"
            + "\"nodeAnswers\":{\"11\":{\"questionAnswer\":\"旧答案\",\"accepted\":[\"别名\"]}},"
            + "\"nodeConfigs\":{\"11\":{\"kind\":\"qa\"}},"
            + "\"themeConfig\":{\"gameplayRuntime\":{\"facilities\":[]}},"
            + "\"journeyRules\":{\"rewards\":{\"coins\":5}}"
            + "}";

    private static final String FROZEN_B = FROZEN_A.replace("\"旧答案\"", "\"新答案\"");

    private TopicContentRevisionServiceImpl service;
    private CmsTopicContentRevisionMapper revisions;
    private CmsTopicMapper topics;

    @BeforeEach
    void setUp() {
        service = new TopicContentRevisionServiceImpl();
        revisions = mock(CmsTopicContentRevisionMapper.class);
        topics = mock(CmsTopicMapper.class);
        ReflectionTestUtils.setField(service, "revisionMapper", revisions);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(OWNER);
        when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        when(revisions.insertRevision(any(CmsTopicContentRevision.class))).thenReturn(1);
    }

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void recordsExactFrozenPayloadWithSelfVerifyingHashAndSecretFreeManifest() throws Exception {
        when(revisions.selectMaxRevisionNo(TOPIC)).thenReturn(0L);
        ArgumentCaptor<CmsTopicContentRevision> inserted = ArgumentCaptor.forClass(CmsTopicContentRevision.class);

        TopicContentRevisionWriteResult result = inWriteTransaction(() ->
                service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_A, "req-1")));

        verify(revisions).insertRevision(inserted.capture());
        CmsTopicContentRevision row = inserted.getValue();
        assertEquals(FROZEN_A, row.getSnapshotJson(), "必须原字节保存,不能被重新序列化");
        assertEquals(CanonicalDigest.sha256Hex(FROZEN_A), row.getContentSha256());
        assertEquals(CanonicalDigest.sha256Hex(FROZEN_A), row.getRequestFingerprint());
        assertEquals(Integer.valueOf(1), row.getSchemaVersion(), "schema version 取 payload 自己的 runtimeSnapshotVersion");
        assertEquals(Long.valueOf(1L), row.getRevisionNo());
        assertEquals(CmsTopicContentRevision.ORIGIN_PUBLISH, row.getOrigin());
        assertEquals(Long.valueOf(OWNER), row.getCreateBy(), "create_by = 本次发布的主题归属者");
        assertFalse(result.isIdempotentReplay());
        assertSame(row, result.getRevision());

        JsonNode manifest = JSON.readTree(row.getPublicManifestJson());
        assertEquals(1, manifest.path("manifestVersion").asInt());
        assertEquals(TOPIC, manifest.path("topicId").asLong());
        assertEquals(1L, manifest.path("revisionNo").asLong());
        assertEquals(row.getContentSha256(), manifest.path("contentSha256").asText());
        assertEquals(1, manifest.path("schemaVersion").asInt());
        assertEquals("BRANCH_GRAPH", manifest.path("routeMode").asText());
        assertEquals(3L, manifest.path("openingChapterId").asLong());
        assertEquals(11L, manifest.path("stations").get(0).path("nodeId").asLong());
        assertTrue(manifest.path("stations").get(0).path("scanReady").asBoolean());

        assertFalse(manifest.has("nodeAnswers"), "答案绝不能进公共 manifest");
        assertFalse(manifest.has("nodeConfigs"), "判题配置绝不进公共 manifest");
        assertFalse(manifest.has("themeConfig"));
        assertFalse(manifest.has("journeyRules"));
        String serialized = row.getPublicManifestJson();
        assertFalse(serialized.contains("旧答案"));
        assertFalse(serialized.contains("别名"));
        assertFalse(serialized.contains("merchantMemberId"), "运营归属不进公共 manifest");
        assertFalse(serialized.contains("coins"), "奖励数值不因本卡变成公共内容合同");
    }

    @Test
    void sameRequestIdAndSamePayloadReplaysTheExistingRevisionWithoutWritingAgain() {
        CmsTopicContentRevision existing = revision(1L, 1L, CanonicalDigest.sha256Hex(FROZEN_A), "req-1");
        when(revisions.selectByPublishRequestId(TOPIC, "req-1")).thenReturn(existing);

        TopicContentRevisionWriteResult result = inWriteTransaction(() ->
                service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_A, "req-1")));

        assertTrue(result.isIdempotentReplay(), "同号同 payload 必须短路,发布头已被推进过");
        assertSame(existing, result.getRevision());
        verify(revisions, never()).insertRevision(any());
    }

    @Test
    void sameRequestIdWithDifferentPayloadIsAConflictAndNeverOverwritesHistory() {
        CmsTopicContentRevision existing = revision(1L, 1L, CanonicalDigest.sha256Hex(FROZEN_A), "req-1");
        when(revisions.selectByPublishRequestId(TOPIC, "req-1")).thenReturn(existing);

        ServiceException conflict = assertThrows(ServiceException.class, () -> inWriteTransaction(() ->
                service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_B, "req-1"))));

        assertEquals(Integer.valueOf(409), conflict.getCode());
        verify(revisions, never()).insertRevision(any());
    }

    @Test
    void restoringAnEarlierVersionIsAThirdRevisionEvenWhenItsHashMatchesTheFirst() {
        when(revisions.selectMaxRevisionNo(TOPIC)).thenReturn(0L, 1L, 2L);
        ArgumentCaptor<CmsTopicContentRevision> inserted = ArgumentCaptor.forClass(CmsTopicContentRevision.class);

        runInWriteTransaction(() -> {
            service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_A, "req-a"));
            service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_B, "req-b"));
            // A→B→恢复A:第三次是新的发布意图,hash 与第一条相同也必须落成第三条。
            service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_A, "req-restore-a"));
        });

        verify(revisions, times(3)).insertRevision(inserted.capture());
        var rows = inserted.getAllValues();
        assertEquals(1L, rows.get(0).getRevisionNo());
        assertEquals(2L, rows.get(1).getRevisionNo());
        assertEquals(3L, rows.get(2).getRevisionNo(), "内容 hash 不是幂等键,恢复旧版是新修订");
        assertEquals(rows.get(0).getContentSha256(), rows.get(2).getContentSha256());
        assertEquals("req-a", rows.get(0).getPublishRequestId());
        assertEquals("req-restore-a", rows.get(2).getPublishRequestId());
    }

    @Test
    void legacyCallersWithoutARequestIdStillCreateOneRevisionPerPublish() {
        when(revisions.selectMaxRevisionNo(TOPIC)).thenReturn(4L);
        ArgumentCaptor<CmsTopicContentRevision> inserted = ArgumentCaptor.forClass(CmsTopicContentRevision.class);

        runInWriteTransaction(() -> service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_A)));

        verify(revisions).insertRevision(inserted.capture());
        assertNull(inserted.getValue().getPublishRequestId());
        assertEquals(Long.valueOf(5L), inserted.getValue().getRevisionNo());
        verify(revisions, never()).selectByPublishRequestId(anyLong(), any());
    }

    @Test
    void refusesToWriteOutsideThePublicationWriteTransaction() {
        assertThrows(IllegalStateException.class, () ->
                service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_A, "req-1")));
        verifyNoInteractions(revisions);
    }
    @Test
    void readOnlyTransactionIsRejectedSoPreviewCanNeverPersistARevision() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            assertThrows(IllegalStateException.class, () ->
                    service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_A, "preview")));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.clear();
        }
        verifyNoInteractions(revisions);
    }

    @Test
    void blankOrNonObjectPayloadIsRejectedInsteadOfStored() {
        when(revisions.selectMaxRevisionNo(TOPIC)).thenReturn(0L);

        assertThrows(IllegalArgumentException.class, () -> runInWriteTransaction(() ->
                service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, "   ", "req-1"))));
        assertThrows(IllegalStateException.class, () -> runInWriteTransaction(() ->
                service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, "[1,2]", "req-2"))));
        verify(revisions, never()).insertRevision(any());
    }

    @Test
    void legacyImportKeepsTheStoredPublishedBytesInsteadOfRebuildingFromLiveContent() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(OWNER);
        topic.setPublishedConfigVersion(3L);
        // 真实存量发布头的字节(与 live 模板可能不同键序/空白),必须原样成为修订 payload。
        String stored = "{ \"runtimeSnapshotVersion\":1,\n \"routeMode\":\"BRANCH_GRAPH\", \"routeStations\":[] }";
        topic.setPublishedRouteGraphJson(stored);
        when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        when(revisions.selectMaxRevisionNo(TOPIC)).thenReturn(0L);
        ArgumentCaptor<CmsTopicContentRevision> inserted = ArgumentCaptor.forClass(CmsTopicContentRevision.class);

        CmsTopicContentRevision imported = inWriteTransaction(() -> service.importLegacyPublished(TOPIC, OWNER));

        verify(revisions).insertRevision(inserted.capture());
        assertEquals(stored, imported.getSnapshotJson());
        assertEquals(stored, inserted.getValue().getSnapshotJson());
        assertEquals(CanonicalDigest.sha256Hex(stored), imported.getContentSha256());
        assertEquals(CmsTopicContentRevision.ORIGIN_LEGACY_IMPORTED, imported.getOrigin());
        assertNull(imported.getPublishRequestId());
    }

    @Test
    void legacyImportIsIdempotentWhenTheCurrentHeadIsAlreadyIndexed() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setPublishedConfigVersion(3L);
        topic.setPublishedRouteGraphJson(FROZEN_A);
        when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        CmsTopicContentRevision latest = revision(1L, 1L, CanonicalDigest.sha256Hex(FROZEN_A), null);
        when(revisions.selectLatestByTopicId(TOPIC)).thenReturn(latest);

        CmsTopicContentRevision imported = inWriteTransaction(() -> service.importLegacyPublished(TOPIC, OWNER));

        assertSame(latest, imported);
        verify(revisions, never()).insertRevision(any());
    }

    @Test
    void crossTopicReadsReturnNothingSoAnArbitraryRevisionIdCannotReadOtherContent() {
        when(revisions.selectByIdAndTopic(9L, TOPIC)).thenReturn(null);
        when(revisions.selectViewByTopicIdAndId(TOPIC, 9L)).thenReturn(null);

        assertNull(service.frozenSnapshot(TOPIC, 9L));
        assertNull(service.publishedRevision(TOPIC, 9L));
        // 非法/缺失 id 不落到 SQL。
        assertNull(service.frozenSnapshot(null, 9L));
        assertNull(service.frozenSnapshot(TOPIC, null));
        assertNull(service.currentPublished(null));
    }

    @Test
    void adoptionUsesTheDedicatedCurrentReadInsideTheCopyWriteTransaction() {
        CmsTopicContentRevision current = revision(91L, 1L, CanonicalDigest.sha256Hex(FROZEN_A), null);
        when(revisions.selectLatestForAdoption(TOPIC)).thenReturn(current);
        assertThrows(IllegalStateException.class, () -> service.currentForAdoption(TOPIC));
        runInWriteTransaction(() -> {
            assertSame(current, service.currentForAdoption(TOPIC));
            assertThrows(IllegalArgumentException.class, () -> service.currentForAdoption(0L));
        });
        verify(revisions).selectLatestForAdoption(TOPIC);
        verify(revisions, never()).selectLatestByTopicId(anyLong());
        verify(revisions, never()).selectLatestViewByTopicId(anyLong());
    }

    private static CmsTopicContentRevision revision(long id, long no, String sha, String requestId) {
        CmsTopicContentRevision row = new CmsTopicContentRevision();
        row.setId(id);
        row.setTopicId(TOPIC);
        row.setRevisionNo(no);
        row.setSchemaVersion(1);
        row.setContentSha256(sha);
        row.setPublishRequestId(requestId);
        return row;
    }

    /** 复刻 Spring 事务事实:服务要求真实写事务,只读/无事务一律拒绝。 */
    private static <T> T inWriteTransaction(java.util.function.Supplier<T> body) {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        try {
            return body.get();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.clear();
        }
    }

    private static void runInWriteTransaction(Runnable body) {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        try {
            body.run();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.clear();
        }
    }
}
