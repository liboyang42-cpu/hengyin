package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopClubApply;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopClubApplyMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.service.impl.CoopClubApplyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CoopClubApplyServiceImpl 状态机单测(P0-4 开放范围·俱乐部承接申请),mock mapper,不依赖 DB。
 *
 * 状态机:0待处理 →(商家婉拒)1 /(俱乐部撤回)2;婉拒/撤回后重新申请 = 同行复位 0。
 * 申请闸:生效俱乐部主理人 / 主题存在且开放(open_club_pool=1)/ 非自己主题 / 在架过审 /
 *        未锁价(lifecycle<3)/ 该主题尚无已接受俱乐部合作 / 无重复待处理申请。
 */
class CoopClubApplyServiceImplTest {

    private static final Long ME = 42L;        // 俱乐部主理人
    private static final Long MY_CLUB = 7L;
    private static final Long MERCHANT = 9L;   // 主题发布商家
    private static final Long TOPIC = 200L;

    private CoopClubApplyMapper applyMapper;
    private ClubMapper clubMapper;
    private CmsTopicMapper topicMapper;
    private CoopInviteMapper inviteMapper;
    private CoopClubApplyServiceImpl svc;

    @BeforeEach
    void setUp() {
        applyMapper = mock(CoopClubApplyMapper.class);
        clubMapper = mock(ClubMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        inviteMapper = mock(CoopInviteMapper.class);
        svc = new CoopClubApplyServiceImpl();
        ReflectionTestUtils.setField(svc, "coopClubApplyMapper", applyMapper);
        ReflectionTestUtils.setField(svc, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(svc, "coopInviteMapper", inviteMapper);
        ReflectionTestUtils.setField(svc, "clubCoopWindowPolicy",
                new com.chengyinhub.business.service.support.ClubCoopWindowPolicy(
                        mock(com.chengyinhub.business.service.support.CommercialTopicPolicy.class)));

        when(clubMapper.selectClubsByMemberId(ME))
                .thenReturn(Arrays.asList(activeClub(MY_CLUB, ME)));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(openTopic());
        when(inviteMapper.selectCoopInviteList(any(CoopInvite.class))).thenReturn(Collections.emptyList());
    }

    private Club activeClub(Long id, Long memberId) {
        Club c = new Club();
        c.setId(id);
        c.setMemberId(memberId);
        c.setStatus(1L);
        c.setName("测试俱乐部");
        return c;
    }

    private CmsTopic openTopic() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setMemberId(MERCHANT);
        t.setOpenClubPool(1);
        t.setStatus(1);
        t.setUserStatus(1);
        t.setLifecycle(1);
        // F04:申请与邀约同一招商期口径,招商期必须带未到期的 recruitDeadline
        t.setRecruitDeadline(new java.util.Date(System.currentTimeMillis() + 86400_000L));
        return t;
    }

    private CoopClubApply row(Long id, int status) {
        CoopClubApply a = new CoopClubApply();
        a.setId(id);
        a.setTopicId(TOPIC);
        a.setClubId(MY_CLUB);
        a.setMemberId(ME);
        a.setStatus(status);
        return a;
    }

    // ---------- apply:准入闸 ----------

    @Test
    void apply_rejects_member_without_club() {
        when(clubMapper.selectClubsByMemberId(ME)).thenReturn(Collections.emptyList());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.apply(TOPIC, ME, null, null));
        assertTrue(e.getMessage().contains("俱乐部"), e.getMessage());
        verify(applyMapper, never()).insertCoopClubApply(any());
    }

