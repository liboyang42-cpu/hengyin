package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** NOT_RUN. Real shell builder, stub SQL identity; no DB or payment validation. */
class OWN_C03_15Test {
    private CmsTopicServiceImpl service;
    private CmsActivityMapper activities;
    private IOmsTicketService tickets;

    @BeforeEach void setup() {
        service = new CmsTopicServiceImpl();
        activities = mock(CmsActivityMapper.class);
        tickets = mock(IOmsTicketService.class);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "omsTicketService", tickets);
        ReflectionTestUtils.setField(service, "settlementDebtLedger", mock(SettlementDebtLedger.class));
    }
    @Test void zeroRowsCannotCopyTicketsEvenIfIdIsPresent() {
        insertion(0, 800L);
        assertRejected();
    }
    @Test void missingActivityIdCannotCopyTickets() {
        insertion(1, null);
        assertRejected();
    }
    @Test void nonpositiveActivityIdCannotCopyTickets() {
        insertion(1, 0L);
        assertRejected();
    }
    @Test void successfulShellCopiesTicketsToGeneratedIdentity() {
        insertion(1, 800L);
        generate();
        verify(tickets).copyTopicTicketsToActivity(700L, 800L, 42L);
    }
    @Test void databaseExceptionStillPropagatesWithoutTicketCopy() {
        doThrow(new IllegalStateException("db failure")).when(activities).insertCmsActivity(any(CmsActivity.class));
        assertThrows(IllegalStateException.class, this::generate);
        verifyNoInteractions(tickets);
    }
    private void insertion(int rows, Long id) {
        doAnswer(call -> { ((CmsActivity) call.getArgument(0)).setId(id); return rows; })
                .when(activities).insertCmsActivity(any(CmsActivity.class));
    }
    private void generate() {
        CmsTopic topic = new CmsTopic();
        topic.setId(700L);
        topic.setMemberId(42L);
        topic.setName("原自办主题");
        ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicPublicationTestWiring.owner(service, "generateSelfHostActivity"), "generateSelfHostActivity", topic, 700L, 42L, "creator");
    }
    private void assertRejected() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, this::generate);
        assertTrue(failure.getMessage().contains("未生成有效活动身份"));
        verifyNoInteractions(tickets);
    }
}
