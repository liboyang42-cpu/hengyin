package com.chengyinhub.business.service.impl;

import java.util.Arrays;
import java.util.Collections;

import com.chengyinhub.business.domain.EventSignup;
import com.chengyinhub.business.domain.MemberCollectible;
import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.mapper.EventSignupMapper;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.mapper.OfficialAudienceMapper;
import com.chengyinhub.business.mapper.OfficialBroadcastLogMapper;
import com.chengyinhub.business.mapper.OfficialBroadcastMapper;
import com.chengyinhub.business.mapper.OfficialEventMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.IWxSubscribeMsgService;
import com.chengyinhub.business.service.INotifyService;
import org.mockito.ArgumentCaptor;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 官方活动系统 契约测试(纯 Mockito,与 MoneyPathContractTest 同风格,零外部依赖可入 CI)。
 * 覆盖:通知分发(圈人去重 / 双渠道 fan-out / uk 防重发 / 分角色文案)、报名状态闸、结算发奖幂等。
 */
class OfficialEventContractTest {

    // ---------------- 通知分发 ----------------

    private OfficialBroadcastServiceImpl broadcastService(OfficialAudienceMapper aud,
                                                          OfficialBroadcastLogMapper logMapper,
                                                          IImService im,
                                                          IWxSubscribeMsgService wx) {
        OfficialBroadcastServiceImpl s = new OfficialBroadcastServiceImpl();
        ReflectionTestUtils.setField(s, "broadcastMapper", mock(OfficialBroadcastMapper.class));
        ReflectionTestUtils.setField(s, "logMapper", logMapper);
        ReflectionTestUtils.setField(s, "audienceMapper", aud);
        ReflectionTestUtils.setField(s, "imService", im);
        ReflectionTestUtils.setField(s, "wxSubscribeMsgService", wx);
        return s;
    }

    private OfficialBroadcast bc(int copyMode, String content) {
        OfficialBroadcast b = new OfficialBroadcast();
        b.setId(1L);
        b.setTitle("默认标题");
        b.setAudience("player,club,merchant");
        b.setChannels("inapp,subscribe");
        b.setCopyMode(copyMode);
        b.setContentJson(content);
        b.setCity("成都");
        return b;
    }

    @Test
    void dispatch_圈人跨角色去重_双渠道fanout() {
        OfficialBroadcastDeliveryTest.Fixture f = new OfficialBroadcastDeliveryTest.Fixture(bc(1,
                "{\"title\":\"统一标题\",\"sub\":\"统一内容\"}"));
        when(f.audience.selectPlayerIds("成都")).thenReturn(Arrays.asList(10L, 11L));
        when(f.audience.selectClubMemberIds("成都")).thenReturn(Arrays.asList(11L, 20L));
        when(f.audience.selectMerchantMemberIds("成都")).thenReturn(Collections.singletonList(30L));
        assertEquals(4, f.service.dispatch(f.broadcast));
        verify(f.im, times(4)).sendSystem(anyLong(), eq(3), anyString(), anyString());
        verify(f.wx, times(4)).notifyOfficialBroadcast(anyLong(), anyString(), anyString());
        assertEquals(8, f.rows.size());
    }

    @Test
    void dispatch_uk已发过则不重发() {
        OfficialBroadcastDeliveryTest.Fixture f = new OfficialBroadcastDeliveryTest.Fixture(bc(1, null));
        when(f.audience.selectPlayerIds("成都")).thenReturn(Arrays.asList(10L, 11L));
        assertEquals(2, f.service.dispatch(f.broadcast));
        assertEquals(2, f.restart().dispatch(f.broadcast)); // Total confirmed inapp receipts, not this-attempt count.
        verify(f.im, times(2)).sendSystem(anyLong(), anyInt(), anyString(), anyString());
        verify(f.wx, times(2)).notifyOfficialBroadcast(anyLong(), anyString(), anyString());
        assertEquals(4, f.rows.size());
    }

    @Test
    void dispatch_分开发_按角色取对应文案() {
        OfficialBroadcastDeliveryTest.Fixture f = new OfficialBroadcastDeliveryTest.Fixture(bc(2,
                "{\"player\":{\"title\":\"玩家标题\",\"sub\":\"出门点亮\"},"
                + "\"merchant\":{\"title\":\"商家标题\",\"sub\":\"客流洪峰\"}}"));
        when(f.audience.selectPlayerIds("成都")).thenReturn(Collections.singletonList(10L));
        f.service.dispatch(f.broadcast);
        verify(f.im).sendSystem(eq(10L), eq(3), eq("玩家标题"), anyString());
        verify(f.wx).notifyOfficialBroadcast(10L, "玩家标题", "出门点亮");
    }

