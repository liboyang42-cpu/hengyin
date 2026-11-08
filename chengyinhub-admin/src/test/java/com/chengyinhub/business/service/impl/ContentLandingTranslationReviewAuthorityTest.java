package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ContentLandingMetadata;
import com.chengyinhub.business.domain.ContentLandingTranslation;
import com.chengyinhub.business.domain.dto.ContentLandingTranslationReviewRequest;
import com.chengyinhub.business.domain.dto.ContentLandingTranslationSaveRequest;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ContentLandingMetadataMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 译文审校权属契约：作者不得自批、审校只翻状态不改文字、看 A 审 B 被拒、审计必写。
 *
 * <p>注：本测试只证明服务层判定与写面顺序，**不**证明 InnoDB 并发/回滚，
 * 也不证明 Spring Security 的 {@code @PreAuthorize} 实际生效（那由控制器集成测试与运行期验证）。
 */
class ContentLandingTranslationReviewAuthorityTest {

    private static final long TOPIC_ID = 42L;
    private static final long AUTHOR = 900L;
    private static final long OPERATOR = 7L;

    private ContentLandingMetadataMapper metadata;
    private CmsTopicMapper topics;
    private BusinessAuditEventMapper audits;
    private ContentLandingMetadataServiceImpl service;

    @BeforeEach
    void setUp() {
        metadata = mock(ContentLandingMetadataMapper.class);
        topics = mock(CmsTopicMapper.class);
        audits = mock(BusinessAuditEventMapper.class);
        service = new ContentLandingMetadataServiceImpl();
        ReflectionTestUtils.setField(service, "metadataMapper", metadata);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", audits);

        when(topics.selectCmsTopicById(TOPIC_ID)).thenReturn(publicTopic());
        when(metadata.selectByTopicId(TOPIC_ID)).thenReturn(new ContentLandingMetadata());
        when(metadata.selectTranslationsByTopicId(TOPIC_ID)).thenReturn(new ArrayList<>());
        when(audits.insertBusinessAuditEvent(any())).thenReturn(1);
    }

    // ------------------------------------------------------------------ 作者面

    @Test
    void authorCannotSelfApprove() {
        ContentLandingTranslationSaveRequest request = authorRequest();
        request.setTranslationState("APPROVED");

        ServiceException rejection = assertThrows(ServiceException.class,
                () -> service.saveTranslation(request, AUTHOR));

        assertEquals(Integer.valueOf(403), rejection.getCode());
        verify(metadata, never()).insertTranslation(any());
        verify(metadata, never()).updateTranslationVersioned(any(), anyLong(), any());
    }

    @Test
    void authorWriteAlwaysLandsAsPendingEvenForApprovedLookingPayloads() {
        when(metadata.selectTranslation(TOPIC_ID, "en")).thenReturn(null);
        ContentLandingTranslationSaveRequest request = authorRequest();
        request.setTranslationState("approved");   // 大小写不同也必须被拒或忽略成 PENDING

        ServiceException rejection = assertThrows(ServiceException.class,
                () -> service.saveTranslation(request, AUTHOR));
        assertEquals(Integer.valueOf(403), rejection.getCode());

        request.setTranslationState(null);
        service.saveTranslation(request, AUTHOR);

        ArgumentCaptor<ContentLandingTranslation> saved =
                ArgumentCaptor.forClass(ContentLandingTranslation.class);
        verify(metadata).insertTranslation(saved.capture());
        assertEquals("PENDING", saved.getValue().getTranslationState());
    }

    @Test
    void editingAnApprovedTranslationDropsItBackToPending() {
        ContentLandingTranslation approved = existingTranslation("APPROVED", 5L);
        when(metadata.selectTranslation(TOPIC_ID, "en")).thenReturn(approved);
        when(metadata.updateTranslationVersioned(any(), eq(5L), any())).thenReturn(1);

        ContentLandingTranslationSaveRequest request = authorRequest();
        request.setExpectedVersion(5L);
        request.setTranslationState("PENDING");
        service.saveTranslation(request, AUTHOR);

        ArgumentCaptor<ContentLandingTranslation> saved =
                ArgumentCaptor.forClass(ContentLandingTranslation.class);
        verify(metadata).updateTranslationVersioned(saved.capture(), eq(5L), any());
        assertEquals("PENDING", saved.getValue().getTranslationState());
    }

    @Test
    void authorStillCannotWriteAnotherMembersTopic() {
        ContentLandingTranslationSaveRequest request = authorRequest();
        ServiceException rejection = assertThrows(ServiceException.class,
                () -> service.saveTranslation(request, AUTHOR + 1));
        assertEquals(Integer.valueOf(403), rejection.getCode());
    }

    // ------------------------------------------------------------------ 审校面

