package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.ViewRegistration;
import com.chengyinhub.business.domain.vo.ActivityInfoVO;
import com.chengyinhub.business.domain.TmsBrowseRecord;
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
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * 活动详情浏览量读写契约（2026-08-04）。
 *
 * <p>两条既往缺陷各配一条断言，都必须断在「传给下游的实参」上，不能用 any() 放行：</p>
 * <ol>
 *   <li>浏览量查询条件曾被 put 进另一个 map，实际传下去的是空 map。空 map 会让
 *       countBrowseByTypeAndTarget 的 &lt;where&gt; 和 GROUP BY 全部不生成，退化成
 *       全表 COUNT(*)，活动详情显示的是全站总浏览数。</li>
 *   <li>写入浏览记录曾用 browse_type=1（主题的码），而读取用 2，写读不匹配 ⇒
 *       活动 viewCount 恒 0，且同号主题的浏览量被活动浏览污染。</li>
 * </ol>
 */
class ApiActivityInfoBrowseContractTest {

    private static final long ACTIVITY_ID = 4321L;
    /** 全仓约定：活动=2、主题=1（见 ApiActivityController 列表页与 ApiTopicController）。 */
    private static final int BROWSE_TYPE_ACTIVITY = 2;

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
    void activityInfo_queriesBrowseCountScopedToThisActivity() {
        Fixture f = fixture();

        AjaxResult res = f.controller.activityInfo(String.valueOf(ACTIVITY_ID));
        assertNotNull(res, "详情应正常返回");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(f.browseRecordService).countBrowseByTypeAndTarget(captor.capture());
        Map<String, Object> params = captor.getValue();

        assertEquals(BROWSE_TYPE_ACTIVITY, params.get("browseType"),
                "浏览量查询必须带 browseType=2，否则 <where>/GROUP BY 不生成，读到的是全站总浏览数");
        assertEquals(ACTIVITY_ID, ((Number) params.get("targetId")).longValue(),
                "浏览量查询必须带本活动的 targetId，否则统计的不是这个活动");
    }

    @Test
    void activityInfo_writesBrowseRecordWithActivityTypeCode() {
        Fixture f = fixture();

        f.controller.activityInfo(String.valueOf(ACTIVITY_ID));

        ArgumentCaptor<TmsBrowseRecord> captor = ArgumentCaptor.forClass(TmsBrowseRecord.class);
        verify(f.browseRecordService).insertTmsBrowseRecord(captor.capture());
        TmsBrowseRecord written = captor.getValue();

        assertEquals(Long.valueOf(BROWSE_TYPE_ACTIVITY), written.getBrowseType(),
                "活动浏览必须写 browse_type=2；写 1 会被当成主题，读侧永远读不到，且污染同号主题的浏览量");
        assertEquals(Long.valueOf(ACTIVITY_ID), written.getTargetId(), "写入的 target_id 应为本活动");
    }

    @Test
    void activityInfo_doesNotReadPrivateRegistrationFields() {
        Fixture f = fixture();
        when(f.registrationService.selectViewRegistrationList(any()))
                .thenThrow(new IllegalStateException("unreadable legacy registration PII"));
        OmsTicket ticket = new OmsTicket();
        ticket.setId(83L);
        when(f.ticketService.selectOmsTicketList(any())).thenReturn(Collections.singletonList(ticket));
        ViewRegistration registration = new ViewRegistration();
        registration.setId(99L);
        registration.setMemberId(9004L);
        registration.setNickname("玩家");
        when(f.registrationService.selectRegistrationSummaries(any()))
                .thenReturn(Collections.singletonList(registration));
        doReturn(9004L).when(f.controller).getAppUserId();
        AjaxResult result = assertDoesNotThrow(() ->
                f.controller.activityInfo(String.valueOf(ACTIVITY_ID)));
        assertEquals(200, result.get("code"));
        ActivityInfoVO detail = (ActivityInfoVO) result.get(AjaxResult.DATA_TAG);
        assertEquals(1, detail.getRegistrationCount());
        assertEquals(1, detail.getIsSignUp());
        assertEquals("玩家", detail.getRegistrationList().get(0).getNickname());
        assertEquals(1, detail.getOmsTicketList().get(0).getRegistrationCount());
        verify(f.registrationService, never()).selectViewRegistrationList(any());
    }

    private Fixture fixture() {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setName("契约测试活动");
        activity.setTemplateId(0L);

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

        Fixture f = new Fixture();
        f.registrationService = registrationService;
        f.ticketService = ticketService;
        f.browseRecordService = mock(ITmsBrowseRecordService.class);
        // 只有查询条件正确时下游才会返回这一行；条件错了实现也读不到本活动的数
        Map<String, Object> row = new HashMap<>();
        row.put("browse_type", BROWSE_TYPE_ACTIVITY);
        row.put("target_id", ACTIVITY_ID);
        row.put("browse_count", 7L);
        when(f.browseRecordService.countBrowseByTypeAndTarget(any()))
                .thenReturn(Collections.<Map<String, Object>>singletonList(row));

        f.controller = spy(new ApiActivityController());
        // release-0917 集成:rule-merchant-0917 给 info 接口加了 @Autowired 营业状态依赖(#10),手工装配须补上;mock 默认 false=营业中。
        ReflectionTestUtils.setField(f.controller, "merchantBusinessStatusService", mock(com.chengyinhub.business.service.IMerchantBusinessStatusService.class));
        doReturn(0L).when(f.controller).getAppUserId();

        ReflectionTestUtils.setField(f.controller, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(f.controller, "memberTemplateService", memberTemplateService);
        ReflectionTestUtils.setField(f.controller, "viewCollaboratorsService", collaboratorsService);
        ReflectionTestUtils.setField(f.controller, "commentService", commentService);
        // CU-C-50:详情评分改按真实评论算,需要评论服务(无评论 → null → 0)
        ReflectionTestUtils.setField(f.controller, "umsCommentService", mock(com.chengyinhub.business.service.IUmsCommentService.class));
        ReflectionTestUtils.setField(f.controller, "omsTicketService", ticketService);
        ReflectionTestUtils.setField(f.controller, "viewRegistrationService", registrationService);
        ReflectionTestUtils.setField(f.controller, "sysCategoryService", mock(ISysCategoryService.class));
        ReflectionTestUtils.setField(f.controller, "cmsRegistrationStatisticsMapper", statisticsMapper);
        ReflectionTestUtils.setField(f.controller, "tmsBrowseRecordService", f.browseRecordService);
        return f;
    }

    /** 只留测试真正断言用得到的两个句柄,其余 mock 装完即弃。 */
    private static class Fixture {
        ApiActivityController controller;
        ITmsBrowseRecordService browseRecordService;
        IViewRegistrationService registrationService;
        IOmsTicketService ticketService;
    }
}
