package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.BizEventLog;
import com.chengyinhub.business.mapper.MemberInterestProfileMapper;
import com.chengyinhub.business.domain.vo.RecommendationItemVO;
import com.chengyinhub.business.mapper.RecommendationScoreDailyMapper;
import com.chengyinhub.business.service.IRecommendationService;
import com.chengyinhub.business.service.impl.RecommendationServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiRecommendationControllerTest {

    private RecommendationScoreDailyMapper scoreMapper;
    private MemberInterestProfileMapper profileMapper;
    private RecommendationServiceImpl service;

    @BeforeEach
    void setUp() {
        scoreMapper = mock(RecommendationScoreDailyMapper.class);
        profileMapper = mock(MemberInterestProfileMapper.class);
        service = new RecommendationServiceImpl();
        ReflectionTestUtils.setField(service, "recommendationScoreDailyMapper", scoreMapper);
        ReflectionTestUtils.setField(service, "memberInterestProfileMapper", profileMapper);
    }

    @Test
    void serviceFiltersDeletedUnapprovedAndUnpublishedCandidates() {
        when(scoreMapper.selectCandidateItems(eq(1001L), eq("topic"), eq(20))).thenReturn(Arrays.asList(
                item(1L, 1, 1, 0, "可推荐"),
                item(2L, 0, 1, 0, "待审核"),
                item(3L, 1, 0, 0, "未上架"),
                item(4L, 1, 1, 2, "已删除")
        ));

        List<RecommendationItemVO> result = service.listForMember(1001L, "topic", 20);

        assertEquals(1, result.size());
        assertEquals(Long.valueOf(1L), result.get(0).getCandidateId());
        assertEquals("可推荐", result.get(0).getTitle());
    }

    @SuppressWarnings("unchecked")
    @Test
    void controllerReturnsRecommendationListForCurrentMember() {
        IRecommendationService mockService = mock(IRecommendationService.class);
        ApiRecommendationController controller = new TestableApiRecommendationController(1001L);
        ReflectionTestUtils.setField(controller, "recommendationService", mockService);
        when(mockService.listForMember(1001L, "topic", 10)).thenReturn(Arrays.asList(item(1L, 1, 1, 0, "可推荐")));

        AjaxResult result = controller.list("topic", 10);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        List<RecommendationItemVO> data = (List<RecommendationItemVO>) result.get(AjaxResult.DATA_TAG);
        assertEquals(1, data.size());
        assertEquals("可推荐", data.get(0).getTitle());
    }

    @Test
    void serviceUsesColdStartFallbackWhenPersonalizedScoresAreEmpty() {
        when(scoreMapper.selectCandidateItems(eq(1001L), eq("topic"), eq(20))).thenReturn(Arrays.asList());
        when(scoreMapper.selectColdStartItems(eq("topic"), eq(20))).thenReturn(Arrays.asList(item(9L, 1, 1, 0, "冷启动主题")));

        List<RecommendationItemVO> result = service.listForMember(1001L, "topic", 20);

        assertEquals(1, result.size());
        assertEquals(Long.valueOf(9L), result.get(0).getCandidateId());
        assertEquals("冷启动主题", result.get(0).getTitle());
    }

    @Test
    void updateProfileFromEventAppliesEventWeight() {
        BizEventLog event = new BizEventLog();
        event.setMemberId(1001L);
        event.setEventName("payment_success");
        event.setBizType("activity");
        event.setCityCode("310000");

        service.updateProfileFromEvent(event);

        verify(profileMapper).incrementMemberCategoryWeight(
                eq(1001L), eq("310000"), eq("$.activity"), eq(8),
                any(Date.class), any(Date.class), any(Date.class));
    }

    @Test
    void communityPostRecommendationUsesQualifiedViewButNotRawImpression() {
        BizEventLog impression = new BizEventLog();
        impression.setMemberId(1001L);
        impression.setEventName("community_post_impression");
        impression.setBizType("community_post");
        service.updateProfileFromEvent(impression);
        verifyNoInteractions(profileMapper);

        BizEventLog qualified = new BizEventLog();
        qualified.setMemberId(1001L);
        qualified.setEventName("community_post_qualified_view");
        qualified.setBizType("community_post");
        service.updateProfileFromEvent(qualified);
        verify(profileMapper).incrementMemberCategoryWeight(
                eq(1001L), eq(null), eq("$.community_post"), eq(1),
                any(Date.class), any(Date.class), any(Date.class));
    }

    @Test
    void untrustedBizTypeCannotBecomeAnArbitraryJsonPath() {
        BizEventLog event = new BizEventLog();
        event.setMemberId(1001L);
        event.setEventName("content_like");
        event.setBizType("x[0].\"unsafe\"");

        service.updateProfileFromEvent(event);

        verify(profileMapper).incrementMemberCategoryWeight(
                eq(1001L), eq(null), eq("$.general"), eq(3),
                any(Date.class), any(Date.class), any(Date.class));
    }

    private static RecommendationItemVO item(Long id, Integer status, Integer publishStatus, Integer delFlag, String title) {
        RecommendationItemVO item = new RecommendationItemVO();
        item.setCandidateType("topic");
        item.setCandidateId(id);
        item.setTitle(title);
        item.setScore(new BigDecimal("10.00"));
        item.setStatus(status);
        item.setPublishStatus(publishStatus);
        item.setDelFlag(delFlag);
        return item;
    }

    private static class TestableApiRecommendationController extends ApiRecommendationController {
        private final Long appUserId;

        private TestableApiRecommendationController(Long appUserId) {
            this.appUserId = appUserId;
        }

        @Override
        public Long getAppUserId() {
            return appUserId;
        }
    }
}
