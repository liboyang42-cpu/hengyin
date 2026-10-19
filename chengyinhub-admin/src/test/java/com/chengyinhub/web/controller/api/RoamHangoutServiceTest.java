package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.RoamHangout;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.RoamHangoutMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IRoamHangoutService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.impl.RoamHangoutServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import java.util.HashMap;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 附近的局 · 三道闸 + 幂等 + 探半径。每条断言都指明是哪道闸拦的文案,防「拆一道另一道接住」。
 * 负控(已实测):去掉 create 里的 checkText → 「标题命中机审」红;去掉 countActiveByMember → 「第 4 个局」红。
 */
class RoamHangoutServiceTest {

    private RoamHangoutMapper mapper;
    private IImService im;
    private IWxContentSecurityService sec;
    private IAuditTaskService audit;
    private IUmsMemberService members;
    private com.chengyinhub.business.mapper.CmsTopicMapper topicMapper;
    private RoamHangoutServiceImpl svc;

    private static final BigDecimal LAT = new BigDecimal("31.2304");
    private static final BigDecimal LNG = new BigDecimal("121.4737");

    @BeforeEach
    void setUp() {
        mapper = mock(RoamHangoutMapper.class);
        im = mock(IImService.class);
        sec = mock(IWxContentSecurityService.class);
        audit = mock(IAuditTaskService.class);
        members = mock(IUmsMemberService.class);
        topicMapper = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        svc = new RoamHangoutServiceImpl();
        org.springframework.transaction.PlatformTransactionManager transactionManager =
                mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(inv ->
                new org.springframework.transaction.support.SimpleTransactionStatus());
        ReflectionTestUtils.setField(svc, "transactionManager", transactionManager);
        ReflectionTestUtils.setField(svc, "roamHangoutMapper", mapper);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(svc, "imService", im);
        ReflectionTestUtils.setField(svc, "wxContentSecurityService", sec);
        ReflectionTestUtils.setField(svc, "auditTaskService", audit);
        ReflectionTestUtils.setField(svc, "umsMemberService", members);
        UmsMember u = new UmsMember();
        u.setWxOpenId("o_test");
        when(members.selectUmsMemberById(anyLong())).thenReturn(u);
        when(mapper.insert(any(RoamHangout.class))).thenAnswer(inv -> {
            ((RoamHangout) inv.getArgument(0)).setId(77L);
            return 1;
        });
        when(mapper.updateStatus(anyLong(), anyInt())).thenReturn(1);
        when(im.getOrCreateHangoutGroup(anyLong(), anyLong(), anyLong())).thenReturn(900L);
    }

    @Test
    void create_标题命中机审_拒绝且不落库() {
        when(sec.checkText(anyString(), any())).thenReturn("含违禁词");
        ServiceException e = assertThrows(ServiceException.class,
                () -> svc.create(1L, "卖假货的局", null, null, LAT, LNG, "人民广场", null));
        assertTrue(e.getMessage().contains("违规"), "文案必须指明是机审闸:" + e.getMessage());
        verify(mapper, never()).insert(any(RoamHangout.class));
    }

    @Test
    void create_第4个局_被数量闸拦() {
        when(mapper.countActiveByMember(1L)).thenReturn(IRoamHangoutService.MAX_ACTIVE_PER_MEMBER);
        ServiceException e = assertThrows(ServiceException.class,
                () -> svc.create(1L, "打 UNO", null, null, LAT, LNG, "人民广场", null));
        assertTrue(e.getMessage().contains("最多开 3 个局"), e.getMessage());
        verify(sec, never()).checkText(anyString(), any());
    }

    @Test
    void create_成功_建群并回填conversationId() {
        when(sec.checkText(anyString(), any())).thenReturn(null);
        RoamHangout h = svc.create(1L, "打 UNO", "带牌", null, LAT, LNG, "人民广场", null);
        assertEquals(77L, h.getId());
        assertEquals(900L, h.getConversationId());
        verify(im).getOrCreateHangoutGroup(77L, 1L, 1L);
        verify(mapper).updateConversationId(77L, 900L);
        verify(sec, never()).submitMediaCheckOne(anyInt(), anyLong(), anyString(), any());
    }

