package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.LegacyCmsProvenanceGuard;
import com.chengyinhub.business.service.support.MerchantAuthoringCopyGuard;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real copy branches and real guards; collaborator mocks prove no insert, not DB rollback. */
class MerchantAuthoringCopyBoundaryTest {
    private CmsTopicServiceImpl topic;
    private TopicEditionReopenServiceImpl edition;
    private CmsMemberTemplateMapper templates;
    private MerchantAuthoringCopyMapper sources;
    private CmsMemberTemplate source;
    private Map<Long,Long> ids;
    private Map<Long,String> blocks;

    @BeforeEach void setUp() {
        templates = mock(CmsMemberTemplateMapper.class);
        sources = mock(MerchantAuthoringCopyMapper.class);
        LegacyCmsProvenanceMapper w18 = mock(LegacyCmsProvenanceMapper.class);
        when(w18.selectLegacyBlockState(1L)).thenReturn(0);
        topic = new CmsTopicServiceImpl();
        edition = new TopicEditionReopenServiceImpl(mock(TopicEditionReopenMapper.class),
                mock(ProductRoleMapper.class), templates, mock(ClubMapper.class), mock(UmsMemberMapper.class), mock(NpcProfileMapper.class));
        ReflectionTestUtils.setField(topic, "cmsMemberTemplateMapper", templates);
        for (Object service : new Object[]{topic, edition}) {
            ReflectionTestUtils.setField(service,"legacyCmsProvenanceGuard",new LegacyCmsProvenanceGuard(w18));
            ReflectionTestUtils.setField(service,"merchantAuthoringCopyGuard",new MerchantAuthoringCopyGuard(sources));
        }
        source = new CmsMemberTemplate(); source.setId(1L); source.setMemberId(10L);
        source.setOriginalTemplateId(555L);
        source.setAdvancedConfigJson("{\"schemaVersion\":1,\"enter\":{\"npcId\":501}}");
        when(templates.selectCmsMemberTemplateById(1L)).thenReturn(source);
        ids = new HashMap<>(); blocks = new HashMap<>();
    }
    private Long topic(Long owner, boolean force) {
        return ReflectionTestUtils.invokeMethod(topic,"copyTemplateForOwner",1L,owner,ids,force);
    }
    private Long edition() {
        return ReflectionTestUtils.invokeMethod(edition,"editionTemplateId",1L,10L,ids,blocks);
    }
    private void noInsert() {
        verify(templates,never()).insertCmsMemberTemplate(any(CmsMemberTemplate.class));
        assertTrue(ids.isEmpty()); assertTrue(blocks.isEmpty());
    }
    @Test void sourceBoundCrossOwnerCopyRejectsBeforeInsert() {
        when(sources.selectCopyBlockState(1L)).thenReturn(1);
        assertThrows(ServiceException.class, () -> topic(20L,false)); noInsert();
    }
    @Test void sourceBoundSameOwnerForcedNpcCopyRejects() {
        when(sources.selectCopyBlockState(1L)).thenReturn(1);
        assertThrows(ServiceException.class, () -> topic(10L,true)); noInsert();
    }
    @Test void sourceBoundEditionNpcCopyRejects() {
        when(sources.selectCopyBlockState(1L)).thenReturn(1);
        assertThrows(ServiceException.class, this::edition); noInsert();
    }
    @Test void queryFailuresAndMissingRowsRejectBothNewIdBranches() {
        when(sources.selectCopyBlockState(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> topic(20L,false));
        assertThrows(ServiceException.class, this::edition);
        DataAccessResourceFailureException error = new DataAccessResourceFailureException("source query failed");
        when(sources.selectCopyBlockState(1L)).thenThrow(error);
        assertSame(error, assertThrows(DataAccessResourceFailureException.class, () -> topic(20L,false)));
        assertSame(error, assertThrows(DataAccessResourceFailureException.class, this::edition)); noInsert();
    }
    @Test void sameOwnerAndUnknownOwnerReuseKeepOriginalSourceIdentity() {
        assertEquals(1L, topic(null,false));
        assertEquals(1L, topic(10L,false));
        assertEquals(1L, topic(10L,false));
        verifyNoInteractions(sources);
        verify(templates,never()).insertCmsMemberTemplate(any(CmsMemberTemplate.class));
    }
    @Test void nonNpcEditionKeepsSourceIdentityIncludingCache() {
        source.setAdvancedConfigJson("{}");
        assertEquals(1L,edition()); assertEquals(1L,edition());
        verifyNoInteractions(sources);
        verify(templates,never()).insertCmsMemberTemplate(any(CmsMemberTemplate.class));
    }
    @Test void unboundTopicCopiesOnceAndRetainsPublicLibraryOriginNotSourceId() {
        when(sources.selectCopyBlockState(1L)).thenReturn(0);
        when(templates.insertCmsMemberTemplate(any())).thenAnswer(call -> {
            CmsMemberTemplate value=call.getArgument(0); assertNull(value.getId());
            assertEquals(555L,value.getOriginalTemplateId()); assertNull(value.getW18ProvenanceKind());
            value.setId(2L); return 1;
        });
        assertEquals(2L,topic(20L,false)); assertEquals(2L,topic(20L,false));
        verify(sources,times(1)).selectCopyBlockState(1L);
        verify(templates,times(1)).insertCmsMemberTemplate(any());
    }
    @Test void unboundEditionNpcCopiesOnceAndCachesBlocks() {
        when(sources.selectCopyBlockState(1L)).thenReturn(0);
        when(templates.insertCmsMemberTemplate(any())).thenAnswer(call -> {
            call.getArgument(0,CmsMemberTemplate.class).setId(2L); return 1;
        });
        assertEquals(2L,edition()); assertEquals(2L,edition());
        assertEquals(source.getAdvancedConfigJson(),blocks.get(2L));
        verify(sources,times(1)).selectCopyBlockState(1L);
        verify(templates,times(1)).insertCmsMemberTemplate(any());
    }
}
