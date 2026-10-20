package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.PointsAwardServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Service contract only; real transaction/current-read evidence belongs to B03AssetMybatisIntegrationTest. */
class B03PointsAwardServiceTest {
    private PointsAwardServiceImpl service;
    private UmsMemberMapper members;
    private IUmsMemberService memberService;
    private IUmsMemberPointsDetailService details;

    @BeforeEach
    void setUp() {
        service = new PointsAwardServiceImpl();
        members = mock(UmsMemberMapper.class);
        memberService = mock(IUmsMemberService.class);
        details = mock(IUmsMemberPointsDetailService.class);
        ICmsPointsSettingService settings = mock(ICmsPointsSettingService.class);
        CmsPointsSetting rule = new CmsPointsSetting();
        rule.setValue(new BigDecimal("5"));
        when(settings.selectCmsPointsSettingList(any())).thenReturn(Collections.singletonList(rule));
        when(details.selectUmsMemberPointsDetailList(any())).thenReturn(Collections.emptyList());
        when(members.selectPointsForUpdate(1L)).thenReturn(70L);
        when(memberService.addPoints(1L, 5)).thenReturn(true);
        when(details.insertUmsMemberPointsDetail(any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "cmsPointsSettingService", settings);
        ReflectionTestUtils.setField(service, "umsMemberMapper", members);
        ReflectionTestUtils.setField(service, "umsMemberService", memberService);
        ReflectionTestUtils.setField(service, "umsMemberPointsDetailService", details);
    }

    @Test
    void awardSnapshotsLockedCurrentPointsBeforeAtomicIncrement() {
        assertEquals(5, service.award(1L, 12L, 101L, "节点打卡"));
        ArgumentCaptor<UmsMemberPointsDetail> row = ArgumentCaptor.forClass(UmsMemberPointsDetail.class);
        InOrder order = inOrder(members, memberService, details);
        order.verify(details).selectUmsMemberPointsDetailList(any());
        order.verify(members).selectPointsForUpdate(1L);
        order.verify(memberService).addPoints(1L, 5);
        order.verify(details).insertUmsMemberPointsDetail(row.capture());
        assertEquals(Long.valueOf(70), row.getValue().getCurrentPoints());
        assertEquals(Long.valueOf(75), row.getValue().getAfterPoints());
        assertEquals(Long.valueOf(5), row.getValue().getChangePoints());
        verify(memberService, never()).selectUmsMemberById(anyLong());
    }

    @Test
    void missingMemberOrNullPointFailsBeforeMutation() {
        when(members.selectPointsForUpdate(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.award(1L, 12L, 101L, "节点打卡"));
        verify(memberService, never()).addPoints(anyLong(), anyInt());
        verify(details, never()).insertUmsMemberPointsDetail(any());
    }

    @Test
    void existingEventRemainsNoopWithoutTakingAnAccountLock() {
        when(details.selectUmsMemberPointsDetailList(any()))
                .thenReturn(Collections.singletonList(new UmsMemberPointsDetail()));
        assertEquals(0, service.award(1L, 12L, 101L, "节点打卡"));
        verify(members, never()).selectPointsForUpdate(anyLong());
        verify(memberService, never()).addPoints(anyLong(), anyInt());
    }

    @Test
    void uniqueConflictStillPropagatesToRollbackTheTransaction() {
        when(details.insertUmsMemberPointsDetail(any())).thenThrow(new DuplicateKeyException("test unique conflict"));
        assertThrows(DuplicateKeyException.class, () -> service.award(1L, 12L, 101L, "节点打卡"));
    }
}
