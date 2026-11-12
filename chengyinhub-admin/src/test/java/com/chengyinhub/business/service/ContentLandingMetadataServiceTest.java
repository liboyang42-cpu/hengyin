package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ContentLandingMetadata;
import com.chengyinhub.business.domain.ContentLandingTranslation;
import com.chengyinhub.business.domain.dto.ContentLandingMetadataSaveRequest;
import com.chengyinhub.business.domain.dto.ContentLandingTranslationSaveRequest;
import com.chengyinhub.business.domain.vo.ContentLandingMetadataVO;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ContentLandingMetadataMapper;
import com.chengyinhub.business.service.impl.ContentLandingMetadataServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M7 落地元信息写面的归属、状态与幂等测试。
 *
 * <p>真库乐观锁行为(影响 0 行 / 唯一键冲突)由 PublicContentCatalogMyBatisIT 覆盖；
 * 本类只固定「谁可以写、什么内容可写、重放如何表现」的合同。
 */
class ContentLandingMetadataServiceTest {

    private static final long OWNER = 1001L;
    private static final long OTHER = 2002L;

    private ContentLandingMetadataMapper metadataMapper;
    private CmsTopicMapper cmsTopicMapper;
    private ContentLandingMetadataServiceImpl service;

    @BeforeEach
    void setUp() {
        metadataMapper = mock(ContentLandingMetadataMapper.class);
        cmsTopicMapper = mock(CmsTopicMapper.class);
        service = new ContentLandingMetadataServiceImpl();
        ReflectionTestUtils.setField(service, "metadataMapper", metadataMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", cmsTopicMapper);
        when(metadataMapper.selectTranslationsByTopicId(anyLong())).thenReturn(new ArrayList<ContentLandingTranslation>());
    }

    // ---------------------------------------------------------------- 归属

    @Test
    void nonOwnerCannotReadOrWrite() {
        when(cmsTopicMapper.selectCmsTopicById(eq(7L))).thenReturn(topic(7L, OWNER));

        assertCode(403, () -> service.readOwn(7L, OTHER));
        assertCode(403, () -> service.saveMetadata(request(7L, 0L), OTHER));
        assertCode(403, () -> service.saveTranslation(translation(7L, "en", 0L), OTHER));
        verify(metadataMapper, never()).insertMetadata(any());
        verify(metadataMapper, never()).updateMetadataVersioned(any(), anyLong(), any());
    }

    @Test
    void deletedOrMissingTopicIsNotFound() {
        when(cmsTopicMapper.selectCmsTopicById(eq(7L))).thenReturn(null);
        assertCode(404, () -> service.readOwn(7L, OWNER));

        CmsTopic deleted = topic(7L, OWNER);
        deleted.setDelFlag(2);
        when(cmsTopicMapper.selectCmsTopicById(eq(7L))).thenReturn(deleted);
        assertCode(404, () -> service.readOwn(7L, OWNER));
    }

    @Test
    void anonymousCallerIsRejected() {
        assertCode(401, () -> service.readOwn(7L, null));
        assertCode(401, () -> service.readOwn(7L, 0L));
    }

    @Test
    void invalidTopicIdIsRejectedBeforeAnyRead() {
        assertCode(400, () -> service.readOwn(null, OWNER));
        assertCode(400, () -> service.readOwn(0L, OWNER));
    }

    // ---------------------------------------------------------------- 写入语义

    @Test
    void firstSaveInsertsWithVersionOne() {
        when(cmsTopicMapper.selectCmsTopicById(eq(7L))).thenReturn(topic(7L, OWNER));
        when(metadataMapper.selectByTopicId(eq(7L))).thenReturn(null);

        service.saveMetadata(request(7L, 0L), OWNER);

        verify(metadataMapper).insertMetadata(any());
        verify(metadataMapper, never()).updateMetadataVersioned(any(), anyLong(), any());
    }

    @Test
    void replacingWithStaleVersionConflictsInsteadOfOverwriting() {
        when(cmsTopicMapper.selectCmsTopicById(eq(7L))).thenReturn(topic(7L, OWNER));
        when(metadataMapper.selectByTopicId(eq(7L))).thenReturn(existing(7L, 5L));
        when(metadataMapper.updateMetadataVersioned(any(), eq(5L), any())).thenReturn(0);

        assertCode(409, () -> service.saveMetadata(request(7L, 5L), OWNER));
    }

    @Test
    void concurrentInsertOnSameTopicIsConflictNotOverwrite() {
        when(cmsTopicMapper.selectCmsTopicById(eq(7L))).thenReturn(topic(7L, OWNER));
        when(metadataMapper.selectByTopicId(eq(7L))).thenReturn(null);
        when(metadataMapper.insertMetadata(any())).thenThrow(new DuplicateKeyException("uk_content_landing_metadata_topic"));

        assertCode(409, () -> service.saveMetadata(request(7L, 0L), OWNER));
    }

    @Test
    void createWithNonZeroVersionIsConflict() {
        when(cmsTopicMapper.selectCmsTopicById(eq(7L))).thenReturn(topic(7L, OWNER));
        when(metadataMapper.selectByTopicId(eq(7L))).thenReturn(null);

        assertCode(409, () -> service.saveMetadata(request(7L, 3L), OWNER));
    }

    @Test
    void groupRangeAndAgeRangeMustBeOrdered() {
        when(cmsTopicMapper.selectCmsTopicById(eq(7L))).thenReturn(topic(7L, OWNER));
        when(metadataMapper.selectByTopicId(eq(7L))).thenReturn(null);

        ContentLandingMetadataSaveRequest badPeople = request(7L, 0L);
        badPeople.setGroupMinPeople(30);
        badPeople.setGroupMaxPeople(10);
        assertCode(400, () -> service.saveMetadata(badPeople, OWNER));

        ContentLandingMetadataSaveRequest badAge = request(7L, 0L);
        badAge.setAgeMin(16);
        badAge.setAgeMax(6);
        assertCode(400, () -> service.saveMetadata(badAge, OWNER));
        verify(metadataMapper, never()).insertMetadata(any());
    }

    @Test
    void blankMetadataReadsAsAllUnknownNotAsFalse() {
        when(cmsTopicMapper.selectCmsTopicById(eq(7L))).thenReturn(topic(7L, OWNER));
        when(metadataMapper.selectByTopicId(eq(7L))).thenReturn(null);

        ContentLandingMetadataVO vo = service.readOwn(7L, OWNER);

        assertNull(vo.getGroupEligible());
        assertNull(vo.getSchoolEligible());
        assertNull(vo.getRowVersion());
        assertTrue(vo.getTranslations().isEmpty());
    }

    // ---------------------------------------------------------------- 译文语义

    @Test
    void translationDefaultsToPendingSoItIsNeverClaimedAsFinished() {
        when(cmsTopicMapper.selectCmsTopicById(eq(7L))).thenReturn(topic(7L, OWNER));
        when(metadataMapper.selectByTopicId(eq(7L))).thenReturn(null);
        when(metadataMapper.selectTranslation(eq(7L), anyString())).thenReturn(null);

        ContentLandingTranslationSaveRequest request = translation(7L, "en", 0L);
        request.setTranslationState(null);
        service.saveTranslation(request, OWNER);

        verify(metadataMapper).insertTranslation(org.mockito.ArgumentMatchers.argThat(
                row -> "PENDING".equals(row.getTranslationState())));
    }

    @Test
    void translationIntoSourceLocaleIsRejected() {
        when(cmsTopicMapper.selectCmsTopicById(eq(7L))).thenReturn(topic(7L, OWNER));
        when(metadataMapper.selectByTopicId(eq(7L))).thenReturn(null);

        assertCode(400, () -> service.saveTranslation(translation(7L, "zh-CN", 0L), OWNER));
        assertCode(400, () -> service.saveTranslation(translation(7L, "zh_cn", 0L), OWNER));
    }

    @Test
    void authorCannotApproveTranslationWithoutReview() {
        when(cmsTopicMapper.selectCmsTopicById(eq(7L))).thenReturn(topic(7L, OWNER));
        when(metadataMapper.selectByTopicId(eq(7L))).thenReturn(null);

        ContentLandingTranslationSaveRequest request = translation(7L, "en", 0L);
        request.setTitle(null);
        request.setTranslationState("APPROVED");

        assertCode(403, () -> service.saveTranslation(request, OWNER));
    }

    @Test
    void emptyTranslationBodyIsRejected() {
        when(cmsTopicMapper.selectCmsTopicById(eq(7L))).thenReturn(topic(7L, OWNER));
        when(metadataMapper.selectByTopicId(eq(7L))).thenReturn(null);

        ContentLandingTranslationSaveRequest request = translation(7L, "en", 0L);
        request.setTitle(" ");
        request.setSubtitle(null);
        request.setSummary(null);

        assertCode(400, () -> service.saveTranslation(request, OWNER));
    }

    @Test
    void unknownTranslationStateIsRejected() {
        when(cmsTopicMapper.selectCmsTopicById(eq(7L))).thenReturn(topic(7L, OWNER));
        when(metadataMapper.selectByTopicId(eq(7L))).thenReturn(null);

        ContentLandingTranslationSaveRequest request = translation(7L, "en", 0L);
        request.setTranslationState("DONE");

        assertCode(400, () -> service.saveTranslation(request, OWNER));
    }

    @Test
    void staleTranslationVersionConflicts() {
        when(cmsTopicMapper.selectCmsTopicById(eq(7L))).thenReturn(topic(7L, OWNER));
        when(metadataMapper.selectByTopicId(eq(7L))).thenReturn(existing(7L, 2L));
        when(metadataMapper.selectTranslation(eq(7L), eq("en"))).thenReturn(translationRow(7L, "en", 4L));
        when(metadataMapper.updateTranslationVersioned(any(), eq(4L), any())).thenReturn(0);

        assertCode(409, () -> service.saveTranslation(translation(7L, "en", 4L), OWNER));
    }

    @Test
    void translationNeverAcceptsBodyOrAnswerFields() {
        // 请求对象只有三段公开摘要字段；即使客户端多传 questionAnswer，也不存在对应 setter 可写入。
        for (java.lang.reflect.Method method : ContentLandingTranslationSaveRequest.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("set")) continue;
            assertFalse(Arrays.asList("setQuestionAnswer", "setCorrectAnswer", "setBlocksJson",
                            "setFragmentText", "setCheckinCode").contains(method.getName()),
                    "译文写面不得出现正文/答案字段: " + method.getName());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void assertCode(int expected, org.junit.jupiter.api.function.Executable action) {
        ServiceException failure = assertThrows(ServiceException.class, action);
        assertEquals(Integer.valueOf(expected), failure.getCode());
    }

    private static CmsTopic topic(Long id, Long memberId) {
        CmsTopic topic = new CmsTopic();
        topic.setId(id);
        topic.setMemberId(memberId);
        topic.setDelFlag(0);
        return topic;
    }

    private static ContentLandingMetadata existing(Long topicId, Long version) {
        ContentLandingMetadata row = new ContentLandingMetadata();
        row.setTopicId(topicId);
        row.setSourceLocale("zh-CN");
        row.setRowVersion(version);
        row.setGroupEligible(0);
        row.setSchoolEligible(0);
        row.setStatus(1);
        return row;
    }

    private static ContentLandingTranslation translationRow(Long topicId, String locale, Long version) {
        ContentLandingTranslation row = new ContentLandingTranslation();
        row.setTopicId(topicId);
        row.setLocale(locale);
        row.setRowVersion(version);
        row.setTranslationState("PENDING");
        return row;
    }

    private static ContentLandingMetadataSaveRequest request(Long topicId, Long expectedVersion) {
        ContentLandingMetadataSaveRequest request = new ContentLandingMetadataSaveRequest();
        request.setTopicId(topicId);
        request.setExpectedVersion(expectedVersion);
        request.setLandingSummary("适合团建的城市解谜");
        request.setGroupEligible(true);
        request.setSchoolEligible(false);
        request.setGroupMinPeople(8);
        request.setGroupMaxPeople(30);
        return request;
    }

    private static ContentLandingTranslationSaveRequest translation(Long topicId, String locale, Long version) {
        ContentLandingTranslationSaveRequest request = new ContentLandingTranslationSaveRequest();
        request.setTopicId(topicId);
        request.setLocale(locale);
        request.setExpectedVersion(version);
        request.setTitle("Shanghai Night Walk");
        request.setSummary("A city puzzle for teams");
        return request;
    }
}