    @Test
    void reviewerApprovesWithoutOverwritingAuthorText() {
        ContentLandingTranslation pending = existingTranslation("PENDING", 3L);
        when(metadata.selectTranslation(TOPIC_ID, "en")).thenReturn(pending);
        when(metadata.updateTranslationVersioned(any(), eq(3L), any())).thenReturn(1);

        ContentLandingTranslationReviewRequest request = reviewRequest(true, "en", 3L);
        request.setObservedContentHash(service.reviewDigest(TOPIC_ID, "en"));
        service.reviewTranslation(request, OPERATOR);

        ArgumentCaptor<ContentLandingTranslation> saved =
                ArgumentCaptor.forClass(ContentLandingTranslation.class);
        verify(metadata).updateTranslationVersioned(saved.capture(), eq(3L), any());
        ContentLandingTranslation written = saved.getValue();
        assertEquals("APPROVED", written.getTranslationState());
        assertEquals(pending.getTitle(), written.getTitle());
        assertEquals(pending.getSubtitle(), written.getSubtitle());
        assertEquals(pending.getSummary(), written.getSummary());
        assertEquals(OPERATOR, written.getUpdateBy());

        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals(BusinessAuditEvent.ACTOR_TYPE_SYS_USER, audit.getValue().getActorType());
        assertEquals(OPERATOR, audit.getValue().getActorMemberId());
        assertEquals("审校通过", audit.getValue().getReason());
        assertEquals(TOPIC_ID, audit.getValue().getDomainId());
    }

