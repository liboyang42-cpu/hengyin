package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicContentRevision;
import com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO;
import com.chengyinhub.business.mapper.CmsTopicContentRevisionMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.ITopicContentRevisionService;
import com.chengyinhub.business.service.support.TopicSupplyReviewService;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F1 发布重试在真实应用服务里的接线(不是 store 复读)。
 *
 * <p>证两件事:
 * <ol>
 *   <li>{@code TopicPublicationService.publishDraftInternal} 在发布已完成(主题不再是草稿)时,
 *       用同 author + 同 publishRequestId 直接返回原修订,<b>不</b>清草稿、不推发布头、不碰额度/审核;</li>
 *   <li>{@code CmsTopicServiceImpl} 的重放在 {@code freezeAuthorAttributes} 之前收口 —— 否则重试会
 *       重写属性定义,变成一次未被察觉的写入。</li>
 * </ol>
 */
class TopicPublishReplayApplicationTest {

    private static final long TOPIC = 33L;
    private static final long OWNER = 77L;

    private TopicPublicationService publication;
    private CmsTopicMapper topics;
    private ITopicContentRevisionService revisions;
    private CmsTopic published;

    @BeforeEach
    void setUp() {
        publication = new TopicPublicationService();
        topics = mock(CmsTopicMapper.class);
        revisions = mock(ITopicContentRevisionService.class);
        ReflectionTestUtils.setField(publication, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(publication, "contentRevisionService", revisions);
        ReflectionTestUtils.setField(publication, "supplyReviewService", mock(TopicSupplyReviewService.class));
        com.chengyinhub.business.service.IAuditTaskService auditTasks =
                mock(com.chengyinhub.business.service.IAuditTaskService.class);
        when(auditTasks.selectAuditTaskList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(publication, "auditTaskService", auditTasks);
        ReflectionTestUtils.setField(publication, "quotaService", mock(com.chengyinhub.business.service.IQuotaService.class));
        ReflectionTestUtils.setField(publication, "mediaPublicationGuard",
                mock(com.chengyinhub.business.service.support.AppMediaPublicationGuard.class));
        ReflectionTestUtils.setField(publication, "publicationContentGuard",
                mock(com.chengyinhub.business.service.support.TopicPublicationContentGuard.class));

        published = new CmsTopic();
        published.setId(TOPIC);
        published.setMemberId(OWNER);
        published.setPublishMode("simple");
        published.setStatus(1);
        published.setUserStatus(1);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(published);
        when(topics.selectTopicIsDraft(TOPIC)).thenReturn(0);
    }

    private CmsTopicContentRevision revision(long id, long no, String sha) {
        CmsTopicContentRevision row = new CmsTopicContentRevision();
        row.setId(id);
        row.setTopicId(TOPIC);
        row.setRevisionNo(no);
        row.setSchemaVersion(1);
        row.setContentSha256(sha);
        return row;
    }

    @Test
    void completedPublishRetryWritesNothingAndReturnsTheSameRevision() {
        TopicSupplyReviewService supply = (TopicSupplyReviewService)
                ReflectionTestUtils.getField(publication, "supplyReviewService");
        when(supply.lockTopic(TOPIC)).thenReturn(published);
        when(revisions.publishedByRequestId(TOPIC, "req-1", null)).thenReturn(revision(9L, 2L, "sha-a"));

        TopicBundleSubmitResultVO result = publication.publishDraftInternal(TOPIC, OWNER, true, "req-1");

        assertNotNull(result, "重试不能返回 null(那会被调用方当成「不是草稿」)");
        assertEquals(Long.valueOf(9L), result.getContentRevisionId());
        assertEquals(Long.valueOf(2L), result.getContentRevisionNo());
        assertEquals("sha-a", result.getContentSha256());
        assertTrue(result.isPublished());

        verify(topics, never()).clearDraftIfDraft(anyLong());
        verify(topics, never()).publishRouteSnapshot(anyLong(), any(), any());
        verify(topics, never()).updateCmsTopic(any());
        // 额度/媒体/内容闸在重放路径上必须一次都不碰:重试既不占额度,也不对已发布内容重跑守卫。
        verifyNoInteractions(ReflectionTestUtils.getField(publication, "quotaService"),
                ReflectionTestUtils.getField(publication, "mediaPublicationGuard"),
                ReflectionTestUtils.getField(publication, "publicationContentGuard"));
    }

    @Test
    void replayOverridesTheCurrentLatestRevisionWithTheRetriedIntentsRevision() {
        TopicSupplyReviewService supply = (TopicSupplyReviewService)
                ReflectionTestUtils.getField(publication, "supplyReviewService");
        when(supply.lockTopic(TOPIC)).thenReturn(published);
        when(revisions.publishedByRequestId(TOPIC, "req-1", null)).thenReturn(revision(9L, 2L, "sha-a"));
        // 之后又发布过一次:当前最新修订是另一条。重放必须回 req-1 那一条,不能冒领最新版。
        com.chengyinhub.business.domain.vo.TopicContentRevisionView latest =
                new com.chengyinhub.business.domain.vo.TopicContentRevisionView();
        latest.setId(12L);
        latest.setRevisionNo(3L);
        latest.setSchemaVersion(1);
        latest.setContentSha256("sha-b");
        when(revisions.currentPublished(TOPIC)).thenReturn(latest);

        TopicBundleSubmitResultVO result = publication.publishDraftInternal(TOPIC, OWNER, true, "req-1");

        assertEquals(Long.valueOf(9L), result.getContentRevisionId());
        assertEquals("sha-a", result.getContentSha256());
    }

    @Test
    void replayStillRefusesAForeignPublisher() {
        TopicSupplyReviewService supply = (TopicSupplyReviewService)
                ReflectionTestUtils.getField(publication, "supplyReviewService");
        when(supply.lockTopic(TOPIC)).thenReturn(published);

        ServiceException refused = assertThrows(ServiceException.class,
                () -> publication.publishDraftInternal(TOPIC, 999L, true, "req-1"));

        assertEquals(Integer.valueOf(403), refused.getCode());
        verifyNoInteractions(revisions);
    }

    @Test
    void unknownIntentFallsThroughToTheOriginalDraftGate() {
        TopicSupplyReviewService supply = (TopicSupplyReviewService)
                ReflectionTestUtils.getField(publication, "supplyReviewService");
        when(supply.lockTopic(TOPIC)).thenReturn(published);
        when(revisions.publishedByRequestId(TOPIC, "req-new", null)).thenReturn(null);

        assertNull(publication.publishDraftInternal(TOPIC, OWNER, false, "req-new"),
                "没有既有修订 + 已不是草稿 = 原样早退,不制造已发布假象");
        verify(topics).clearDraftIfDraft(TOPIC);
    }

    @Test
    void facadeShortCircuitsReplayBeforeFreezingAuthorAttributes() {
        CmsTopicServiceImpl facade = new CmsTopicServiceImpl();
        TopicSupplyReviewService supply = mock(TopicSupplyReviewService.class);
        ThemeStateKeyService keys = mock(ThemeStateKeyService.class);
        TopicPublicationService owner = mock(TopicPublicationService.class);
        ReflectionTestUtils.setField(facade, "supplyReviewService", supply);
        ReflectionTestUtils.setField(facade, "themeStateKeyService", keys);
        ReflectionTestUtils.setField(facade, "topicPublicationService", owner);

        CmsTopic bound = new CmsTopic();
        bound.setId(TOPIC);
        bound.setMemberId(OWNER);
        bound.setConfigVersion(4L);
        bound.setJourneyRulesJson("{\"schemaVersion\":1,\"attributeBinding\":{\"schemaVersion\":1,"
                + "\"registryVersion\":\"7\",\"enabledKeys\":[]},\"attributes\":[]}");
        when(supply.lockTopic(TOPIC)).thenReturn(bound);
        TopicBundleSubmitResultVO replay = new TopicBundleSubmitResultVO(
                TOPIC, null, "NOT_REQUIRED", Collections.emptyList());
        replay.setContentRevisionId(9L);
        // 陈旧 configVersion + 陈旧 registryVersion:重试不应因本地草稿版本前进而 409。
        when(owner.replayPublishedRevision(TOPIC, OWNER, "req-1", null)).thenReturn(replay);

        TopicBundleSubmitResultVO result =
                facade.publishDraftWithBinding(TOPIC, OWNER, 2L, "1", "req-1");

        assertSame(replay, result);
        verifyNoInteractions(keys);
        verify(owner, never()).publishDraftInternal(anyLong(), anyLong(), anyBoolean(), any());
    }

    @Test
    void requestIdLookupIsNullForBlankOrAbsentAndConflictsOnADifferentPayload() {
        TopicContentRevisionServiceImpl store = new TopicContentRevisionServiceImpl();
        CmsTopicContentRevisionMapper mapper = mock(CmsTopicContentRevisionMapper.class);
        ReflectionTestUtils.setField(store, "revisionMapper", mapper);
        ReflectionTestUtils.setField(store, "cmsTopicMapper", topics);

        assertNull(store.publishedByRequestId(TOPIC, "   ", null));
        assertNull(store.publishedByRequestId(TOPIC, "x".repeat(65), null),
                "超长号不可能写出过修订,读路径按没有处理而不是抛错");
        verifyNoInteractions(mapper);

        when(mapper.selectByPublishRequestId(TOPIC, "req-1")).thenReturn(revision(9L, 2L, "sha-a"));
        assertEquals("sha-a", store.publishedByRequestId(TOPIC, "req-1", "SHA-A").getContentSha256(),
                "客户端回显的 sha 大小写不敏感");

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> store.publishedByRequestId(TOPIC, "req-1", "sha-b"));
        assertEquals(Integer.valueOf(409), conflict.getCode());
    }

    @Test
    void publicationWritesTheSameGeographicallyDecoratedBytesToRevisionAndHead() {
        CmsTopicMapper mappers = mock(CmsTopicMapper.class);
        ITopicContentRevisionService revisionStore = mock(ITopicContentRevisionService.class);
        TopicPublicationService owner = new TopicPublicationService();
        ReflectionTestUtils.setField(owner, "cmsTopicMapper", mappers);
        ReflectionTestUtils.setField(owner, "contentRevisionService", revisionStore);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setCityCode("310100");
        topic.setContentContractJson("{\"placeCountryCode\":\"JP\"}");
        when(mappers.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(revisionStore.recordPublishedRevision(any()))
                .thenReturn(com.chengyinhub.business.domain.dto.TopicContentRevisionWriteResult.recorded(
                        revision(9L, 1L, "sha")));
        when(mappers.publishRouteSnapshot(eq(TOPIC), any(), any())).thenReturn(1);
        String compiled = "{\"runtimeSnapshotVersion\":1,\"routeStations\":[],\"themeConfig\":{\"chapters\":[]}}";

        assertEquals(1, owner.persistRouteSnapshot(TOPIC, compiled, new java.util.Date(), "req-1"));

        org.mockito.ArgumentCaptor<com.chengyinhub.business.domain.dto.TopicContentRevisionWrite> written =
                org.mockito.ArgumentCaptor.forClass(
                        com.chengyinhub.business.domain.dto.TopicContentRevisionWrite.class);
        org.mockito.ArgumentCaptor<String> headed = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(revisionStore).recordPublishedRevision(written.capture());
        verify(mappers).publishRouteSnapshot(eq(TOPIC), headed.capture(), any());
        assertEquals(headed.getValue(), written.getValue().getFrozenSnapshot(),
                "修订 payload 与发布头必须是同一份字节,否则 hash 无法自证当前发布");
        assertTrue(headed.getValue().contains("\"contentGeography\""), headed.getValue());
        assertTrue(headed.getValue().contains("\"countryCode\":\"JP\""), headed.getValue());
        assertTrue(headed.getValue().contains("\"cityCode\":\"310100\""), headed.getValue());
    }
}
