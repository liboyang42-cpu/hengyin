package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.LegacyCmsProvenanceMapper;
import com.chengyinhub.business.service.support.LegacyCmsProvenanceGuard;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Real copy method plus real provenance guard; only query collaborators are mocked.
 * Tests guard ordering around early returns, not SQL marker/sidecar semantics. Those are covered
 * separately by LegacyCmsProvenanceGuardMyBatisTest. No DB locks or transaction rollback claim.
 */
class CmsTopicCopyProvenanceGuardTest {
    private static final Long TEMPLATE = 7001L;
    private static final Long OWNER = 20088L;
    private CmsTopicServiceImpl service;
    private CmsMemberTemplateMapper templates;
    private LegacyCmsProvenanceMapper provenance;
    private Map<Long, Long> copiedIds;

    @BeforeEach
    void setUp() {
        service = new CmsTopicServiceImpl();
        templates = mock(CmsMemberTemplateMapper.class);
        provenance = mock(LegacyCmsProvenanceMapper.class);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateMapper", templates);
        ReflectionTestUtils.setField(service, "legacyCmsProvenanceGuard", new LegacyCmsProvenanceGuard(provenance));
        copiedIds = new HashMap<>();
    }

    private Long copy(Long id, Long owner, boolean force) {
        return ReflectionTestUtils.invokeMethod(service, "copyTemplateForOwner", id, owner, copiedIds, force);
    }

    private void blocked(Long owner, boolean force) {
        Map<Long, Long> before = new HashMap<>(copiedIds);
        when(provenance.selectLegacyBlockState(TEMPLATE)).thenReturn(1);
        assertThrows(ServiceException.class, () -> copy(TEMPLATE, owner, force));
        assertEquals(before, copiedIds, "Denied copy must not populate or overwrite the identity cache");
        verify(provenance).selectLegacyBlockState(TEMPLATE);
        verifyNoInteractions(templates);
    }

    @Test
    void protectedTemplateCannotReachSameOwnerReuse() {
        blocked(OWNER, false);
    }

    @Test
    void protectedTemplateCannotUseUnknownOwnerEarlyReturn() {
        blocked(null, false);
    }

    @Test
    void protectedTemplateCannotReachForceCopy() {
        blocked(OWNER, true);
    }

    @Test
    void protectedTemplateCannotUseCachedCopyId() {
        copiedIds.put(TEMPLATE, 8001L);
        blocked(OWNER, false);
    }

    @Test
    void missingProvenanceRowIsRejectedBeforeTemplateReadOrCacheReturn() {
        copiedIds.put(TEMPLATE, TEMPLATE);
        when(provenance.selectLegacyBlockState(TEMPLATE)).thenReturn(null);
        assertThrows(ServiceException.class, () -> copy(TEMPLATE, OWNER, false));
        assertEquals(TEMPLATE, copiedIds.get(TEMPLATE));
        verifyNoInteractions(templates);
    }

    @Test
    void queryFailureCannotFallBackToUnknownOwnerReuse() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("query unavailable");
        when(provenance.selectLegacyBlockState(TEMPLATE)).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> copy(TEMPLATE, null, false)));
        assertTrue(copiedIds.isEmpty());
        verifyNoInteractions(templates);
    }

    @Test
    void everyPositiveReferenceRechecksProvenanceEvenAfterLegalCacheEntry() {
        when(provenance.selectLegacyBlockState(TEMPLATE)).thenReturn(0, 1);
        CmsMemberTemplate source = new CmsMemberTemplate();
        source.setId(TEMPLATE);
        source.setMemberId(OWNER);
        when(templates.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(source);
        assertEquals(TEMPLATE, copy(TEMPLATE, OWNER, false));
        assertThrows(ServiceException.class, () -> copy(TEMPLATE, OWNER, false));
        verify(provenance, times(2)).selectLegacyBlockState(TEMPLATE);
        verify(templates, times(1)).selectCmsMemberTemplateById(TEMPLATE);
    }

    @Test
    void confirmedLegacyUnknownOwnerPreservesNoCopyCompatibility() {
        when(provenance.selectLegacyBlockState(TEMPLATE)).thenReturn(0);
        assertEquals(TEMPLATE, copy(TEMPLATE, null, false));
        verify(provenance).selectLegacyBlockState(TEMPLATE);
        verifyNoInteractions(templates);
    }

    @Test
    void absentTemplateRemainsNoOpWithoutProvenanceQuery() {
        for (Long id : new Long[] {null, 0L, -1L}) assertEquals(id, copy(id, OWNER, false));
        verifyNoInteractions(provenance, templates);
    }
}
