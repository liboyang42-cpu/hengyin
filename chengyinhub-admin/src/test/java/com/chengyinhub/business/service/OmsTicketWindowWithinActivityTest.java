package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.OmsTicketServiceImpl;
import com.chengyinhub.business.support.OmsTicketWriteValidator;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CU-C-48(2026-09-24 走查):活动顶部写 9 月 24 日,票种却是 10 月 1/2 日,第一张票的报名截止
 * 还晚于活动结束时间 —— 同一个界面里两个互相打架的日期。
 *
 * <p>用户裁决:票种时间 = 场次时间,后端写口加「票种窗口必须落在活动 start_date–end_date 内」。
 * 本类守两件事:判据本身,以及三条写路径(App 发布 / App 编辑 / 后台票务)真的接上了它。
 */
class OmsTicketWindowWithinActivityTest {

    private static final Long ACTIVITY_ID = 702L;
    private static final Long TICKET_ID = 6L;

    private OmsTicketMapper ticketMapper;
    private CmsTopicMapper topicMapper;
    private CmsActivityMapper activityMapper;
    private CmsRegistrationMapper registrationMapper;
    private OmsTicketServiceImpl service;

    private static Date at(String value) {
        return Timestamp.valueOf(value);
    }

    private static CmsActivity activity(java.util.Date start, java.util.Date end) {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setMemberId(66L);
        activity.setStartDate(start);
        activity.setEndDate(end);
        return activity;
    }

    @BeforeEach
    void setUp() {
        ticketMapper = mock(OmsTicketMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        // 已售锁会先查该活动票上还有没有活着的报名;这里没有报名,放行到窗口闸。
        when(registrationMapper.selectOpenSaleTicketIdsByActivity(any())).thenReturn(Collections.emptyList());
        service = new OmsTicketServiceImpl();
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "commercialTopicPolicy",
                mock(com.chengyinhub.business.service.support.CommercialTopicPolicy.class));
        ReflectionTestUtils.setField(service, "productRoleService",
                mock(com.chengyinhub.business.service.IProductRoleService.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ── 判据 ─────────────────────────────────────────────────────────── */

    @Test
    void ticketWindowInsideActivityWindowIsAccepted() {
        assertDoesNotThrow(() -> OmsTicketWriteValidator.validateTicketWindowWithinActivity(
                at("2026-09-24 09:00:00"), at("2026-09-24 18:00:00"),
                at("2026-09-24 00:00:00"), at("2026-09-24 23:59:59")));
    }

    @Test
    void ticketEndAfterActivityEndIsRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> OmsTicketWriteValidator.validateTicketWindowWithinActivity(
                        at("2026-10-01 09:00:00"), at("2026-10-02 18:00:00"),
                        at("2026-09-24 00:00:00"), at("2026-09-24 23:59:59")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("票种结束时间不能晚于活动结束时间"), ex.getMessage());
    }

    @Test
    void ticketStartBeforeActivityStartIsRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> OmsTicketWriteValidator.validateTicketWindowWithinActivity(
                        at("2026-09-23 09:00:00"), at("2026-09-24 18:00:00"),
                        at("2026-09-24 00:00:00"), at("2026-09-24 23:59:59")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("票种开始时间不能早于活动开始时间"), ex.getMessage());
    }

    @Test
    void missingActivityWindowOrPartialTicketSkipsTheCheck() {
        assertDoesNotThrow(() -> OmsTicketWriteValidator.validateTicketWindowWithinActivity(
                at("2026-10-01 09:00:00"), at("2026-10-02 18:00:00"), null, null),
                "活动没有日期时判不了,不能因此把票务全都拒掉");
        assertDoesNotThrow(() -> OmsTicketWriteValidator.validateTicketWindowWithinActivity(
                null, at("2026-09-24 18:00:00"),
                at("2026-09-24 00:00:00"), at("2026-09-24 23:59:59")),
                "部分更新时只送了一个端点,另一端点保持原值");
    }

    /* ── App 发布路径 ─────────────────────────────────────────────────── */

    @Test
    void publishActivityValidatesTicketWindowWithinActivityWindow() throws Exception {
        String source = WiringSource.read(
                "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsActivityServiceImpl.java")
                .replaceAll("\\s+", "");
        assertTrue(source.contains(
                        "OmsTicketWriteValidator.validateTicketWindowWithinActivity(ticket.getStartTime(),"
                                + "ticket.getEndTime(),activity.getStartDate(),activity.getEndDate());"),
                "发布活动落票前必须拦住越界场次");
        assertTrue(source.contains(
                        "OmsTicketWriteValidator.validateTicketWindowWithinActivity(ticketRequest.getStartTime(),"
                                + "ticketRequest.getEndTime(),request.getStartDate(),request.getEndDate());"),
                "编辑活动的票务同步(更新与新增两条)必须先过同一把尺子");
    }

    /* ── 后台票务路径(能直接写 oms_ticket 的第二条口子) ──────────────── */

    @Test
    void activityTicketOutsideActivityWindowIsRejectedOnInsertWithoutWriting() {
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID))
                .thenReturn(activity(at("2026-09-24 00:00:00"), at("2026-09-24 23:59:59")));
        loginWith("business:ticket:add", "business:activity:edit");

