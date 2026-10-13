package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubMemberImCleanupTransactionTest {

    @Test
    void imCleanupFailureRollsBackMemberRemovalTransaction() {
        ClubMemberServiceImpl target = new ClubMemberServiceImpl();
        ClubMapper clubMapper = mock(ClubMapper.class);
        ClubMemberMapper memberMapper = mock(ClubMemberMapper.class);
        IImService imService = mock(IImService.class);
        ReflectionTestUtils.setField(target, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(target, "clubMemberMapper", memberMapper);
        ReflectionTestUtils.setField(target, "imService", imService);
        ReflectionTestUtils.setField(target, "clubRoleAssignmentService", mock(IClubRoleAssignmentService.class));

        Club club = new Club();
        club.setId(42L);
        when(clubMapper.selectClubByIdForUpdate(42L)).thenReturn(club);
        when(memberMapper.deleteByClubAndMember(42L, 7L)).thenReturn(1);
        doThrow(new RuntimeException("IM_MARK_DELETED_FAILED"))
                .when(imService).leaveClubGroup(42L, 7L);

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(IClubMemberService.class);
        factory.addAdvice(interceptor);
        IClubMemberService service = (IClubMemberService) factory.getProxy();

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.quitMemberAndLeaveGroup(42L, 7L));

        assertEquals("IM_MARK_DELETED_FAILED", error.getMessage());
        verify(memberMapper).deleteByClubAndMember(42L, 7L);
        verify(imService).leaveClubGroup(42L, 7L);
        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
    }
}
