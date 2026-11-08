package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CreatorProfile;
import com.chengyinhub.business.domain.vo.CreatorCenterVO;
import com.chengyinhub.business.domain.vo.CreatorRevenueLedgerVO;
import com.chengyinhub.business.mapper.CreatorIncomeDetailMapper;
import com.chengyinhub.business.mapper.CreatorMetricDailyMapper;
import com.chengyinhub.business.mapper.CreatorProfileMapper;
import com.chengyinhub.business.service.ICreatorRevenueReadService;
import com.chengyinhub.business.service.impl.CreatorCenterServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code POST /api/creator/center} 的未知收益表达：网络层仍是成功响应，
 * 但载荷里必须带 {@code revenueState=UNKNOWN}，让 UI/App 明确显示「收益暂不可用」，
 * 而不是把读失败渲染成 0 收入。
 */
class ApiCreatorCenterRevenueUnknownTest {

    private CreatorProfileMapper profileMapper;
    private ICreatorRevenueReadService revenueService;
    private CreatorCenterServiceImpl service;

    @BeforeEach
    void setUp() {
        profileMapper = mock(CreatorProfileMapper.class);
        revenueService = mock(ICreatorRevenueReadService.class);
        service = new CreatorCenterServiceImpl();
        ReflectionTestUtils.setField(service, "creatorProfileMapper", profileMapper);
        ReflectionTestUtils.setField(service, "creatorMetricDailyMapper", mock(CreatorMetricDailyMapper.class));
        ReflectionTestUtils.setField(service, "creatorIncomeDetailMapper", mock(CreatorIncomeDetailMapper.class));
        ReflectionTestUtils.setField(service, "creatorRevenueReadService", revenueService);
        CreatorProfile profile = new CreatorProfile();
        profile.setId(9L);
        profile.setMemberId(1001L);
        profile.setCreatorName("城市记录者");
        profile.setApplyStatus(1);
        when(profileMapper.selectByMemberId(1001L)).thenReturn(profile);
    }

    @Test
    void centerStillReturnsSuccessButFlagsRevenueUnknown() {
        when(revenueService.readForMember(eq(1001L), any(), any()))
                .thenThrow(new IllegalStateException("原账本读取超时"));
        ApiCreatorCenterController controller = new TestableApiCreatorCenterController(1001L);
        ReflectionTestUtils.setField(controller, "creatorCenterService", service);

        AjaxResult result = controller.center();

        assertEquals(Integer.valueOf(200), result.get(AjaxResult.CODE_TAG));
        CreatorCenterVO data = (CreatorCenterVO) result.get(AjaxResult.DATA_TAG);
        assertNotNull(data);
        assertEquals(CreatorRevenueLedgerVO.State.UNKNOWN, data.getRevenueState());
        assertNull(data.getRevenue());
        assertNotNull(data.getRevenueUnavailableReason());
        // 档案与状态仍在，前端可正常渲染页面其余部分。
        assertEquals("approved", data.getApplyStatus());
        assertNotNull(data.getProfile());
    }

    @Test
    void unavailableRevenueDoesNotLeakAnEmptyListAsZeroIncome() {
        when(revenueService.readForMember(eq(1001L), any(), any()))
                .thenThrow(new IllegalStateException("原账本不可读"));
        ApiCreatorCenterController controller = new TestableApiCreatorCenterController(1001L);
        ReflectionTestUtils.setField(controller, "creatorCenterService", service);

        CreatorCenterVO data = (CreatorCenterVO) controller.center().get(AjaxResult.DATA_TAG);

        assertEquals(CreatorRevenueLedgerVO.State.UNKNOWN, data.getRevenueState());
        assertNull(data.getRevenue(), "revenue 必须是 null（未知），不能是空对象或 0");
        // recentIncome 是另一张遗留表，语义保持不变，不受原账本读失败影响。
        assertEquals(List.of(), data.getRecentIncome());
    }

    private static class TestableApiCreatorCenterController extends ApiCreatorCenterController {
        private final Long memberId;

        private TestableApiCreatorCenterController(Long memberId) {
            this.memberId = memberId;
        }

        @Override
        public Long getAppUserId() {
            return memberId;
        }
    }
}
