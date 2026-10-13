package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.UmsComment;
import com.chengyinhub.business.domain.ViewComment;
import com.chengyinhub.business.domain.vo.ActivityInfoVO;
import com.chengyinhub.business.mapper.CmsRegistrationStatisticsMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.IMerchantBusinessStatusService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.ITmsBrowseRecordService;
import com.chengyinhub.business.service.IUmsCommentService;
import com.chengyinhub.business.service.IViewCollaboratorsService;
import com.chengyinhub.business.service.IViewCommentService;
import com.chengyinhub.business.service.IViewRegistrationService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 活动详情评分契约（CU-C-50）。
 *
 * <p>既往缺陷：活动详情分支先按真实评论列表算出 commentCount，紧接着 {@code setAverageRating(5)}
 * 写死五分，于是「0 条评价」的页面同时显示 5 分。修复后评分必须来自
 * {@code umsCommentService.selectAvgRatingByCondition}（与主题详情同口径），无评论落 0。</p>
 */
class ApiActivityInfoRatingContractTest {

    private static final long ACTIVITY_ID = 4321L;

    @BeforeEach
    void bindRequest() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void unbindRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void activityInfo_zeroComments_averageRatingFallsToZero() {
        // AVG 在无行时返回 null，契约是「没有评价就显示 0」
        Fixture f = fixture(Collections.<ViewComment>emptyList(), null);

        ActivityInfoVO vo = info(f);

        assertEquals(0, vo.getCommentCount().intValue(), "0 条评论时 commentCount 应为 0");
        assertEquals(0F, vo.getAverageRating(), 0F,
                "0 条评价时评分必须是 0；写死 5 会与同屏「0 条评价」自相矛盾");
    }

    @Test
    void activityInfo_withComments_averageRatingIsRealAverageScopedToThisActivity() {
        Fixture f = fixture(Arrays.asList(comment(5L), comment(4L)), 4.5D);

        ActivityInfoVO vo = info(f);

        assertEquals(2, vo.getCommentCount().intValue(), "commentCount 应按真实评论列表算");
        assertEquals(4.5F, vo.getAverageRating(), 0F,
                "有评论时评分应等于 selectAvgRatingByCondition 的返回值，而不是固定 5");

        ArgumentCaptor<UmsComment> captor = ArgumentCaptor.forClass(UmsComment.class);
        verify(f.umsCommentService).selectAvgRatingByCondition(captor.capture());
        UmsComment condition = captor.getValue();
        assertEquals(Long.valueOf(2L), condition.getOwnerType(), "活动评价 owner_type 必须是 2");
        assertEquals(Long.valueOf(ACTIVITY_ID), condition.getOwnerId(), "评分必须只统计本活动");
        assertEquals(Integer.valueOf(1), condition.getStatus(), "只统计已通过(status=1)的评价");
        assertEquals(Integer.valueOf(0), condition.getDelFlag(), "只统计未删除(del_flag=0)的评价");
    }

    private ActivityInfoVO info(Fixture f) {
        AjaxResult res = f.controller.activityInfo(String.valueOf(ACTIVITY_ID));
        assertNotNull(res, "详情应正常返回");
        Object data = res.get(AjaxResult.DATA_TAG);
        assertNotNull(data, "详情应带 data");
        return (ActivityInfoVO) data;
    }

    private static ViewComment comment(long rating) {
        ViewComment c = new ViewComment();
        c.setRating(rating);
        return c;
    }

    private Fixture fixture(List<ViewComment> comments, Double avgRating) {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setName("评分契约测试活动");
        activity.setTemplateId(0L);

        ICmsActivityService activityService = mock(ICmsActivityService.class);
        when(activityService.selectCmsActivityDetailById(anyLong(), anyLong())).thenReturn(activity);
        ICmsMemberTemplateService memberTemplateService = mock(ICmsMemberTemplateService.class);
        when(memberTemplateService.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.emptyList());
        IViewCollaboratorsService collaboratorsService = mock(IViewCollaboratorsService.class);
        when(collaboratorsService.selectViewCollaboratorsList(any())).thenReturn(Collections.emptyList());
        IViewCommentService commentService = mock(IViewCommentService.class);
        when(commentService.selectViewCommentList(any())).thenReturn(comments);
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.emptyList());
        IViewRegistrationService registrationService = mock(IViewRegistrationService.class);
        when(registrationService.selectRegistrationSummaries(any())).thenReturn(Collections.emptyList());
        CmsRegistrationStatisticsMapper statisticsMapper = mock(CmsRegistrationStatisticsMapper.class);
        when(statisticsMapper.statisticsSalesAmoutByOwner(any())).thenReturn(Collections.emptyList());

        Fixture f = new Fixture();
        f.umsCommentService = mock(IUmsCommentService.class);
        when(f.umsCommentService.selectAvgRatingByCondition(any())).thenReturn(avgRating);

        ITmsBrowseRecordService browseRecordService = mock(ITmsBrowseRecordService.class);
        Map<String, Object> row = new HashMap<>();
        row.put("browse_type", 2);
        row.put("target_id", ACTIVITY_ID);
        row.put("browse_count", 0L);
        when(browseRecordService.countBrowseByTypeAndTarget(any()))
                .thenReturn(Collections.<Map<String, Object>>singletonList(row));

        f.controller = spy(new ApiActivityController());
        ReflectionTestUtils.setField(f.controller, "merchantBusinessStatusService", mock(IMerchantBusinessStatusService.class));
        doReturn(0L).when(f.controller).getAppUserId();

        ReflectionTestUtils.setField(f.controller, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(f.controller, "memberTemplateService", memberTemplateService);
        ReflectionTestUtils.setField(f.controller, "viewCollaboratorsService", collaboratorsService);
        ReflectionTestUtils.setField(f.controller, "commentService", commentService);
        ReflectionTestUtils.setField(f.controller, "omsTicketService", ticketService);
        ReflectionTestUtils.setField(f.controller, "viewRegistrationService", registrationService);
        ReflectionTestUtils.setField(f.controller, "sysCategoryService", mock(ISysCategoryService.class));
        ReflectionTestUtils.setField(f.controller, "cmsRegistrationStatisticsMapper", statisticsMapper);
        ReflectionTestUtils.setField(f.controller, "tmsBrowseRecordService", browseRecordService);
        ReflectionTestUtils.setField(f.controller, "umsCommentService", f.umsCommentService);
        return f;
    }

    /** 只留测试断言用得到的句柄。 */
    private static class Fixture {
        ApiActivityController controller;
        IUmsCommentService umsCommentService;
    }
}
