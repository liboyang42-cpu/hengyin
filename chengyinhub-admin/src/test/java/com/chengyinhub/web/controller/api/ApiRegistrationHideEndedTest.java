package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static com.chengyinhub.web.service.registration.RegistrationQueryApplication.filterEndedOverWeek;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 「我参与中」(myJoined,进行中 Tab):活动/主题结束满 7 天后隐藏该单(仅列表不展示,不删数据);
 * 订单详情(/info)、票夹、核销记录等其它读接口不经过该过滤,仍可查到。
 *
 * <p>⚠️ 2026-09-16 截图冒烟改口径:{@code myRegistrationList}(我的订单账本)不再套该过滤 ——
 * 客户端七态 Tab 的「已完成/已退款/不可退款」指的就是结束过的单,按结束时间隐藏会让这些 Tab 恒空
 * (真实商家号有已支付订单却看到整屏空列表)。两个用途分工见
 * {@link #orderList_keepsEndedOverWeekOrders()} 与 {@link #myJoined_wiresFilter_hidesOverWeekKeepsRecent()}。
 *
 * 纯逻辑走 {@link com.chengyinhub.web.service.registration.RegistrationQueryApplication#filterEndedOverWeek} 静态 helper 直接断言;
 * 列表接口 wiring / 详情不受影响走 spy 控制器(stub getAppUserId + service)。
 */
class ApiRegistrationHideEndedTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private final Date now = new Date();

    private Date daysAgo(int n)   { return new Date(now.getTime() - n * DAY); }
    private Date daysLater(int n) { return new Date(now.getTime() + n * DAY); }

    /** owner_type=1 主题报名,挂载 cms_topic.end_date=end(end 可为 null) */
    private CmsRegistration topicReg(long id, Date end) {
        CmsRegistration r = baseReg(id, 1L);
        CmsTopic t = new CmsTopic();
        t.setId(r.getOwnerId());
        t.setEndDate(end);
        r.setCmsTopic(t);
        return r;
    }

    /** owner_type=2 活动报名,挂载 cms_activity.end_date=end */
    private CmsRegistration activityReg(long id, Date end) {
        CmsRegistration r = baseReg(id, 2L);
        CmsActivity a = new CmsActivity();
        a.setId(r.getOwnerId());
        a.setEndDate(end);
        r.setCmsActivity(a);
        return r;
    }

    /** owner_type=1 但 owner 未挂载(模拟被删/查不到主题) */
    private CmsRegistration topicRegNoOwner(long id) {
        return baseReg(id, 1L);
    }

    private CmsRegistration baseReg(long id, long ownerType) {
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        r.setMemberId(100L);
        r.setOwnerType(ownerType);
        r.setOwnerId(id * 10);
        r.setCreateTime(now);
        return r;
    }

    // ---------- 纯 helper:隐藏口径 ----------

    @Test
    void kept_whenEndedWithinWindow() {
        // ① 刚结束(3 天前,<7 天)仍在列表
        List<CmsRegistration> out = filterEndedOverWeek(new ArrayList<>(Arrays.asList(topicReg(1, daysAgo(3)))), now);
        assertEquals(1, out.size(), "结束仅 3 天,应仍展示");
    }

    @Test
    void hidden_whenEndedOverWindow() {
        // ② 结束 10 天(>7 天)被隐藏(活动口径:cms_activity.end_date)
        List<CmsRegistration> out = filterEndedOverWeek(new ArrayList<>(Arrays.asList(activityReg(2, daysAgo(10)))), now);
        assertEquals(0, out.size(), "结束超过 7 天,应隐藏");
    }

    @Test
    void kept_whenEndDateNull() {
        // ③ 无结束时间(end_date=null)不隐藏(保守)
        List<CmsRegistration> out = filterEndedOverWeek(new ArrayList<>(Arrays.asList(topicReg(3, null))), now);
        assertEquals(1, out.size(), "无结束时间应保留");
    }

    @Test
    void kept_whenOwnerMissing() {
        // owner 查不到(end_date 未知)→ 保守保留,不误隐藏
        List<CmsRegistration> out = filterEndedOverWeek(new ArrayList<>(Arrays.asList(topicRegNoOwner(4))), now);
        assertEquals(1, out.size(), "owner 未挂载,结束时间未知,应保留");
    }

    @Test
    void kept_atExactly7DayBoundary() {
        // 边界:恰好 7 天前结束 → cutoff 严格 < 比较,不隐藏(展示更久,保守)
        List<CmsRegistration> out = filterEndedOverWeek(new ArrayList<>(Arrays.asList(topicReg(5, daysAgo(7)))), now);
        assertEquals(1, out.size(), "恰好 7 天边界应保留");
    }

    @Test
    void mixedList_onlyOverWeekHidden() {
        List<CmsRegistration> in = new ArrayList<>(Arrays.asList(
                topicReg(1, daysAgo(3)),      // 保留
                activityReg(2, daysAgo(10)),  // 隐藏
                topicReg(3, null),            // 保留(null)
                activityReg(4, daysAgo(8)),   // 隐藏
                topicReg(5, daysLater(2)),    // 保留(未结束)
                topicRegNoOwner(6)            // 保留(owner 缺失)
        ));
        List<Long> keptIds = new ArrayList<>();
        for (CmsRegistration r : filterEndedOverWeek(in, now)) keptIds.add(r.getId());
        assertEquals(Arrays.asList(1L, 3L, 5L, 6L), keptIds, "仅结束>7天的 2、4 被隐藏");
    }

    // ---------- 控制器 wiring:订单账本不隐藏 / 「我参与中」仍隐藏 / 详情不受影响 ----------

    /**
     * 2026-09-16 截图冒烟实测的缺陷:真实商家号有已支付订单,「我的订单」却整屏空 ——
     * 根因是本接口末端套了只该服务「进行中」视觉收纳的 filterEndedOverWeek。
     * 负控:把 {@code list = filterEndedOverWeek(list, new Date());} 加回
     * {@link ApiRegistrationController#myRegistrationList} → 本用例立刻红(期望 2 实得 1)。
     */
    @Test
    void orderList_keepsEndedOverWeekOrders() {
        ApiRegistrationController controller = spy(new ApiRegistrationController());
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        IRefundService refundService = mock(IRefundService.class);
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", registrationService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "topicService", topicService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "activityService", activityService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "refundService", refundService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "omsTicketService", ticketService);
        when(refundService.selectRefundList(any())).thenReturn(new ArrayList<>());
        // UI-20 返修:product_type 为 NULL 的主题会按票 mode 回落,票服务必须给真数据而不是留 null 字段
        OmsTicket freeExploreTicket = new OmsTicket();
        freeExploreTicket.setOwnerType(1L);
        freeExploreTicket.setOwnerId(10L);
        freeExploreTicket.setMode(ProductType.FREE_EXPLORE);
        when(ticketService.selectOmsTicketList(any()))
                .thenReturn(new ArrayList<>(Arrays.asList(freeExploreTicket)));
        doReturn(100L).when(controller).getAppUserId();

        // 服务返回:主题(3天前结束)+ 活动(10天前结束)。控制器会用 service 回填 owner。
        CmsRegistration recent = baseReg(1L, 1L);   // owner_id=10
        CmsRegistration old = baseReg(2L, 2L);      // owner_id=20
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Arrays.asList(recent, old)));
        CmsTopic recentTopic = new CmsTopic(); recentTopic.setId(10L); recentTopic.setEndDate(daysAgo(3));
        CmsActivity oldActivity = new CmsActivity(); oldActivity.setId(20L); oldActivity.setEndDate(daysAgo(10));
        when(topicService.selectCmsTopicById(10L)).thenReturn(recentTopic);
        when(activityService.selectCmsActivityById(20L)).thenReturn(oldActivity);

        AjaxResult res = RegistrationQueryTestFixtures.wire(controller).myRegistrationList("3", "0", null);
        TableDataInfo data = (TableDataInfo) res.get(AjaxResult.DATA_TAG);
        assertEquals(2, data.getRows().size(), "订单账本必须保留结束>7天的单,否则「已完成/已退款」Tab 恒空");
        List<Long> keptIds = new ArrayList<>();
        for (Object row : data.getRows()) keptIds.add(((CmsRegistration) row).getId());
        assertTrue(keptIds.contains(2L), "结束 10 天的活动单也必须在「我的订单」里能看到,实得=" + keptIds);
        assertEquals(Integer.valueOf(ProductType.FREE_EXPLORE), recent.getCmsTopic().getProductType(),
                "cms_topic.product_type 为 NULL 时须按票 mode 回填,否则订单卡的产品标签整块消失");
    }

    @Test
    void myJoined_wiresFilter_hidesOverWeekKeepsRecent() {
        ApiRegistrationController controller = spy(new ApiRegistrationController());
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        IRefundService refundService = mock(IRefundService.class);
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", registrationService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "topicService", topicService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "activityService", activityService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "refundService", refundService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "omsTicketService", ticketService);
        when(refundService.selectRefundList(any())).thenReturn(new ArrayList<>());
        when(ticketService.selectOmsTicketList(any())).thenReturn(new ArrayList<>());
        doReturn(100L).when(controller).getAppUserId();

        CmsRegistration recent = baseReg(1L, 1L);
        CmsRegistration old = baseReg(2L, 2L);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Arrays.asList(recent)), new ArrayList<>(Arrays.asList(old)));
        CmsTopic recentTopic = new CmsTopic(); recentTopic.setId(10L); recentTopic.setEndDate(daysAgo(3));
        CmsActivity oldActivity = new CmsActivity(); oldActivity.setId(20L); oldActivity.setEndDate(daysAgo(10));
        when(topicService.selectCmsTopicById(10L)).thenReturn(recentTopic);
        when(activityService.selectCmsActivityById(20L)).thenReturn(oldActivity);

        AjaxResult res = RegistrationQueryTestFixtures.wire(controller).myJoined();
        @SuppressWarnings("unchecked")
        List<CmsRegistration> rows = (List<CmsRegistration>) res.get(AjaxResult.DATA_TAG);
        assertEquals(1, rows.size(), "我的参与也应隐藏结束超过 7 天的活动");
        assertEquals(Long.valueOf(1L), rows.get(0).getId(), "仅保留近期结束的主题单");
    }

    @Test
    void detail_returnsEvenIfEndedOverWeek() {
        ApiRegistrationController controller = spy(new ApiRegistrationController());
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ICmsRegistrationScanService scanService = mock(ICmsRegistrationScanService.class);
        IRefundService refundService = mock(IRefundService.class);
        IUmsMemberService umsMemberService = mock(IUmsMemberService.class);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", registrationService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "topicService", topicService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationScanService", scanService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "refundService", refundService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "umsMemberService", umsMemberService);
        when(refundService.selectRefundList(any())).thenReturn(new ArrayList<>());
        doReturn(100L).when(controller).getAppUserId();

        // 结束 30 天(远超 7 天)的主题报名:详情接口仍须返回,不隐藏
        CmsRegistration reg = baseReg(5L, 1L); // owner_id=50, memberId=100, ticketId=null
        when(registrationService.selectCmsRegistrationById(5L)).thenReturn(reg);
        CmsTopic topic = new CmsTopic(); topic.setId(50L); topic.setMemberId(200L); topic.setEndDate(daysAgo(30));
        when(topicService.selectCmsTopicById(50L)).thenReturn(topic);
        UmsMember organizer = new UmsMember(); organizer.setNickname("城市主理人");
        when(umsMemberService.selectUmsMemberById(200L)).thenReturn(organizer);
        when(scanService.selectCmsRegistrationScanList(any())).thenReturn(new ArrayList<CmsRegistrationScan>());

        AjaxResult res = RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail("5");
        assertEquals(200, res.get(AjaxResult.CODE_TAG), "详情接口应正常返回 200");
        CmsRegistration got = (CmsRegistration) res.get(AjaxResult.DATA_TAG);
        assertTrue(got != null && got.getId() == 5L, "结束超 7 天的单,详情仍可查到");
        assertEquals("城市主理人", got.getOrganizerName(), "订单详情只展示主理人公开昵称");
    }
}
