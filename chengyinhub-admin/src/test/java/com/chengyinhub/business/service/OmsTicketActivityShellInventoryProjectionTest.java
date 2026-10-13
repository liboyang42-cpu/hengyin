package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.ActivityShellTicketInventoryVO;
import com.chengyinhub.business.domain.vo.PublicTicketVO;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.OmsTicketServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OmsTicketActivityShellInventoryProjectionTest
{
    @Test
    void freeExplorePublicTicketUsesActivityShellRemainingInventory()
    {
        OmsTicketServiceImpl service = new OmsTicketServiceImpl();
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        ReflectionTestUtils.setField(service, "omsTicketMapper", mapper);
        ActivityShellTicketInventoryVO row = new ActivityShellTicketInventoryVO();
        row.setActivityId(101L);
        row.setTopicTicketId(41L);
        row.setRemainingInventory(6L);
        when(mapper.selectActivityShellTicketRemainingByTopic(9L)).thenReturn(Collections.singletonList(row));

        PublicTicketVO ticket = new PublicTicketVO();
        ticket.setId(41L);
        ticket.setRemainingInventory(30L);
        service.applyActivityShellRemaining(9L, Collections.singletonList(ticket));

        assertEquals(6L, ticket.getRemainingInventory());
    }

    @Test
    void multipleActiveShellsForSameSourceStayUnknown()
    {
        OmsTicketServiceImpl service = new OmsTicketServiceImpl();
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        ReflectionTestUtils.setField(service, "omsTicketMapper", mapper);
        ActivityShellTicketInventoryVO first = new ActivityShellTicketInventoryVO();
        first.setActivityId(101L);
        first.setTopicTicketId(41L);
        first.setRemainingInventory(6L);
        ActivityShellTicketInventoryVO second = new ActivityShellTicketInventoryVO();
        second.setActivityId(102L);
        second.setTopicTicketId(41L);
        second.setRemainingInventory(5L);
        when(mapper.selectActivityShellTicketRemainingByTopic(9L))
                .thenReturn(java.util.Arrays.asList(first, second));
        PublicTicketVO ticket = new PublicTicketVO();
        ticket.setId(41L);
        ticket.setRemainingInventory(30L);

        service.applyActivityShellRemaining(9L, Collections.singletonList(ticket));

        assertEquals(null, ticket.getRemainingInventory(), "多壳不能挑一个库存冒充真值");
    }

    @Test
    void differentSourceKeysAcrossTwoActiveShellsMakeTheWholeProjectionUnknown()
    {
        OmsTicketServiceImpl service = new OmsTicketServiceImpl();
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        ReflectionTestUtils.setField(service, "omsTicketMapper", mapper);
        ActivityShellTicketInventoryVO first = new ActivityShellTicketInventoryVO();
        first.setActivityId(101L);
        first.setTopicTicketId(41L);
        first.setRemainingInventory(6L);
        ActivityShellTicketInventoryVO second = new ActivityShellTicketInventoryVO();
        second.setActivityId(102L);
        second.setTopicTicketId(42L);
        second.setRemainingInventory(5L);
        when(mapper.selectActivityShellTicketRemainingByTopic(9L))
                .thenReturn(java.util.Arrays.asList(first, second));
        PublicTicketVO firstTicket = new PublicTicketVO();
        firstTicket.setId(41L);
        PublicTicketVO secondTicket = new PublicTicketVO();
        secondTicket.setId(42L);

        service.applyActivityShellRemaining(9L, java.util.Arrays.asList(firstTicket, secondTicket));

        assertEquals(null, firstTicket.getRemainingInventory(), "异键双壳也不能拼成确定票面");
        assertEquals(null, secondTicket.getRemainingInventory(), "同主题多壳必须整体 unknown");
    }

    @Test
    void unlimitedActivityTicketStaysUnknownInsteadOfInventingZero()
    {
        OmsTicketServiceImpl service = new OmsTicketServiceImpl();
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        ReflectionTestUtils.setField(service, "omsTicketMapper", mapper);
        ActivityShellTicketInventoryVO unlimited = new ActivityShellTicketInventoryVO();
        unlimited.setActivityId(101L);
        unlimited.setTopicTicketId(41L);
        unlimited.setRemainingInventory(null);
        when(mapper.selectActivityShellTicketRemainingByTopic(9L))
                .thenReturn(Collections.singletonList(unlimited));
        PublicTicketVO ticket = new PublicTicketVO();
        ticket.setId(41L);

        service.applyActivityShellRemaining(9L, Collections.singletonList(ticket));

        assertEquals(null, ticket.getRemainingInventory(), "无限库存没有有限余席事实，必须保留 unknown");
    }

    @Test
    void missingActivityShellTruthNeverFallsBackToTopicInventory()
    {
        OmsTicketServiceImpl service = new OmsTicketServiceImpl();
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        ReflectionTestUtils.setField(service, "omsTicketMapper", mapper);
        when(mapper.selectActivityShellTicketRemainingByTopic(9L)).thenReturn(Collections.emptyList());
        PublicTicketVO ticket = new PublicTicketVO();
        ticket.setId(41L);
        ticket.setRemainingInventory(30L);

        service.applyActivityShellRemaining(9L, Collections.singletonList(ticket));

        assertEquals(null, ticket.getRemainingInventory(), "主题壳库存不是真实成交库存");
    }

    /** 壳可见但没有任何配对票，是与「缺配对键」不同的数据缺陷，必须按壳点名，别让运营去找票。 */
    @Test
    void shellWithoutAnyPairedTicketIsNamedAsUnpairedShell()
    {
        OmsTicketServiceImpl service = new OmsTicketServiceImpl();
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        ReflectionTestUtils.setField(service, "omsTicketMapper", mapper);
        ActivityShellTicketInventoryVO unpairedShell = new ActivityShellTicketInventoryVO();
        unpairedShell.setActivityId(101L);
        unpairedShell.setTopicTicketId(null);
        unpairedShell.setRemainingInventory(null);
        when(mapper.selectActivityShellTicketRemainingByTopic(9L))
                .thenReturn(Collections.singletonList(unpairedShell));
        PublicTicketVO ticket = new PublicTicketVO();
        ticket.setId(41L);
        ticket.setRemainingInventory(30L);

        java.util.List<String> warnings = captureWarnings(
                () -> service.applyActivityShellRemaining(9L, Collections.singletonList(ticket)));

        assertEquals(null, ticket.getRemainingInventory(), "无配对票的壳没有余席事实，必须 unknown");
        assertTrue(warnings.stream().anyMatch(message -> message.contains("unpairedShellIds=[101]")),
                "无配对票的活动壳必须被点名，才能和缺配对键的票区分开");
    }

    private static java.util.List<String> captureWarnings(Runnable action)
    {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(OmsTicketServiceImpl.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            action.run();
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
        return logs.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.toList());
    }

    @Test
    void unknownProjectionEmitsObservableWarningWithTopicId()
    {
        OmsTicketServiceImpl service = new OmsTicketServiceImpl();
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        ReflectionTestUtils.setField(service, "omsTicketMapper", mapper);
        when(mapper.selectActivityShellTicketRemainingByTopic(9L)).thenReturn(Collections.emptyList());
        PublicTicketVO ticket = new PublicTicketVO();
        ticket.setId(41L);

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(OmsTicketServiceImpl.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            service.applyActivityShellRemaining(9L, Collections.singletonList(ticket));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }

        assertTrue(logs.list.stream()
                        .anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN
                                && event.getFormattedMessage().contains("topicId=9")),
                "余席 unknown 必须留运营可观测 warn");
    }
}