    @Test
    void dispatch_频控_超限用户被跳过() {
        OfficialBroadcastDeliveryTest.Fixture f = new OfficialBroadcastDeliveryTest.Fixture(bc(1, null));
        when(f.audience.selectPlayerIds("成都")).thenReturn(Arrays.asList(10L, 11L));
        when(f.logs.countRecentByMember(eq(10L), anyInt())).thenReturn(5);
        assertEquals(1, f.service.dispatch(f.broadcast));
        verify(f.im).sendSystem(eq(11L), anyInt(), anyString(), anyString());
        verify(f.im, never()).sendSystem(eq(10L), anyInt(), anyString(), anyString());
        verify(f.wx, never()).notifyOfficialBroadcast(eq(10L), anyString(), anyString());
        assertEquals(OfficialBroadcast.ST_PENDING, f.broadcast.getStatus());
    }

    @Test
    void dispatch_频控_查询异常fallback禁止发送() {
        OfficialBroadcastDeliveryTest.Fixture f = new OfficialBroadcastDeliveryTest.Fixture(bc(1, null));
        when(f.audience.selectPlayerIds("成都")).thenReturn(Arrays.asList(10L, 11L));
        when(f.logs.countRecentByMember(eq(10L), anyInt())).thenThrow(new RuntimeException("DB timeout"));
        assertEquals(1, f.service.dispatch(f.broadcast));
        verify(f.im).sendSystem(eq(11L), anyInt(), anyString(), anyString());
        verify(f.im, never()).sendSystem(eq(10L), anyInt(), anyString(), anyString());
        verify(f.wx, never()).notifyOfficialBroadcast(eq(10L), anyString(), anyString());
    }

    @Test
    void broadcast_V2活动通用入口拒绝越权读写() {
        OfficialBroadcastMapper broadcastMapper = mock(OfficialBroadcastMapper.class);
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        IOfficialEventV2Service v2 = mock(IOfficialEventV2Service.class);
        OfficialBroadcastServiceImpl service = broadcastService(mock(OfficialAudienceMapper.class),
                mock(OfficialBroadcastLogMapper.class), mock(IImService.class), mock(IWxSubscribeMsgService.class));
        ReflectionTestUtils.setField(service, "broadcastMapper", broadcastMapper);
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "officialEventV2Service", v2);

