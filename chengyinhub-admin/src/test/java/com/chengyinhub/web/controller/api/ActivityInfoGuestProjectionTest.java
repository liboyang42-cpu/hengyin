package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.ViewComment;
import com.chengyinhub.business.domain.ViewRegistration;
import com.chengyinhub.business.domain.vo.ActivityInfoVO;
import com.chengyinhub.business.domain.vo.PublicTicketVO;
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
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * walk-fix Round5 #6:分享来的活动详情对游客只读开放(@Anonymous)。
 * 游客只拿公开字段:无报名名单(会员头像/昵称/ID)、无评价人明细、无销售态、无手机号;
 * 下架/待审活动对游客按不存在处理;已登录用户的响应不变。
 */
class ActivityInfoGuestProjectionTest {

    private static final long ACTIVITY_ID = 8801L;
    private static final long HOST = 901L;

    private ITmsBrowseRecordService browse;

    @Test
    void activityInfoIsTheOnlyHandlerOnItsPathAndIsMethodLevelAnonymous() throws Exception {
        assertNull(ApiActivityController.class.getAnnotation(Anonymous.class), "不得整类匿名");
        Method info = ApiActivityController.class.getMethod("activityInfo", String.class);
        assertTrue(info.isAnnotationPresent(Anonymous.class));
        assertEquals("/info", info.getAnnotation(PostMapping.class).value()[0]);
        long sharing = Arrays.stream(ApiActivityController.class.getDeclaredMethods())
                .filter(m -> m.getAnnotation(PostMapping.class) != null
                        && Arrays.asList(m.getAnnotation(PostMapping.class).value()).contains("/info"))
                .count();
        assertEquals(1, sharing, "@Anonymous 不分 HTTP 方法,/info 不得被写方法共用");
    }

    @Test
    void guestGetsPublicFieldsOnlyWithoutRostersCommentsSalesStateOrPhones() throws Exception {
        ApiActivityController controller = controller(null, published());

        AjaxResult result = controller.activityInfo(String.valueOf(ACTIVITY_ID));

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        ActivityInfoVO vo = (ActivityInfoVO) result.get("data");
        assertEquals("城市夜跑", vo.getName());
        assertNull(vo.getRegistrationList(), "报名名单是会员数据");
        assertNull(vo.getCommentList(), "评价明细带评价人会员信息");
        assertEquals(Integer.valueOf(2), vo.getRegistrationCount(), "聚合人数照常");
        assertEquals(Integer.valueOf(1), vo.getCommentCount());
        assertEquals(Integer.valueOf(0), vo.getIsSignUp());
        assertFalse(Boolean.TRUE.equals(vo.getTimeLocationLocked()), "是否已有在售订单是销售态");
        PublicTicketVO ticket = vo.getOmsTicketList().get(0);
        assertEquals("标准票", ticket.getName());
        assertNull(ticket.getCmsRegistrationList(), "票种阵容名单是会员数据");

        String json = new ObjectMapper().writeValueAsString(vo);
        for (String forbidden : new String[]{"13800000000", "报名者昵称", "评价者昵称", "\"phone\"", "\"mobile\"",
                "orderNo", "paymentStatus", "salesAmout"}) {
            assertFalse(json.contains(forbidden), forbidden + " 不得出现在游客响应里: " + json);
        }
        verify(browse, never()).insertTmsBrowseRecord(any());
    }

    @Test
    void guestCannotOpenUnpublishedOrUnreviewedActivity() throws Exception {
        for (long[] state : new long[][]{{1L, 0L}, {0L, 1L}, {2L, 1L}}) {
            CmsActivity activity = published();
            activity.setStatus(state[0]);
            activity.setPublishStatus(state[1]);
            AjaxResult result = controller(null, activity).activityInfo(String.valueOf(ACTIVITY_ID));
            assertEquals(200, result.get(AjaxResult.CODE_TAG));
            assertNull(result.get("data"), "status=" + state[0] + " publish=" + state[1] + " 对游客按不存在处理");
        }
    }

