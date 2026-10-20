package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.LegacyCmsProvenanceGuard;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real private service boundaries and real guard, with explicit mapper states.
 * This is not database, transaction or public HTTP authorization evidence. */
class CmsTopicReferenceProvenanceGuardTest {
    private static final String[] BATCH_GATES = {"assertBlockNarrativeNpcReferences",
            "assertBlockSysVarReferences", "assertJourneyStoryReferences"};
    private CmsTopicServiceImpl service;
    private CmsMemberTemplateMapper templates;
    private CmsTopicNodeMapper nodes;
    private LegacyCmsProvenanceMapper provenance;
    private CmsTopic topic;

    @BeforeEach void setUp() {
        service = new CmsTopicServiceImpl();
        templates = mock(CmsMemberTemplateMapper.class);
        nodes = mock(CmsTopicNodeMapper.class);
        provenance = mock(LegacyCmsProvenanceMapper.class);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateMapper", templates);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", mock(CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(service, "npcProfileMapper", mock(NpcProfileMapper.class));
        ReflectionTestUtils.setField(service, "legacyCmsProvenanceGuard", new LegacyCmsProvenanceGuard(provenance));
        topic = new CmsTopic(); topic.setId(50L); topic.setJourneyStoryJson("{\"schemaVersion\":1}");
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(node(9L), node(3L), node(9L)));
    }
    private CmsTopicNode node(Long templateId) {
        CmsTopicNode row = new CmsTopicNode(); row.setId(templateId == null ? 100L : templateId + 100L);
        row.setTemplateId(templateId); return row;
    }
    private void invoke(String gate) { ReflectionTestUtils.invokeMethod(service, gate, topic); }
    private void owned(boolean allowDraft) { ReflectionTestUtils.invokeMethod(service, "assertTemplateOwned", 3L, 7L, allowDraft); }
    private CmsMemberTemplate template(boolean draft) {
        CmsMemberTemplate row = new CmsMemberTemplate(); row.setId(3L); row.setMemberId(7L);
        row.setStatus(draft ? 0L : 1L); row.setDraftStatus(draft ? 0 : 1);
        row.setCheckStatus(0); row.setPublishStatus(0L); row.setDelFlag(0);
        when(templates.selectCmsMemberTemplateById(3L)).thenReturn(row); return row;
    }

    @Test void allThreeBatchGatesRejectProtectedBeforeAnyFilteredBodyRead() {
        for (String gate : BATCH_GATES) {
            reset(provenance, templates);
            when(provenance.selectLegacyBlockState(3L)).thenReturn(0);
            when(provenance.selectLegacyBlockState(9L)).thenReturn(1);
            assertThrows(ServiceException.class, () -> invoke(gate));
            InOrder order = inOrder(provenance);
            order.verify(provenance).selectLegacyBlockState(3L);
            order.verify(provenance).selectLegacyBlockState(9L);
            verifyNoInteractions(templates);
        }
    }
    @Test void allThreeBatchGatesRejectMissingAndUnknownStates() {
        for (String gate : BATCH_GATES) for (Integer state : new Integer[]{null, -1, 2}) {
            reset(provenance, templates);
            when(provenance.selectLegacyBlockState(3L)).thenReturn(state);
            assertThrows(ServiceException.class, () -> invoke(gate));
            verifyNoInteractions(templates);
        }
    }
    @Test void allThreeBatchGatesPropagateDatabaseFailureWithoutBodyFallback() {
        for (String gate : BATCH_GATES) {
            reset(provenance, templates);
            DataAccessResourceFailureException failure = new DataAccessResourceFailureException("schema missing");
            when(provenance.selectLegacyBlockState(3L)).thenThrow(failure);
            assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> invoke(gate)));
            verifyNoInteractions(templates);
        }
    }
    @Test void legacyReferencesAreCheckedSortedOnceBeforeBatchEvenWhenBatchIsEmpty() {
        for (String gate : BATCH_GATES) {
            reset(provenance, templates);
            when(provenance.selectLegacyBlockState(anyLong())).thenReturn(0);
            assertDoesNotThrow(() -> invoke(gate));
            InOrder order = inOrder(provenance, templates);
            order.verify(provenance).selectLegacyBlockState(3L);
            order.verify(provenance).selectLegacyBlockState(9L);
            order.verify(templates).selectCmsMemberTemplateByIds(Arrays.asList(9L, 3L));
            verifyNoMoreInteractions(provenance);
        }
    }
    @Test void nonpositiveReferencesDoNotInvokeGuardOrBatch() {
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(node(null), node(0L), node(-1L)));
        for (String gate : BATCH_GATES) assertDoesNotThrow(() -> invoke(gate));
        verifyNoInteractions(provenance, templates);
    }
    @Test void ownedPrivateDraftCannotBypassProtectedSourceCheck() {
        template(true); when(provenance.selectLegacyBlockState(3L)).thenReturn(1);
        assertThrows(ServiceException.class, () -> owned(true));
    }
    @Test void ownedPublishedTemplateCannotBindProtectedOrMissingSource() {
        template(false);
        for (Integer state : new Integer[]{1, null, 2}) {
            when(provenance.selectLegacyBlockState(3L)).thenReturn(state);
            assertThrows(ServiceException.class, () -> owned(false));
        }
    }
    @Test void legitimateOwnedDraftAndPublishedReferencesKeepExistingStatusPolicy() {
        when(provenance.selectLegacyBlockState(3L)).thenReturn(0);
        template(true); assertDoesNotThrow(() -> owned(true));
        assertThrows(RuntimeException.class, () -> owned(false));
        template(false); assertDoesNotThrow(() -> owned(false));
    }
    @Test void existenceAndOwnershipStillRejectBeforeProvenanceRead() {
        assertThrows(RuntimeException.class, () -> owned(true));
        CmsMemberTemplate row = template(true); row.setMemberId(8L);
        assertThrows(RuntimeException.class, () -> owned(true));
        row.setMemberId(7L); row.setDelFlag(2);
        assertThrows(RuntimeException.class, () -> owned(true));
        verifyNoInteractions(provenance);
    }
    @Test void absentTemplateReferenceRemainsNoOp() {
        for (Long id : new Long[]{null, 0L, -1L})
            ReflectionTestUtils.invokeMethod(service, "assertTemplateOwned", id, 7L, true);
        verifyNoInteractions(provenance, templates);
    }
}