        OfficialEvent event = event(OfficialEvent.ST_DRAFT);
        event.setContractVersion(2);
        OfficialBroadcast broadcast = bc(OfficialBroadcast.MODE_UNIFIED, null);
        broadcast.setEventId(7L);
        broadcast.setSenderAccount(202L);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event);
        when(v2.canOperateControlledEvent(7L, 101L)).thenReturn(false);

        assertThrows(ServiceException.class, () -> service.publish(broadcast, 101L));
        verify(broadcastMapper, never()).insertOfficialBroadcast(any(OfficialBroadcast.class));

        when(broadcastMapper.selectOfficialBroadcastById(71L)).thenReturn(broadcast);
        assertThrows(ServiceException.class, () -> service.getById(71L, 101L));
    }

    // ---------------- 活动壳 ----------------

    private OfficialEventServiceImpl eventService(OfficialEventMapper em, EventSignupMapper sm,
                                                  MemberCollectibleMapper cm,
                                                  ISmsCouponHistoryService coupon, IGrowthService growth) {
        // 发奖已下沉到独立事务 helper —— 用同一批 mock 装配真实 helper,发券/发经验/发徽章的验证仍端到端有效
        OfficialSettlementHelper helper = new OfficialSettlementHelper();
        ReflectionTestUtils.setField(helper, "signupMapper", sm);
        ReflectionTestUtils.setField(helper, "collectibleMapper", cm);
        ReflectionTestUtils.setField(helper, "couponHistoryService", coupon);
        ReflectionTestUtils.setField(helper, "growthService", growth);
        ReflectionTestUtils.setField(helper, "alarmService", mock(com.chengyinhub.business.service.impl.AlarmService.class));

        OfficialEventServiceImpl s = new OfficialEventServiceImpl();
        ReflectionTestUtils.setField(s, "eventMapper", em);
        ReflectionTestUtils.setField(s, "signupMapper", sm);
        ReflectionTestUtils.setField(s, "growthService", growth);
        ReflectionTestUtils.setField(s, "settlementHelper", helper);
        com.chengyinhub.business.service.IOfficialEventV2Service v2 = mock(com.chengyinhub.business.service.IOfficialEventV2Service.class);
        when(v2.activeForRoam(any(), anyLong())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(s, "officialEventV2Service", v2);
        return s;
    }

    private OfficialEvent event(int status) {
        OfficialEvent e = new OfficialEvent();
        e.setId(1L);
        e.setStatus(status);
        return e;
    }

    @Test
    void signup_草稿态拒绝_报名态放行且幂等入库() {
        EventSignupMapper sm = mock(EventSignupMapper.class);
        OfficialEventMapper em = mock(OfficialEventMapper.class);
        OfficialEventServiceImpl svc = eventService(em, sm, mock(MemberCollectibleMapper.class),
                mock(ISmsCouponHistoryService.class), mock(IGrowthService.class));

        when(em.selectOfficialEventByIdForUpdate(1L)).thenReturn(event(OfficialEvent.ST_DRAFT));
        assertNotNull(svc.signup(1L, 500L)); // 草稿 → 报错

        when(em.selectOfficialEventByIdForUpdate(1L)).thenReturn(event(OfficialEvent.ST_ENDED));
        assertNotNull(svc.signup(1L, 500L)); // 已结束 → 报错

        when(em.selectOfficialEventByIdForUpdate(1L)).thenReturn(event(OfficialEvent.ST_SIGNUP));
        when(sm.insertIgnore(any(EventSignup.class))).thenReturn(1);
        assertNull(svc.signup(1L, 500L));    // 报名态 → 成功
        verify(sm, times(1)).insertIgnore(any(EventSignup.class));
    }

    @Test
    void detail_V1草稿和下线凭id也不可公开_报名中和进行中可读() {
        EventSignupMapper sm = mock(EventSignupMapper.class);
        OfficialEventMapper em = mock(OfficialEventMapper.class);
        OfficialEventServiceImpl svc = eventService(em, sm, mock(MemberCollectibleMapper.class),
                mock(ISmsCouponHistoryService.class), mock(IGrowthService.class));

        when(em.selectOfficialEventById(1L)).thenReturn(event(OfficialEvent.ST_DRAFT));
        assertNull(svc.detail(1L, 0L));
        when(em.selectOfficialEventById(1L)).thenReturn(event(OfficialEvent.ST_OFFLINE));
        assertNull(svc.detail(1L, 0L));

        when(em.selectOfficialEventById(1L)).thenReturn(event(OfficialEvent.ST_SIGNUP));
        assertNotNull(svc.detail(1L, 0L));
        when(em.selectOfficialEventById(1L)).thenReturn(event(OfficialEvent.ST_LIVE));
        assertNotNull(svc.detail(1L, 0L));
    }

    @Test
    void completeTask_V2活动拒绝旧progress入口且不写入奖励() {
        EventSignupMapper sm = mock(EventSignupMapper.class);
        OfficialEventMapper em = mock(OfficialEventMapper.class);
        IGrowthService growth = mock(IGrowthService.class);
        OfficialEventServiceImpl svc = eventService(em, sm, mock(MemberCollectibleMapper.class),
                mock(ISmsCouponHistoryService.class), growth);

        OfficialEvent v2 = event(OfficialEvent.ST_LIVE);
        v2.setContractVersion(2);
        v2.setContentJson("{\"tasks\":[{\"id\":1}]}");
        EventSignup signed = signup(101L, 0, 0);
        when(em.selectOfficialEventById(1L)).thenReturn(v2);
        when(sm.selectByEventAndMember(1L, 101L)).thenReturn(signed);

        assertNotNull(svc.completeTask(1L, 101L));
        verify(sm, never()).updateEventSignup(any(EventSignup.class));
        verify(growth, never()).addExp(anyLong(), anyInt(), anyString());
    }

    @Test
    void legacyCompletionNeverChangesProgressOrAwardsEvenWhenCalledRepeatedly() {
        EventSignupMapper signups = mock(EventSignupMapper.class);
        OfficialEventMapper events = mock(OfficialEventMapper.class);
        IGrowthService growth = mock(IGrowthService.class);
        OfficialEventServiceImpl service = eventService(events, signups, mock(MemberCollectibleMapper.class),
                mock(ISmsCouponHistoryService.class), growth);
        for (Integer version : new Integer[] {null, 1, 2}) {
            OfficialEvent event = event(OfficialEvent.ST_LIVE);
            event.setContractVersion(version);
            event.setRewardJson("{\"instantXp\":100}");
            when(events.selectOfficialEventById(1L)).thenReturn(event);
            for (int i = 0; i < 5; i++) assertNotNull(service.completeTask(1L, 101L));
        }
        org.mockito.Mockito.verifyNoInteractions(signups, growth);
    }

    @Test
    void progress_V2活动只返回公开汇总而不泄露运营360() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        IOfficialEventV2Service v2 = mock(IOfficialEventV2Service.class);
        OfficialEventServiceImpl service = new OfficialEventServiceImpl();
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "officialEventV2Service", v2);

        OfficialEvent event = event(OfficialEvent.ST_LIVE);
        event.setContractVersion(2);
        when(eventMapper.selectOfficialEventById(1L)).thenReturn(event);
        java.util.Map<String, Object> publicSummary = Collections.<String, Object>singletonMap("participants", 3);
        when(v2.publicProgress(1L)).thenReturn(publicSummary);

        assertEquals(publicSummary, service.progress(1L));
        verify(v2).publicProgress(1L);
    }

    @Test
    void settle_合格发奖_已领跳过_幂等() {
        OfficialEventMapper em = mock(OfficialEventMapper.class);
        EventSignupMapper sm = mock(EventSignupMapper.class);
        MemberCollectibleMapper cm = mock(MemberCollectibleMapper.class);
        ISmsCouponHistoryService coupon = mock(ISmsCouponHistoryService.class);
        IGrowthService growth = mock(IGrowthService.class);

        OfficialEvent e = event(OfficialEvent.ST_SETTLING);
        e.setRewardJson("{\"settleCouponId\":77,\"settleXp\":50,\"settleBadge\":true}");
        when(em.selectOfficialEventById(1L)).thenReturn(e);

        EventSignup s1 = signup(101L, 2, 0); // 合格(progress>0),未领
        EventSignup s2 = signup(102L, 0, 0); // 不合格(progress=0),未领
        EventSignup s3 = signup(103L, 5, 1); // 已领 → 整体跳过
        when(sm.selectByEvent(1L)).thenReturn(Arrays.asList(s1, s2, s3));
        when(cm.countOne(eq(101L), eq(MemberCollectible.TYPE_EVENT_BADGE), eq(1L))).thenReturn(0);
        // 原子领取:s1、s2 抢到(返回1),真正发放
        when(sm.claimReward(eq(101L), eq(EventSignup.ST_REWARDED))).thenReturn(1);
        when(sm.claimReward(eq(102L), eq(EventSignup.ST_REWARDED))).thenReturn(1);
        when(coupon.grantCouponById(101L, 77L)).thenReturn(true); // C1:合格者发券成功(新契约下 false 会触发回滚)

        int granted = eventService(em, sm, cm, coupon, growth).settle(1L);

        assertEquals(2, granted); // s1+s2 处理,s3 快速幂等跳过
        verify(coupon, times(1)).grantCouponById(101L, 77L);   // 仅合格者发券
        verify(coupon, never()).grantCouponById(eq(102L), anyLong());
        verify(growth, times(1)).addExp(eq(101L), eq(50), anyString());
        verify(cm, times(1)).insertMemberCollectible(any(MemberCollectible.class)); // 仅合格者发徽章
        verify(sm, times(2)).claimReward(anyLong(), eq(EventSignup.ST_REWARDED)); // s1、s2 原子领取
        verify(sm, never()).claimReward(eq(103L), anyInt());   // s3 已领,不再进原子领取
    }

    @Test
    void settle_发券失败_不吞异常且不计发放() {
        OfficialEventMapper em = mock(OfficialEventMapper.class);
        EventSignupMapper sm = mock(EventSignupMapper.class);
        MemberCollectibleMapper cm = mock(MemberCollectibleMapper.class);
        ISmsCouponHistoryService coupon = mock(ISmsCouponHistoryService.class);
        IGrowthService growth = mock(IGrowthService.class);

        OfficialEvent e = event(OfficialEvent.ST_SETTLING);
        e.setRewardJson("{\"settleCouponId\":77,\"settleXp\":50}");
        when(em.selectOfficialEventById(1L)).thenReturn(e);

        EventSignup s1 = signup(101L, 2, 0); // 合格未领
        when(sm.selectByEvent(1L)).thenReturn(Collections.singletonList(s1));
        when(sm.claimReward(eq(101L), eq(EventSignup.ST_REWARDED))).thenReturn(1); // 抢到
        when(coupon.grantCouponById(101L, 77L)).thenReturn(false); // C1:库存耗尽返回 false,settleOne 检测到后抛异常触发回滚

        assertThrows(ServiceException.class, () -> eventService(em, sm, cm, coupon, growth).settle(1L));

        // 发券异常必须向调用方传播;
        // 真实环境下 settleOne 的 REQUIRES_NEW 事务回滚使 reward_claimed 退回0,下轮可重试,杜绝"标记已领却没发出"
    }

    @Test
    void settle_结算券已手动失效_跳过发券_经验徽章照发且不回滚() {
        // C-30:运营把结算券手动失效后,结算不能每轮抛错重试;券跳过,其余奖励照常
        OfficialEventMapper em = mock(OfficialEventMapper.class);
        EventSignupMapper sm = mock(EventSignupMapper.class);
        MemberCollectibleMapper cm = mock(MemberCollectibleMapper.class);
        ISmsCouponHistoryService coupon = mock(ISmsCouponHistoryService.class);
        IGrowthService growth = mock(IGrowthService.class);

        OfficialEvent e = event(OfficialEvent.ST_SETTLING);
        e.setRewardJson("{\"settleCouponId\":77,\"settleXp\":50,\"settleBadge\":true}");
        when(em.selectOfficialEventById(1L)).thenReturn(e);
        when(sm.selectByEvent(1L)).thenReturn(Collections.singletonList(signup(101L, 2, 0)));
        when(sm.claimReward(eq(101L), eq(EventSignup.ST_REWARDED))).thenReturn(1);
        when(coupon.isCouponNotIssuable(77L)).thenReturn(true);

        assertEquals(1, eventService(em, sm, cm, coupon, growth).settle(1L));

        verify(coupon, never()).grantCouponById(anyLong(), anyLong());
        verify(growth, times(1)).addExp(eq(101L), eq(50), anyString());
        verify(cm, times(1)).insertMemberCollectible(any(MemberCollectible.class));
    }

    @Test
    void activeForRoam_仅返回roam绑定且报名中或进行中的活动() {
        OfficialEventMapper em = mock(OfficialEventMapper.class);
        EventSignupMapper sm = mock(EventSignupMapper.class);
        OfficialEventServiceImpl svc = eventService(em, sm, mock(MemberCollectibleMapper.class),
                mock(ISmsCouponHistoryService.class), mock(IGrowthService.class));

        OfficialEvent roamLive = event(OfficialEvent.ST_LIVE);
        roamLive.setId(1L);
        roamLive.setContentJson("{\"roam\":true}");             // roam 标记 + 进行中 → 命中
        OfficialEvent roamSignup = event(OfficialEvent.ST_SIGNUP);
        roamSignup.setId(2L);
        roamSignup.setContentJson("{\"play_type\":\"roam\"}");   // play_type=roam + 报名中 → 命中
        OfficialEvent roamEnded = event(OfficialEvent.ST_ENDED);
        roamEnded.setId(3L);
        roamEnded.setContentJson("{\"roam\":true}");             // roam 但已结束 → 排除
        OfficialEvent linearLive = event(OfficialEvent.ST_LIVE);
        linearLive.setId(4L);
        linearLive.setContentJson("{\"play_type\":\"linear\"}"); // 进行中但非 roam → 排除

        when(em.selectPublicList(null)).thenReturn(Arrays.asList(roamLive, roamSignup, roamEnded, linearLive));

        java.util.List<java.util.Map<String, Object>> out = svc.activeForRoam(null, 0L);

        assertEquals(2, out.size()); // 仅 roamLive + roamSignup
        java.util.Set<Object> ids = new java.util.HashSet<>();
        for (java.util.Map<String, Object> m : out) {
            ids.add(m.get("id"));
            assertEquals(Boolean.TRUE, m.get("roamEnabled")); // 命中项都标了 roamEnabled
        }
        assertEquals(new java.util.HashSet<>(Arrays.asList(1L, 2L)), ids);
    }

    @Test
    void listBySender_活动结束后主办按历史参与身份只读_非参与者与撤权仍拒() {
        OfficialBroadcastMapper bm = mock(OfficialBroadcastMapper.class);
        OfficialEventMapper em = mock(OfficialEventMapper.class);
        IOfficialEventV2Service v2 = mock(IOfficialEventV2Service.class);

        OfficialBroadcast b = new OfficialBroadcast();
        b.setId(1L);
        b.setEventId(9L);
        b.setSenderAccount(101L);
        when(bm.selectBySender(101L)).thenReturn(Collections.singletonList(b));
        when(bm.selectBySender(202L)).thenReturn(Collections.singletonList(b));
        OfficialEvent ended = event(OfficialEvent.ST_ENDED);
        ended.setId(9L);
        ended.setContractVersion(2);
        when(em.selectOfficialEventById(9L)).thenReturn(ended);

        OfficialBroadcastServiceImpl svc = broadcastService(mock(OfficialAudienceMapper.class),
                mock(OfficialBroadcastLogMapper.class), mock(IImService.class), mock(IWxSubscribeMsgService.class));
        ReflectionTestUtils.setField(svc, "broadcastMapper", bm);
        ReflectionTestUtils.setField(svc, "eventMapper", em);
        ReflectionTestUtils.setField(svc, "officialEventV2Service", v2);

        when(v2.canReadControlledEvent(9L, 101L)).thenReturn(true);
        when(v2.canOperateControlledEvent(9L, 101L)).thenReturn(false);
        assertEquals(1, svc.listBySender(101L, 101L).size(),
                "COMPLETED 主办的活动结束后「我发布的」必须能读(只读闸,不受操作闸影响)");

        // 负控:非参与者/撤权(读闸不放行)仍拒 —— 不放宽到非参与者
        when(v2.canReadControlledEvent(9L, 202L)).thenReturn(false);
        assertThrows(ServiceException.class, () -> svc.listBySender(202L, 202L));
    }

    @Test
    void getById_活动结束后主办按只读闸取单条_非参与者仍拒() {
        OfficialBroadcastMapper bm = mock(OfficialBroadcastMapper.class);
        OfficialEventMapper em = mock(OfficialEventMapper.class);
        IOfficialEventV2Service v2 = mock(IOfficialEventV2Service.class);

        OfficialBroadcast b = new OfficialBroadcast();
        b.setId(1L);
        b.setEventId(9L);
        when(bm.selectOfficialBroadcastById(1L)).thenReturn(b);
        OfficialEvent ended = event(OfficialEvent.ST_ENDED);
        ended.setId(9L);
        ended.setContractVersion(2);
        when(em.selectOfficialEventById(9L)).thenReturn(ended);

        OfficialBroadcastServiceImpl svc = broadcastService(mock(OfficialAudienceMapper.class),
                mock(OfficialBroadcastLogMapper.class), mock(IImService.class), mock(IWxSubscribeMsgService.class));
        ReflectionTestUtils.setField(svc, "broadcastMapper", bm);
        ReflectionTestUtils.setField(svc, "eventMapper", em);
        ReflectionTestUtils.setField(svc, "officialEventV2Service", v2);

        when(v2.canReadControlledEvent(9L, 101L)).thenReturn(true);
        when(v2.canOperateControlledEvent(9L, 101L)).thenReturn(false);
        assertEquals(1L, svc.getById(1L, 101L).getId(),
                "触达统计要用的取单条是纯读口,活动结束后必须走只读闸");

        // 负控:非参与者(读闸不放行)仍拒 —— 不放宽
        when(v2.canReadControlledEvent(9L, 202L)).thenReturn(false);
        assertThrows(ServiceException.class, () -> svc.getById(1L, 202L));
    }

    private EventSignup signup(long memberId, int progress, int claimed) {
        EventSignup s = new EventSignup();
        s.setId(memberId);
        s.setEventId(1L);
        s.setMemberId(memberId);
        s.setProgress(progress);
        s.setRewardClaimed(claimed);
        s.setStatus(EventSignup.ST_DOING);
        return s;
    }

    private OfficialBroadcastServiceImpl durableBroadcastService(OfficialAudienceMapper aud,
                                                          OfficialBroadcastLogMapper logMapper,
                                                          IImService im,
                                                          INotifyService wx) {
        OfficialBroadcastServiceImpl s = new OfficialBroadcastServiceImpl();
        ReflectionTestUtils.setField(s, "broadcastMapper", mock(OfficialBroadcastMapper.class));
        ReflectionTestUtils.setField(s, "logMapper", logMapper);
        ReflectionTestUtils.setField(s, "audienceMapper", aud);
        ReflectionTestUtils.setField(s, "imService", im);
        // Exercise the new delivery adapter; old audience/content assertions stay intact.
        OfficialBroadcastMapper batch=mock(OfficialBroadcastMapper.class);
        when(batch.updateOfficialBroadcast(any())).thenReturn(1);
        when(batch.lockDispatch(anyLong())).thenReturn(java.util.Map.of("audience_frozen",0,"status",OfficialBroadcast.ST_PENDING));
        java.util.Map<Long,java.util.Map<String,Object>> rows=new java.util.LinkedHashMap<>();
        org.mockito.Mockito.doAnswer(c->{
            com.chengyinhub.business.domain.OfficialBroadcastLog legacy=new com.chengyinhub.business.domain.OfficialBroadcastLog();
            if(logMapper.insertIgnore(legacy)==0)return 0;
            long id=rows.size()+1;java.util.Map<String,Object> row=new java.util.HashMap<>();
            row.put("id",id);row.put("broadcast_id",c.getArgument(0));row.put("member_id",c.getArgument(1));row.put("channel",c.getArgument(3));row.put("frozen_title",c.getArgument(4));row.put("frozen_sub",c.getArgument(5));row.put("delivery_state",c.getArgument(6));rows.put(id,row);return 1;
        }).when(logMapper).snapshot(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString(),anyString());
        when(logMapper.deliveryRows(anyLong())).thenAnswer(c->new java.util.ArrayList<>(rows.values()));
        when(logMapper.lockDelivery(anyLong())).thenAnswer(c->rows.get(c.getArgument(0)));
        org.mockito.Mockito.doAnswer(c->{rows.get(c.getArgument(0)).put("delivery_state",c.getArgument(1));return 1;}).when(logMapper).deliveryState(anyLong(),anyString(),org.mockito.ArgumentMatchers.nullable(Long.class),org.mockito.ArgumentMatchers.nullable(Long.class),anyInt());
        when(im.enqueueSystemNotification(anyLong(),anyInt(),anyString(),anyString(),anyString())).thenReturn(101L);
        when(im.deliverSystemNotification(anyLong())).thenReturn(201L);
        com.chengyinhub.business.notify.WxSubscribeChannel channel=mock(com.chengyinhub.business.notify.WxSubscribeChannel.class);
        when(channel.sendOfficialBroadcast(anyLong(),anyString(),anyString())).thenAnswer(c->{wx.notifyOfficialBroadcast(c.getArgument(0),c.getArgument(1),c.getArgument(2));return new com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult(com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.State.UNKNOWN,com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.Reason.TRANSPORT_UNCONFIRMED,null);});
        org.springframework.transaction.PlatformTransactionManager tm=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenAnswer(c->new org.springframework.transaction.support.SimpleTransactionStatus());
        OfficialBroadcastDeliveryService delivery = new OfficialBroadcastDeliveryService(logMapper,batch,im,channel,tm);
        com.chengyinhub.business.mapper.UmsMemberMapper members = mock(com.chengyinhub.business.mapper.UmsMemberMapper.class);
        when(members.lockMemberById(anyLong())).thenAnswer(c -> c.getArgument(0));
        ReflectionTestUtils.setField(delivery,"members",members);
        ReflectionTestUtils.setField(s,"delivery",delivery);
        ReflectionTestUtils.setField(s,"transactionManager",tm);
        return s;
    }

    private OfficialBroadcast durableBc(int mode, String content) {
        OfficialBroadcast result = bc(mode, content);
        result.setClientRequestId("0123456789abcdef0123456789abcdef");
        result.setRequestOwner("ADMIN:1");
        return result;
    }

    @Test
    void dispatch_圈人跨角色去重_双渠道fanout_durableCreation() {
        OfficialAudienceMapper aud = mock(OfficialAudienceMapper.class);
        // 11 同时命中 player 与 club → 去重,首命中 player 为准;总去重后 = {10,11,20,30} = 4 人
        when(aud.selectPlayerIds("成都")).thenReturn(Arrays.asList(10L, 11L));
        when(aud.selectClubMemberIds("成都")).thenReturn(Arrays.asList(11L, 20L));
        when(aud.selectMerchantMemberIds("成都")).thenReturn(Collections.singletonList(30L));

        OfficialBroadcastLogMapper logMapper = mock(OfficialBroadcastLogMapper.class);
        when(logMapper.insertIgnore(any())).thenReturn(1); // 全新,可发

        IImService im = mock(IImService.class);
        INotifyService wx = mock(INotifyService.class);

        int reached = durableBroadcastService(aud, logMapper, im, wx).dispatch(durableBc(1, "{\"title\":\"统一标题\",\"sub\":\"统一内容\"}"));

        // 去重后 4 人,站内信 4 次(未去重会是 5 次)
        assertEquals(4, reached);
        verify(im, times(4)).enqueueSystemNotification(anyLong(), eq(3), anyString(), anyString(), anyString());
        // 订阅渠道也 4 次
        verify(wx, times(4)).notifyOfficialBroadcast(anyLong(), anyString(), anyString());
    }

    @Test
    void dispatch_uk已发过则不重发_durableCreation() {
        OfficialAudienceMapper aud = mock(OfficialAudienceMapper.class);
        when(aud.selectPlayerIds(any())).thenReturn(Arrays.asList(10L, 11L));
        when(aud.selectClubMemberIds(any())).thenReturn(Collections.emptyList());
        when(aud.selectMerchantMemberIds(any())).thenReturn(Collections.emptyList());

        OfficialBroadcastLogMapper logMapper = mock(OfficialBroadcastLogMapper.class);
        when(logMapper.insertIgnore(any())).thenReturn(0); // uk 冲突,已发过

        IImService im = mock(IImService.class);
        INotifyService wx = mock(INotifyService.class);

        int reached = durableBroadcastService(aud, logMapper, im, wx).dispatch(durableBc(1, null));

        assertEquals(0, reached);
        verify(im, never()).enqueueSystemNotification(anyLong(), anyInt(), anyString(), anyString(), anyString());
        verify(wx, never()).notifyOfficialBroadcast(anyLong(), anyString(), anyString());
    }

    @Test
    void dispatch_分开发_按角色取对应文案_durableCreation() {
        OfficialAudienceMapper aud = mock(OfficialAudienceMapper.class);
        when(aud.selectPlayerIds("成都")).thenReturn(Collections.singletonList(10L));
        when(aud.selectClubMemberIds("成都")).thenReturn(Collections.emptyList());
        when(aud.selectMerchantMemberIds("成都")).thenReturn(Collections.emptyList());

        OfficialBroadcastLogMapper logMapper = mock(OfficialBroadcastLogMapper.class);
        when(logMapper.insertIgnore(any())).thenReturn(1);
        IImService im = mock(IImService.class);
        INotifyService wx = mock(INotifyService.class);

        String content = "{\"player\":{\"title\":\"玩家标题\",\"sub\":\"出门点亮\"},\"merchant\":{\"title\":\"商家标题\",\"sub\":\"客流洪峰\"}}";
        durableBroadcastService(aud, logMapper, im, wx).dispatch(durableBc(2, content));

        ArgumentCaptor<String> titleCap = ArgumentCaptor.forClass(String.class);
        verify(im, times(1)).enqueueSystemNotification(eq(10L), eq(3), titleCap.capture(), anyString(), anyString());
        assertEquals("玩家标题", titleCap.getValue()); // 玩家收到玩家文案,而非默认标题
    }

    @Test
    void dispatch_频控_超限用户被跳过_durableCreation() {
        OfficialAudienceMapper aud = mock(OfficialAudienceMapper.class);
        when(aud.selectPlayerIds("成都")).thenReturn(Arrays.asList(10L, 11L));
        when(aud.selectClubMemberIds("成都")).thenReturn(Collections.emptyList());
        when(aud.selectMerchantMemberIds("成都")).thenReturn(Collections.emptyList());

        OfficialBroadcastLogMapper logMapper = mock(OfficialBroadcastLogMapper.class);
        when(logMapper.insertIgnore(any())).thenReturn(1);
        // 10 号近1小时已收5条(达上限)→ 跳过;11 号未达 → 正常发
        when(logMapper.countRecentByMember(eq(10L), anyInt())).thenReturn(5);
        when(logMapper.countRecentByMember(eq(11L), anyInt())).thenReturn(0);

        IImService im = mock(IImService.class);
        INotifyService wx = mock(INotifyService.class);

        int reached = durableBroadcastService(aud, logMapper, im, wx).dispatch(durableBc(1, null));

        assertEquals(1, reached); // 仅 11 收到
        verify(im, times(1)).enqueueSystemNotification(eq(11L), anyInt(), anyString(), anyString(), anyString());
        verify(im, never()).enqueueSystemNotification(eq(10L), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void dispatch_频控_查询异常fallback禁止发送_durableCreation() {
        OfficialAudienceMapper aud = mock(OfficialAudienceMapper.class);
        when(aud.selectPlayerIds("成都")).thenReturn(Arrays.asList(10L, 11L));
        when(aud.selectClubMemberIds("成都")).thenReturn(Collections.emptyList());
        when(aud.selectMerchantMemberIds("成都")).thenReturn(Collections.emptyList());

        OfficialBroadcastLogMapper logMapper = mock(OfficialBroadcastLogMapper.class);
        when(logMapper.insertIgnore(any())).thenReturn(1);
        when(logMapper.countRecentByMember(eq(10L), anyInt())).thenThrow(new RuntimeException("simulated DB timeout"));
        when(logMapper.countRecentByMember(eq(11L), anyInt())).thenReturn(0);

        IImService im = mock(IImService.class);
        INotifyService wx = mock(INotifyService.class);

        int reached = durableBroadcastService(aud, logMapper, im, wx).dispatch(durableBc(1, null));

        assertEquals(1, reached);
        verify(im, times(1)).enqueueSystemNotification(eq(11L), anyInt(), anyString(), anyString(), anyString());
        verify(im, never()).enqueueSystemNotification(eq(10L), anyInt(), anyString(), anyString(), anyString());
    }

}
