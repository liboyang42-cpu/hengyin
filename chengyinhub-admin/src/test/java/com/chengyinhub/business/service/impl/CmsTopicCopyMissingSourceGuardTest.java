package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.mapper.LegacyCmsProvenanceMapper;
import com.chengyinhub.business.service.support.LegacyCmsProvenanceGuard;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Focused regression for missing source during template copy with a known target owner.
 * Invokes the real service method; only mapper access is mocked, following
 * TopicCopyTemplateOwnershipTest. Does not validate HTTP reachability, transaction rollback,
 * MySQL, license provenance or deleted-source policy. Unknown owner retains legacy P0 behavior.
 */
class CmsTopicCopyMissingSourceGuardTest {
    private static final Long TEMPLATE_ID = 7001L;
    private static final Long OWNER = 20088L;
    private CmsTopicServiceImpl service;
    private CmsMemberTemplateMapper mapper;
    private Map<Long, Long> copiedIds;

    @BeforeEach
    void setUp() {
        service = new CmsTopicServiceImpl();
        // These existing fixtures exercise legacy copying; provenance denial has dedicated tests.
        LegacyCmsProvenanceMapper provenanceMapper = mock(LegacyCmsProvenanceMapper.class);
        org.mockito.Mockito.when(provenanceMapper.selectLegacyBlockState(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(0);
        ReflectionTestUtils.setField(service, "legacyCmsProvenanceGuard", new LegacyCmsProvenanceGuard(provenanceMapper));
        com.chengyinhub.business.mapper.MerchantAuthoringCopyMapper authoringCopies =
                mock(com.chengyinhub.business.mapper.MerchantAuthoringCopyMapper.class);
        when(authoringCopies.selectCopyBlockState(TEMPLATE_ID)).thenReturn(0);
        ReflectionTestUtils.setField(service, "merchantAuthoringCopyGuard",
                new com.chengyinhub.business.service.support.MerchantAuthoringCopyGuard(authoringCopies));
        mapper = mock(CmsMemberTemplateMapper.class);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateMapper", mapper);
        copiedIds = new HashMap<>();
    }

    private Long copy(Long templateId, Long owner, boolean forceCopy) {
        return ReflectionTestUtils.invokeMethod(service, "copyTemplateForOwner",
                templateId, owner, copiedIds, forceCopy);
    }

    private CmsMemberTemplate source(Long owner) {
        CmsMemberTemplate row = new CmsMemberTemplate();
        row.setId(TEMPLATE_ID);
        row.setMemberId(owner);
        row.setOriginalTemplateId(3001L);
        row.setDelFlag(0);
        row.setStatus(1L);
        row.setDraftStatus(1);
        return row;
    }

    private void assertMissingSourceRejected(boolean forceCopy) {
        when(mapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(null);
        ServiceException error = assertThrows(ServiceException.class,
                () -> copy(TEMPLATE_ID, OWNER, forceCopy));
        assertTrue(error.getMessage().contains("来源节点模板不存在"));
        assertTrue(copiedIds.isEmpty(), "Failed lookup must not cache the missing source as a reusable ID");
        verify(mapper).selectCmsMemberTemplateById(TEMPLATE_ID);
        verify(mapper, never()).insertCmsMemberTemplate(any(CmsMemberTemplate.class));
    }

    @Test
    void missingSourceRejectsWithoutCachingOrInserting() {
        assertMissingSourceRejected(false);
    }

    @Test
    void forceCopyAlsoRejectsMissingSourceWithoutCachingOrInserting() {
        assertMissingSourceRejected(true);
    }

    @Test
    void validSameOwnerReusesExistingIdWithoutCopy() {
        when(mapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(source(OWNER));
        assertEquals(TEMPLATE_ID, copy(TEMPLATE_ID, OWNER, false));
        assertEquals(TEMPLATE_ID, copiedIds.get(TEMPLATE_ID));
        verify(mapper).selectCmsMemberTemplateById(TEMPLATE_ID);
        verify(mapper, never()).insertCmsMemberTemplate(any(CmsMemberTemplate.class));
    }

    private void assertValidCopy(Long sourceOwner, boolean forceCopy) {
        CmsMemberTemplate original = source(sourceOwner);
        when(mapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(original);
        doAnswer(invocation -> {
            CmsMemberTemplate inserted = invocation.getArgument(0);
            inserted.setId(8001L);
            return 1;
        }).when(mapper).insertCmsMemberTemplate(any(CmsMemberTemplate.class));

        assertEquals(Long.valueOf(8001L), copy(TEMPLATE_ID, OWNER, forceCopy));
        assertEquals(Long.valueOf(8001L), copy(TEMPLATE_ID, OWNER, forceCopy));
        assertEquals(Long.valueOf(8001L), copiedIds.get(TEMPLATE_ID));
        ArgumentCaptor<CmsMemberTemplate> inserted = ArgumentCaptor.forClass(CmsMemberTemplate.class);
        verify(mapper, times(1)).insertCmsMemberTemplate(inserted.capture());
        verify(mapper, times(1)).selectCmsMemberTemplateById(TEMPLATE_ID);
        assertNotSame(original, inserted.getValue());
        assertEquals(OWNER, inserted.getValue().getMemberId());
        assertEquals(Long.valueOf(3001L), inserted.getValue().getOriginalTemplateId());
        assertEquals(Integer.valueOf(0), inserted.getValue().getIsSync());
        assertEquals(TEMPLATE_ID, original.getId());
        assertEquals(sourceOwner, original.getMemberId());
    }

    @Test
    void validForeignOwnerSourceCopiesOnceAndPreservesLibraryLineage() {
        assertValidCopy(30099L, false);
    }

    @Test
    void validSameOwnerForceCopyCreatesSeparateRowOnce() {
        assertValidCopy(OWNER, true);
    }

    @Test
    void missingOwnerPreservesLegacyNoCopyBehavior() {
        assertEquals(TEMPLATE_ID, copy(TEMPLATE_ID, null, false));
        assertTrue(copiedIds.isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void absentTemplateIsNoOpWithoutMapperAccess() {
        for (Long templateId : new Long[] {null, 0L, -1L}) {
            assertEquals(templateId, copy(templateId, OWNER, false));
        }
        assertTrue(copiedIds.isEmpty());
        verifyNoInteractions(mapper);
    }
}
