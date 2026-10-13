package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.OmsTicketServiceImpl;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-1(审查D §1):后台改票 PUT 是已售锁在 App 票务同步之外的第二条写口。
 *
 * <p>活动票一旦有用户下单(待支付 0/支付中 1/已支付 2),后台也不许改它的时间、集合点、
 * 坐标、价格、退款规则,更不许软删(del_flag=2);票名等文字仍可改。主题票不在此列。
 *
 * <p>⚠️ 负控:拆掉 {@code assertSoldActivityTicketUntouched} 调用 ⇒ 锁用例全红;
 * 把 ownerType 判据删掉(主题票也锁)⇒ {@code topicTicketNotCoveredByActivitySoldLock} 红。
 */
class OmsTicketSoldActivityTicketGuardTest {

    private static final Long ACTIVITY = 702L;
    private static final Long TICKET = 55L;
    private static final Date START = new Date(1_800_000_000_000L);
    private static final Date END = new Date(1_800_003_600_000L);

    private OmsTicketMapper ticketMapper;
    private CmsRegistrationMapper registrationMapper;
    private OmsTicketServiceImpl service;

    @BeforeEach
    void setUp() {
        ticketMapper = mock(OmsTicketMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        service = new OmsTicketServiceImpl();
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        // 2026-09-15 集成第二段:lifecycle-refund 给 updateOmsTicket 加了 conversionProtectedFor(免费转商业闸),
        // 它要读 productRoleService;本夹具建于该字段出现之前,不注入会 NPE。默认 isPlatformOwned=false
        // 正是生产的 fail-closed 口径(查不到内容槽当第三方),本用例只验活动已售锁,不借平台自营逃逸。
        ReflectionTestUtils.setField(service, "productRoleService",
                mock(com.chengyinhub.business.service.IProductRoleService.class));
        // 同上:免费转商业闸的判定入口。本用例的主题是「活动已售锁管不管主题票」的对照组,
        // 与免费转商业正交 —— 该闸自己的覆盖在 OmsTicketFreeTopicConversionGuardTest
        // (含 updateTicketFromFreeToPaidOnPublishedFreeTopicIsRejected),这里取中性 false 不留缺口。
        ReflectionTestUtils.setField(service, "commercialTopicPolicy",
                mock(com.chengyinhub.business.service.support.CommercialTopicPolicy.class));

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setMemberId(66L);
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(existing());
        when(ticketMapper.updateOmsTicket(any(OmsTicket.class))).thenReturn(1);
        loginWith("business:ticket:edit", "business:activity:edit");
    }

    private OmsTicket existing() {
        OmsTicket t = new OmsTicket();
        t.setId(TICKET);
        t.setOwnerType(2L);
        t.setOwnerId(ACTIVITY);
        t.setDelFlag(0);
        t.setName("早鸟票");
        t.setPrice(new BigDecimal("199.00"));
        t.setStartTime(START);
        t.setEndTime(END);
        t.setMeetingPoint("人民广场");
        t.setGatherLng(new BigDecimal("121.470000"));
        t.setGatherLat(new BigDecimal("31.230000"));
        t.setRefundRule("提前24小时可全额退");
        return t;
    }

    private OmsTicket patch() {
        OmsTicket p = new OmsTicket();
        p.setId(TICKET);
        // 2026-09-16 补合 pay-leftover3 B4:票 update 必须声明归属(与票真实父对象一致),否则先被 400 挡掉。
        p.setOwnerType(2L);
        p.setOwnerId(ACTIVITY);
        return p;
    }

    private void givenSold() {
        when(registrationMapper.selectOpenSaleTicketIdsByActivity(ACTIVITY))
                .thenReturn(Collections.singletonList(TICKET));
    }

    private ServiceException assertRejected(OmsTicket submitted, String field) {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateOmsTicket(submitted));
        assertEquals(409, ex.getCode(), "已售锁是冲突语义,不是参数错误");
        assertTrue(ex.getMessage().contains(field), "实际:" + ex.getMessage());
        verify(ticketMapper, never()).updateOmsTicket(any(OmsTicket.class));
        return ex;
    }

