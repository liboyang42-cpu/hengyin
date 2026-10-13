package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CreatorIncomeDetail;
import com.chengyinhub.business.domain.CreatorMetricDaily;
import com.chengyinhub.business.domain.CreatorProfile;
import com.chengyinhub.business.domain.dto.CreatorProfileApplyDTO;
import com.chengyinhub.business.domain.vo.CreatorCenterVO;
import com.chengyinhub.business.mapper.CreatorIncomeDetailMapper;
import com.chengyinhub.business.mapper.CreatorMetricDailyMapper;
import com.chengyinhub.business.mapper.CreatorProfileMapper;
import com.chengyinhub.business.service.ICreatorCenterService;
import com.chengyinhub.business.service.impl.CreatorCenterServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiCreatorCenterControllerTest {

    private CreatorProfileMapper profileMapper;
    private CreatorMetricDailyMapper metricMapper;
    private CreatorIncomeDetailMapper incomeMapper;
    private CreatorCenterServiceImpl service;

    @BeforeEach
    void setUp() {
        profileMapper = mock(CreatorProfileMapper.class);
        metricMapper = mock(CreatorMetricDailyMapper.class);
        incomeMapper = mock(CreatorIncomeDetailMapper.class);
        service = new CreatorCenterServiceImpl();
        ReflectionTestUtils.setField(service, "creatorProfileMapper", profileMapper);
        ReflectionTestUtils.setField(service, "creatorMetricDailyMapper", metricMapper);
        ReflectionTestUtils.setField(service, "creatorIncomeDetailMapper", incomeMapper);
    }

    @Test
    void getCenterReturnsNotAppliedWhenNoCreatorProfileExists() {
        when(profileMapper.selectByMemberId(1001L)).thenReturn(null);

        CreatorCenterVO result = service.getCenter(1001L);

        assertEquals("not_applied", result.getApplyStatus());
        assertEquals(null, result.getProfile());
    }

    @Test
    void applyCreatesPendingCreatorProfileForCurrentMember() {
        when(profileMapper.selectByMemberId(1001L)).thenReturn(null);
        when(profileMapper.insertCreatorProfile(any(CreatorProfile.class))).thenReturn(1);
        CreatorProfileApplyDTO request = new CreatorProfileApplyDTO();
        request.setCreatorName("城市记录者");
        request.setBio("探索城市");

        int accepted = service.apply(1001L, request);

        assertEquals(1, accepted);
        verify(profileMapper).insertCreatorProfile(any(CreatorProfile.class));
    }

    @Test
    void applyDoesNotAllowDuplicateProfileForSameMember() {
        when(profileMapper.selectByMemberId(1001L)).thenReturn(profile(9L, 1001L, 1));

        int accepted = service.apply(1001L, new CreatorProfileApplyDTO());

        assertEquals(0, accepted);
        verify(profileMapper, never()).insertCreatorProfile(any(CreatorProfile.class));
    }

    @Test
    void centerReturnsMetricAndIncomeSummariesReadOnly() {
        CreatorProfile profile = profile(9L, 1001L, 1);
        when(profileMapper.selectByMemberId(1001L)).thenReturn(profile);
        CreatorMetricDaily metric = new CreatorMetricDaily();
        metric.setCreatorId(9L);
        metric.setViewCount(100L);
        when(metricMapper.selectLatestMetric(9L)).thenReturn(metric);
        CreatorIncomeDetail income = new CreatorIncomeDetail();
        income.setAmount(new BigDecimal("12.30"));
        when(incomeMapper.selectRecentIncome(9L)).thenReturn(Collections.singletonList(income));

        CreatorCenterVO result = service.getCenter(1001L);

        assertEquals("approved", result.getApplyStatus());
        assertEquals(Long.valueOf(100L), result.getMetric().getViewCount());
        assertEquals(1, result.getRecentIncome().size());
    }

    @Test
    void controllerApplyUsesCurrentMember() {
        ICreatorCenterService mockService = mock(ICreatorCenterService.class);
        ApiCreatorCenterController controller = new TestableApiCreatorCenterController(1001L);
        ReflectionTestUtils.setField(controller, "creatorCenterService", mockService);
        CreatorProfileApplyDTO request = new CreatorProfileApplyDTO();
        when(mockService.apply(eq(1001L), any(CreatorProfileApplyDTO.class))).thenReturn(1);

        AjaxResult result = controller.apply(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(mockService).apply(eq(1001L), any(CreatorProfileApplyDTO.class));
    }

    private static CreatorProfile profile(Long id, Long memberId, Integer status) {
        CreatorProfile profile = new CreatorProfile();
        profile.setId(id);
        profile.setMemberId(memberId);
        profile.setCreatorName("城市记录者");
        profile.setApplyStatus(status);
        return profile;
    }

    private static class TestableApiCreatorCenterController extends ApiCreatorCenterController {
        private final Long appUserId;

        private TestableApiCreatorCenterController(Long appUserId) {
            this.appUserId = appUserId;
        }

        @Override
        public Long getAppUserId() {
            return appUserId;
        }
    }
}
