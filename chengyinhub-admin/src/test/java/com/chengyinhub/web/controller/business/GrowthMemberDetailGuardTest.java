package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.MemberGrowthCenterVO;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 用户 360 的成长详情必须只读、只读真实用户，不能因 URL 参数为任意 ID 虚构成长账户。 */
class GrowthMemberDetailGuardTest {

    private IGrowthService growthService;
    private IUmsMemberService memberService;
    private GrowthController controller;

    @BeforeEach
    void setUp() {
        growthService = mock(IGrowthService.class);
        memberService = mock(IUmsMemberService.class);
        controller = new GrowthController();
        ReflectionTestUtils.setField(controller, "growthService", growthService);
        ReflectionTestUtils.setField(controller, "umsMemberService", memberService);
    }

    @Test
    void existingMemberGetsReadOnlyGrowthCenter() {
        UmsMember member = new UmsMember();
        member.setId(42L);
        MemberGrowthCenterVO center = new MemberGrowthCenterVO();
        when(memberService.selectUmsMemberById(42L)).thenReturn(member);
        when(growthService.getCenter(42L)).thenReturn(center);

        AjaxResult result = controller.memberCenter(42L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(center, result.get(AjaxResult.DATA_TAG));
        verify(growthService).getCenter(42L);
    }

    @Test
    void missingMemberDoesNotCreateOrReadGrowthCenter() {
        when(memberService.selectUmsMemberById(404L)).thenReturn(null);

        AjaxResult result = controller.memberCenter(404L);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(growthService, never()).getCenter(404L);
    }
}
