package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.TopicSaleSnapshot;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.service.TopicSaleCanonicalTestFixture;
import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.domain.dto.RegistrationTicketItem;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.business.service.support.TopicSaleCanonicalCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 探店日活动壳购买的积分闸：拒绝必须早于算价及任何订单、库存、权益或积分副作用。 */
class ShopDayPurchaseFundsPolicyTest {

    private static final Long MEMBER_ID = 88L;
    private static final Long ACTIVITY_ID = 200L;
    private static final Long TOPIC_ID = 201L;
    private static final Long TICKET_ID = 300L;
    private static final String POINTS_FORBIDDEN = "探店日不支持积分抵扣，请关闭积分后按全价购买";

    @Test
    void shopDayQuoteWithPointsFailsBeforeMemberReadAndPriceCalculation() {
        Fixture f = new Fixture(ProductType.FREE_EXPLORE);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> f.service.quoteRegistration(MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 1));

        assertEquals(POINTS_FORBIDDEN, error.getMessage());
        verifyNoInteractions(f.memberMapper, f.ticketMapper, f.registrationMapper,
                f.pointsDetailMapper, f.perkLedgerService);
    }

    @Test
    void shopDaySingleCreateWithPointsHasZeroFinancialAndFulfillmentSideEffects() {
        Fixture f = new Fixture(ProductType.FREE_EXPLORE);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> f.service.createRegistration(MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, 1,
                        "张三", "18800000000", null, null, 1, null, null, null));

        assertEquals(POINTS_FORBIDDEN, error.getMessage());
        verifyNoInteractions(f.memberMapper, f.ticketMapper, f.registrationMapper,
                f.pointsDetailMapper, f.perkLedgerService);
    }

    @Test
    void shopDayMultiCreateWithPointsFailsBeforeOrderRowsInventoryAndPoints() {
        Fixture f = new Fixture(ProductType.FREE_EXPLORE);
        RegistrationTicketItem item = new RegistrationTicketItem();
        item.setTicketId(TICKET_ID);
        item.setCount(1);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> f.service.createMultiTicketRegistrations(MEMBER_ID, 2, ACTIVITY_ID, 1,
                        "张三", "18800000000", null, null, Collections.singletonList(item)));

        assertEquals(POINTS_FORBIDDEN, error.getMessage());
        verifyNoInteractions(f.memberMapper, f.ticketMapper, f.registrationMapper,
                f.pointsDetailMapper, f.perkLedgerService);
    }

    @Test
    void shopDayWithoutPointsCreatesFullPriceOrderAndEntitlements() {
        Fixture f = new Fixture(ProductType.FREE_EXPLORE);

        CmsRegistration created = f.service.createRegistration(MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null,
                "张三", "18800000000", null, null, 1, null, null, null);

        assertEquals(0, new BigDecimal("100.00").compareTo(created.getPayableAmount()));
        assertEquals(0L, created.getPointUsed().longValue());
        ArgumentCaptor<CmsRegistration> inserted = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(f.registrationMapper).insertCmsRegistration(inserted.capture());
        assertEquals(0, new BigDecimal("100.00").compareTo(inserted.getValue().getPayableAmount()));
        verify(f.ticketMapper).deductInventory(TICKET_ID, 1L);
        verify(f.perkLedgerService).reserveEntitlements(901L, TOPIC_ID);
        verify(f.memberMapper, never()).deductPoints(anyLong(), anyInt());
        verifyNoInteractions(f.pointsDetailMapper);
    }

    @Test
    void shopDayFalsePointFlagQuotesFullPrice() {
        Fixture f = new Fixture(ProductType.FREE_EXPLORE);

        RegistrationQuoteResult quote = f.service.quoteRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0);

        assertEquals(0, new BigDecimal("100.00").compareTo(quote.getPayAmount()));
        assertEquals(0, quote.getPointsUsed().intValue());
    }

    @Test
    void classicOrientationActivityStillSupportsPointsDiscount() {
        Fixture f = new Fixture(ProductType.ORIENTEERING);

        RegistrationQuoteResult quote = f.service.quoteRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 1);

        assertEquals(0, new BigDecimal("50.00").compareTo(quote.getPayAmount()));
        assertEquals(2500, quote.getPointsUsed().intValue());
    }

    private static final class Fixture {
        final CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        final UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        final OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        final CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        final UmsMemberPointsDetailMapper pointsDetailMapper = mock(UmsMemberPointsDetailMapper.class);
        final IPerkLedgerService perkLedgerService = mock(IPerkLedgerService.class);
        final TopicSaleSnapshotMapper snapshotMapper = mock(TopicSaleSnapshotMapper.class);
        final CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();

        Fixture(Integer productType) {
            CmsActivity activity = new CmsActivity();
            activity.setId(ACTIVITY_ID);
            activity.setTopicId(TOPIC_ID);
            activity.setName("探店日活动壳");
            activity.setStatus(1L);
            activity.setPublishStatus(1L);
            activity.setDelFlag(0);
            when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);

            CmsTopic topic = new CmsTopic();
            topic.setId(TOPIC_ID);
            topic.setProductType(productType);
            topic.setName("源主题");
            topic.setStatus(1);
            topic.setUserStatus(1);
            topic.setDelFlag(0);
            topic.setLifecycle(3);
            topic.setSellingStartedAt(new Date(System.currentTimeMillis() - 60_000L));
            topic.setSaleEndAt(new Date(System.currentTimeMillis() + 86_400_000L));
            topic.setFeePlatformRate(new BigDecimal("0.1000"));
            when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
            when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
            when(snapshotMapper.selectSnapshotByTopicId(TOPIC_ID)).thenReturn(snapshot());
            when(registrationMapper.lockExploreTopicForUpdate(TOPIC_ID)).thenReturn(TOPIC_ID);

            UmsMember member = new UmsMember();
            member.setId(MEMBER_ID);
            member.setStatus(1L);
            member.setDelFlag(0);
            member.setPoint(5000L);
            when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member);

            OmsTicket ticket = new OmsTicket();
            ticket.setId(TICKET_ID);
            ticket.setOwnerType(2L);
            ticket.setOwnerId(ACTIVITY_ID);
            ticket.setName("探店日全价票");
            ticket.setPrice(new BigDecimal("100.00"));
            ticket.setRemainingInventory(10L);
            ticket.setDelFlag(0);
            when(ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(ticket);
            when(ticketMapper.deductInventory(TICKET_ID, 1L)).thenReturn(1);
            when(registrationMapper.insertCmsRegistration(any(CmsRegistration.class))).thenAnswer(invocation -> {
                invocation.<CmsRegistration>getArgument(0).setId(901L);
                return 1;
            });

            IProductRoleService productRoleService = mock(IProductRoleService.class);
            when(productRoleService.isPlatformOwned(TOPIC_ID)).thenReturn(true);
            ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
            ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
            ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
            ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
            ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
            ReflectionTestUtils.setField(service, "umsMemberPointsDetailMapper", pointsDetailMapper);
            ReflectionTestUtils.setField(service, "perkLedgerService", perkLedgerService);
            ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
            ReflectionTestUtils.setField(service, "topicSaleSnapshotMapper", snapshotMapper);
        }

        private static TopicSaleSnapshot snapshot() {
            return TopicSaleCanonicalTestFixture.snapshot(TOPIC_ID);
        }
    }
}
