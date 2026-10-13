package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CU-C-32(2026-09-25 裁决:场次开始即停售):活动票以 startTime(场次开始时间)为准,
 * 一到开始即不可建单 —— 返回 {@link ServiceException}「该场次已开始,不能再报名」。
 *
 * <p>历史:#1169 拿 endTime 当截止,于是「开场后、散场前」这一段仍能建单(走查 9-25 复现)。
 * 本闸判据与小程序 utils/ticket-window.js 同尺:startTime 缺失(oms_ticket 该列可空)时才退到
 * endTime 这唯一已知边界;两个都缺就不判。候补凭证(库存已为你 hold)走 signupDeadline,不在本闸内。
 * 只管活动票(ownerType=2),主题票由 SaleAvailabilityPolicy 定窗口,这道闸不误伤。
 */
class RegistrationTicketWindowGateTest {

    private static final Long TICKET_ID = 9L;
    private static final Long ACTIVITY_ID = 500L;

    private OmsTicketMapper ticketMapper;
    private CmsRegistrationServiceImpl service;
    private Method gate;

    private static Date ago(String value) {
        return Timestamp.valueOf(value);
    }

    private static Date future(String value) {
        return Timestamp.valueOf(value);
    }

    @BeforeEach
    void setUp() throws Exception {
        ticketMapper = mock(OmsTicketMapper.class);
        service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        gate = CmsRegistrationServiceImpl.class.getDeclaredMethod(
                "validateAndGetTicket", Long.class, Integer.class, Long.class, boolean.class);
        gate.setAccessible(true);
    }

    private OmsTicket ticket(Date startTime, Date endTime) {
        OmsTicket ticket = new OmsTicket();
        ticket.setId(TICKET_ID);
        ticket.setOwnerType(2L);
        ticket.setOwnerId(ACTIVITY_ID);
        ticket.setDelFlag(0);
        ticket.setRemainingInventory(5L);
        ticket.setStartTime(startTime);
        ticket.setEndTime(endTime);
        return ticket;
    }

    private Object invoke(OmsTicket ticket, boolean inventoryAlreadyHeld) throws Exception {
        when(ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(ticket);
        return gate.invoke(service, TICKET_ID, 2, ACTIVITY_ID, inventoryAlreadyHeld);
    }

    /** 负控核心:开场即停售 —— 已结束场次(开始/结束都过了)当然也拒。 */
    @Test
    void endedSessionTicketIsRejectedAtOrderCreation() throws Exception {
        OmsTicket expired = ticket(ago("2020-09-22 09:00:00"), ago("2020-09-22 18:00:00"));

        Exception ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> invoke(expired, false));

        assertTrue(ex.getCause() instanceof ServiceException, "建单闸要返回 ServiceException,便于把文案带给用户");
        assertTrue(ex.getCause().getMessage().contains("该场次已开始"), ex.getCause().getMessage());
    }

    /** ★9-25 裁决的关键变化:开场后、散场前( endTime 仍在未来)这一段以前能买,现在必须拒。 */
    @Test
    void startedButNotEndedSessionIsRejected() throws Exception {
        OmsTicket inProgress = ticket(ago("2020-09-22 09:00:00"), future("2999-01-01 18:00:00"));

        Exception ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> invoke(inProgress, false));

        assertTrue(ex.getCause() instanceof ServiceException);
        assertTrue(ex.getCause().getMessage().contains("该场次已开始"),
                "场次一到开始即停售,不再因 endTime 未到而放行(撤销修复此条必红)");
    }

    /** startTime 缺失(列可空)时才退到 endTime 这唯一已知边界。 */
    @Test
    void missingStartTimeFallsBackToEndTimeDeadline() throws Exception {
        OmsTicket noStart = ticket(null, ago("2020-09-22 18:00:00"));

        Exception ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> invoke(noStart, false));

        assertTrue(ex.getCause().getMessage().contains("该场次已开始"), ex.getCause().getMessage());
    }

    /** 只有还没到开始时间的场次才允许建单。 */
    @Test
    void notYetStartedSessionStillAllowsOrderCreation() throws Exception {
        assertDoesNotThrow(() -> invoke(ticket(future("2999-01-01 09:00:00"), future("2999-01-01 18:00:00")), false));
        assertDoesNotThrow(() -> invoke(ticket(future("2999-01-01 09:00:00"), null), false));
    }

    @Test
    void ticketWithoutAnyWindowIsNotJudged() throws Exception {
        assertDoesNotThrow(() -> invoke(ticket(null, null), false),
                "两个时间都缺 = 判不了,不能拿「判不出来」当过期");
    }

    @Test
    void selfGuidedTicketRemainsAvailableUntilItsEndOrDeadline() throws Exception {
        OmsTicket self = ticket(ago("2020-09-22 09:00:00"), future("2999-01-01 18:00:00"));
        self.setSelfGuided(true);
        assertDoesNotThrow(() -> invoke(self, false));
        self.setSignupDeadline(ago("2020-09-22 10:00:00"));
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> invoke(self, false));
        self.setSignupDeadline(null);
        self.setEndTime(ago("2020-09-22 18:00:00"));
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> invoke(self, false));
        self.setEndTime(null);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> invoke(self, false));
    }

    @Test
    void waitlistClaimKeepsItsOwnDeadlineRule() throws Exception {
        OmsTicket expired = ticket(ago("2020-09-22 09:00:00"), ago("2020-09-22 18:00:00"));

        assertDoesNotThrow(() -> invoke(expired, true),
                "候补名额的售止口径是 signupDeadline,持有效凭证的人不该被票的 startTime 误伤");
    }

    // 集成复审:主题票(ownerType=1)的售卖窗口由 SaleAvailabilityPolicy 决定,不看票的起止,这道闸不能误伤它
    @Test
    void topicTicketIsNotJudgedByTicketWindow() throws Exception {
        OmsTicket topicTicket = ticket(ago("2020-09-22 09:00:00"), null);
        topicTicket.setOwnerType(1L);
        when(ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(topicTicket);

        assertDoesNotThrow(() -> gate.invoke(service, TICKET_ID, 1, ACTIVITY_ID, false));
    }
}
