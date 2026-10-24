package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.TopicSaleSnapshot;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.domain.dto.RegistrationTicketItem;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.TopicSaleCanonicalTestFixture;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 票源俱乐部归因的信任边界（R9-P1）。
 *
 * <p>攻击面：`sourceClubId` 是分享深链上的 query 参数，任何登录玩家都能自己填。旧实现把它原样写进
 * `cms_registration.source_club_id`，支付冻结再拿它跟执行俱乐部比一次相等 —— 被伪造的正是那个相等条件，
 * 于是「买家把执行俱乐部 id 填进 body」= 平台自然流量被换成该俱乐部 `G_earned × 10%` 的 TICKET_SOURCE 应付。
 *
 * <p>因此这里断言的是**服务端事实边界**：客户端自报的俱乐部 id 一律不得落进资金归因列，
 * 审计码仍必须留下（可追溯是谁在自报），且这条边界对单票与多票两条建单路径同时成立。
 */
class TicketSourceAttributionTrustBoundaryTest {

    /** 该期开售冻结的执行俱乐部 id —— 俱乐部页/分享链路上公开可得，正是最容易被填进 body 的那个值。 */
    private static final Long EXECUTING_CLUB_ID = 7L;

    @Test
    void clientClaimedSourceClubIdIsNeverPersistedAsFundsAttribution() {
        Fixture f = new Fixture();
        RegistrationRequest request = f.request();
        request.setSourceClubId(EXECUTING_CLUB_ID);
        request.setSourceChannelCode("club-7-t201");

        f.service.create(Fixture.MEMBER_ID, request, null, null);

        assertNull(f.inserted().getSourceClubId(),
                "客户端自报的 sourceClubId 不得进入 source_club_id —— 那一列决定 10% 票源应付");
    }

    @Test
    void clientChannelCodeStaysAsAuditOnlyEvidence() {
        Fixture f = new Fixture();
        RegistrationRequest request = f.request();
        request.setSourceClubId(EXECUTING_CLUB_ID);
        request.setSourceChannelCode("  club-7-t201  ");

        f.service.create(Fixture.MEMBER_ID, request, null, null);

        assertEquals("club-7-t201", f.inserted().getSourceChannelCode(),
                "审计码必须保留：它是「谁在自报」的唯一线索，删掉等于把伪造尝试也一起抹了");
    }

    @Test
    void multiTicketOrderAlsoRefusesTheClientClaimedSourceClub() {
        Fixture f = new Fixture();
        RegistrationRequest request = f.request();
        RegistrationTicketItem item = new RegistrationTicketItem();
        item.setTicketId(Fixture.TICKET_ID);
        item.setCount(1);
        request.setTicketId(null);
        request.setTickets(Collections.singletonList(item));
        request.setSourceClubId(EXECUTING_CLUB_ID);

        f.service.create(Fixture.MEMBER_ID, request, null, null);

        assertNull(f.inserted().getSourceClubId(),
                "多票路径与单票走同一 internal，信任边界必须对两条路径同时成立");
    }

    // 正控制【不在这里】：本类只钉「建单入口不写归因列」这一条边界。
    // 「服务端写入的归因确实落进 FINAL 逐票归因」由 RegistrationFundsMybatisIntegrationTest /
    // TandianriBatch3FundsMybatisIntegrationTest（真库，EXTERNAL_GATE）承重。
    // ⚠️ 2026-08-25 TICKET_SOURCE 分量退役，原先并列在这里的 TicketSourceRewardCalculatorTest
    // 一并删除；承重方只剩上面两条真库测试，别再引用那个已不存在的类。在本类里写一条「new CmsRegistration + setter/getter」当正控制
    // 是造出来的绿：它不经过任何被测逻辑，永远不可能红。

    private static final class Fixture {
        static final Long MEMBER_ID = 88L;
        static final Long ACTIVITY_ID = 200L;
        static final Long TOPIC_ID = 201L;
        static final Long TICKET_ID = 300L;

        final CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        final CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();

        Fixture() {
            CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
            CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
            UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
            OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
            TopicSaleSnapshotMapper snapshotMapper = mock(TopicSaleSnapshotMapper.class);
            IProductRoleService productRoleService = mock(IProductRoleService.class);

            CmsActivity activity = new CmsActivity();
            activity.setId(ACTIVITY_ID);
            activity.setTopicId(TOPIC_ID);
            activity.setName("探店日活动壳");
            activity.setStatus(1L);
            activity.setPublishStatus(1L);
            activity.setDelFlag(0);
            // 平台建期：活动/主题的 club_id 都为空，执行俱乐部只存在于 club_edition_compensation_terms。
            // 建单路径压根不读它 —— 所以「自报的 id 恰好等于执行俱乐部」在建单这一刻是无法证伪的。
            when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
            RegistrationScopeFixture.currentReads(null, activityMapper, null);

            CmsTopic topic = new CmsTopic();
            topic.setId(TOPIC_ID);
            topic.setProductType(ProductType.FREE_EXPLORE);
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
            member.setPoint(0L);
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
            when(registrationMapper.insertCmsRegistration(any(CmsRegistration.class))).thenAnswer(call -> {
                call.<CmsRegistration>getArgument(0).setId(901L);
                return 1;
            });
            when(productRoleService.isPlatformOwned(TOPIC_ID)).thenReturn(true);

            ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
            ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
            ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
            ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
            ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
            ReflectionTestUtils.setField(service, "umsMemberPointsDetailMapper",
                    mock(UmsMemberPointsDetailMapper.class));
            ReflectionTestUtils.setField(service, "perkLedgerService", mock(IPerkLedgerService.class));
            ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
            ReflectionTestUtils.setField(service, "topicSaleSnapshotMapper", snapshotMapper);
        }

        RegistrationRequest request() {
            RegistrationRequest request = new RegistrationRequest();
            request.setOwnerType(2);
            request.setOwnerId(ACTIVITY_ID);
            request.setTicketId(TICKET_ID);
            request.setRealName("张三");
            request.setPhone("18800000000");
            return request;
        }

        CmsRegistration inserted() {
            ArgumentCaptor<CmsRegistration> captor = ArgumentCaptor.forClass(CmsRegistration.class);
            verify(registrationMapper).insertCmsRegistration(captor.capture());
            List<CmsRegistration> rows = captor.getAllValues();
            return rows.get(rows.size() - 1);
        }

        private static TopicSaleSnapshot snapshot() {
            return TopicSaleCanonicalTestFixture.snapshot(TOPIC_ID);
        }
    }
}
