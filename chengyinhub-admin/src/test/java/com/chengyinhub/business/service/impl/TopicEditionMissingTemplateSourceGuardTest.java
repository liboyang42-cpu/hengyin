package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.LegacyCmsProvenanceMapper;
import com.chengyinhub.business.service.support.LegacyCmsProvenanceGuard;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real reopen service helper, mocked mapper. No HTTP/transaction/MySQL/license acceptance. */
class TopicEditionMissingTemplateSourceGuardTest {
    private static final Long TEMPLATE = 7001L;
    private static final Long OWNER = 20088L;
    private static final String NPC_CONFIG = "{\"schemaVersion\":1,\"enter\":{\"npcId\":501}}";
    private CmsMemberTemplateMapper mapper;
    private LegacyCmsProvenanceMapper provenanceMapper;
    private TopicEditionReopenServiceImpl service;
    private Map<Long, Long> ids;
    private Map<Long, String> blocks;

    @BeforeEach
    void setUp() {
        mapper = mock(CmsMemberTemplateMapper.class);
        service = new TopicEditionReopenServiceImpl(null, null, mapper, null, null, null);
        provenanceMapper = mock(LegacyCmsProvenanceMapper.class);
        when(provenanceMapper.selectLegacyBlockState(TEMPLATE)).thenReturn(0);
        ReflectionTestUtils.setField(service, "legacyCmsProvenanceGuard", new LegacyCmsProvenanceGuard(provenanceMapper));
        com.chengyinhub.business.mapper.MerchantAuthoringCopyMapper authoringCopies =
                mock(com.chengyinhub.business.mapper.MerchantAuthoringCopyMapper.class);
        when(authoringCopies.selectCopyBlockState(TEMPLATE)).thenReturn(0);
        ReflectionTestUtils.setField(service, "merchantAuthoringCopyGuard",
                new com.chengyinhub.business.service.support.MerchantAuthoringCopyGuard(authoringCopies));
        ids = new HashMap<>();
        blocks = new HashMap<>();
    }

    private Long resolve(Long templateId) {
        return ReflectionTestUtils.invokeMethod(service, "editionTemplateId", templateId, OWNER, ids, blocks);
    }

    private CmsMemberTemplate source(String configuration) {
        CmsMemberTemplate source = new CmsMemberTemplate();
        source.setId(TEMPLATE);
        source.setMemberId(OWNER);
        source.setOriginalTemplateId(3001L);
        source.setAdvancedConfigJson(configuration);
        return source;
    }

    @Test
    void missingSourceRejectsBeforeCachingOrInsertAndRemainsRejectedOnRepeat() {
        when(mapper.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(null);
        for (int attempt = 0; attempt < 2; attempt++) {
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> resolve(TEMPLATE));
            assertTrue(error.getMessage().contains("来源模板不存在"));
            assertTrue(ids.isEmpty());
            assertTrue(blocks.isEmpty());
        }
        verify(mapper, times(2)).selectCmsMemberTemplateById(TEMPLATE);
        verify(mapper, never()).insertCmsMemberTemplate(any(CmsMemberTemplate.class));
    }

    @Test
    void validTemplateWithoutNpcKeepsSharedIdentityAndCachesOnlyValidSource() {
        when(mapper.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(source("{}"));
        assertEquals(TEMPLATE, resolve(TEMPLATE));
        assertEquals(TEMPLATE, ids.get(TEMPLATE));
        assertEquals(TEMPLATE, resolve(TEMPLATE));
        assertTrue(blocks.isEmpty());
        verify(mapper, times(1)).selectCmsMemberTemplateById(TEMPLATE);
        verify(mapper, never()).insertCmsMemberTemplate(any(CmsMemberTemplate.class));
    }

    @Test
    void nullZeroAndNegativeReferencesRemainNoOpWithoutMapperAccess() {
        assertNull(resolve(null));
        assertEquals(Long.valueOf(0L), resolve(0L));
        assertEquals(Long.valueOf(-1L), resolve(-1L));
        assertTrue(ids.isEmpty());
        assertTrue(blocks.isEmpty());
        verifyNoInteractions(mapper, provenanceMapper);
    }

    @Test
    void validNpcTemplateStillCopiesEvenForSameOwnerAndRegistersBlocksOnce() {
        CmsMemberTemplate original = source(NPC_CONFIG);
        when(mapper.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(original);
        doAnswer(invocation -> {
            CmsMemberTemplate copy = invocation.getArgument(0);
            copy.setId(8001L);
            return 1;
        }).when(mapper).insertCmsMemberTemplate(any(CmsMemberTemplate.class));
        assertEquals(Long.valueOf(8001L), resolve(TEMPLATE));
        assertEquals(Long.valueOf(8001L), resolve(TEMPLATE));
        ArgumentCaptor<CmsMemberTemplate> inserted = ArgumentCaptor.forClass(CmsMemberTemplate.class);
        verify(mapper, times(1)).insertCmsMemberTemplate(inserted.capture());
        assertNotSame(original, inserted.getValue());
        assertEquals(OWNER, inserted.getValue().getMemberId());
        assertEquals(Long.valueOf(3001L), inserted.getValue().getOriginalTemplateId());
        assertEquals(NPC_CONFIG, blocks.get(8001L));
        assertEquals(TEMPLATE, original.getId());
        verify(mapper, times(1)).selectCmsMemberTemplateById(TEMPLATE);
    }
    @Test
    void markedOrUnknownSourceRejectsBeforeBodyReadCacheOrInsert() {
        for (Integer state : new Integer[] {1, null}) {
            when(provenanceMapper.selectLegacyBlockState(TEMPLATE)).thenReturn(state);
            assertThrows(ServiceException.class, () -> resolve(TEMPLATE));
            assertTrue(ids.isEmpty());
            assertTrue(blocks.isEmpty());
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void existingCopyCacheCannotBypassCurrentProvenanceGate() {
        ids.put(TEMPLATE, 8001L);
        blocks.put(8001L, NPC_CONFIG);
        for (Integer state : new Integer[] {1, null}) {
            when(provenanceMapper.selectLegacyBlockState(TEMPLATE)).thenReturn(state);
            assertThrows(ServiceException.class, () -> resolve(TEMPLATE));
            assertEquals(Long.valueOf(8001L), ids.get(TEMPLATE));
            assertEquals(1, ids.size());
            assertEquals(NPC_CONFIG, blocks.get(8001L));
            assertEquals(1, blocks.size());
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void provenanceDatabaseFailureNeverFallsBackToSourceOrCachedCopy() {
        ids.put(TEMPLATE, 8001L);
        IllegalStateException failure = new IllegalStateException("synthetic query failure");
        when(provenanceMapper.selectLegacyBlockState(TEMPLATE)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> resolve(TEMPLATE)));
        assertEquals(Long.valueOf(8001L), ids.get(TEMPLATE));
        assertTrue(blocks.isEmpty());
        verifyNoInteractions(mapper);
    }

}
