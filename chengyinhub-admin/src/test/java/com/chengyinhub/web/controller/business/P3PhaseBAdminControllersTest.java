package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CreatorProfile;
import com.chengyinhub.business.domain.GrowthMission;
import com.chengyinhub.business.domain.IpWorld;
import com.chengyinhub.business.domain.MemberGrowth;
import com.chengyinhub.business.domain.dto.CreatorReviewDTO;
import com.chengyinhub.business.service.ICreatorCenterService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IIpWorldService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class P3PhaseBAdminControllersTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void growthControllerProvidesMemberListAndMissionSave() {
        IGrowthService service = mock(IGrowthService.class);
        GrowthController controller = new GrowthController();
        ReflectionTestUtils.setField(controller, "growthService", service);
        when(service.selectAdminGrowthList(any(MemberGrowth.class))).thenReturn(Collections.singletonList(new MemberGrowth()));
        when(service.saveMission(any(GrowthMission.class))).thenReturn(1);
        bindPageRequest();

        TableDataInfo list = controller.list(new MemberGrowth());
        AjaxResult save = controller.saveMission(new GrowthMission());

        assertEquals(1, list.getRows().size());
        assertEquals(200, save.get(AjaxResult.CODE_TAG));
        verify(service).selectAdminGrowthList(any(MemberGrowth.class));
        verify(service).saveMission(any(GrowthMission.class));
    }

    @Test
    void worldControllerProvidesListAndSave() {
        IIpWorldService service = mock(IIpWorldService.class);
        IpWorldController controller = new IpWorldController();
        ReflectionTestUtils.setField(controller, "ipWorldService", service);
        when(service.selectAdminWorldList(any(IpWorld.class))).thenReturn(Collections.singletonList(new IpWorld()));
        when(service.saveWorld(any(IpWorld.class))).thenReturn(1);
        bindPageRequest();

        TableDataInfo list = controller.list(new IpWorld());
        AjaxResult save = controller.save(new IpWorld());

        assertEquals(1, list.getRows().size());
        assertEquals(200, save.get(AjaxResult.CODE_TAG));
        verify(service).selectAdminWorldList(any(IpWorld.class));
        verify(service).saveWorld(any(IpWorld.class));
    }

    @Test
    void creatorControllerProvidesListAndReview() {
        ICreatorCenterService service = mock(ICreatorCenterService.class);
        CreatorCenterController controller = new TestableCreatorCenterController();
        ReflectionTestUtils.setField(controller, "creatorCenterService", service);
        when(service.selectAdminCreatorList(any(CreatorProfile.class))).thenReturn(Collections.singletonList(new CreatorProfile()));
        when(service.review(any(CreatorReviewDTO.class), eq(1L))).thenReturn(1);
        bindPageRequest();

        TableDataInfo list = controller.list(new CreatorProfile());
        CreatorReviewDTO request = new CreatorReviewDTO();
        request.setId(1L);
        request.setApplyStatus(1);
        AjaxResult review = controller.review(request);

        assertEquals(1, list.getRows().size());
        assertEquals(200, review.get(AjaxResult.CODE_TAG));
        verify(service).selectAdminCreatorList(any(CreatorProfile.class));
        verify(service).review(any(CreatorReviewDTO.class), eq(1L));
    }

    private void bindPageRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("pageNum", "1");
        request.setParameter("pageSize", "10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static class TestableCreatorCenterController extends CreatorCenterController {
        @Override
        public Long getUserId() {
            return 1L;
        }
    }
}
