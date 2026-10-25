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
import com.chengyinhub.web.service.registration.RegistrationQueryApplication;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * 真实 controller → QueryApplication 接线；删掉读 owner 的 stampServerNow / setServerNow 即红。
 */
class ApiRegistrationServerNowTest {

    private final ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
    private final ICmsTopicService topicService = mock(ICmsTopicService.class);
    private RegistrationQueryApplication query;

    private ApiRegistrationController wired() {
        ApiRegistrationController controller = spy(new ApiRegistrationController());
        query = new RegistrationQueryApplication();
        ReflectionTestUtils.setField(controller, "registrationQueryApplication", query);
        IRefundService refundService = mock(IRefundService.class);
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        ReflectionTestUtils.setField(query, "registrationService", registrationService);
        ReflectionTestUtils.setField(query, "topicService", topicService);
        ReflectionTestUtils.setField(query, "activityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(query, "refundService", refundService);
        ReflectionTestUtils.setField(query, "omsTicketService", ticketService);
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
        assertEquals(2, data.getRows().size());
        assertEquals(2L, data.getTotal());
        assertEquals(((CmsRegistration) data.getRows().get(0)).getServerNow(),
                ((CmsRegistration) data.getRows().get(1)).getServerNow());
        for (Object row : data.getRows()) {
            assertStamped(((CmsRegistration) row).getServerNow(), before, after, "/api/registration/list");
        }
    }

    @Test
    void myJoined_stampsServerNowOnEveryRow() {
        ApiRegistrationController controller = wired();
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Arrays.asList(reg(1L), reg(2L))), new ArrayList<>());
        long before = System.currentTimeMillis();
        AjaxResult res = controller.myJoined();
        long after = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        List<CmsRegistration> rows = (List<CmsRegistration>) res.get(AjaxResult.DATA_TAG);
        assertTrue(!rows.isEmpty());
        assertEquals(2, rows.size());
        assertEquals(rows.get(0).getServerNow(), rows.get(1).getServerNow());
        for (CmsRegistration row : rows) {
            assertStamped(row.getServerNow(), before, after, "/api/registration/my-joined");
        }
    }

    @Test
    void stampServerNow_usesOneInstantAndSkipsNullRows() {
        List<CmsRegistration> rows = Arrays.asList(reg(1L), null, reg(2L));
        long before = System.currentTimeMillis();
        ReflectionTestUtils.invokeMethod(RegistrationQueryApplication.class, "stampServerNow", rows);
        ReflectionTestUtils.invokeMethod(RegistrationQueryApplication.class, "stampServerNow", (Object) null);
        long after = System.currentTimeMillis();
        assertEquals(3, rows.size());
        assertNull(rows.get(1));
        assertEquals(rows.get(0).getServerNow(), rows.get(2).getServerNow());
        assertStamped(rows.get(0).getServerNow(), before, after, "batch first");
        assertStamped(rows.get(2).getServerNow(), before, after, "batch last");
    }

    @Test
    void detail_stampsServerNow() {
        ApiRegistrationController controller = wired();
        ICmsRegistrationScanService scanService = mock(ICmsRegistrationScanService.class);
        IUmsMemberService umsMemberService = mock(IUmsMemberService.class);
        ReflectionTestUtils.setField(query, "registrationScanService", scanService);
        ReflectionTestUtils.setField(query, "umsMemberService", umsMemberService);
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