        OmsTicket submitted = activityTicket(at("2026-10-01 09:00:00"), at("2026-10-02 18:00:00"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertOmsTicket(submitted));

        assertEquals(400, ex.getCode());
        verify(ticketMapper, never()).insertOmsTicket(any(OmsTicket.class));
    }

    @Test
    void activityTicketInsideActivityWindowStillInserts() {
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID))
                .thenReturn(activity(at("2026-09-24 00:00:00"), at("2026-09-24 23:59:59")));
        when(ticketMapper.insertOmsTicket(any(OmsTicket.class))).thenReturn(1);
        loginWith("business:ticket:add", "business:activity:edit");

        assertEquals(1, service.insertOmsTicket(activityTicket(at("2026-09-24 09:00:00"), at("2026-09-24 18:00:00"))));
    }

    @Test
    void activityTicketOutsideActivityWindowIsRejectedOnUpdate() {
        OmsTicket existing = activityTicket(at("2026-09-24 09:00:00"), at("2026-09-24 18:00:00"));
        existing.setId(TICKET_ID);
        existing.setRemainingInventory(8L);
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET_ID)).thenReturn(existing);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID))
                .thenReturn(activity(at("2026-09-24 00:00:00"), at("2026-09-24 23:59:59")));
        loginWith("business:ticket:edit", "business:activity:edit");

        OmsTicket patch = new OmsTicket();
        patch.setId(TICKET_ID);
        patch.setOwnerType(2L);
        patch.setOwnerId(ACTIVITY_ID);
        patch.setEndTime(at("2026-10-01 18:00:00"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateOmsTicket(patch));

        assertEquals(400, ex.getCode());
        assertEquals("票种结束时间不能晚于活动结束时间", ex.getMessage());
        verify(ticketMapper, never()).updateOmsTicket(any(OmsTicket.class));
    }

    @Test
    void topicTicketIsNotSubjectToTheActivityWindow() {
        Long topicId = 901L;
        CmsTopic topic = new CmsTopic();
        topic.setId(topicId);
        topic.setMemberId(88L);
        topic.setLifecycle(0);
        when(topicMapper.selectCmsTopicById(topicId)).thenReturn(topic);
        when(ticketMapper.insertOmsTicket(any(OmsTicket.class))).thenReturn(1);
        loginWith("business:ticket:add", "business:topic:edit");

        OmsTicket submitted = new OmsTicket();
        submitted.setOwnerType(1L);
        submitted.setOwnerId(topicId);
        submitted.setName("主题票");
        submitted.setTotalInventory(10L);
        submitted.setStartTime(at("2026-10-01 09:00:00"));
        submitted.setEndTime(at("2026-10-02 18:00:00"));

        assertEquals(1, service.insertOmsTicket(submitted));
    }

    private OmsTicket activityTicket(Date startTime, Date endTime) {
        OmsTicket ticket = new OmsTicket();
        ticket.setOwnerType(2L);
        ticket.setOwnerId(ACTIVITY_ID);
        ticket.setName("场次票");
        ticket.setTotalInventory(10L);
        ticket.setPrice(new BigDecimal("69.00"));
        ticket.setStartTime(startTime);
        ticket.setEndTime(endTime);
        return ticket;
    }

    private void loginWith(String... permissions) {
        SysUser user = new SysUser();
        user.setUserId(1L);
        LoginUser loginUser = new LoginUser(user, new HashSet<>(Arrays.asList(permissions)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }
}
