package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WechatMemberRegistrationTest {
    private final UmsMemberMapper mapper = mock(UmsMemberMapper.class);
    private final IUmsMemberPointsDetailService points = mock(IUmsMemberPointsDetailService.class);
    private final ICmsPointsSettingService settings = mock(ICmsPointsSettingService.class);
    private final Transactions transactions = new Transactions();
    private final UmsMemberServiceImpl service = new UmsMemberServiceImpl();

    @BeforeEach void setUp() {
        ReflectionTestUtils.setField(service, "umsMemberMapper", mapper);
        ReflectionTestUtils.setField(service, "pointsDetailService", points);
        ReflectionTestUtils.setField(service, "cmsPointsSettingService", settings);
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        ReflectionTestUtils.setField(service, "defaultAvatarUrl", "configured-avatar");
        when(mapper.selectUmsMemberList(any())).thenReturn(Collections.emptyList());
        when(mapper.insertUmsMember(any())).thenAnswer(invocation -> {
            assertTrue(transactions.active);
            ((UmsMember) invocation.getArgument(0)).setId(42L);
            return 1;
        });
        when(points.insertUmsMemberPointsDetail(any())).thenReturn(1);
    }

    @Test void createsOneMemberAndGiftThenRetryOnlyReads() {
        CmsPointsSetting setting = new CmsPointsSetting();
        setting.setValue(new java.math.BigDecimal("73"));
        when(settings.selectCmsPointsSettingById(1L)).thenReturn(setting);
        UmsMember created = service.getOrCreateWechatMember("wx-id");
        assertEquals(50L, created.getPoint());
        assertEquals("configured-avatar", created.getAvatar());
        verify(points).insertUmsMemberPointsDetail(argThat(detail ->
                detail.getMemberId().equals(42L) && detail.getChangePoints().equals(73L)));
        when(mapper.selectUmsMemberList(any())).thenReturn(Collections.singletonList(created));
        assertSame(created, service.getOrCreateWechatMember("wx-id"));
        verify(mapper, times(1)).insertUmsMember(any());
        verify(points, times(1)).insertUmsMemberPointsDetail(any());
        assertEquals(2, transactions.commits);
    }

    @Test void expectedConflictReadsOnlyAfterRollbackInFreshTransaction() {
        UmsMember winner = new UmsMember();
        winner.setId(43L);
        when(mapper.selectUmsMemberList(any())).thenAnswer(invocation -> {
            if (transactions.begins == 1) return Collections.emptyList();
            assertEquals(1, transactions.rollbacks);
            assertTrue(transactions.active);
            return Collections.singletonList(winner);
        });
        doThrow(duplicate("uk_ums_member_wx_open_id_nonblank")).when(mapper).insertUmsMember(any());
        assertSame(winner, service.getOrCreateWechatMember("wx-id"));
        assertEquals(2, transactions.begins);
        verifyNoInteractions(points);
    }

    @Test void expectedKeyOnAnotherTableNeverRecovers() {
        DuplicateKeyException unrelated = new DuplicateKeyException("gift", new SQLException(
                "Duplicate entry 'redacted' for key 'other.uk_ums_member_wx_open_id_nonblank'", "23000", 1062));
        when(points.insertUmsMemberPointsDetail(any())).thenThrow(unrelated);
        assertSame(unrelated, assertThrows(DuplicateKeyException.class,
                () -> service.getOrCreateWechatMember("wx-id")));
        assertEquals(1, transactions.begins);
        assertEquals(1, transactions.rollbacks);
    }

    @Test void bareWechatKeyGiftFailureNeverRecoversToConcurrentWinner() {
        UmsMember winner = new UmsMember();
        winner.setId(43L);
        winner.setWxOpenId("wx-id");
        when(mapper.selectUmsMemberList(any())).thenReturn(
                Collections.emptyList(), Collections.singletonList(winner));
        DuplicateKeyException giftFailure = new DuplicateKeyException("gift", new SQLException(
                "Duplicate entry 'redacted' for key 'uk_ums_member_wx_open_id_nonblank'", "23000", 1062));
        when(points.insertUmsMemberPointsDetail(any())).thenThrow(giftFailure);

        assertSame(giftFailure, assertThrows(DuplicateKeyException.class,
                () -> service.getOrCreateWechatMember("wx-id")));
        assertEquals(1, transactions.rollbacks);
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.begins);
        verify(mapper, times(1)).selectUmsMemberList(any());
        verify(mapper, times(1)).insertUmsMember(any());
        verify(points, times(1)).insertUmsMemberPointsDetail(any());
    }

    @Test void bareWechatKeyLookupFailureNeverRecoversToConcurrentWinner() {
        UmsMember winner = new UmsMember();
        winner.setId(43L);
        DuplicateKeyException lookupFailure = new DuplicateKeyException("lookup", new SQLException(
                "Duplicate entry 'redacted' for key 'uk_ums_member_wx_open_id_nonblank'", "23000", 1062));
        doThrow(lookupFailure).doReturn(Collections.singletonList(winner))
                .when(mapper).selectUmsMemberList(any());

        assertSame(lookupFailure, assertThrows(DuplicateKeyException.class,
                () -> service.getOrCreateWechatMember("wx-id")));
        assertEquals(1, transactions.rollbacks);
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.begins);
        verify(mapper, times(1)).selectUmsMemberList(any());
        verify(mapper, never()).insertUmsMember(any());
        verifyNoInteractions(points);
    }

    @Test void conflictWithoutUniqueWinnerFailsClosed() {
        doThrow(duplicate("uk_ums_member_wx_open_id_nonblank")).when(mapper).insertUmsMember(any());
        assertThrows(DuplicateKeyException.class, () -> service.getOrCreateWechatMember("wx-id"));
        verifyNoInteractions(points);
    }

    @Test void unrelatedGiftDuplicateAlsoRollsBackWithoutLookingUpAnotherMember() {
        DuplicateKeyException unrelated = duplicate("uk_reward_business_key");
        when(points.insertUmsMemberPointsDetail(any())).thenThrow(unrelated);
        assertSame(unrelated, assertThrows(DuplicateKeyException.class,
                () -> service.getOrCreateWechatMember("wx-id")));
        assertEquals(1, transactions.begins);
        assertEquals(1, transactions.rollbacks);
        verify(mapper, times(1)).selectUmsMemberList(any());
    }

    @Test void unrelatedDuplicateAndGiftFailurePropagateWithoutRecovery() {
        DuplicateKeyException unrelated = duplicate("uk_other");
        doThrow(unrelated).when(mapper).insertUmsMember(any());
        assertSame(unrelated, assertThrows(DuplicateKeyException.class,
                () -> service.getOrCreateWechatMember("wx-id")));
        assertEquals(1, transactions.begins);
        assertEquals(1, transactions.rollbacks);
    }

    @Test void failedGiftRollsBackAndDoesNotReturnMember() {
        when(points.insertUmsMemberPointsDetail(any())).thenReturn(0);
        assertThrows(ServiceException.class, () -> service.getOrCreateWechatMember("wx-id"));
        assertEquals(1, transactions.rollbacks);
        assertEquals(0, transactions.commits);
    }

    @Test void blankAndAmbiguousIdentitiesNeverCreate() {
        assertThrows(ServiceException.class, () -> service.getOrCreateWechatMember(" "));
        when(mapper.selectUmsMemberList(any())).thenReturn(Arrays.asList(new UmsMember(), new UmsMember()));
        assertThrows(ServiceException.class, () -> service.getOrCreateWechatMember("wx-id"));
        verify(mapper, never()).insertUmsMember(any());
    }

    @Test void genericInsertRejectsWechatCollisionButPreservesOtherErrors() {
        doThrow(duplicate("uk_ums_member_wx_open_id_nonblank")).when(mapper).insertUmsMember(any());
        assertEquals("微信身份已绑定其他账号", assertThrows(ServiceException.class,
                () -> service.insertUmsMember(new UmsMember())).getMessage());
        DuplicateKeyException unrelated = duplicate("PRIMARY");
        doThrow(unrelated).when(mapper).insertUmsMember(any());
        assertSame(unrelated, assertThrows(DuplicateKeyException.class,
                () -> service.insertUmsMember(new UmsMember())));
    }

    private DuplicateKeyException duplicate(String key) {
        return new DuplicateKeyException("insert", new SQLException(
                "Duplicate entry 'redacted' for key 'ums_member." + key + "'", "23000", 1062));
    }

    private static class Transactions extends AbstractPlatformTransactionManager {
        int begins, commits, rollbacks;
        boolean active;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
            begins++;
            active = true;
        }
        protected void doCommit(DefaultTransactionStatus status) { commits++; active = false; }
        protected void doRollback(DefaultTransactionStatus status) { rollbacks++; active = false; }
    }
}
