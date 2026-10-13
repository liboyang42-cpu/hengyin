package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UmsMemberSessionRevocationTest {

    private UmsMemberMapper memberMapper;
    private IAppSessionRegistryService registry;
    private UmsMemberServiceImpl memberService;

    @BeforeEach
    void setUp() {
        memberMapper = mock(UmsMemberMapper.class);
        registry = mock(IAppSessionRegistryService.class);
        when(registry.beginMemberMutation(anyLong())).thenAnswer(invocation ->
                "mutation-" + invocation.getArgument(0));
        memberService = new UmsMemberServiceImpl();
        ReflectionTestUtils.setField(memberService, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(memberService, "appSessionRegistryService", registry);
    }

    @Test
    void passwordChangeRevokesAllSessionsBeforeDatabaseWrite() {
        assertSensitiveChangeRevokes(member("old-password", 1L, 0),
                member("new-password", null, null));
    }

    @Test
    void freezeRevokesAllSessionsBeforeDatabaseWrite() {
        assertSensitiveChangeRevokes(member("password", 1L, 0),
                member(null, 2L, null));
    }

    @Test
    void deletionRevokesAllSessionsBeforeDatabaseWrite() {
        assertSensitiveChangeRevokes(member("password", 1L, 0),
                member(null, null, 2));
    }

    @Test
    void phoneChangeRevokesAllSessionsBeforeDatabaseWrite() {
        UmsMember current = member("password", 1L, 0);
        current.setPhone("13800000000");
        UmsMember update = member(null, null, null);
        update.setPhone("13900000000");
        assertSensitiveChangeRevokes(current, update);
    }

    @Test
    void wxOpenIdChangeRevokesAllSessionsBeforeDatabaseWrite() {
        UmsMember current = member("password", 1L, 0);
        current.setWxOpenId("old-openid");
        UmsMember update = member(null, null, null);
        update.setWxOpenId("new-openid");
        assertSensitiveChangeRevokes(current, update);
    }

    @Test
    void unionIdChangeRevokesAllSessionsBeforeDatabaseWrite() {
        UmsMember current = member("password", 1L, 0);
        current.setUnionId("old-unionid");
        UmsMember update = member(null, null, null);
        update.setUnionId("new-unionid");
        assertSensitiveChangeRevokes(current, update);
    }

    @Test
    void appleIdChangeRevokesAllSessionsBeforeDatabaseWrite() {
        UmsMember current = member("password", 1L, 0);
        current.setAppleId("old-appleid");
        UmsMember update = member(null, null, null);
        update.setAppleId("new-appleid");
        assertSensitiveChangeRevokes(current, update);
    }

    @Test
    void ordinaryProfileUpdateDoesNotRevokeSessions() {
        UmsMember current = member("password", 1L, 0);
        UmsMember update = member(null, null, null);
        update.setNickname("新昵称");
        when(memberMapper.selectUmsMemberById(42L)).thenReturn(current);

        memberService.updateUmsMember(update);

        verify(registry, never()).revokeAll(42L);
        verify(memberMapper).updateUmsMember(update);
    }

    @Test
    void unchangedSensitiveFieldsDoNotRevokeSessions() {
        UmsMember current = member("password", 1L, 0);
        UmsMember update = member("password", 1L, 0);
        when(memberMapper.selectUmsMemberById(42L)).thenReturn(current);

        memberService.updateUmsMember(update);

        verify(registry, never()).revokeAll(42L);
        verify(memberMapper).updateUmsMember(update);
    }

    @Test
    void redisRevocationFailurePreventsSensitiveDatabaseWrite() {
        UmsMember current = member("old-password", 1L, 0);
        UmsMember update = member("new-password", null, null);
        when(memberMapper.selectUmsMemberById(42L)).thenReturn(current);
        doThrow(new IllegalStateException("redis unavailable")).when(registry).beginMemberMutation(42L);

        assertThrows(IllegalStateException.class, () -> memberService.updateUmsMember(update));

        verify(memberMapper, never()).updateUmsMember(any(UmsMember.class));
    }

    @Test
    void secondRevocationFailureIsPropagatedAfterSensitiveWriteForTransactionRollback() {
        UmsMember current = member("old-password", 1L, 0);
        UmsMember update = member("new-password", null, null);
        when(memberMapper.selectUmsMemberById(42L)).thenReturn(current);
        doThrow(new IllegalStateException("redis unavailable")).when(registry).revokeAll(42L);

        assertThrows(IllegalStateException.class, () -> memberService.updateUmsMember(update));

        verify(memberMapper).updateUmsMember(update);
    }

    @Test
    void physicalDeletionRevokesAllSessionsBeforeDatabaseDelete() {
        memberService.deleteUmsMemberById(42L);

        InOrder order = inOrder(registry, memberMapper);
        order.verify(registry).beginMemberMutation(42L);
        order.verify(memberMapper).deleteUmsMemberById(42L);
        order.verify(registry).revokeAll(42L);
        order.verify(registry).finishMemberMutationAfterTransaction(42L, "mutation-42");
    }

    @Test
    void batchPhysicalDeletionRevokesEveryMemberBeforeDatabaseDelete() {
        Long[] ids = {42L, 43L};

        memberService.deleteUmsMemberByIds(ids);

        InOrder order = inOrder(registry, memberMapper);
        order.verify(registry).beginMemberMutation(42L);
        order.verify(registry).beginMemberMutation(43L);
        order.verify(memberMapper).deleteUmsMemberByIds(ids);
        order.verify(registry).revokeAll(42L);
        order.verify(registry).finishMemberMutationAfterTransaction(42L, "mutation-42");
        order.verify(registry).revokeAll(43L);
        order.verify(registry).finishMemberMutationAfterTransaction(43L, "mutation-43");
    }

    @Test
    void redisFailurePreventsPhysicalDatabaseDelete() {
        doThrow(new IllegalStateException("redis unavailable")).when(registry).beginMemberMutation(42L);

        assertThrows(IllegalStateException.class, () -> memberService.deleteUmsMemberById(42L));

        verify(memberMapper, never()).deleteUmsMemberById(42L);
    }

    @Test
    void batchGuardAcquisitionFailureReleasesAlreadyAcquiredMemberGuard() {
        Long[] ids = {42L, 43L};
        when(registry.beginMemberMutation(43L))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThrows(IllegalStateException.class, () -> memberService.deleteUmsMemberByIds(ids));

        verify(registry).finishMemberMutation(42L, "mutation-42");
        verify(memberMapper, never()).deleteUmsMemberByIds(ids);
    }

    private void assertSensitiveChangeRevokes(UmsMember current, UmsMember update) {
        when(memberMapper.selectUmsMemberById(42L)).thenReturn(current);

        memberService.updateUmsMember(update);

        InOrder order = inOrder(registry, memberMapper);
        order.verify(registry).beginMemberMutation(42L);
        order.verify(memberMapper).updateUmsMember(update);
        order.verify(registry).revokeAll(42L);
        order.verify(registry).finishMemberMutationAfterTransaction(42L, "mutation-42");
    }

    private UmsMember member(String password, Long status, Integer delFlag) {
        UmsMember member = new UmsMember();
        member.setId(42L);
        member.setPassword(password);
        member.setStatus(status);
        member.setDelFlag(delFlag);
        return member;
    }
}
