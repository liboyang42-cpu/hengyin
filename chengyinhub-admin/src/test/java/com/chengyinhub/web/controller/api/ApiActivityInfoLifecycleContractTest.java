package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.domain.vo.ActivityInfoVO;
import com.chengyinhub.business.mapper.CmsRegistrationStatisticsMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.ITmsBrowseRecordService;
import com.chengyinhub.business.service.IViewCollaboratorsService;
import com.chengyinhub.business.service.IViewCommentService;
import com.chengyinhub.business.service.IViewRegistrationService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Calendar;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * CU-M-106：活动详情接口下发的状态必须与「我的项目」列表同一口径（同一个 ActivityLifecycle）。
 *
 * 病灶是详情页拿 cms_activity.status（审核码）去查 official_event 的生命周期文案表：
 * 审核通过=1 被念成「即将开始」，而同一行在列表按档期说「进行中」。
 * 因此这里既钉住「档期覆盖现在 ⇒ 进行中」，也钉住两条会暴露混用口径的对照：
 * 未通过（status=2）不得被念成那套表里的「报名中」，未来档期不得被念成「审核通过」的字面。
 */
class ApiActivityInfoLifecycleContractTest
{
    private static final long ACTIVITY_ID = 5701L;

    @BeforeEach
    void bindRequest()
    {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void unbindRequest()
    {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void activityInfo_carriesTheSameLifecycleTheListShows()
    {
        ActivityInfoVO vo = info(activity(1L, 1L, daysAgo(1), daysAhead(5)));

        assertEquals("running", vo.getState());
        assertEquals("进行中", vo.getStateText(), "商家在详情看到的必须是列表同一时刻说的那句话");
    }

    @Test
    void activityInfo_doesNotReadTheAuditCodeAsAnOfficialEventLifecycle()
    {
        // 审核未过（2）在 official_event 那套表里是「报名中」——读到这个字面就是两套口径混用。
        ActivityInfoVO rejected = info(activity(2L, 1L, daysAgo(1), daysAhead(5)));
        assertEquals("rejected", rejected.getState());
        assertEquals("未通过", rejected.getStateText());
        assertNotEquals("报名中", rejected.getStateText(),
                "status 是审核码，不得再被当成 0..9 生命周期码查表");

        // 审核通过（1）在该表里是「即将开始」，但档期没到 ⇒ 只能说未开始。
        ActivityInfoVO upcoming = info(activity(1L, 1L, daysAhead(1), daysAhead(5)));
        assertEquals("notStarted", upcoming.getState());
        assertEquals("未开始", upcoming.getStateText());
        assertNotEquals("即将开始", upcoming.getStateText(),
                "「即将开始」是生命周期表给 1 的字面，正是这条走查读到的错话");
    }

    @Test
    void linkedActivityCountsThemeNodesInsteadOfItsEmptyTemplateList()
    {
        CmsActivity linked = activity(1L, 1L, daysAgo(1), daysAhead(1));
        linked.setTopicId(990027L);
        assertEquals(4, info(linked).getPlayNodeCount());
        assertEquals(0, info(activity(1L, 1L, daysAgo(1), daysAhead(1))).getPlayNodeCount());
    }

    @Test
    void activityInfo_stampsServerNowForSignupGreying()
    {
        // U-168:报名页置灰改按服务器时刻。负控:删掉控制器里的 setServerNow → 这里为 null 而红。
        long before = System.currentTimeMillis();
        ActivityInfoVO vo = info(activity(1L, 1L, daysAgo(1), daysAhead(5)));
        long after = System.currentTimeMillis();
        assertNotNull(vo.getServerNow(), "/api/activity/info 必须带 serverNow,否则报名页只能退回手机时钟");
        assertTrue(vo.getServerNow() >= before && vo.getServerNow() <= after, "serverNow 应是本次读的服务端时刻");
    }

    private static CmsActivity activity(Long status, Long publishStatus, Date start, Date end)
    {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setName("契约测试活动");
        activity.setStatus(status);
        activity.setPublishStatus(publishStatus);
        activity.setStartDate(start);
        activity.setEndDate(end);
        activity.setTemplateId(0L);
        return activity;
    }

    private static ActivityInfoVO info(CmsActivity activity)
    {
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        when(activityService.selectCmsActivityDetailById(anyLong(), anyLong())).thenReturn(activity);
        ICmsMemberTemplateService memberTemplateService = mock(ICmsMemberTemplateService.class);
        when(memberTemplateService.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.emptyList());
        IViewCollaboratorsService collaboratorsService = mock(IViewCollaboratorsService.class);
        when(collaboratorsService.selectViewCollaboratorsList(any())).thenReturn(Collections.emptyList());
        IViewCommentService commentService = mock(IViewCommentService.class);
        when(commentService.selectViewCommentList(any())).thenReturn(Collections.emptyList());
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.emptyList());
        IViewRegistrationService registrationService = mock(IViewRegistrationService.class);
        when(registrationService.selectRegistrationSummaries(any())).thenReturn(Collections.emptyList());
        CmsRegistrationStatisticsMapper statisticsMapper = mock(CmsRegistrationStatisticsMapper.class);
        when(statisticsMapper.statisticsSalesAmoutByOwner(any())).thenReturn(Collections.emptyList());

        ApiActivityController controller = spy(new ApiActivityController());
        doReturn(0L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        when(nodes.selectCmsTopicNodeList(any())).thenAnswer(invocation -> {
            CmsTopicNode probe = invocation.getArgument(0);
            assertEquals(990027L, probe.getTopicId());
            assertEquals(1L, probe.getStatus());
            return Collections.nCopies(4, new CmsTopicNode());
        });
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodes);
        ReflectionTestUtils.setField(controller, "memberTemplateService", memberTemplateService);
        ReflectionTestUtils.setField(controller, "viewCollaboratorsService", collaboratorsService);
        ReflectionTestUtils.setField(controller, "commentService", commentService);
        ReflectionTestUtils.setField(controller, "umsCommentService", mock(com.chengyinhub.business.service.IUmsCommentService.class));
        ReflectionTestUtils.setField(controller, "omsTicketService", ticketService);
        ReflectionTestUtils.setField(controller, "viewRegistrationService", registrationService);
        ReflectionTestUtils.setField(controller, "sysCategoryService", mock(ISysCategoryService.class));
        ReflectionTestUtils.setField(controller, "cmsRegistrationStatisticsMapper", statisticsMapper);
        ReflectionTestUtils.setField(controller, "tmsBrowseRecordService", mock(ITmsBrowseRecordService.class));
        ReflectionTestUtils.setField(controller, "merchantBusinessStatusService",
                mock(com.chengyinhub.business.service.IMerchantBusinessStatusService.class));

        AjaxResult res = controller.activityInfo(String.valueOf(ACTIVITY_ID));
        assertTrue(res.isSuccess(), "夹具没搭好:详情没走到成功分支");
        Object data = res.get(AjaxResult.DATA_TAG);
        assertTrue(data instanceof ActivityInfoVO, "详情必须返回 ActivityInfoVO");
        return (ActivityInfoVO) data;
    }

    private static Date daysAgo(int days)
    {
        return offset(-days);
    }

    private static Date daysAhead(int days)
    {
        return offset(days);
    }

    private static Date offset(int days)
    {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTime();
    }
}
