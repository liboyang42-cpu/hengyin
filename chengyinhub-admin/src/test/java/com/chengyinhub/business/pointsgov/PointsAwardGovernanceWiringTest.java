package com.chengyinhub.business.pointsgov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.impl.PointsAwardServiceImpl;

/** R7: PointsAwardServiceImpl.award asks governance under the member lock and writes nothing when refused. */
class PointsAwardGovernanceWiringTest {

    private PointsAwardServiceImpl service;
    private UmsMemberMapper members;
    private IUmsMemberService memberService;
    private IUmsMemberPointsDetailService details;
    private PointsGovernanceService governance;

    @BeforeEach
    void setUp() {
        service = new PointsAwardServiceImpl();
        members = mock(UmsMemberMapper.class);
        memberService = mock(IUmsMemberService.class);
        details = mock(IUmsMemberPointsDetailService.class);
        governance = mock(PointsGovernanceService.class);
        ICmsPointsSettingService settings = mock(ICmsPointsSettingService.class);
        CmsPointsSetting rule = new CmsPointsSetting();
        rule.setValue(new BigDecimal("5"));
        rule.setStatus(1L);
        when(settings.selectCmsPointsSettingList(any())).thenReturn(Collections.singletonList(rule));
        when(details.selectUmsMemberPointsDetailList(any())).thenReturn(Collections.emptyList());
        when(members.selectPointsForUpdate(1L)).thenReturn(70L);
        when(memberService.addPoints(1L, 5)).thenReturn(true);
        when(details.insertUmsMemberPointsDetail(any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "cmsPointsSettingService", settings);
        ReflectionTestUtils.setField(service, "umsMemberMapper", members);
        ReflectionTestUtils.setField(service, "umsMemberService", memberService);
        ReflectionTestUtils.setField(service, "umsMemberPointsDetailService", details);
        ReflectionTestUtils.setField(service, "pointsGovernanceService", governance);
    }

    @Test
    void refusedAwardWritesNothing() {
        when(governance.admit(1L, 12L, 5)).thenReturn(new PointsGovernanceService.Admission(false, "DAILY_CAP", "PLAY"));
        assertEquals(0, service.award(1L, 12L, 101L, "节点打卡"));
        verify(memberService, never()).addPoints(anyLong(), anyInt());
        verify(details, never()).insertUmsMemberPointsDetail(any());
    }

    @Test
    void admittedAwardIsCheckedUnderTheMemberLock() {
        when(governance.admit(1L, 12L, 5)).thenReturn(new PointsGovernanceService.Admission(true, "ADMITTED", "PLAY"));
        assertEquals(5, service.award(1L, 12L, 101L, "节点打卡"));
        InOrder order = inOrder(members, governance, memberService);
        order.verify(members).selectPointsForUpdate(1L);
        order.verify(governance).admit(1L, 12L, 5);
        order.verify(memberService).addPoints(1L, 5);
    }
}
