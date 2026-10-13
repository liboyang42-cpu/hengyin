package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.TopicSaleCanonicalDraft;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.impl.TopicSaleCanonicalMutationServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopicSaleCanonicalMutationServiceTest {

    @Test
    void everySuccessfulLiveMutationAdvancesVersionEvenWhenValueIsChangedBack() {
        TopicSaleSnapshotMapper mapper = mock(TopicSaleSnapshotMapper.class);
        AtomicLong version = new AtomicLong(1L);
        TopicSaleCanonicalDraft draft = draft(1L);
        when(mapper.selectDraftByTopicIdForUpdate(83L)).thenAnswer(invocation -> draft(version.get()));
        doAnswer(invocation -> {
            Long expected = invocation.getArgument(1);
            if (version.compareAndSet(expected, expected + 1L)) return 1;
            return 0;
        }).when(mapper).advanceDraftVersion(eq(83L), any(Long.class), any());
        TopicSaleCanonicalMutationServiceImpl service = new TopicSaleCanonicalMutationServiceImpl(mapper);

        assertEquals(2L, service.invalidateApprovedCanonical(83L, "ticket-price-to-99"));
        assertEquals(3L, service.invalidateApprovedCanonical(83L, "ticket-price-back-to-69"));
        assertEquals(3L, version.get());
    }

    @Test
    void mutationBeforeCanonicalDraftExistsDoesNotInventAContract() {
        TopicSaleSnapshotMapper mapper = mock(TopicSaleSnapshotMapper.class);
        when(mapper.selectDraftByTopicIdForUpdate(83L)).thenReturn(null);
        TopicSaleCanonicalMutationServiceImpl service = new TopicSaleCanonicalMutationServiceImpl(mapper);

        assertNull(service.invalidateApprovedCanonical(83L, "chapter-created"));
    }

    private static TopicSaleCanonicalDraft draft(Long version) {
        TopicSaleCanonicalDraft draft = new TopicSaleCanonicalDraft();
        draft.setTopicId(83L);
        draft.setParameterVersion(version);
        draft.setSchemaVersion("CANONICAL_15_V1");
        draft.setCanonicalJson("{}");
        draft.setUpdatedBy(100L);
        return draft;
    }
}
