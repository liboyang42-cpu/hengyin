package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ClubEditionCompensationTerms;
import com.chengyinhub.business.domain.MemberCollectible;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.domain.UmsMemberFollow;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.domain.vo.ExploreCompletionVO;
import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import com.chengyinhub.business.mapper.CmsTopicCompletionCouponRewardMapper;
import com.chengyinhub.business.mapper.ClubEditionCompensationMapper;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.TopicCompletionXpAwardMapper;
import com.chengyinhub.business.service.impl.ExploreCompletionReadServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 批5 完局面只读服务的行为契约 + 负控。
 *
 * <p>核心断言不是「有没有数据」,而是<b>「没有数据时是什么」</b>:
 * <ul>
 *   <li>无积分流水 ⇒ points 必须是 null,<b>不是 0</b>;</li>
 *   <li>无下期 ⇒ nextEdition 必须是 null,<b>不是一个空壳对象</b>;</li>
 *   <li>俱乐部事实缺失 / 活动与主题两侧冲突 ⇒ clubId 必须是 null,不许挑一个;</li>
 *   <li>图鉴按 topic_id 过滤,别期的凭证一条都不许串进来。</li>
 * </ul>
 * 这几条正是「宁可明确没有,不要假装有」在读面的落点 —— 写 0 会让玩家和验收同时被骗。
 */
class ExploreCompletionReadServiceImplTest {

    private static final Long MEMBER = 9L;
    private static final Long REG = 1001L;
    private static final Long TOPIC = 77L;
    private static final Long ACTIVITY = 55L;

    private IPerkLedgerService perkLedgerService;
    private MemberCollectibleMapper collectibleMapper;
    private PlayerBadgeMapper playerBadgeMapper;
    private IUmsMemberPointsDetailService pointsDetailService;
    private CmsTopicCompletionCouponRewardMapper couponRewardMapper;
    private TopicCompletionXpAwardMapper xpAwardMapper;
    private ICmsTopicService topicService;
    private ICmsTopicChapterService chapterService;
    private IClubService clubService;
    private IClubMemberService clubMemberService;
    private IUmsMemberFollowService followService;
    private ICmsActivityService activityService;
    private ClubEditionCompensationMapper compensationMapper;
    private ExploreCompletionReadServiceImpl svc;