    @Test
    void create_带封面_异步送检() {
        when(sec.checkText(anyString(), any())).thenReturn(null);
        svc.create(1L, "打 UNO", null, "https://oss/x.jpg", LAT, LNG, "人民广场", null);
        verify(sec).submitMediaCheckOne(eq(IWxContentSecurityService.BIZ_TYPE_HANGOUT), eq(77L), eq("https://oss/x.jpg"), any());
    }

    @Test
    void create_坐标缺失_拒绝() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> svc.create(1L, "打 UNO", null, null, null, LNG, null, null));
        assertTrue(e.getMessage().contains("地点"), e.getMessage());
    }

    private RoamHangout open(long id, long owner, Long convId) {
        RoamHangout h = new RoamHangout();
        h.setId(id); h.setMemberId(owner); h.setStatus(RoamHangout.STATUS_OPEN); h.setConversationId(convId);
        return h;
    }

    @Test
    void join_已在群_幂等不重复建群() {
        when(mapper.selectByIdForUpdate(5L)).thenReturn(open(5L, 9L, 500L));
        when(im.isActiveGroupMember(2L, 500L)).thenReturn(true);
        assertEquals(500L, svc.join(2L, 5L));
        verify(im, never()).getOrCreateHangoutGroup(anyLong(), anyLong(), anyLong());
    }

    @Test
    void join_群满_被人数闸拦() {
        when(mapper.selectByIdForUpdate(5L)).thenReturn(open(5L, 9L, 500L));
        when(im.isActiveGroupMember(2L, 500L)).thenReturn(false);
        when(mapper.countActiveMembers(500L)).thenReturn(IRoamHangoutService.MAX_MEMBERS);
        ServiceException e = assertThrows(ServiceException.class, () -> svc.join(2L, 5L));
        assertTrue(e.getMessage().contains("群已满"), e.getMessage());
    }

    @Test
    void join_已关的局_拒绝() {
        RoamHangout h = open(5L, 9L, 500L);
        h.setStatus(RoamHangout.STATUS_CLOSED);
        when(mapper.selectByIdForUpdate(5L)).thenReturn(h);
        ServiceException e = assertThrows(ServiceException.class, () -> svc.join(2L, 5L));
        assertTrue(e.getMessage().contains("关了"), e.getMessage());
    }

    @Test
    void close_非局主_拒绝() {
        when(mapper.selectByIdForUpdate(5L)).thenReturn(open(5L, 9L, 500L));
        ServiceException e = assertThrows(ServiceException.class, () -> svc.close(2L, 5L));
        assertTrue(e.getMessage().contains("局主"), e.getMessage());
        verify(mapper, never()).updateStatus(anyLong(), anyInt());
    }

    @Test
    void close_局主_关局并关群() {
        when(mapper.selectByIdForUpdate(5L)).thenReturn(open(5L, 9L, 500L));
        svc.close(9L, 5L);
        verify(mapper).updateStatus(5L, RoamHangout.STATUS_CLOSED);
        verify(im).closeHangoutGroup(5L);
    }

    @Test
    void leave_局主不能退() {
        when(mapper.selectById(5L, 9L)).thenReturn(open(5L, 9L, 500L));
        ServiceException e = assertThrows(ServiceException.class, () -> svc.leave(9L, 5L));
        assertTrue(e.getMessage().contains("只能关局"), e.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void nearby_不再返回搭子局图层_也不按局探半径() {
        // 旧行为:半径内没局时按局数探半径(10000/20000),并提示前端拉远。
        // 局图层下线后这条梯子必须停用 —— 拉远也看不到任何局,提示就是误导。
        when(mapper.selectNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyLong(), anyInt())).thenReturn(Collections.<RoamHangout>emptyList());
        when(mapper.countNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyLong())).thenReturn(7);
        when(mapper.selectNearbyActivities(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyInt(), anyLong())).thenReturn(Collections.<Map<String, Object>>emptyList());
        Map<String, Object> out = svc.nearby(1L, 31.23, 121.47, 3000);
        assertNull(out.get("suggestedRadius"), "局图层下线后不再按局数探半径");
        assertTrue(((List<Object>) out.get("items")).isEmpty());
        verify(mapper, never()).countNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void nearby_存量搭子局不进items但活动照常上图() {
        RoamHangout h = open(5L, 9L, 500L); h.setTitle("打 UNO"); h.setMemberCount(3); h.setIsMember(1);
        when(mapper.selectNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyLong(), anyInt())).thenReturn(Arrays.asList(h));
        Map<String, Object> act = new HashMap<String, Object>();
        act.put("id", 6L); act.put("name", "周五 19:30 场"); act.put("viewerHasTicket", 1L);
        when(mapper.selectNearbyActivities(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyInt(), anyLong())).thenReturn(new ArrayList<Map<String, Object>>(Arrays.asList(act)));
        Map<String, Object> out = svc.nearby(1L, 31.23, 121.47, 0);
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertEquals(1, items.size());
        assertEquals("activity", items.get(0).get("kind"), "活动图层照常");
        assertEquals(Boolean.TRUE, items.get(0).get("viewerHasTicket"), "无队伍时仍返回本人持票事实");
        verify(mapper).selectNearbyActivities(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt(), eq(1L));
        assertTrue(items.stream().noneMatch(item -> "hangout".equals(item.get("kind"))), "kind 不得再出现 hangout");
        verify(mapper, never()).countNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong());
    }

    @Test
    void expireDue_逐条关局关群() {
        when(mapper.selectExpired(any(Date.class))).thenReturn(Arrays.asList(open(1L, 9L, 10L), open(2L, 9L, 20L)));
        when(mapper.selectByIdForUpdate(1L)).thenReturn(open(1L, 9L, 10L));
        when(mapper.selectByIdForUpdate(2L)).thenReturn(open(2L, 9L, 20L));
        assertEquals(2, svc.expireDue(new Date()));
        verify(mapper).updateStatus(1L, RoamHangout.STATUS_CLOSED);
        verify(im).closeHangoutGroup(2L);
    }

    @Test
    void expireDue_单项失败回滚且后项继续_不覆盖已处置状态() {
        RoamHangout hidden = open(3L, 9L, 30L);
        hidden.setStatus(RoamHangout.STATUS_HIDDEN);
        when(mapper.selectExpired(any(Date.class))).thenReturn(Arrays.asList(
                open(1L, 9L, 10L), open(2L, 9L, 20L), hidden));
        when(mapper.selectByIdForUpdate(1L)).thenReturn(open(1L, 9L, 10L));
        when(mapper.selectByIdForUpdate(2L)).thenReturn(open(2L, 9L, 20L));
        when(mapper.selectByIdForUpdate(3L)).thenReturn(hidden);
        doThrow(new IllegalStateException("IM write failed")).when(im).closeHangoutGroup(1L);
        assertEquals(1, svc.expireDue(new Date()));
        org.springframework.transaction.PlatformTransactionManager tx =
                (org.springframework.transaction.PlatformTransactionManager) ReflectionTestUtils.getField(svc, "transactionManager");
        verify(tx).rollback(any());
        verify(tx, times(2)).commit(any());
        verify(im).closeHangoutGroup(2L);
        verify(mapper, never()).updateStatus(3L, RoamHangout.STATUS_CLOSED);
    }

    @Test
    void nearby_已发布主题也上图_标kind为topic且ownerRole兜底player() {
        when(mapper.selectNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt(), anyLong(), anyInt()))
                .thenReturn(new ArrayList<RoamHangout>());
        when(mapper.selectNearbyActivities(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new ArrayList<Map<String, Object>>());
        Map<String, Object> t1 = new HashMap<String, Object>(); t1.put("id", 7L); t1.put("name", "外滩定向"); t1.put("productType", 1); t1.put("ownerRole", "merchant"); t1.put("viewerHasTicket", 1L);
        Map<String, Object> t2 = new HashMap<String, Object>(); t2.put("id", 8L); t2.put("name", "弄堂探索"); t2.put("productType", 2);
        List<Map<String, Object>> topics = new ArrayList<Map<String, Object>>(); topics.add(t1); topics.add(t2);
        when(topicMapper.selectNearbyTopics(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt(), anyInt(), anyLong())).thenReturn(topics);
        Map<String, Object> out = svc.nearby(9L, 31.2, 121.4, 3000);
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertEquals(2, items.size());
        assertEquals("topic", items.get(0).get("kind"));
        assertEquals(Boolean.TRUE, items.get(0).get("viewerHasTicket"));
        assertEquals(Boolean.FALSE, items.get(1).get("viewerHasTicket"));
        verify(topicMapper).selectNearbyTopics(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt(), anyInt(), eq(9L));
        assertEquals("merchant", items.get(0).get("ownerRole"));
        assertEquals("player", items.get(1).get("ownerRole"), "没查到发布者身份时按玩家画,别让点位丢色");
    }

    @Test
    void nearby_未登录_拒绝() {
        ServiceException e = assertThrows(ServiceException.class, () -> svc.nearby(0L, 31.23, 121.47, 3000));
        assertTrue(e.getMessage().contains("登录"), e.getMessage());
    }

    @Test
    void create_机审服务繁忙_不当成违规() {
        when(sec.checkText(anyString(), any())).thenReturn("内容审核服务繁忙，请稍后重试");
        ServiceException e = assertThrows(ServiceException.class,
                () -> svc.create(1L, "打 UNO", null, null, LAT, LNG, "人民广场", null));
        assertTrue(!e.getMessage().contains("违规"), "繁忙不是违规:" + e.getMessage());
        assertEquals(503, e.getCode());
        verify(mapper, never()).insert(any(RoamHangout.class));
    }

    @Test
    void moderate_违规成立_隐藏并关群() {
        when(mapper.selectByIdForUpdate(5L)).thenReturn(open(5L, 9L, 500L));
        svc.moderate(5L, true);
        verify(mapper).updateStatus(5L, RoamHangout.STATUS_HIDDEN);
        verify(im).closeHangoutGroup(5L);
    }

    @Test
    void moderate_已关的局被举报_保持CLOSED不改成HIDDEN() {
        // 否则举报驳回时 HIDDEN→OPEN 会把一个群早已结束的局「复活」成开放
        RoamHangout closed = open(5L, 9L, 500L);
        closed.setStatus(RoamHangout.STATUS_CLOSED);
        when(mapper.selectByIdForUpdate(5L)).thenReturn(closed);
        svc.moderate(5L, true);
        verify(mapper, never()).updateStatus(eq(5L), anyInt());
        svc.moderate(5L, false);
        verify(mapper, never()).updateStatus(eq(5L), anyInt());
    }

    @Test
    void detail_未登录_拒绝() {
        ServiceException e = assertThrows(ServiceException.class, () -> svc.detail(0L, 5L));
        assertTrue(e.getMessage().contains("登录"), e.getMessage());
    }

    @Test
    void moderate_举报无效_不得恢复任何来源处罚() {
        RoamHangout hidden = open(5L, 9L, 500L); hidden.setStatus(RoamHangout.STATUS_HIDDEN);
        when(mapper.selectByIdForUpdate(5L)).thenReturn(hidden);
        svc.moderate(5L, false);
        verify(mapper, never()).updateStatus(5L, RoamHangout.STATUS_OPEN);
        verify(im, never()).closeHangoutGroup(anyLong());
        // 本来就进行中的局,「无效」不该动它
        when(mapper.selectByIdForUpdate(6L)).thenReturn(open(6L, 9L, 600L));
        svc.moderate(6L, false);
        verify(mapper, never()).updateStatus(eq(6L), anyInt());
    }

    @Test
    void closeAllByMember_注销时逐局关闭() {
        when(mapper.selectOpenByMember(9L)).thenReturn(Arrays.asList(open(1L, 9L, 10L), open(2L, 9L, 20L)));
        assertEquals(2, svc.closeAllByMember(9L));
        verify(mapper).updateStatus(1L, RoamHangout.STATUS_CLOSED);
        verify(mapper).updateStatus(2L, RoamHangout.STATUS_CLOSED);
        verify(im).closeHangoutGroup(1L);
        verify(im).closeHangoutGroup(2L);
    }
}
