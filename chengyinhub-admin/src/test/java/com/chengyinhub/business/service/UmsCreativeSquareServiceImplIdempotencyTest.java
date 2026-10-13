package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.mapper.UmsCreativeSquareMapper;
import com.chengyinhub.business.service.impl.UmsCreativeSquareServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R10-03:发布意图键幂等插入的状态机(可见/被拒/待审/冲突/并发唯一键兜底)。 */
class UmsCreativeSquareServiceImplIdempotencyTest {

    private static final Long ME = 9L;
    private static final String RID = "sq_intent_000000000001";

    private UmsCreativeSquareMapper mapper;
    private UmsCreativeSquareServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(UmsCreativeSquareMapper.class);
        service = new UmsCreativeSquareServiceImpl();
        ReflectionTestUtils.setField(service, "umsCreativeSquareMapper", mapper);
    }

    @Test
    void newIntentInsertsOnce() {
        when(mapper.selectUmsCreativeSquareByMemberAndRequestId(ME, RID)).thenReturn(null);
        when(mapper.insertUmsCreativeSquare(any())).thenReturn(1);

        IUmsCreativeSquareService.IdempotentPublishResult result =
                service.insertUmsCreativeSquareIdempotent(post(), RID, "sig-a");

        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.NEW, result.getKind());
        assertNotNull(result.getSquare().getCreateTime());
        assertEquals(RID, result.getSquare().getRequestId());
        assertEquals("sig-a", result.getSquare().getRequestSignature());
    }

    @Test
    void committedButLostResponseRetryReturnsExistingVisible() {
        UmsCreativeSquare existing = post();
        existing.setId(50L);
        existing.setStatus(1);
        existing.setRequestId(RID);
        existing.setRequestSignature("sig-a");
        when(mapper.selectUmsCreativeSquareByMemberAndRequestId(ME, RID)).thenReturn(existing);

        IUmsCreativeSquareService.IdempotentPublishResult result =
                service.insertUmsCreativeSquareIdempotent(post(), RID, "sig-a");

        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.EXISTING_VISIBLE, result.getKind());
        assertEquals(50L, result.getSquare().getId());
        verify(mapper, never()).insertUmsCreativeSquare(any());
    }

    @Test
    void auditRejectedRetryDoesNotInsertAgain() {
        UmsCreativeSquare existing = post();
        existing.setId(51L);
        existing.setStatus(2);
        existing.setRequestSignature("sig-a");
        when(mapper.selectUmsCreativeSquareByMemberAndRequestId(ME, RID)).thenReturn(existing);

        IUmsCreativeSquareService.IdempotentPublishResult result =
                service.insertUmsCreativeSquareIdempotent(post(), RID, "sig-a");

        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.EXISTING_REJECTED, result.getKind());
        verify(mapper, never()).insertUmsCreativeSquare(any());
    }

    @Test
    void softDeletedIntentIsTerminalNotVisibleNotResurrected() {
        // 软删帖 status 仍是 1(可见时被删),旧代码会当 EXISTING_VISIBLE 回假成功
        UmsCreativeSquare existing = post();
        existing.setId(55L);
        existing.setStatus(1);
        existing.setDelFlag(2);
        existing.setRequestSignature("sig-a");
        when(mapper.selectUmsCreativeSquareByMemberAndRequestId(ME, RID)).thenReturn(existing);

        IUmsCreativeSquareService.IdempotentPublishResult result =
                service.insertUmsCreativeSquareIdempotent(post(), RID, "sig-a");

        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.EXISTING_DELETED, result.getKind());
        assertEquals(55L, result.getSquare().getId());
        verify(mapper, never()).insertUmsCreativeSquare(any());
    }

    @Test
    void softDeletedBeatsSignatureConflictAndPending() {
        UmsCreativeSquare deletedAfterEdit = post();
        deletedAfterEdit.setId(56L);
        deletedAfterEdit.setStatus(1);
        deletedAfterEdit.setDelFlag(2);
        deletedAfterEdit.setRequestSignature("sig-old");
        when(mapper.selectUmsCreativeSquareByMemberAndRequestId(ME, RID)).thenReturn(deletedAfterEdit);
        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.EXISTING_DELETED,
                service.insertUmsCreativeSquareIdempotent(post(), RID, "sig-new").getKind());

        UmsCreativeSquare deletedPending = post();
        deletedPending.setId(57L);
        deletedPending.setStatus(0);
        deletedPending.setDelFlag(2);
        deletedPending.setRequestSignature("sig-a");
        when(mapper.selectUmsCreativeSquareByMemberAndRequestId(ME, RID)).thenReturn(deletedPending);
        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.EXISTING_DELETED,
                service.insertUmsCreativeSquareIdempotent(post(), RID, "sig-a").getKind());
        verify(mapper, never()).insertUmsCreativeSquare(any());
    }

    @Test
    void sameKeyDifferentPayloadIsConflict() {
        UmsCreativeSquare existing = post();
        existing.setId(52L);
        existing.setStatus(1);
        existing.setRequestSignature("sig-old");
        when(mapper.selectUmsCreativeSquareByMemberAndRequestId(ME, RID)).thenReturn(existing);

        IUmsCreativeSquareService.IdempotentPublishResult result =
                service.insertUmsCreativeSquareIdempotent(post(), RID, "sig-new");

        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.CONFLICT, result.getKind());
        verify(mapper, never()).insertUmsCreativeSquare(any());
    }

    @Test
    void concurrentInsertLosesRaceAndReadsBackExisting() {
        UmsCreativeSquare existing = post();
        existing.setId(53L);
        existing.setStatus(1);
        existing.setRequestSignature("sig-a");
        when(mapper.selectUmsCreativeSquareByMemberAndRequestId(ME, RID))
                .thenReturn(null)
                .thenReturn(existing);
        when(mapper.insertUmsCreativeSquare(any())).thenThrow(new DuplicateKeyException("uk"));

        IUmsCreativeSquareService.IdempotentPublishResult result =
                service.insertUmsCreativeSquareIdempotent(post(), RID, "sig-a");

        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.EXISTING_VISIBLE, result.getKind());
        assertEquals(53L, result.getSquare().getId());
    }

    @Test
    void pendingRowIsReportedForSingleFlightAudit() {
        UmsCreativeSquare existing = post();
        existing.setId(54L);
        existing.setStatus(0);
        existing.setRequestSignature("sig-a");
        when(mapper.selectUmsCreativeSquareByMemberAndRequestId(ME, RID)).thenReturn(existing);

        IUmsCreativeSquareService.IdempotentPublishResult result =
                service.insertUmsCreativeSquareIdempotent(post(), RID, "sig-a");

        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.PENDING, result.getKind());
    }

    @Test
    void invalidRequestKeyFailsClosedWithoutAnyWrite() {
        IUmsCreativeSquareService.IdempotentPublishResult shortKey =
                service.insertUmsCreativeSquareIdempotent(post(), "short", "sig-a");
        IUmsCreativeSquareService.IdempotentPublishResult blank =
                service.insertUmsCreativeSquareIdempotent(post(), null, "sig-a");
        UmsCreativeSquare noMember = post();
        noMember.setMemberId(null);
        IUmsCreativeSquareService.IdempotentPublishResult anonymous =
                service.insertUmsCreativeSquareIdempotent(noMember, RID, "sig-a");

        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.INVALID, shortKey.getKind());
        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.INVALID, blank.getKind());
        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.INVALID, anonymous.getKind());
        verify(mapper, never()).insertUmsCreativeSquare(any());
    }

    @Test
    void insertZeroRowsIsFailure() {
        when(mapper.selectUmsCreativeSquareByMemberAndRequestId(ME, RID)).thenReturn(null);
        when(mapper.insertUmsCreativeSquare(any())).thenReturn(0);

        IUmsCreativeSquareService.IdempotentPublishResult result =
                service.insertUmsCreativeSquareIdempotent(post(), RID, "sig-a");

        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.FAILED, result.getKind());
    }

    private UmsCreativeSquare post() {
        UmsCreativeSquare square = new UmsCreativeSquare();
        square.setMemberId(ME);
        square.setContents("正文");
        square.setPics("");
        square.setStatus(0);
        square.setDelFlag(0);
        return square;
    }
}