    @BeforeEach
    void setUp() {
        perkLedgerService = mock(IPerkLedgerService.class);
        collectibleMapper = mock(MemberCollectibleMapper.class);
        playerBadgeMapper = mock(PlayerBadgeMapper.class);
        pointsDetailService = mock(IUmsMemberPointsDetailService.class);
        couponRewardMapper = mock(CmsTopicCompletionCouponRewardMapper.class);
        xpAwardMapper = mock(TopicCompletionXpAwardMapper.class);
        topicService = mock(ICmsTopicService.class);
        chapterService = mock(ICmsTopicChapterService.class);
        clubService = mock(IClubService.class);
        clubMemberService = mock(IClubMemberService.class);
        followService = mock(IUmsMemberFollowService.class);
        activityService = mock(ICmsActivityService.class);
        compensationMapper = mock(ClubEditionCompensationMapper.class);

        svc = new ExploreCompletionReadServiceImpl();
        ReflectionTestUtils.setField(svc, "perkLedgerService", perkLedgerService);
        ReflectionTestUtils.setField(svc, "memberCollectibleMapper", collectibleMapper);
        ReflectionTestUtils.setField(svc, "playerBadgeMapper", playerBadgeMapper);
        ReflectionTestUtils.setField(svc, "umsMemberPointsDetailService", pointsDetailService);
        ReflectionTestUtils.setField(svc, "couponRewardMapper", couponRewardMapper);
        ReflectionTestUtils.setField(svc, "xpAwardMapper", xpAwardMapper);
        ReflectionTestUtils.setField(svc, "topicService", topicService);
        ReflectionTestUtils.setField(svc, "topicChapterService", chapterService);
        ReflectionTestUtils.setField(svc, "clubService", clubService);
        ReflectionTestUtils.setField(svc, "clubMemberService", clubMemberService);
        ReflectionTestUtils.setField(svc, "memberFollowService", followService);
        ReflectionTestUtils.setField(svc, "activityService", activityService);
        ReflectionTestUtils.setField(svc, "compensationMapper", compensationMapper);

        when(perkLedgerService.listEntitlements(REG)).thenReturn(Arrays.asList(
                entitlement(1L, "第一章"), entitlement(2L, "第二章")));
        when(perkLedgerService.isExploreCompleted(REG, TOPIC)).thenReturn(true);
        when(collectibleMapper.selectListByMember(anyLong(), any())).thenReturn(new ArrayList<>());
        when(playerBadgeMapper.selectListByMember(anyLong())).thenReturn(new ArrayList<>());
        when(pointsDetailService.selectUmsMemberPointsDetailList(any())).thenReturn(new ArrayList<>());
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic(TOPIC, null));
        when(topicService.selectCmsTopicList(any())).thenReturn(new ArrayList<>());
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity(ACTIVITY, TOPIC, null));
    }

    private RegistrationEntitlement entitlement(Long chapterId, String name) {
        RegistrationEntitlement e = new RegistrationEntitlement();
        e.setChapterId(chapterId);
        e.setChapterName(name);
        e.setStatus(1);
        return e;
    }

    private CmsTopic topic(Long id, Long clubId) {
        CmsTopic t = new CmsTopic();
        t.setId(id);
        t.setName("第一期");
        t.setClubId(clubId);
        return t;
    }

    private CmsActivity activity(Long id, Long topicId, Long clubId) {
        CmsActivity a = new CmsActivity();
        a.setId(id);
        a.setTopicId(topicId);
        a.setClubId(clubId);
        return a;
    }

    private MemberCollectible stamp(Long topicId, Long chapterId, String content) {
        MemberCollectible c = new MemberCollectible();
        c.setMemberId(MEMBER);
        c.setCollType(MemberCollectible.TYPE_EXPLORE_STAMP);
        c.setTopicId(topicId);
        c.setRefId(chapterId);
        c.setContent(content);
        c.setCreateTime(new Date());
        return c;
    }

    private ExploreCompletionVO read() {
        return svc.read(REG, MEMBER, TOPIC, ACTIVITY);
    }

    // ---------- 图鉴(F4) ----------

    @Test
    void 图鉴按章出格并标记已收集() {
        when(collectibleMapper.selectListByMember(MEMBER, MemberCollectible.TYPE_EXPLORE_STAMP))
                .thenReturn(Collections.singletonList(stamp(TOPIC, 1L, "第一章 · 咖啡店")));

        ExploreCompletionVO vo = read();

        assertEquals(2, vo.getRequiredChapterCount());
        assertEquals(1, vo.getRedeemedChapterCount());
        assertEquals(2, vo.getStamps().size());
        assertTrue(vo.getStamps().get(0).isCollected());
        assertEquals("第一章 · 咖啡店", vo.getStamps().get(0).getTitle());
        assertFalse(vo.getStamps().get(1).isCollected(), "没核销的章不许标成已收集");
    }

    @Test
    void 读的是coll_type_5这一档() {
        read();
        verify(collectibleMapper).selectListByMember(MEMBER, MemberCollectible.TYPE_EXPLORE_STAMP);
        assertEquals(5, MemberCollectible.TYPE_EXPLORE_STAMP, "图鉴档位是 5,写侧改了这里必须一起改");
    }

    @Test
    void negative_别期的凭证不许串进本期图鉴() {
        // 幂等键 (member, type, ref) 不含 topic_id ⇒ 只能靠行上的 topic_id 过滤。
        when(collectibleMapper.selectListByMember(MEMBER, MemberCollectible.TYPE_EXPLORE_STAMP))
                .thenReturn(Arrays.asList(stamp(999L, 1L, "别期的第一章"), stamp(TOPIC, 2L, "本期第二章")));

        ExploreCompletionVO vo = read();

        assertFalse(vo.getStamps().get(0).isCollected(), "别期同 chapterId 的凭证不得算进本期");
        assertTrue(vo.getStamps().get(1).isCollected());
        assertEquals(1, vo.getRedeemedChapterCount());
    }

    @Test
    void negative_topic_id为空的历史凭证一律不认() {
        when(collectibleMapper.selectListByMember(MEMBER, MemberCollectible.TYPE_EXPLORE_STAMP))
                .thenReturn(Collections.singletonList(stamp(null, 1L, "无期凭证")));

        assertEquals(0, read().getRedeemedChapterCount(), "缺 topic_id 的行不能猜成本期");
    }

    // ---------- 通关奖励到账明细 ----------

    @Test
    void 撤销核销后保留的图鉴不得算入本票进度() {
        RegistrationEntitlement pending = entitlement(1L, "第一章");
        pending.setStatus(0);
        when(perkLedgerService.listEntitlements(REG)).thenReturn(Collections.singletonList(pending));
        when(perkLedgerService.isExploreCompleted(REG, TOPIC)).thenReturn(false);
        when(collectibleMapper.selectListByMember(MEMBER, MemberCollectible.TYPE_EXPLORE_STAMP))
                .thenReturn(Collections.singletonList(stamp(TOPIC, 1L, "旧核销凭证")));

        ExploreCompletionVO vo = read();

        assertFalse(vo.isCompleted());
        assertEquals(1, vo.getRequiredChapterCount());
        assertEquals(0, vo.getRedeemedChapterCount());
        assertFalse(vo.getStamps().get(0).isCollected());
        assertNull(vo.getStamps().get(0).getObtainedAt());
        assertEquals("第一章", vo.getStamps().get(0).getTitle());
    }

    @Test
    void 失效或未知权益状态不能借历史图鉴显示已核销() {
        for (Integer status : Arrays.asList(2, null)) {
            RegistrationEntitlement invalid = entitlement(1L, "第一章");
            invalid.setStatus(status);
            when(perkLedgerService.listEntitlements(REG)).thenReturn(Collections.singletonList(invalid));
            when(collectibleMapper.selectListByMember(MEMBER, MemberCollectible.TYPE_EXPLORE_STAMP))
                    .thenReturn(Collections.singletonList(stamp(TOPIC, 1L, "旧核销凭证")));

            ExploreCompletionVO vo = read();

            assertEquals(0, vo.getRedeemedChapterCount());
            assertFalse(vo.getStamps().get(0).isCollected());
        }
    }

    @Test
    void 到账明细列出勋章与实发积分() {
        PlayerBadge badge = new PlayerBadge();
        badge.setBadgeName("通关勋章");
        badge.setSourceType(2);
        badge.setSourceId(TOPIC);
        badge.setObtainTime(new Date());
        when(playerBadgeMapper.selectListByMember(MEMBER)).thenReturn(Collections.singletonList(badge));

        UmsMemberPointsDetail row = new UmsMemberPointsDetail();
        row.setEventType(13L);
        row.setEventId(ACTIVITY);
        row.setChangePoints(13L);
        row.setCreateTime(new Date());
        when(pointsDetailService.selectUmsMemberPointsDetailList(any()))
                .thenReturn(Collections.singletonList(row));

        ExploreCompletionVO vo = read();

        assertTrue(vo.getAwards().isCredited());
        assertEquals(Integer.valueOf(13), vo.getAwards().getPoints());
        assertEquals(2, vo.getAwards().getItems().size());
    }

    @Test
    void negative_没有积分流水时points必须是null不是0() {
        ExploreCompletionVO vo = read();
        assertNull(vo.getAwards().getPoints(),
                "无流水写 0 会让玩家看到「获得 0 积分」,也会让验收误判 2-4 没做");
        assertFalse(vo.getAwards().isCredited());
    }

    @Test
    void negative_别期的积分流水不许算进本期() {
        UmsMemberPointsDetail other = new UmsMemberPointsDetail();
        other.setEventType(13L);
        other.setEventId(999L); // 另一期的活动壳
        other.setChangePoints(13L);
        when(pointsDetailService.selectUmsMemberPointsDetailList(any()))
                .thenReturn(Collections.singletonList(other));
        when(activityService.selectCmsActivityById(999L)).thenReturn(activity(999L, 888L, null));

        assertNull(read().getAwards().getPoints());
    }

    @Test
    void negative_别的主题的勋章不许算进本期() {
        PlayerBadge foreign = new PlayerBadge();
        foreign.setBadgeName("别期勋章");
        foreign.setSourceType(2);
        foreign.setSourceId(999L);
        when(playerBadgeMapper.selectListByMember(MEMBER)).thenReturn(Collections.singletonList(foreign));

        assertTrue(read().getAwards().getItems().isEmpty());
    }

    @Test
    void negative_主题没配通关券时couponGranted是null不是false() {
        assertNull(read().getAwards().getCouponGranted(),
                "「没配券」不等于「配了没发」;写 false 会让运营以为发放失败");
        verifyNoInteractions(couponRewardMapper);
    }

    @Test
    void 配了券且已发_couponGranted为true并进明细() {
        CmsTopic t = topic(TOPIC, null);
        t.setCompleteRewardCouponId(66L);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(t);
        when(couponRewardMapper.selectGrantedAt(TOPIC, MEMBER)).thenReturn(new Date());

        ExploreCompletionVO vo = read();

        assertEquals(Boolean.TRUE, vo.getAwards().getCouponGranted());
        assertTrue(vo.getAwards().getItems().stream().anyMatch(i -> "COUPON".equals(i.getKind())));
    }

    @Test
    void negative_配了券但没发_couponGranted为false且不进明细() {
        CmsTopic t = topic(TOPIC, null);
        t.setCompleteRewardCouponId(66L);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(t);
        when(couponRewardMapper.selectGrantedAt(TOPIC, MEMBER)).thenReturn(null);

        ExploreCompletionVO vo = read();

        assertEquals(Boolean.FALSE, vo.getAwards().getCouponGranted());
        assertFalse(vo.getAwards().getItems().stream().anyMatch(i -> "COUPON".equals(i.getKind())));
    }

    @Test
    void 玩家完局奖励必须暴露可回读的通关XP事实() {
        Date grantedAt = new Date(1_755_244_320_000L);
        TopicCompletionAwardVO xpFact = new TopicCompletionAwardVO();
        xpFact.setEarnedXp(36);
        xpFact.setEarnedXpAt(grantedAt);
        when(xpAwardMapper.selectOne(MEMBER, TOPIC)).thenReturn(xpFact);

        ExploreCompletionVO vo = read();

        assertEquals(Integer.valueOf(36), vo.getAwards().getEarnedXp());
        ExploreCompletionVO.Award xp = vo.getAwards().getItems().stream()
                .filter(item -> "XP".equals(item.getKind()))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(Integer.valueOf(36), xp.getAmount());
        assertEquals(grantedAt, xp.getGrantedAt());
        assertTrue(vo.getAwards().isCredited());
    }

    // ---------- J:关注 / 入群 ----------

    @Test
    void 有俱乐部时给出关注与入群所需的事实() {
        Club club = new Club();
        club.setId(7L);
        club.setName("毛孩子俱乐部");
        club.setMemberId(70L);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic(TOPIC, 7L));
        when(clubService.selectClubById(7L)).thenReturn(club);
        when(followService.selectUmsMemberFollowsByMemberAndFollowMemberIds(eq(MEMBER), any()))
                .thenReturn(new ArrayList<UmsMemberFollow>());
        when(clubMemberService.selectByClubAndMember(7L, MEMBER)).thenReturn(null);

        ExploreCompletionVO.Revisit revisit = read().getRevisit();

        assertEquals(Long.valueOf(7L), revisit.getClubId());
        assertEquals(Long.valueOf(70L), revisit.getClubLeaderMemberId());
        assertFalse(revisit.isFollowed());
        assertFalse(revisit.isJoined());
    }

    @Test
    void 已关注已入群时如实回报() {
        Club club = new Club();
        club.setId(7L);
        club.setMemberId(70L);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic(TOPIC, 7L));
        when(clubService.selectClubById(7L)).thenReturn(club);
        when(followService.selectUmsMemberFollowsByMemberAndFollowMemberIds(eq(MEMBER), any()))
                .thenReturn(Collections.singletonList(new UmsMemberFollow()));
        when(clubMemberService.selectByClubAndMember(7L, MEMBER)).thenReturn(new ClubMember());

        ExploreCompletionVO.Revisit revisit = read().getRevisit();

        assertTrue(revisit.isFollowed());
        assertTrue(revisit.isJoined());
    }

    @Test
    void negative_没有俱乐部事实时整块入口为空不猜() {
        ExploreCompletionVO.Revisit revisit = read().getRevisit();
        assertNull(revisit.getClubId());
        assertNull(revisit.getClubLeaderMemberId());
        verifyNoInteractions(clubService);
    }

    @Test
    void negative_活动与主题的club互相冲突时判没有() {
        // 与 freezeTicketSourceOnPayment 同口径:两侧冲突 ⇒ 不许挑一边。
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic(TOPIC, 7L));
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity(ACTIVITY, TOPIC, 8L));

        assertNull(read().getRevisit().getClubId());
        verifyNoInteractions(clubService);
    }

    @Test
    void 只有活动侧有club时也认() {
        Club club = new Club();
        club.setId(8L);
        club.setMemberId(80L);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity(ACTIVITY, TOPIC, 8L));
        when(clubService.selectClubById(8L)).thenReturn(club);

        assertEquals(Long.valueOf(8L), read().getRevisit().getClubId());
    }

    @Test
    void 只有开售冻结执行俱乐部时也认且与支付冻结同口径() {
        ClubEditionCompensationTerms terms = new ClubEditionCompensationTerms();
        terms.setExecutingClubId(7L);
        when(compensationMapper.selectTerms(TOPIC)).thenReturn(terms);
        Club club = new Club();
        club.setId(7L);
        when(clubService.selectClubById(7L)).thenReturn(club);

        assertEquals(Long.valueOf(7L), read().getRevisit().getClubId());
    }

    @Test
    void 冻结执行俱乐部与主题侧冲突时不许挑一边() {
        ClubEditionCompensationTerms terms = new ClubEditionCompensationTerms();
        terms.setExecutingClubId(7L);
        when(compensationMapper.selectTerms(TOPIC)).thenReturn(terms);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic(TOPIC, 8L));

        assertNull(read().getRevisit().getClubId());
        verifyNoInteractions(clubService);
    }

    // ---------- R90:下期预告 ----------

    @Test
    void negative_没有下期时nextEdition是null不是空壳() {
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic(TOPIC, 7L));
        Club club = new Club();
        club.setId(7L);
        club.setMemberId(70L);
        when(clubService.selectClubById(7L)).thenReturn(club);

        assertNull(read().getRevisit().getNextEdition(),
                "返空壳会让前端渲染出「0 场 / 0%」—— R90 的口径是没有就说没有");
    }

    @Test
    void 有在售的下期时取开场最早的一期() {
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic(TOPIC, 7L));
        Club club = new Club();
        club.setId(7L);
        club.setMemberId(70L);
        when(clubService.selectClubById(7L)).thenReturn(club);

        Date future = new Date(System.currentTimeMillis() + 86400000L);
        CmsTopic later = topic(99L, 7L);
        later.setName("第三期");
        later.setSaleEndAt(future);
        later.setStartDate(new Date(System.currentTimeMillis() + 20L * 86400000L));
        CmsTopic sooner = topic(88L, 7L);
        sooner.setName("第二期");
        sooner.setSaleEndAt(future);
        sooner.setStartDate(new Date(System.currentTimeMillis() + 10L * 86400000L));
        when(topicService.selectCmsTopicList(any())).thenReturn(Arrays.asList(later, sooner));

        ExploreCompletionVO.NextEdition next = read().getRevisit().getNextEdition();

        assertNotNull(next);
        assertEquals(Long.valueOf(88L), next.getTopicId());
    }

    @Test
    void negative_售票窗已过或当期自身都不算下期() {
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic(TOPIC, 7L));
        Club club = new Club();
        club.setId(7L);
        club.setMemberId(70L);
        when(clubService.selectClubById(7L)).thenReturn(club);

        CmsTopic self = topic(TOPIC, 7L);
        self.setSaleEndAt(new Date(System.currentTimeMillis() + 86400000L));
        CmsTopic expired = topic(88L, 7L);
        expired.setSaleEndAt(new Date(System.currentTimeMillis() - 86400000L));
        CmsTopic noWindow = topic(89L, 7L); // sale_end_at 为空 = 窗事实缺失,不放行
        when(topicService.selectCmsTopicList(any()))
                .thenReturn(Arrays.asList(self, expired, noWindow));

        assertNull(read().getRevisit().getNextEdition());
    }

    // ---------- 入参 fail-closed ----------

    @Test
    void negative_期缺失时返空壳且一张表都不查() {
        ExploreCompletionVO vo = svc.read(REG, MEMBER, null, ACTIVITY);

        assertFalse(vo.isCompleted());
        assertEquals(0, vo.getRequiredChapterCount());
        assertTrue(vo.getStamps().isEmpty());
        assertNull(vo.getAwards().getPoints());
        assertNull(vo.getRevisit().getClubId());
        verifyNoInteractions(perkLedgerService, collectibleMapper, playerBadgeMapper, clubService);
    }

    @Test
    void negative_未通关时completed为false且奖励区为空() {
        when(perkLedgerService.isExploreCompleted(REG, TOPIC)).thenReturn(false);

        ExploreCompletionVO vo = read();

        assertFalse(vo.isCompleted());
        assertFalse(vo.getAwards().isCredited());
        assertNull(vo.getAwards().getPoints());
    }

    @Test
    void 读面不得触发任何写() {
        // 只读服务:整条链路里不许出现任何 insert/update。图鉴的写口在核销侧,
        // 「读面顺手补发」会绕开 grantOnce 的幂等闸。
        List<Object> writers = Arrays.asList(collectibleMapper, playerBadgeMapper, couponRewardMapper);
        read();
        verify(collectibleMapper, org.mockito.Mockito.never()).insertMemberCollectible(any());
        verify(collectibleMapper, org.mockito.Mockito.never()).deleteOne(any(), any(), any());
        verify(playerBadgeMapper, org.mockito.Mockito.never()).insertPlayerBadge(any());
        verify(couponRewardMapper, org.mockito.Mockito.never()).insertIgnore(any(), any(), any());
        assertEquals(3, writers.size());
    }
}