    @Test
    void loggedInMemberResponseIsUnchanged() throws Exception {
        ApiActivityController controller = controller(77L, published());

        ActivityInfoVO vo = (ActivityInfoVO) controller.activityInfo(String.valueOf(ACTIVITY_ID)).get("data");

        assertNotNull(vo.getRegistrationList());
        assertEquals("报名者昵称", vo.getRegistrationList().get(0).getNickname());
        assertNotNull(vo.getCommentList());
        assertNotNull(vo.getOmsTicketList().get(0).getCmsRegistrationList());
        verify(browse, times(1)).insertTmsBrowseRecord(any());
    }

    private CmsActivity published() {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setMemberId(HOST);
        activity.setName("城市夜跑");
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        return activity;
    }

    private ApiActivityController controller(Long viewer, CmsActivity activity) throws Exception {
        ApiActivityController controller = spy(new ApiActivityController());
        if (viewer == null) {
            doThrow(new ServiceException("获取APP用户信息异常", HttpStatus.UNAUTHORIZED)).when(controller).getAppUserId();
        } else {
            doReturn(viewer).when(controller).getAppUserId();
        }
        ICmsActivityService activities = mock(ICmsActivityService.class);
        when(activities.selectCmsActivityDetailById(eq(ACTIVITY_ID), anyLong())).thenReturn(activity);
        when(activities.countOpenSaleRegistrations(ACTIVITY_ID)).thenReturn(3);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activities);
        ReflectionTestUtils.setField(controller, "merchantBusinessStatusService", mock(IMerchantBusinessStatusService.class));
        ICmsMemberTemplateService templates = mock(ICmsMemberTemplateService.class);
        when(templates.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "memberTemplateService", templates);
        IViewCollaboratorsService collaborators = mock(IViewCollaboratorsService.class);
        when(collaborators.selectViewCollaboratorsList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "viewCollaboratorsService", collaborators);
        ViewComment comment = new ViewComment();
        comment.setId(1L);
        comment.setMemberId(55L);
        comment.setMemberNickname("评价者昵称");
        comment.setContents("很好玩");
        IViewCommentService comments = mock(IViewCommentService.class);
        when(comments.selectViewCommentList(any())).thenReturn(Collections.singletonList(comment));
        ReflectionTestUtils.setField(controller, "commentService", comments);
        ReflectionTestUtils.setField(controller, "umsCommentService", mock(IUmsCommentService.class));
        OmsTicket ticket = new OmsTicket();
        ticket.setId(31L);
        ticket.setName("标准票");
        IOmsTicketService tickets = mock(IOmsTicketService.class);
        when(tickets.selectOmsTicketList(any())).thenReturn(Collections.singletonList(ticket));
        ReflectionTestUtils.setField(controller, "omsTicketService", tickets);
        ViewRegistration registrant = new ViewRegistration();
        registrant.setId(5L);
        registrant.setMemberId(66L);
        registrant.setNickname("报名者昵称");
        registrant.setAvatar("a.png");
        registrant.setPhone("13800000000");
        List<ViewRegistration> roster = Arrays.asList(registrant, registrant);
        IViewRegistrationService registrations = mock(IViewRegistrationService.class);
        when(registrations.selectRegistrationSummaries(any())).thenAnswer(inv -> {
            ViewRegistration probe = inv.getArgument(0);
            // 本人报名探针(memberId 已设)查不到,其余返回名单
            return probe.getMemberId() != null ? Collections.emptyList() : roster;
        });
        ReflectionTestUtils.setField(controller, "viewRegistrationService", registrations);
        CmsRegistrationStatisticsMapper statistics = mock(CmsRegistrationStatisticsMapper.class);
        when(statistics.statisticsSalesAmoutByOwner(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "cmsRegistrationStatisticsMapper", statistics);
        browse = mock(ITmsBrowseRecordService.class);
        when(browse.countBrowseByTypeAndTarget(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "tmsBrowseRecordService", browse);
        return controller;
    }
}
