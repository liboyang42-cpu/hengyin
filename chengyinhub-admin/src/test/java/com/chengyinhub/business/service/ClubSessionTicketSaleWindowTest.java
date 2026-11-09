package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.service.impl.OmsTicketServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 第十二轮 k3 第 3 项(业主 2026-11-05:售卖期来自票的设置,通常活动开始即停售)。
 *
 * <p>俱乐部开一场时,主题票被复制成场次票;复制件的开始时刻取场次的集合时刻,
 * 建单闸(CmsRegistrationServiceImpl「该场次已开始，不能再报名」,见 RegistrationTicketWindowGateTest)
 * 和小程序 utils/ticket-window.js 都以票的 startTime 停售 —— 所以每一场在自己开始时停售,
 * 不会因为主题票上某个别的时刻(p3 N5 里的 10:18)提前或推后。
 */
class ClubSessionTicketSaleWindowTest {

    @Test
    void clubSessionTicketStartsAndEndsWithTheSession() {
        OmsTicket themeTicket = new OmsTicket();
        themeTicket.setStartTime(new Date(1_000L));
        themeTicket.setEndTime(new Date(2_000L));
        CmsActivity session = new CmsActivity();
        session.setClubId(7001L);
        session.setStartDate(new Date(50_000L));
        session.setEndDate(new Date(60_000L));
        assertEquals(new Date(50_000L), OmsTicketServiceImpl.projectedStartTime(themeTicket, session));
        assertEquals(new Date(60_000L), OmsTicketServiceImpl.projectedEndTime(themeTicket, session));
    }

    @Test
    void nonClubActivityKeepsTheTicketsOwnWindow() {
        OmsTicket ticket = new OmsTicket();
        ticket.setStartTime(new Date(1_000L));
        ticket.setEndTime(new Date(2_000L));
        CmsActivity activity = new CmsActivity();
        activity.setStartDate(new Date(50_000L));
        assertEquals(new Date(1_000L), OmsTicketServiceImpl.projectedStartTime(ticket, activity));
    }
}
