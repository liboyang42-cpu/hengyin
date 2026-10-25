package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * U-033:玩家订单「未开始/进行中/已完成」标签要按服务端时刻判,不读手机时钟。
 * /list、/my-joined、/info 三个读接口每条报名都带 serverNow(epoch ms)。
 * 负控:删掉控制器里的 stampServerNow / setServerNow → 对应用例 serverNow 为 null 而红。
 */
class ApiRegistrationServerNowTest {

    private final ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
    private final ICmsTopicService topicService = mock(ICmsTopicService.class);

    private ApiRegistrationController wired() {
        ApiRegistrationController controller = spy(new ApiRegistrationController());
        IRefundService refundService = mock(IRefundService.class);
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        ReflectionTestUtils.setField(controller, "registrationService", registrationService);
        ReflectionTestUtils.setField(controller, "topicService", topicService);
        ReflectionTestUtils.setField(controller, "activityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(controller, "refundService", refundService);
        ReflectionTestUtils.setField(controller, "omsTicketService", ticketService);
        when(refundService.selectRefundList(any())).thenReturn(new ArrayList<>());
        when(ticketService.selectOmsTicketList(any())).thenReturn(new ArrayList<>());
        doReturn(100L).when(controller).getAppUserId();
        CmsTopic topic = new CmsTopic();
        topic.setId(10L);
        topic.setMemberId(200L);
        topic.setEndDate(new Date());
        when(topicService.selectCmsTopicById(10L)).thenReturn(topic);
        return controller;
    }

    private CmsRegistration reg(long id) {
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        r.setMemberId(100L);
        r.setOwnerType(1L);
        r.setOwnerId(10L);
        r.setCreateTime(new Date());
        return r;
    }

    private static void assertStamped(Long serverNow, long before, long after, String where) {
        assertNotNull(serverNow, where + " 必须带 serverNow,否则小程序只能退回手机时钟");
        assertTrue(serverNow >= before && serverNow <= after, where + " serverNow 应是本次读的服务端时刻");
    }

    @Test
    void orderList_stampsServerNowOnEveryRow() {
        ApiRegistrationController controller = wired();
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Arrays.asList(reg(1L), reg(2L))));
        long before = System.currentTimeMillis();
        AjaxResult res = controller.myRegistrationList("3", "0", null);
        long after = System.currentTimeMillis();
        TableDataInfo data = (TableDataInfo) res.get(AjaxResult.DATA_TAG);
        for (Object row : data.getRows()) {
            assertStamped(((CmsRegistration) row).getServerNow(), before, after, "/api/registration/list");
        }
    }

    @Test
    void myJoined_stampsServerNowOnEveryRow() {
        ApiRegistrationController controller = wired();
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Arrays.asList(reg(1L))), new ArrayList<>());
        long before = System.currentTimeMillis();
        AjaxResult res = controller.myJoined();
        long after = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        List<CmsRegistration> rows = (List<CmsRegistration>) res.get(AjaxResult.DATA_TAG);
        assertTrue(!rows.isEmpty());
        for (CmsRegistration row : rows) {
            assertStamped(row.getServerNow(), before, after, "/api/registration/my-joined");
        }
    }

    @Test
    void detail_stampsServerNow() {
        ApiRegistrationController controller = wired();
        ICmsRegistrationScanService scanService = mock(ICmsRegistrationScanService.class);
        IUmsMemberService umsMemberService = mock(IUmsMemberService.class);
        ReflectionTestUtils.setField(controller, "registrationScanService", scanService);
        ReflectionTestUtils.setField(controller, "umsMemberService", umsMemberService);
        when(scanService.selectCmsRegistrationScanList(any())).thenReturn(new ArrayList<CmsRegistrationScan>());
        when(umsMemberService.selectUmsMemberById(200L)).thenReturn(new UmsMember());
        when(registrationService.selectCmsRegistrationById(5L)).thenReturn(reg(5L));
        long before = System.currentTimeMillis();
        AjaxResult res = controller.getRegistrationDetail("5");
        long after = System.currentTimeMillis();
        assertStamped(((CmsRegistration) res.get(AjaxResult.DATA_TAG)).getServerNow(), before, after,
                "/api/registration/info");
    }
}