    @Test
    void apply_rejects_inactive_club() {
        Club dormant = activeClub(MY_CLUB, ME);
        dormant.setStatus(0L);
        when(clubMapper.selectClubsByMemberId(ME)).thenReturn(Collections.singletonList(dormant));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.apply(TOPIC, ME, null, null));
        assertTrue(e.getMessage().contains("俱乐部"), e.getMessage());
    }

    @Test
    void apply_rejects_explicit_club_not_mine() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.apply(TOPIC, ME, 999L, null));
        assertTrue(e.getMessage().contains("俱乐部"), e.getMessage());
        verify(applyMapper, never()).insertCoopClubApply(any());
    }

    @Test
    void apply_rejects_missing_topic() {
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.apply(TOPIC, ME, null, null));
        assertTrue(e.getMessage().contains("主题不存在"), e.getMessage());
    }

    @Test
    void apply_rejects_topic_not_open_to_clubs() {
        CmsTopic t = openTopic();
        t.setOpenClubPool(0);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(t);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.apply(TOPIC, ME, null, null));
        assertTrue(e.getMessage().contains("未开放"), e.getMessage());
    }

    @Test
    void apply_rejects_own_topic() {
        CmsTopic t = openTopic();
        t.setMemberId(ME);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(t);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.apply(TOPIC, ME, null, null));
        assertTrue(e.getMessage().contains("自己"), e.getMessage());
    }

    @Test
    void apply_rejects_unowned_topic() {
        // 归属不明(member_id 为空的存量脏数据)= 拒,与 invite 三类统一的闸同理
        CmsTopic t = openTopic();
        t.setMemberId(null);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(t);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.apply(TOPIC, ME, null, null));
        assertTrue(e.getMessage().contains("归属"), e.getMessage());
    }

    @Test
    void apply_rejects_club_published_topic() {
        // §9.2/9.3 开放报名只覆盖玩家/商家发布的主题;俱乐部主题的承接三态按对手方=商家派生,
        // 放行会造成「开了池、三态永远显示不出」的哑巴状态
        CmsTopic t = openTopic();
        t.setClubId(77L);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(t);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.apply(TOPIC, ME, null, null));
        assertTrue(e.getMessage().contains("俱乐部发布"), e.getMessage());
        verify(applyMapper, never()).insertCoopClubApply(any());
    }

    @Test
    void apply_rejects_offline_or_unaudited_topic() {
        CmsTopic t = openTopic();
        t.setUserStatus(0);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(t);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.apply(TOPIC, ME, null, null));
        assertTrue(e.getMessage().contains("上架"), e.getMessage());
    }

    @Test
    void apply_rejects_price_locked_topic() {
        CmsTopic t = openTopic();
        t.setLifecycle(3);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(t);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.apply(TOPIC, ME, null, null));
        assertTrue(e.getMessage().contains("锁价"), e.getMessage());
    }

    @Test
    void apply_rejects_when_topic_already_has_accepted_club() {
        CoopInvite acceptedClub = new CoopInvite();
        acceptedClub.setTopicId(TOPIC);
        acceptedClub.setToType("club");
        acceptedClub.setToId(88L);
        acceptedClub.setStatus(1);
        when(inviteMapper.selectCoopInviteList(any(CoopInvite.class)))
                .thenReturn(Collections.singletonList(acceptedClub));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.apply(TOPIC, ME, null, null));
        assertTrue(e.getMessage().contains("已有俱乐部承接"), e.getMessage());
    }

    @Test
    void apply_accepted_merchant_coop_does_not_block_club_apply() {
        // 主题上已有商家合作(type0/2, toType=merchant)不挡俱乐部申请
        CoopInvite acceptedMerchant = new CoopInvite();
        acceptedMerchant.setTopicId(TOPIC);
        acceptedMerchant.setToType("merchant");
        acceptedMerchant.setStatus(1);
        when(inviteMapper.selectCoopInviteList(any(CoopInvite.class)))
                .thenReturn(Collections.singletonList(acceptedMerchant));
        svc.apply(TOPIC, ME, null, null);
        verify(applyMapper).insertCoopClubApply(any(CoopClubApply.class));
    }

    // ---------- apply:落行 ----------

    @Test
    void apply_inserts_pending_row_for_my_club() {
        CoopClubApply created = svc.apply(TOPIC, ME, null, "带队20人经验");
        ArgumentCaptor<CoopClubApply> cap = ArgumentCaptor.forClass(CoopClubApply.class);
        verify(applyMapper).insertCoopClubApply(cap.capture());
        CoopClubApply row = cap.getValue();
        assertEquals(TOPIC, row.getTopicId());
        assertEquals(MY_CLUB, row.getClubId());
        assertEquals(ME, row.getMemberId());
        assertEquals(0, row.getStatus());
        assertEquals(0, row.getDelFlag());
        assertEquals("带队20人经验", row.getMessage());
        assertNotNull(row.getCreateTime());
        assertEquals(row, created);
    }

    @Test
    void apply_rejects_duplicate_pending() {
        when(applyMapper.selectByTopicAndClub(TOPIC, MY_CLUB)).thenReturn(row(5L, 0));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.apply(TOPIC, ME, null, null));
        assertTrue(e.getMessage().contains("已申请"), e.getMessage());
        verify(applyMapper, never()).insertCoopClubApply(any());
        verify(applyMapper, never()).reapplyCoopClubApply(anyLong(), any());
    }

    @Test
    void apply_after_decline_resets_same_row() {
        when(applyMapper.selectByTopicAndClub(TOPIC, MY_CLUB)).thenReturn(row(5L, 1));
        CoopClubApply again = svc.apply(TOPIC, ME, null, "再试一次");
        verify(applyMapper).reapplyCoopClubApply(eq(5L), eq("再试一次"));
        verify(applyMapper, never()).insertCoopClubApply(any());
        assertEquals(0, again.getStatus());
    }

    // ---------- withdraw ----------

    @Test
    void withdraw_flips_pending_to_withdrawn() {
        when(applyMapper.selectByTopicAndClub(TOPIC, MY_CLUB)).thenReturn(row(5L, 0));
        svc.withdraw(TOPIC, ME);
        ArgumentCaptor<CoopClubApply> cap = ArgumentCaptor.forClass(CoopClubApply.class);
        verify(applyMapper).updateCoopClubApply(cap.capture());
        assertEquals(5L, cap.getValue().getId());
        assertEquals(2, cap.getValue().getStatus());
        assertNotNull(cap.getValue().getHandleTime());
    }

    @Test
    void withdraw_without_pending_rejects() {
        when(applyMapper.selectByTopicAndClub(TOPIC, MY_CLUB)).thenReturn(row(5L, 1));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.withdraw(TOPIC, ME));
        assertTrue(e.getMessage().contains("撤回"), e.getMessage());
        verify(applyMapper, never()).updateCoopClubApply(any());
    }

    // ---------- decline ----------

    @Test
    void decline_only_topic_owner_can_handle() {
        when(applyMapper.selectCoopClubApplyById(5L)).thenReturn(row(5L, 0));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.decline(5L, ME)); // ME 不是主题发布者(MERCHANT 才是)
        assertTrue(e.getMessage().contains("发布者"), e.getMessage());
        verify(applyMapper, never()).updateCoopClubApply(any());
    }

    @Test
    void decline_flips_pending_to_declined() {
        when(applyMapper.selectCoopClubApplyById(5L)).thenReturn(row(5L, 0));
        svc.decline(5L, MERCHANT);
        ArgumentCaptor<CoopClubApply> cap = ArgumentCaptor.forClass(CoopClubApply.class);
        verify(applyMapper).updateCoopClubApply(cap.capture());
        assertEquals(1, cap.getValue().getStatus());
        assertNotNull(cap.getValue().getHandleTime());
    }

    @Test
    void decline_already_handled_rejects() {
        when(applyMapper.selectCoopClubApplyById(5L)).thenReturn(row(5L, 2));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.decline(5L, MERCHANT));
        assertTrue(e.getMessage().contains("已处理"), e.getMessage());
    }

    @Test
    void decline_missing_apply_rejects() {
        when(applyMapper.selectCoopClubApplyById(5L)).thenReturn(null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.decline(5L, MERCHANT));
        assertTrue(e.getMessage().contains("申请不存在"), e.getMessage());
    }
}