    @Test
    void soldActivityTicket_locksStartTimeChange() {
        givenSold();
        OmsTicket p = patch();
        p.setStartTime(new Date(START.getTime() + 3_600_000L));

        assertRejected(p, "集合时间");
    }

    @Test
    void soldActivityTicket_locksEndTimeChange() {
        givenSold();
        OmsTicket p = patch();
        p.setEndTime(new Date(END.getTime() + 3_600_000L));

        assertRejected(p, "结束时间");
    }

    @Test
    void soldActivityTicket_locksMeetingPointChange() {
        givenSold();
        OmsTicket p = patch();
        p.setMeetingPoint("新集合点");

        assertRejected(p, "集合地点");
    }

    @Test
    void soldActivityTicket_locksGatherCoordinatesChange() {
        givenSold();
        OmsTicket p = patch();
        p.setGatherLng(new BigDecimal("121.480000"));

        assertRejected(p, "集合点坐标");
    }

    @Test
    void soldActivityTicket_locksPriceChange() {
        givenSold();
        OmsTicket p = patch();
        p.setPrice(new BigDecimal("149.00"));

        assertRejected(p, "价格");
    }

    @Test
    void soldActivityTicket_locksRefundRuleChange() {
        givenSold();
        OmsTicket p = patch();
        p.setRefundRule("不可退");

        assertRejected(p, "退款规则");
    }

    @Test
    void soldActivityTicket_softDeleteRejected() {
        givenSold();
        OmsTicket p = patch();
        p.setDelFlag(2);

        assertRejected(p, "不能删除");
    }

    @Test
    void soldActivityTicket_allowsNameChange() {
        givenSold();
        OmsTicket p = patch();
        p.setName("早鸟票(含保险)");

        assertEquals(1, service.updateOmsTicket(p));

        verify(ticketMapper).updateOmsTicket(any(OmsTicket.class));
    }

    @Test
    void unsoldActivityTicket_canChangePriceAndSoftDelete() {
        when(registrationMapper.selectOpenSaleTicketIdsByActivity(ACTIVITY))
                .thenReturn(Collections.emptyList());
        OmsTicket p = patch();
        p.setPrice(new BigDecimal("149.00"));

        assertEquals(1, service.updateOmsTicket(p));

        OmsTicket deleting = patch();
        deleting.setDelFlag(2);
        assertEquals(1, service.updateOmsTicket(deleting));
    }

    @Test
    void topicTicketNotCoveredByActivitySoldLock() {
        CmsTopic topic = new CmsTopic();
        topic.setId(701L);
        topic.setMemberId(88L);
        topic.setLifecycle(0);
        CmsTopicMapper topicMapper = (CmsTopicMapper) ReflectionTestUtils.getField(service, "cmsTopicMapper");
        when(topicMapper.selectCmsTopicById(701L)).thenReturn(topic);
        OmsTicket topicTicket = existing();
        topicTicket.setId(56L);
        topicTicket.setOwnerType(1L);
        topicTicket.setOwnerId(701L);
        when(ticketMapper.selectOmsTicketByIdForUpdate(56L)).thenReturn(topicTicket);
        // 主题票的已售在主题侧另有 lifecycle/重建两道口径,本闸只管活动票。
        when(registrationMapper.selectOpenSaleTicketIdsByActivity(701L))
                .thenReturn(Collections.singletonList(56L));
        loginWith("business:ticket:edit", "business:topic:edit");

        OmsTicket p = new OmsTicket();
        p.setId(56L);
        p.setOwnerType(1L);
        p.setOwnerId(701L);
        p.setPrice(new BigDecimal("99.00"));

        assertEquals(1, service.updateOmsTicket(p));
    }

    private void loginWith(String... permissions) {
        SysUser user = new SysUser();
        user.setUserId(1L);
        LoginUser loginUser = new LoginUser(user, new HashSet<>(Arrays.asList(permissions)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }
}