    @Test
    void approvingSomethingElseThanWhatWasReviewedIsRejected() {
        ContentLandingTranslation current = existingTranslation("PENDING", 3L);
        when(metadata.selectTranslation(TOPIC_ID, "en")).thenReturn(current);

        ContentLandingTranslationReviewRequest request = reviewRequest(true, "en", 3L);
        request.setObservedContentHash(service.reviewDigest(TOPIC_ID, "en"));

        // 审校读过之后作者改了译文正文：旧摘要必须失效。
        current.setSummary("作者刚改过的摘要");
        ServiceException rejection = assertThrows(ServiceException.class,
                () -> service.reviewTranslation(request, OPERATOR));

        assertEquals(Integer.valueOf(409), rejection.getCode());
        verify(metadata, never()).updateTranslationVersioned(any(), anyLong(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void sourceTopicEditAlsoInvalidatesTheDigest() {
        ContentLandingTranslation current = existingTranslation("PENDING", 3L);
        when(metadata.selectTranslation(TOPIC_ID, "en")).thenReturn(current);
        CmsTopic topic = publicTopic();
        when(topics.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        ContentLandingTranslationReviewRequest request = reviewRequest(true, "en", 3L);
        request.setObservedContentHash(service.reviewDigest(TOPIC_ID, "en"));

        // 作者改了主题原文（updateTime 推进），译文一字未动 —— 旧摘要也必须失效。
        topic.setUpdateTime(new Date(topic.getUpdateTime().getTime() + 60_000L));
        ServiceException rejection = assertThrows(ServiceException.class,
                () -> service.reviewTranslation(request, OPERATOR));

        assertEquals(Integer.valueOf(409), rejection.getCode());
        verify(metadata, never()).updateTranslationVersioned(any(), anyLong(), any());
    }

    @Test
    void staleExpectedVersionIsRejected() {
        when(metadata.selectTranslation(TOPIC_ID, "en")).thenReturn(existingTranslation("PENDING", 9L));
        ContentLandingTranslationReviewRequest request = reviewRequest(true, "en", 3L);
        request.setObservedContentHash(service.reviewDigest(TOPIC_ID, "en"));

        assertEquals(Integer.valueOf(409),
                assertThrows(ServiceException.class,
                        () -> service.reviewTranslation(request, OPERATOR)).getCode());
    }

    @Test
    void delistedOrUnpublishedTopicCanNeverBeApproved() {
        CmsTopic delisted = publicTopic();
        delisted.setStatus(0);
        when(topics.selectCmsTopicById(TOPIC_ID)).thenReturn(delisted);
        ContentLandingTranslationReviewRequest request = reviewRequest(true, "en", 3L);
        request.setObservedContentHash("x".repeat(64));

        assertEquals(Integer.valueOf(409),
                assertThrows(ServiceException.class,
                        () -> service.reviewTranslation(request, OPERATOR)).getCode());

        CmsTopic pendingReview = publicTopic();
        pendingReview.setUserStatus(0);
        when(topics.selectCmsTopicById(TOPIC_ID)).thenReturn(pendingReview);
        assertEquals(Integer.valueOf(409),
                assertThrows(ServiceException.class,
                        () -> service.reviewTranslation(request, OPERATOR)).getCode());
    }

    @Test
    void reviewRequiresReasonVersionAndDigest() {
        ContentLandingTranslationReviewRequest noReason = reviewRequest(true, "en", 3L);
        noReason.setReason("  ");
        assertEquals(Integer.valueOf(400),
                assertThrows(ServiceException.class,
                        () -> service.reviewTranslation(noReason, OPERATOR)).getCode());

        ContentLandingTranslationReviewRequest noVersion = reviewRequest(true, "en", 3L);
        noVersion.setExpectedVersion(null);
        assertEquals(Integer.valueOf(400),
                assertThrows(ServiceException.class,
                        () -> service.reviewTranslation(noVersion, OPERATOR)).getCode());

        ContentLandingTranslationReviewRequest noDigest = reviewRequest(true, "en", 3L);
        noDigest.setObservedContentHash(null);
        assertEquals(Integer.valueOf(400),
                assertThrows(ServiceException.class,
                        () -> service.reviewTranslation(noDigest, OPERATOR)).getCode());

        ContentLandingTranslationReviewRequest noOperator = reviewRequest(true, "en", 3L);
        assertEquals(Integer.valueOf(401),
                assertThrows(ServiceException.class,
                        () -> service.reviewTranslation(noOperator, null)).getCode());
    }

    @Test
    void approvalWithoutTitleIsRejected() {
        ContentLandingTranslation current = existingTranslation("PENDING", 3L);
        current.setTitle(null);
        when(metadata.selectTranslation(TOPIC_ID, "en")).thenReturn(current);
        ContentLandingTranslationReviewRequest request = reviewRequest(true, "en", 3L);
        request.setObservedContentHash(service.reviewDigest(TOPIC_ID, "en"));

        assertEquals(Integer.valueOf(400),
                assertThrows(ServiceException.class,
                        () -> service.reviewTranslation(request, OPERATOR)).getCode());
    }

    @Test
    void alreadyInTargetStateIsIdempotentAndLeavesNoFakeAudit() {
        when(metadata.selectTranslation(TOPIC_ID, "en")).thenReturn(existingTranslation("APPROVED", 4L));
        ContentLandingTranslationReviewRequest request = reviewRequest(true, "en", 4L);
        request.setObservedContentHash(service.reviewDigest(TOPIC_ID, "en"));

        service.reviewTranslation(request, OPERATOR);

        verify(metadata, never()).updateTranslationVersioned(any(), anyLong(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void missingAuditStoreRefusesToReviewInsteadOfSilentlySkippingTheTrail() {
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", null);
        when(metadata.selectTranslation(TOPIC_ID, "en")).thenReturn(existingTranslation("PENDING", 3L));
        when(metadata.updateTranslationVersioned(any(), eq(3L), any())).thenReturn(1);
        ContentLandingTranslationReviewRequest request = reviewRequest(true, "en", 3L);
        request.setObservedContentHash(service.reviewDigest(TOPIC_ID, "en"));

        assertEquals(Integer.valueOf(503),
                assertThrows(ServiceException.class,
                        () -> service.reviewTranslation(request, OPERATOR)).getCode());
    }

    @Test
    void reviewCannotTouchTheSourceLocaleEntry() {
        when(metadata.selectTranslation(anyLong(), anyString())).thenReturn(null);
        ContentLandingTranslationReviewRequest request = reviewRequest(true, "zh-CN", 1L);
        request.setObservedContentHash("x".repeat(64));

        assertEquals(Integer.valueOf(400),
                assertThrows(ServiceException.class,
                        () -> service.reviewTranslation(request, OPERATOR)).getCode());
    }

    // ------------------------------------------------------------------ helpers

    private CmsTopic publicTopic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(AUTHOR);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setDelFlag(0);
        topic.setUpdateTime(new Date(1_700_000_000_000L));
        return topic;
    }

    private ContentLandingTranslation existingTranslation(String state, long version) {
        ContentLandingTranslation row = new ContentLandingTranslation();
        row.setId(555L);
        row.setTopicId(TOPIC_ID);
        row.setLocale("en");
        row.setSourceLocale("zh-cn");
        row.setTitle("Ancient Town Walk");
        row.setSubtitle("Two hours");
        row.setSummary("A short walk through the old town.");
        row.setTranslationState(state);
        row.setRowVersion(version);
        return row;
    }

    private ContentLandingTranslationSaveRequest authorRequest() {
        ContentLandingTranslationSaveRequest request = new ContentLandingTranslationSaveRequest();
        request.setTopicId(TOPIC_ID);
        request.setLocale("en");
        request.setTitle("Ancient Town Walk");
        request.setSummary("A short walk through the old town.");
        return request;
    }

    private ContentLandingTranslationReviewRequest reviewRequest(boolean approve, String locale, Long version) {
        ContentLandingTranslationReviewRequest request = new ContentLandingTranslationReviewRequest();
        request.setTopicId(TOPIC_ID);
        request.setLocale(locale);
        request.setExpectedVersion(version);
        request.setApprove(approve);
        request.setReason("审校通过");
        return request;
    }
}
