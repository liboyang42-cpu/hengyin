package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.service.support.ClubPrincipalPayee;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClubPersonalBeneficiaryFreezeTest {
    final ClubMapper clubs = mock(ClubMapper.class);
    final UmsMemberMapper members = mock(UmsMemberMapper.class);
    final ClubPayoutBeneficiaryMapper snapshots = mock(ClubPayoutBeneficiaryMapper.class);
    final PayoutSwitches switches = mock(PayoutSwitches.class);
    final ClubPrincipalPayee resolver = new ClubPrincipalPayee();
    final CmsActivity activity = new CmsActivity();
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(resolver,"clubMapper",clubs);
        ReflectionTestUtils.setField(resolver,"members",members);
        ReflectionTestUtils.setField(resolver,"beneficiaries",snapshots);
        ReflectionTestUtils.setField(resolver,"switches",switches);
        when(switches.personalClubEnabled()).thenReturn(true);
        activity.setId(10L); activity.setClubId(20L); activity.setMemberId(1L);
        when(members.lockMemberById(anyLong())).thenAnswer(call->call.getArgument(0));
        when(snapshots.insertFrozen(any())).thenReturn(1);
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }
    Club club(long owner,String name) { Club c=new Club();c.setId(20L);c.setMemberId(owner);c.setName(name);return c; }
    ClubPayoutBeneficiary snapshot() { ClubPayoutBeneficiary b=new ClubPayoutBeneficiary();b.setScopeType("ACTIVITY");b.setScopeId(10L);b.setClubId(20L);b.setClubName("原俱乐部");b.setMemberId(2L);return b; }
    @Test void transferBeforeFirstObligationFreezesCurrentOwnerUnderAccountThenClubLock() {
        when(clubs.selectClubById(20L)).thenReturn(club(2L,"新名称"));
        when(clubs.selectClubByIdForUpdate(20L)).thenReturn(club(2L,"新名称"));
        assertEquals(2L,resolver.forActivity(activity));
        var order=inOrder(members,clubs,snapshots);
        order.verify(members).lockMemberById(2L);
        order.verify(clubs).selectClubByIdForUpdate(20L);
        order.verify(snapshots).selectFrozenForUpdate("ACTIVITY",10L);
        ArgumentCaptor<ClubPayoutBeneficiary> captured=ArgumentCaptor.forClass(ClubPayoutBeneficiary.class);
        verify(snapshots).insertFrozen(captured.capture());
        assertEquals("新名称",captured.getValue().getClubName());
    }
    @Test void transferAndRenameAfterFreezeNeverRewriteOwnerOrLabelEvenWhenSwitchDisabled() {
        when(snapshots.selectFrozen("ACTIVITY",10L)).thenReturn(snapshot());
        when(switches.personalClubEnabled()).thenReturn(false);
        assertEquals(2L,resolver.forActivity(activity));
        assertEquals("原俱乐部",resolver.frozenActivity(activity).getClubName());
        verifyNoInteractions(clubs,members);
        verify(snapshots,never()).insertFrozen(any());
    }
    @Test void concurrentTransferDetectedAfterLockNeverFreezesStaleOwner() {
        when(clubs.selectClubById(20L)).thenReturn(club(1L,"旧"));
        when(clubs.selectClubByIdForUpdate(20L)).thenReturn(club(2L,"新"));
        assertThrows(RuntimeException.class,()->resolver.forActivity(activity));
        verify(snapshots,never()).insertFrozen(any());
    }
    @Test void defaultOffPreservesLegacyCurrentOwnerWithoutFreezing() {
        when(switches.personalClubEnabled()).thenReturn(false);
        when(clubs.selectClubById(20L)).thenReturn(club(3L,"当前"));
        assertEquals(3L,resolver.forActivity(activity));
        verify(snapshots,never()).insertFrozen(any());
        verifyNoInteractions(members);
    }
    @Test void frozenOwnThemeKeepsOriginalOwnerAndLabelAfterPersonalSwitchCloses() {
        CmsTopic topic = new CmsTopic(); topic.setId(11L); topic.setClubId(20L); topic.setMemberId(99L);
        ClubPayoutBeneficiary frozen = snapshot(); frozen.setScopeType("TOPIC"); frozen.setScopeId(11L);
        when(snapshots.selectFrozen("TOPIC",11L)).thenReturn(frozen);
        when(switches.personalClubEnabled()).thenReturn(false);
        assertEquals(2L,resolver.forTopic(topic));
        assertEquals("原俱乐部",resolver.frozenTopic(topic).getClubName());
        verifyNoInteractions(clubs,members);
        verify(snapshots,never()).insertFrozen(any());
    }
}
