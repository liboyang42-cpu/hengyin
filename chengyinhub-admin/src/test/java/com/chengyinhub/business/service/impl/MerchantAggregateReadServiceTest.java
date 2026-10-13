package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.dto.MerchantMarketingAnalyticsFact;
import com.chengyinhub.business.domain.vo.PublicMemberVO;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.MerchantMarketingAnalyticsMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class MerchantAggregateReadServiceTest {

    private static final long VIEWER = 10L;
    private static final long MERCHANT = 77L;
    private static final long TOPIC = 88L;

    @Test
    void relationHomeDoesNotExpireInvitationsInTheDatabase() {
        CoopInviteMapper mapper = mock(CoopInviteMapper.class);
        when(mapper.selectCoopInviteList(any())).thenReturn(Collections.emptyList());

        Map<String, Object> result = service(mock(IMmsMerchantService.class),
                mock(IClubService.class), mapper, mock(ICmsTopicService.class))
                .relationHome(VIEWER, null, null, null, 10);

        assertEquals(0, castMap(result.get("stats")).get("pendingCoopCount"));
        verify(mapper, org.mockito.Mockito.never()).expirePendingInvites(any());
    }

    @Test
    void relationHomeProjectsExpiredPendingInvitesWithoutChangingStoredFacts() {
        CoopInviteMapper invites = mock(CoopInviteMapper.class);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        long now = System.currentTimeMillis();
        long day = 24L * 60L * 60L * 1000L;
        CoopInvite deadline = invite(101L, VIEWER, 20L, TOPIC, 0);
        deadline.setInviteType(0);
        deadline.setExpireTime(new Date(now));
        CoopInvite old = invite(102L, VIEWER, 20L, TOPIC, 0);
        old.setInviteType(0);
        old.setCreateTime(new Date(now - 2 * day));
        old.setExpireTime(new Date(now + day));
        CoopInvite fresh = invite(103L, VIEWER, 20L, TOPIC, 0);
        fresh.setInviteType(0);
        fresh.setCreateTime(new Date(now));
        fresh.setExpireTime(new Date(now + day));
        CoopInvite typeTwo = invite(104L, VIEWER, 20L, TOPIC, 0);
        typeTwo.setExpireTime(new Date(now - day));
        CoopInvite noDates = invite(105L, VIEWER, 20L, TOPIC, 0);
        noDates.setInviteType(0);
        CoopInvite noType = invite(106L, VIEWER, 20L, TOPIC, 0);
        noType.setInviteType(null);
        noType.setExpireTime(new Date(now - day));
        CoopInvite accepted = invite(107L, VIEWER, 20L, TOPIC, 1);
        accepted.setInviteType(0);
        accepted.setExpireTime(new Date(now - day));
        List<CoopInvite> stored = Arrays.asList(deadline, old, fresh, typeTwo, noDates, noType, accepted);
        when(invites.selectCoopInviteList(any())).thenReturn(stored);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setDelFlag(0);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);

        Map<String, Object> result = service(mock(IMmsMerchantService.class),
                mock(IClubService.class), invites, topics)
                .relationHome(VIEWER, TOPIC, null, null, 10);

        assertEquals(4, castMap(result.get("stats")).get("pendingCoopCount"),
                "显式截止或满48小时任一到期即不再算待办；type2及NULL不属于原SQL过期范围");
        assertEquals(1, castList(result.get("relations")).size(),
                "已接受关系不因待确认过期规则消失");
        for (CoopInvite row : stored) {
            assertEquals(row == accepted ? 1 : 0, row.getStatus(),
                    "只读投影不得修改mapper返回的原始事实");
        }
        verify(invites, org.mockito.Mockito.never()).expirePendingInvites(any());
    }

    @Test
    void marketingWindowUsesCompleteShanghaiDaysAndMovesOnlyAtLocalMidnight() {
        Clock beforeShanghaiMidnight = Clock.fixed(
                Instant.parse("2026-08-23T15:59:59.999Z"), ZoneOffset.UTC);
        Clock atShanghaiMidnight = Clock.fixed(
                Instant.parse("2026-08-23T16:00:00Z"), ZoneOffset.UTC);

        MerchantAggregateReadServiceImpl.MarketingWindow before =
                MerchantAggregateReadServiceImpl.resolveMarketingWindow(7, beforeShanghaiMidnight);
        MerchantAggregateReadServiceImpl.MarketingWindow after =
                MerchantAggregateReadServiceImpl.resolveMarketingWindow(7, atShanghaiMidnight);

        assertEquals(Instant.parse("2026-08-22T16:00:00Z"), before.getEndExclusive().toInstant(),
                "上海 23:59 仍只能统计截至昨日的完整自然日");
        assertEquals(Instant.parse("2026-08-15T16:00:00Z"), before.getCurrentStart().toInstant());
        assertEquals(Instant.parse("2026-08-08T16:00:00Z"), before.getPreviousStart().toInstant());
        assertEquals(Instant.parse("2026-08-23T16:00:00Z"), after.getEndExclusive().toInstant(),
                "只有跨过 Asia/Shanghai 00:00 窗口才向前移动一天");
        assertEquals(Instant.parse("2026-08-16T16:00:00Z"), after.getCurrentStart().toInstant());
        assertEquals(Instant.parse("2026-08-09T16:00:00Z"), after.getPreviousStart().toInstant());
    }

    @Test
    void relationHomeOnlyRevealsContactForAcceptedRelationOnRequestedTopic() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        IClubService clubs = mock(IClubService.class);
        CoopInviteMapper invites = mock(CoopInviteMapper.class);
        ICmsTopicService topics = mock(ICmsTopicService.class);

        MmsMerchant viewer = merchant(VIEWER, "当前商家", "10086", "self-wx");
        MmsMerchant acceptedPeer = merchant(20L, "已合作商家", "13800000001", "accepted-wx");
        MmsMerchant pendingPeer = merchant(21L, "待确认商家", "13800000002", "pending-wx");
        MmsMerchant otherTopicPeer = merchant(22L, "其他主题商家", "13800000003", "other-wx");
        MmsMerchant receivedPeer = merchant(23L, "邀我合作商家", "13800000004", "received-wx");
        when(merchants.selectMmsMerchantByMemberId(VIEWER)).thenReturn(viewer);
        when(merchants.selectMmsMerchantByMemberId(20L)).thenReturn(acceptedPeer);
        when(merchants.selectMmsMerchantByMemberId(21L)).thenReturn(pendingPeer);
        when(merchants.selectMmsMerchantByMemberId(22L)).thenReturn(otherTopicPeer);
        when(merchants.selectMmsMerchantByMemberId(23L)).thenReturn(receivedPeer);
        when(merchants.selectMmsMerchantList(any())).thenAnswer(call -> {
            MmsMerchant probe = call.getArgument(0);
            assertEquals(1L, probe.getAccountStatus(),
                    "合作发现也必须过滤已停用账号，不能只过滤审核状态");
            return Arrays.asList(viewer, acceptedPeer);
        });

        Club club = club(30L, "公开俱乐部", "13900000000");
        when(clubs.selectClubList(any())).thenReturn(Collections.singletonList(club));

        CoopInvite accepted = invite(1L, VIEWER, 20L, TOPIC, 1);
        CoopInvite pending = invite(2L, VIEWER, 21L, TOPIC, 0);
        CoopInvite otherTopic = invite(3L, VIEWER, 22L, 99L, 1);
        CoopInvite received = invite(4L, 23L, VIEWER, TOPIC, 1);
        when(invites.selectCoopInviteList(any())).thenAnswer(call -> {
            CoopInvite probe = call.getArgument(0);
            if (Long.valueOf(VIEWER).equals(probe.getFromId())) return Arrays.asList(accepted, pending, otherTopic);
            if (Long.valueOf(VIEWER).equals(probe.getToId())) return Collections.singletonList(received);
            return Collections.emptyList();
        });
        when(topics.selectCmsTopicById(any())).thenAnswer(call -> {
            CmsTopic topic = new CmsTopic();
            topic.setId(call.getArgument(0));
            topic.setDelFlag(0);
            return topic;
        });

        MerchantAggregateReadServiceImpl service = service(merchants, clubs, invites, topics);
        Map<String, Object> result = service.relationHome(VIEWER, TOPIC, null, null, 10);

        List<Map<String, Object>> relations = castList(result.get("relations"));
        assertEquals(1020L, relation(relations, 1L).get("partnerId"), "不得把玩家 memberId 当公开商家ID下发");
        assertEquals("13800000001", contact(relations, 1L).get("phone"));
        assertEquals("accepted-wx", contact(relations, 1L).get("wechat"));
        assertFalse(hasRelation(relations, 2L), "待确认邀约只计待办，不得混进已合作关系列表");
        assertFalse(relation(relations, 3L).containsKey("contact"), "不同主题的已接受关系不得暴露联系方式");
        assertEquals(1023L, relation(relations, 4L).get("partnerId"), "标准入口写入 talent fromType 时仍须按 type2 识别商家关系");

        Map<String, Object> discovery = castMap(result.get("discovery"));
        List<Map<String, Object>> publicMerchants = castList(discovery.get("merchants"));
        List<Map<String, Object>> publicClubs = castList(discovery.get("clubs"));
        assertFalse(publicMerchants.get(0).containsKey("phone"));
        assertFalse(publicMerchants.get(0).containsKey("wechat"));
        assertFalse(publicClubs.get(0).containsKey("phone"));
    }

    @Test
    void relationHomeOmitsInvitesWhoseClubOrTopicParentWasDissolved() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        IClubService clubs = mock(IClubService.class);
        CoopInviteMapper invites = mock(CoopInviteMapper.class);
        ICmsTopicService topics = mock(ICmsTopicService.class);

        CoopInvite targetClub = invite(10L, VIEWER, 30L, 88L, 1);
        targetClub.setInviteType(1);
        targetClub.setToType("club");
        CoopInvite topicClub = invite(11L, VIEWER, 20L, 99L, 1);
        when(invites.selectCoopInviteList(any())).thenAnswer(call -> {
            CoopInvite probe = call.getArgument(0);
            return Long.valueOf(VIEWER).equals(probe.getFromId())
                    ? Arrays.asList(targetClub, topicClub) : Collections.emptyList();
        });
        CmsTopic ordinary = new CmsTopic();
        ordinary.setId(88L);
        ordinary.setDelFlag(0);
        CmsTopic dissolvedParent = new CmsTopic();
        dissolvedParent.setId(99L);
        dissolvedParent.setClubId(40L);
        dissolvedParent.setDelFlag(0);
        when(topics.selectCmsTopicById(88L)).thenReturn(ordinary);
        when(topics.selectCmsTopicById(99L)).thenReturn(dissolvedParent);
        when(clubs.selectClubById(30L)).thenReturn(null);
        when(clubs.selectClubById(40L)).thenReturn(null);

        Map<String, Object> result = service(merchants, clubs, invites, topics)
                .relationHome(VIEWER, 88L, null, null, 10);

        assertTrue(castList(result.get("relations")).isEmpty());
        assertEquals(0, castMap(result.get("stats")).get("clubCount"));
        assertEquals(0, castMap(result.get("stats")).get("merchantCount"));
    }

    @Test
    void marketingHomeUsesIndependentEventWindowsAndPartialNonAttribution() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        IClubService clubs = mock(IClubService.class);
        CoopInviteMapper invites = mock(CoopInviteMapper.class);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        ISmsCouponService coupons = mock(ISmsCouponService.class);
        ICmsActivityService activities = mock(ICmsActivityService.class);
        MerchantMarketingAnalyticsMapper analyticsMapper = mock(MerchantMarketingAnalyticsMapper.class);

        CmsTopic recruiting = new CmsTopic();
        recruiting.setId(101L);
        recruiting.setName("招募主题");
        recruiting.setProductType(2);
        CmsTopic ownTopic = new CmsTopic();
        ownTopic.setProductType(1);
        CmsTopic ownFreeExplore = new CmsTopic();
        ownFreeExplore.setProductType(2);
        when(topics.selectCmsTopicList(any())).thenAnswer(call -> {
            CmsTopic probe = call.getArgument(0);
            assertTrue(Boolean.TRUE.equals(probe.getActiveClubOnly()),
                    "商家营销首页的招募与当前项目均不得展示已解散俱乐部内容");
            return probe.getMemberId() == null
                    ? Collections.singletonList(recruiting)
                    : Arrays.asList(ownTopic, ownFreeExplore);
        });

        SmsCoupon coupon = new SmsCoupon();
        coupon.setReceiveCount(7L);
        coupon.setUseCount(3L);
        when(coupons.selectSmsCouponList(any())).thenReturn(Collections.singletonList(coupon));
        when(activities.selectCmsActivityList(any())).thenReturn(Collections.singletonList(new CmsActivity()));

        MerchantMarketingAnalyticsFact fact = new MerchantMarketingAnalyticsFact();
        fact.setCurrentBrowse(120L);
        fact.setPreviousBrowse(100L);
        fact.setCurrentSignup(24L);
        fact.setPreviousSignup(20L);
        fact.setCurrentPayment(18L);
        fact.setPreviousPayment(0L);
        fact.setCurrentVerify(12L);
        fact.setPreviousVerify(15L);
        fact.setCurrentRefundApply(2L);
        fact.setPreviousRefundApply(1L);
        fact.setCurrentTopicTouchpoint(16L);
        fact.setCurrentActivityTouchpoint(8L);
        fact.setCurrentUnattributedTouchpoint(0L);
        when(analyticsMapper.selectAnalytics(eq(VIEWER), any(Date.class), any(Date.class), any(Date.class)))
                .thenReturn(fact);

        MerchantAggregateReadServiceImpl service = new MerchantAggregateReadServiceImpl(
                merchants, clubs, mock(IClubMemberService.class), invites, topics, coupons,
                activities, analyticsMapper,
                mock(CmsTopicChapterMapper.class), mock(ISysCategoryService.class));
        Map<String, Object> result = service.marketingHome(VIEWER, MERCHANT, 30);

        assertEquals(1, castMap(result.get("recruiting")).get("count"));
        assertEquals(7L, castMap(result.get("coupons")).get("received"));
        assertEquals(1, castMap(result.get("content")).get("topicCount"));
        assertEquals(1, castMap(result.get("content")).get("freeExploreCount"));
        assertEquals(1, castMap(result.get("content")).get("activityCount"));
        Map<String, Object> analytics = castMap(result.get("analytics"));
        assertEquals("INDEPENDENT_EVENT_COUNTS", analytics.get("semantics"));
        assertEquals(30, analytics.get("windowDays"));
        Map<String, Object> window = castMap(analytics.get("window"));
        assertEquals("Asia/Shanghai", window.get("timezone"));
        assertEquals("COMPLETE_CALENDAR_DAYS", window.get("basis"));
        List<Map<String, Object>> stages = castList(analytics.get("stages"));
        assertEquals(Arrays.asList("BROWSE", "SIGNUP", "PAYMENT", "VERIFY", "REFUND_APPLY"),
                Arrays.asList(stages.get(0).get("code"), stages.get(1).get("code"),
                        stages.get(2).get("code"), stages.get(3).get("code"), stages.get(4).get("code")));
        assertEquals("0.2000", stages.get(0).get("changeRate"));
        assertEquals(null, stages.get(2).get("changeRate"), "上一期为 0 时必须保留 unknown");
        Map<String, Object> touchpoints = castMap(analytics.get("touchpoints"));
        assertEquals("PARTIAL", touchpoints.get("coverage"));
        assertEquals(false, touchpoints.get("attributed"));
        assertEquals(Arrays.asList("IN_APP_TOPIC", "IN_APP_ACTIVITY", "UNATTRIBUTED"),
                Arrays.asList(castList(touchpoints.get("items")).get(0).get("code"),
                        castList(touchpoints.get("items")).get(1).get("code"),
                        castList(touchpoints.get("items")).get(2).get("code")));
        assertFalse(result.containsKey("postCount"), "没有商家归属帖文数据源时不得伪造统计");
        assertTrue(castList(castMap(result.get("recruiting")).get("items")).size() <= 10);
        ArgumentCaptor<CmsActivity> activityQueries = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activities).selectCmsActivityList(activityQueries.capture());
        assertTrue(activityQueries.getAllValues().stream()
                        .anyMatch(probe -> Boolean.TRUE.equals(probe.getActiveClubOnly())),
                "商家营销首页当前活动不得展示已解散俱乐部内容");

        ArgumentCaptor<Date> previousStart = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Date> currentStart = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Date> endExclusive = ArgumentCaptor.forClass(Date.class);
        verify(analyticsMapper).selectAnalytics(eq(VIEWER), previousStart.capture(),
                currentStart.capture(), endExclusive.capture());
        long day = 24L * 60L * 60L * 1000L;
        assertEquals(30L * day, currentStart.getValue().getTime() - previousStart.getValue().getTime());
        assertEquals(30L * day, endExclusive.getValue().getTime() - currentStart.getValue().getTime());

        clearInvocations(analyticsMapper);
        Map<String, Object> compatible = service.funnel(VIEWER, 7);
        assertEquals("INDEPENDENT_EVENT_COUNTS", compatible.get("semantics"));
        verify(analyticsMapper).selectAnalytics(eq(VIEWER), any(Date.class), any(Date.class), any(Date.class));
    }

    /**
     * CU-M-94(2026-09-24 走查):合作页俱乐部卡「0 位成员」,点进详情「1 名」。
     * discovery.clubs 是当时唯一还在直发 {@code club.member_count} 原始列的入口 ——
     * 那一列压根不含主理人。这里故意让原始列 = 0、口径方法 = 1,断言下发的是后者。
     */
    @Test
    void discoveryClubsUseEffectiveMemberCount() {
        Club club = new Club();
        club.setId(7001L);
        club.setMemberId(9002L);
        club.setName("城瘾跑团");
        club.setMemberCount(0);
        IClubService clubs = mock(IClubService.class);
        when(clubs.selectClubList(any(Club.class))).thenReturn(Collections.singletonList(club));

        IClubMemberService members = mock(IClubMemberService.class);
        when(members.countEffectiveMembers(club)).thenReturn(1);

        MerchantAggregateReadServiceImpl service = new MerchantAggregateReadServiceImpl(
                mock(IMmsMerchantService.class), clubs, members, mock(CoopInviteMapper.class),
                mock(ICmsTopicService.class), mock(ISmsCouponService.class),
                mock(ICmsActivityService.class), mock(MerchantMarketingAnalyticsMapper.class),
                mock(CmsTopicChapterMapper.class), mock(ISysCategoryService.class));

        Map<String, Object> result = service.relationHome(VIEWER, null, null, null, 10);
        List<Map<String, Object>> rows = castList(castMap(result.get("discovery")).get("clubs"));

        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).get("memberCount"), "合作页不得下发不含主理人的原始列");
        verify(members).countEffectiveMembers(club);
    }

    /**
     * CU-M-51 / CU-M-86(2026-09-24 走查):卡面写「N 张在投放」,原来统计只剔 del_flag=2 ——
     * 已停发(4)与未开始(0)也算进去,数字与文案两张皮。领取/核销仍是这叠券的累计数
     * (停发不影响已领的照常核销),券总数另出 couponTotal 给前端判「有没有券」。
     *
     * <p>CU-M-126 起归属与删除都收进查询条件(与商家券列表同一组 where),所以下面这三条
     * 断言和「在投放」本身一样是承重项:卡片数的是**这一个商家**的、**没删掉的**券。
     */
    @Test
    void marketingHomeCountsOnlyCouponsThatAreOnAir() {
        ISmsCouponService coupons = mock(ISmsCouponService.class);
        long day = 24L * 60L * 60L * 1000L;
        long now = System.currentTimeMillis();

        SmsCoupon onAir = couponRow(1, now - day, now + day, 0, 5L, 2L);
        SmsCoupon stopped = couponRow(4, now - day, now + day, 0, 9L, 3L);
        SmsCoupon notStarted = couponRow(0, now + day, now + 2 * day, 0, 1L, 0L);
        when(coupons.selectSmsCouponList(any()))
                .thenReturn(Arrays.asList(onAir, stopped, notStarted));

        MerchantMarketingAnalyticsMapper analyticsMapper = mock(MerchantMarketingAnalyticsMapper.class);
        when(analyticsMapper.selectAnalytics(eq(VIEWER), any(Date.class), any(Date.class), any(Date.class)))
                .thenReturn(new MerchantMarketingAnalyticsFact());
        MerchantAggregateReadServiceImpl service = new MerchantAggregateReadServiceImpl(
                mock(IMmsMerchantService.class), mock(IClubService.class), mock(IClubMemberService.class),
                mock(CoopInviteMapper.class),
                mock(ICmsTopicService.class), coupons, mock(ICmsActivityService.class), analyticsMapper,
                mock(CmsTopicChapterMapper.class), mock(ISysCategoryService.class));

        Map<String, Object> stats = castMap(service.marketingHome(VIEWER, MERCHANT, 30).get("coupons"));

        assertEquals(1, stats.get("couponCount"), "「在投放」只数 status=1 且在有效期内的券");
        assertEquals(3, stats.get("couponTotal"), "券总数只剔已删除的(依据 couponTotal 判空态)");
        assertEquals(15L, stats.get("received"), "领取仍是这叠券的累计数,不跟着在投放数缩水");
        assertEquals(5L, stats.get("verified"));

        ArgumentCaptor<SmsCoupon> probe = ArgumentCaptor.forClass(SmsCoupon.class);
        verify(coupons).selectSmsCouponList(probe.capture());
        assertEquals(VIEWER, probe.getValue().getMemberId(), "券是挂在归属人名下的");
        assertEquals(MERCHANT, probe.getValue().getMerchantId(),
                "只按归属人数券,会把同一主理人名下其它商家节点的券也算进这张卡(CU-M-126)");
        assertEquals(1, probe.getValue().getScopeType(), "商家券列表按 scope_type=1 查,卡片不能不带");
        assertEquals(0, probe.getValue().getDelFlag(),
                "删除口径也得同源:列表用 del_flag=0,卡片原来只在 Java 里剔 2");
    }

    private static SmsCoupon couponRow(int status, long startMs, long endMs, int delFlag,
                                       long received, long used) {
        SmsCoupon coupon = new SmsCoupon();
        coupon.setStatus(status);
        coupon.setStartTime(new Date(startMs));
        coupon.setEndTime(new Date(endMs));
        coupon.setDelFlag(delFlag);
        coupon.setReceiveCount(received);
        coupon.setUseCount(used);
        return coupon;
    }

    /**
     * CU-M-61:「合作 → 商家」发现行要带品类名。
     *
     * <p>商家卡无封面/Logo 时按品类挑兜底图标。这一页以前只下发 category_id,前端拿不到名字,
     * 于是书店和花店在列表里长成同一只咖啡杯。三条一起才成立:名字落到行里、
     * 没分类的行整个键都不写(不能拿别人的品类顶)、整页只查一次库。
     */
    @Test
    void relationHomeDiscoveryRowsCarryCategoryNamesFromOneBatchedQuery() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        IClubService clubs = mock(IClubService.class);
        CoopInviteMapper invites = mock(CoopInviteMapper.class);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        ISysCategoryService categories = mock(ISysCategoryService.class);

        MmsMerchant viewer = merchant(VIEWER, "当前商家", "10086", "self-wx");
        MmsMerchant bookstore = merchant(20L, "旧书房", "13800000001", "wx-1");
        bookstore.setCategoryId(3);
        bookstore.setBusinessTime("09:00–21:00");
        bookstore.setCapacity(30);
        bookstore.setChargeType(0);
        bookstore.setCoopOpen(1);
        MmsMerchant florist = merchant(21L, "一日花", "13800000002", "wx-2");
        florist.setCategoryId(7);
        MmsMerchant unclassified = merchant(22L, "巷口小店", "13800000003", "wx-3");
        when(merchants.selectMmsMerchantByMemberId(VIEWER)).thenReturn(viewer);
        when(merchants.selectMmsMerchantList(any())).thenReturn(
                Arrays.asList(viewer, bookstore, florist, unclassified));
        when(clubs.selectClubList(any(Club.class))).thenReturn(Collections.<Club>emptyList());
        when(invites.selectCoopInviteList(any())).thenReturn(Collections.<CoopInvite>emptyList());
        when(categories.selectSysCategoryListByIds(any(String[].class))).thenAnswer(call -> {
            String[] requested = call.getArgument(0);
            List<SysCategory> out = new java.util.ArrayList<>();
            for (String id : requested) {
                SysCategory category = new SysCategory();
                category.setId(Long.valueOf(id));
                category.setCategoryName("3".equals(id) ? "书店" : "花艺");
                out.add(category);
            }
            return out;
        });

        Map<String, Object> discovery = castMap(service(merchants, clubs, invites, topics, categories)
                .relationHome(VIEWER, null, null, null, 10).get("discovery"));
        List<Map<String, Object>> rows = castList(discovery.get("merchants"));

        assertEquals(3, rows.size(), "自己的店不进发现列表");
        assertEquals("书店", firstCategoryName(rows.get(0)));
        assertEquals("09:00–21:00", rows.get(0).get("businessTime"));
        assertEquals(30, rows.get(0).get("capacity"));
        assertEquals(0, rows.get(0).get("chargeType"));
        assertEquals(1, rows.get(0).get("coopOpen"));
        assertFalse(rows.get(0).containsKey("phone"));
        assertFalse(rows.get(0).containsKey("wechat"));
        assertEquals("花艺", firstCategoryName(rows.get(1)),
                "两行同一只咖啡杯 = 品类名没落到行里,这一条就是走查读到的现象");
        assertFalse(rows.get(2).containsKey("sysCategoryList"),
                "没分类就整个键都不写,由前端退回中性店铺图标,不冒充品类");

        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        verify(categories, org.mockito.Mockito.times(1)).selectSysCategoryListByIds(captor.capture());
        assertEquals(2, captor.getValue().length, "整页去重后一次批量查,不按行查库");
    }

    @SuppressWarnings("unchecked")
    private static String firstCategoryName(Map<String, Object> row) {
        List<PublicMemberVO.PublicCategoryVO> list =
                (List<PublicMemberVO.PublicCategoryVO>) row.get("sysCategoryList");
        return list == null || list.isEmpty() ? "" : list.get(0).getCategoryName();
    }

    private MerchantAggregateReadServiceImpl service(IMmsMerchantService merchants,
                                                       IClubService clubs,
                                                       CoopInviteMapper invites,
                                                       ICmsTopicService topics) {
        return service(merchants, clubs, invites, topics, mock(ISysCategoryService.class));
    }

    private MerchantAggregateReadServiceImpl service(IMmsMerchantService merchants,
                                                       IClubService clubs,
                                                       CoopInviteMapper invites,
                                                       ICmsTopicService topics,
                                                       ISysCategoryService categories) {
        return new MerchantAggregateReadServiceImpl(
                merchants, clubs, mock(IClubMemberService.class), invites, topics,
                mock(ISmsCouponService.class),
                mock(ICmsActivityService.class), mock(MerchantMarketingAnalyticsMapper.class),
                mock(CmsTopicChapterMapper.class), categories);
    }

    private static MmsMerchant merchant(long memberId, String name, String phone, String wechat) {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(memberId + 1000);
        merchant.setMemberId(memberId);
        merchant.setName(name);
        merchant.setPhone(phone);
        merchant.setWechat(wechat);
        merchant.setStatus(1L);
        merchant.setDelFlag(0);
        return merchant;
    }

    private static Club club(long id, String name, String phone) {
        Club club = new Club();
        club.setId(id);
        club.setName(name);
        club.setPhone(phone);
        club.setStatus(1L);
        club.setDelFlag(0);
        return club;
    }

    private static CoopInvite invite(long id, long fromId, long toId, long topicId, int status) {
        CoopInvite invite = new CoopInvite();
        invite.setId(id);
        invite.setInviteType(2);
        // 现有标准写入口固定落 talent；type2 才是商家→商家的可靠类型事实。
        invite.setFromType("talent");
        invite.setFromId(fromId);
        invite.setToType("merchant");
        invite.setToId(toId);
        invite.setTopicId(topicId);
        invite.setStatus(status);
        invite.setDelFlag(0);
        return invite;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static Map<String, Object> relation(List<Map<String, Object>> relations, long id) {
        return relations.stream().filter(item -> Long.valueOf(id).equals(item.get("id"))).findFirst().orElseThrow(AssertionError::new);
    }

    private static boolean hasRelation(List<Map<String, Object>> relations, long id) {
        return relations.stream().anyMatch(item -> Long.valueOf(id).equals(item.get("id")));
    }

    private static Map<String, Object> contact(List<Map<String, Object>> relations, long id) {
        return castMap(relation(relations, id).get("contact"));
    }

    /**
     * CU-M-132:营销卡那句「N 个开放商机」过去读的是查询命中数,已截止的主题也算进去,
     * 商家按这个数点进广场,看到的却是一屏「已截止」。CU-M-133:首屏 10 席没有排序保证,
     * 已截止的可以整段挤掉还没截止的。两条都在 recruiting() 的口径与排列上,可见性闸不变。
     */
    @Test
    void recruitingCountsOnlyOpenRoutesAndListsThemFirst() {
        long day = 86400000L;
        CmsTopic closedFirst = recruitingTopic(103L, "已截止", new Date(System.currentTimeMillis() - day));
        CmsTopic open = recruitingTopic(101L, "没有截止日", null);
        CmsTopic closingSoon = recruitingTopic(102L, "三天后截止", new Date(System.currentTimeMillis() + 3 * day));
        ICmsTopicService topics = mock(ICmsTopicService.class);
        when(topics.selectCmsTopicList(any())).thenReturn(Arrays.asList(closedFirst, open, closingSoon));
        MerchantAggregateReadServiceImpl service = new MerchantAggregateReadServiceImpl(
                mock(IMmsMerchantService.class), mock(IClubService.class), mock(IClubMemberService.class), mock(CoopInviteMapper.class),
                topics, mock(ISmsCouponService.class), mock(ICmsActivityService.class),
                mock(MerchantMarketingAnalyticsMapper.class), mock(CmsTopicChapterMapper.class), mock(ISysCategoryService.class));

        Map<String, Object> recruiting = castMap(service.marketingHome(VIEWER, MERCHANT, 30).get("recruiting"));

        assertEquals(2, recruiting.get("count"), "开放商机只数还没截止的两条");
        assertEquals(2, recruiting.get("openCount"));
        assertEquals(3, recruiting.get("shownCount"), "已截止的仍下发,由卡面标「已截止」");
        assertEquals(false, recruiting.get("hasMoreOpen"));
        assertEquals(Arrays.asList(101L, 102L, 103L),
                castList(recruiting.get("items")).stream().map(item -> item.get("id"))
                        .collect(java.util.stream.Collectors.toList()),
                "未截止的排在前,已截止的沉底但不藏");
        // 负控:计数口径退回「查询命中数」时,这条断言变红
        assertFalse(Integer.valueOf(3).equals(recruiting.get("count")),
                "把已截止的算进「开放商机」正是 CU-M-132 的现场");
    }

    private static CmsTopic recruitingTopic(Long id, String name, Date merchantSignUpEndDate) {
        CmsTopic topic = new CmsTopic();
        topic.setId(id);
        topic.setName(name);
        topic.setProductType(2);
        topic.setMerchantSignUpEndDate(merchantSignUpEndDate);
        return topic;
    }
}
