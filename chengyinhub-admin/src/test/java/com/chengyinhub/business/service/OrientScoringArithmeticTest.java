package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OrientScoreAppeal;
import com.chengyinhub.business.domain.OrientScoreEntry;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.OrientScoringMapper;
import com.chengyinhub.business.service.impl.OrientScoringServiceImpl;
import com.chengyinhub.business.service.support.OrientScoringException;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrientScoringArithmeticTest {
    private final OrientScoringServiceImpl service = new OrientScoringServiceImpl();
    private final OrientScoringMapper mapper = mock(OrientScoringMapper.class);
    private final List<OrientScoreEntry> ledger = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ISysConfigService config = mock(ISysConfigService.class);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "configService", config);
        ReflectionTestUtils.setField(service, "cmsTopicService", topics);
        when(config.selectConfigByKey("orient.scoring.enabled")).thenReturn("true");

        CmsTopic topic = new CmsTopic();
        topic.setId(300L);
        topic.setProductType(1);
        when(topics.selectCmsTopicById(300L)).thenReturn(topic);
        TopicRouteSession session = new TopicRouteSession();
        session.setId(10L);
        session.setActivityId(500L);
        session.setTopicId(300L);
        session.setMemberId(7L);
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        when(mapper.selectSessionForUpdate(10L)).thenReturn(session);

        // 与同域测试一致,插入后按幂等键和冲正关系回读内存流水。
        when(mapper.insertEntry(any())).thenAnswer(inv -> {
            OrientScoreEntry entry = inv.getArgument(0);
            entry.setId((long) ledger.size() + 1);
            ledger.add(entry);
            return 1;
        });
        when(mapper.selectEntryById(anyLong())).thenAnswer(inv -> ledger.stream()
                .filter(entry -> entry.getId().equals(inv.getArgument(0))).findFirst().orElse(null));
        when(mapper.selectEntryByIdem(eq(10L), anyString())).thenAnswer(inv -> ledger.stream()
                .filter(entry -> entry.getIdempotencyKey().equals(inv.getArgument(1))).findFirst().orElse(null));
        when(mapper.selectEntryByReverses(anyLong())).thenAnswer(inv -> ledger.stream()
                .filter(entry -> inv.getArgument(0).equals(entry.getReversesEntryId())).findFirst().orElse(null));
    }

    @Test
    void legacyMinimumCannotBeReversedOrWritten() {
        OrientScoreEntry original = legacyMinimum();

        assertEquals(OrientScoringException.INVALID_ARGUMENT, assertThrows(OrientScoringException.class,
                () -> service.reverseEntry(original.getId(), "WRONG_NODE", 1L, "reverse-min")).getErrorCode());

        assertEquals(Integer.MIN_VALUE, original.getDeltaCenti());
        assertNull(original.getReversesEntryId());
        assertEquals(List.of(original), ledger);
        verifyNoWrites();
    }

    @Test
    void appealCannotReverseLegacyMinimumOrChangePendingStatus() {
        OrientScoreEntry original = legacyMinimum();
        OrientScoreAppeal pending = pendingAppeal(original.getId());

        assertEquals(OrientScoringException.INVALID_ARGUMENT, assertThrows(OrientScoringException.class,
                () -> service.resolveAppeal(3L, true, null, "冲正", "admin", 1L)).getErrorCode());

        assertEquals(Integer.MIN_VALUE, original.getDeltaCenti());
        assertEquals(List.of(original), ledger);
        assertPending(pending);
        verifyNoWrites();
    }

    @Test
    void appendRejectsMinimumBeforeWriting() {
        assertEquals(OrientScoringException.INVALID_ARGUMENT, assertThrows(OrientScoringException.class,
                () -> append(Integer.MIN_VALUE)).getErrorCode());

        assertEquals(List.of(), ledger);
        verifyNoWrites();
    }

    @Test
    void appealRejectsMinimumAdjustmentBeforeWritingOrChangingStatus() {
        OrientScoreAppeal pending = pendingAppeal(null);

        assertEquals(OrientScoringException.INVALID_ARGUMENT, assertThrows(OrientScoringException.class,
                () -> service.resolveAppeal(3L, true, Integer.MIN_VALUE, "补记", "admin", 1L)).getErrorCode());

        assertEquals(List.of(), ledger);
        assertPending(pending);
        verifyNoWrites();
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MAX_VALUE, -Integer.MAX_VALUE, 0, 500, -500})
    void signedValuesReverseExactlyAndReplayWithoutAnotherWrite(int delta) {
        OrientScoreEntry original = append(delta);
        assertSame(original, append(delta));
        OrientScoreEntry reversal = service.reverseEntry(original.getId(), "WRONG_NODE", 1L, "reverse");

        assertEquals(-delta, reversal.getDeltaCenti());
        assertEquals(original.getId(), reversal.getReversesEntryId());
        assertEquals(delta, original.getDeltaCenti());
        assertNull(original.getReversesEntryId());
        assertEquals(0L, (long) original.getDeltaCenti() + reversal.getDeltaCenti());
        assertSame(reversal, service.reverseEntry(original.getId(), "WRONG_NODE", 1L, "reverse"));
        assertEquals(OrientScoringException.ALREADY_REVERSED, assertThrows(OrientScoringException.class,
                () -> service.reverseEntry(original.getId(), "WRONG_NODE", 1L, "reverse-again")).getErrorCode());
        assertEquals(List.of(original, reversal), ledger);
        verify(mapper, times(2)).insertEntry(any());
        verify(mapper, never()).resolveAppeal(any());
    }

    private OrientScoreEntry append(int delta) {
        return service.appendEntry(10L, OrientScoreEntry.DIM_ADJUSTMENT, delta, "CORRECTION", "ADMIN",
                "admin:1", null, "original");
    }

    private OrientScoreEntry legacyMinimum() {
        OrientScoreEntry original = new OrientScoreEntry();
        original.setId(1L);
        original.setSessionId(10L);
        original.setDimension(OrientScoreEntry.DIM_ADJUSTMENT);
        original.setDeltaCenti(Integer.MIN_VALUE);
        original.setIdempotencyKey("legacy-min");
        ledger.add(original);
        return original;
    }

    private OrientScoreAppeal pendingAppeal(Long entryId) {
        OrientScoreAppeal pending = new OrientScoreAppeal();
        pending.setId(3L);
        pending.setSessionId(10L);
        pending.setMemberId(7L);
        pending.setEntryId(entryId);
        pending.setStatus(OrientScoreAppeal.STATUS_PENDING);
        when(mapper.selectAppealById(3L)).thenReturn(pending);
        return pending;
    }

    private void assertPending(OrientScoreAppeal pending) {
        assertEquals(OrientScoreAppeal.STATUS_PENDING, pending.getStatus());
        assertNull(pending.getResolutionEntryId());
        assertNull(pending.getHandledAt());
        assertNull(pending.getUpdateTime());
    }

    private void verifyNoWrites() {
        verify(mapper, never()).insertEntry(any());
        verify(mapper, never()).insertSettlement(any());
        verify(mapper, never()).insertAppeal(any());
        verify(mapper, never()).insertDispute(any());
        verify(mapper, never()).resolveAppeal(any());
        verify(mapper, never()).resolveDispute(any());
    }
}
