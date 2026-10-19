package com.chengyinhub.business.service.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.BeforeStartNotifyResult;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.NotificationDeliveryReceiptMapper;
import com.chengyinhub.business.notify.NotifyResult;
import com.chengyinhub.business.notify.NotifyChannel;
import com.chengyinhub.business.notify.NotifyMessage;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.common.core.redis.RedisCache;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.text.SimpleDateFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 出发前提醒的「失败必须能重试」契约(纯 Mockito,零外部依赖)。
 *
 * 背景:notifyBeforeStart 是 best-effort、**失败不抛异常**(抛出会回滚退款事务 = 资损),
 * 所以调用方 try/catch 接不到任何东西。原实现按票去重、且不管成败都写去重键 + 计数,
 * 后果是整批全失败也被标记成已发、24h 窗口内永不重试,而返回值(票种数)照样 >0 看着像成功。
 *
 * 这里锁三条:①失败不写去重键(下轮能重试)②成功才写(不重发)③返回值 = 真实送达条数。
 */
public class BeforeStartNotifyRetryContractTest {

    /** 内存版 RedisCache 替身:只需要 get/set 两个行为,顺便让测试能断言"键到底写没写"。 */
    private static class FakeCache {
        final Set<String> keys = new HashSet<>();
    }

    private TeamGroupServiceImpl newService(FakeCache cache, INotifyService wx,
                                            OmsTicket ticket, Long... paidMemberIds) {
        TeamGroupServiceImpl service = new TeamGroupServiceImpl();

        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        when(ticketMapper.selectTeamFormedStartingSoon(any(Date.class), any(Date.class)))
                .thenReturn(Collections.singletonList(ticket));

        CmsRegistrationMapper regMapper = mock(CmsRegistrationMapper.class);
        CmsRegistration[] regs = new CmsRegistration[paidMemberIds.length];
        for (int i = 0; i < paidMemberIds.length; i++) {
            CmsRegistration r = new CmsRegistration();
            r.setMemberId(paidMemberIds[i]);
            regs[i] = r;
        }
        when(regMapper.selectPaidByTicket(eq(ticket.getId()))).thenReturn(Arrays.asList(regs));

        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getCacheObject(anyString()))
                .thenAnswer(inv -> cache.keys.contains(inv.getArgument(0)) ? "1" : null);
        doAnswer(inv -> {
            cache.keys.add(inv.getArgument(0));
            return null;
        }).when(redisCache).setCacheObject(anyString(), any(), anyInt(), any(TimeUnit.class));

        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(service, "redisCache", redisCache);
        ReflectionTestUtils.setField(service, "notifyService", wx);
        NotificationDeliveryReceiptMapper receipts = mock(NotificationDeliveryReceiptMapper.class);
        Map<Long, String> states = new HashMap<>();
        when(receipts.claim(eq("BEFORE_START"), eq(ticket.getId()), anyLong(), eq("WECHAT_SUBSCRIBE")))
                .thenAnswer(inv -> {
                    Long memberId = inv.getArgument(2);
                    String state = states.get(memberId);
                    if (state == null || "FAILED".equals(state)) {
                        states.put(memberId, "PROCESSING");
                        return 1;
                    }
                    return 0;
                });
        when(receipts.complete(eq("BEFORE_START"), eq(ticket.getId()), anyLong(),
                eq("WECHAT_SUBSCRIBE"), anyString(), any()))
                .thenAnswer(inv -> { states.put(inv.getArgument(2), inv.getArgument(4)); return 1; });
        org.springframework.transaction.PlatformTransactionManager manager =
                mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(inv ->
                new org.springframework.transaction.support.SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "notificationReceiptService",
                new NotificationDeliveryReceiptService(receipts, mock(com.chengyinhub.business.service.IImService.class), manager));
        return service;
    }

    /**
     * 端到端接缝:TeamGroupServiceImpl → **真** NotifyServiceImpl → 抛 Error 的通道。
     *
     * 为什么要这条:上面几条用 mock(INotifyService) 打桩,NotifyFanoutContractTest 又只驱动
     * NotifyServiceImpl 本身 —— 两端各测各的,**中间这道接缝从没被测过**。
     * 而资损正是发生在接缝上:通道抛出 → 穿过 fanout → 穿到 autoRefund 的 @Transactional →
     * 回滚掉已被微信受理的退款。
     *
     * 用 Error 而不是 RuntimeException:catch(RuntimeException) 能接住后者,接不住前者。
     * 这条测试就是要钉死「接的是 Throwable」。
     */
    @Test
    public void 通道抛Error也不得穿透到批量提醒_且失败必须可重试() {
        FakeCache cache = new FakeCache();
        NotifyServiceImpl real = new NotifyServiceImpl();
        NotifyChannel explodingChannel = new NotifyChannel() {
            @Override public String channelId() { return "WECHAT_SUBSCRIBE"; }
            @Override
            public boolean send(NotifyMessage message) {
                // 模拟 P0-2 要接的 JPush SDK 缺类:抛的是 Error,不是 Exception。
                throw new NoClassDefFoundError("cn/jpush/api/JPushClient");
            }
        };
        ReflectionTestUtils.setField(real, "channels", Collections.singletonList(explodingChannel));

        OmsTicket t = ticket(7L);
        TeamGroupServiceImpl service = newService(cache, real, t, 100L, 101L);

        // 不 try/catch:穿透出来本测试直接 error —— 那正是要防的资损路径。
        int delivered = service.notifyBeforeStartBatch().getDelivered();

        assertEquals(0, delivered, "通道全炸 = 一条都没送达,不能拿票种数冒充送达数");
        assertTrue(cache.keys.isEmpty(),
                "炸了却写去重键 ⇒ 24h 内永不重试。异常被吞不等于可以假装发过了");
    }

    private static OmsTicket ticket(long id) {
        OmsTicket t = new OmsTicket();
        t.setId(id);
        t.setName("上海滨江10KM骑行挑战赛");
        t.setStartTime(new Date(System.currentTimeMillis() + 3600_000L));
        return t;
    }

    @Test
    public void 下发失败不写去重键_下一轮必须能重试() {
        FakeCache cache = new FakeCache();
        INotifyService wx = mock(INotifyService.class);
        // 关键:失败时**返回 false 而不是抛异常** —— 这正是 best-effort 合同的形状
        when(wx.notifyBeforeStartResult(anyLong(), anyString(), anyString()))
                .thenReturn(NotifyResult.failed("WECHAT_SUBSCRIBE", "43101"));

        TeamGroupServiceImpl service = newService(cache, wx, ticket(7L), 100L, 101L);

        BeforeStartNotifyResult first = service.notifyBeforeStartBatch();
        assertEquals(0, first.getDelivered(), "一条都没送达,送达数必须是 0(不能拿票种数冒充送达数)");
        assertEquals(2, first.getScanned(), "扫到 2 个「票×人」候选");
        assertEquals(2, first.getFailed(), "两条都失败,失败数必须是 2 —— 任务层靠它告警");
        assertTrue(cache.keys.isEmpty(), "失败却写了去重键 ⇒ 24h 窗口内永不重试,等于永久错过");

        // 第二轮:微信恢复了
        when(wx.notifyBeforeStartResult(anyLong(), anyString(), anyString()))
                .thenReturn(NotifyResult.accepted("WECHAT_SUBSCRIBE"));
        BeforeStartNotifyResult second = service.notifyBeforeStartBatch();
        assertEquals(2, second.getDelivered(), "上一轮失败的两个人,这一轮必须补发成功");
        assertEquals(0, second.getFailed(), "失败已清零");
        assertEquals(0, second.getSkipped(), "上一轮没写键,本轮是重试不是跳过");
    }

    @Test
    public void 成功的人写键不重发_失败的人下轮补发() {
        FakeCache cache = new FakeCache();
        INotifyService wx = mock(INotifyService.class);
        // 100 成功、101 失败 —— 混合场景才能同时验"不重发"和"能重试"
        when(wx.notifyBeforeStartResult(eq(100L), anyString(), anyString()))
                .thenReturn(NotifyResult.accepted("WECHAT_SUBSCRIBE"));
        when(wx.notifyBeforeStartResult(eq(101L), anyString(), anyString()))
                .thenReturn(NotifyResult.failed("WECHAT_SUBSCRIBE", "43101"));

        TeamGroupServiceImpl service = newService(cache, wx, ticket(7L), 100L, 101L);

        assertEquals(1, service.notifyBeforeStartBatch().getDelivered(), "只有 100 送达");
        assertTrue(cache.keys.contains("team:beforenotify:7:100"), "成功的人必须写键");
        assertFalse(cache.keys.contains("team:beforenotify:7:101"), "失败的人不能写键");

        when(wx.notifyBeforeStartResult(eq(101L), anyString(), anyString()))
                .thenReturn(NotifyResult.accepted("WECHAT_SUBSCRIBE"));
        assertEquals(1, service.notifyBeforeStartBatch().getDelivered(), "第二轮只补 101,不能重发 100");
        // 100 全程只被调用一次 = 没有重复打扰已收到的用户
        verify(wx, times(1)).notifyBeforeStartResult(eq(100L), anyString(), anyString());
        verify(wx, times(2)).notifyBeforeStartResult(eq(101L), anyString(), anyString());
    }

    @Test
    public void 去重粒度必须是票乘人_不能退回按票去重() {
        FakeCache cache = new FakeCache();
        INotifyService wx = mock(INotifyService.class);
        when(wx.notifyBeforeStartResult(anyLong(), anyString(), anyString()))
                .thenReturn(NotifyResult.accepted("WECHAT_SUBSCRIBE"));

        TeamGroupServiceImpl service = newService(cache, wx, ticket(7L), 100L, 101L);
        service.notifyBeforeStartBatch();

        // 负控锚点:一旦有人把键退回成按票的 "team:beforenotify:7",这条会红。
        assertFalse(cache.keys.contains("team:beforenotify:7"),
                "按票去重会让同票里失败的人被别人的成功连坐,必须按「票:人」");
        assertEquals(2, cache.keys.size(), "两个人应各有一个去重键");
    }

    /**
     * 审查D §8 P1-1:生产者给微信 date 类型的 whenText 必须带年份。
     * 旧实现 fmt() 产 "MM-dd HH:mm",再被 WxSubscribeMsgServiceImpl 截前 10 位 ⇒ "09-16 14:0",
     * 微信判 47003 ⇒ 102 启用后一条都发不出去且零告警。这里钉住生产者这一端。
     */
    @Test
    public void whenText必须是带年份的完整日期_不能是MMdd() {
        FakeCache cache = new FakeCache();
        INotifyService wx = mock(INotifyService.class);
        when(wx.notifyBeforeStartResult(anyLong(), anyString(), anyString()))
                .thenReturn(NotifyResult.accepted("WECHAT_SUBSCRIBE"));

        OmsTicket t = ticket(7L);
        long startMillis = t.getStartTime().getTime();
        TeamGroupServiceImpl service = newService(cache, wx, t, 100L);
        service.notifyBeforeStartBatch();

        ArgumentCaptor<String> whenText = ArgumentCaptor.forClass(String.class);
        verify(wx).notifyBeforeStartResult(eq(100L), anyString(), whenText.capture());
        assertEquals(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(startMillis)),
                whenText.getValue(), "whenText 必须含年份:微信 date 类型拒绝 MM-dd 形状");
    }

    @Test
    public void unknown收据不得在下一小时盲重发() {
        FakeCache cache = new FakeCache();
        INotifyService wx = mock(INotifyService.class);
        when(wx.notifyBeforeStartResult(anyLong(), anyString(), anyString()))
                .thenReturn(NotifyResult.unknown("WECHAT_SUBSCRIBE"));
        TeamGroupServiceImpl service = newService(cache, wx, ticket(7L), 100L);

        assertEquals(1, service.notifyBeforeStartBatch().getFailed());
        assertEquals(1, service.notifyBeforeStartBatch().getSkipped());
        verify(wx, times(1)).notifyBeforeStartResult(eq(100L), anyString(), anyString());
    }

    @Test
    public void 外部接受后本地落库失败_原processing不得再发() {
        FakeCache cache = new FakeCache();
        INotifyService wx = mock(INotifyService.class);
        when(wx.notifyBeforeStartResult(anyLong(), anyString(), anyString()))
                .thenReturn(NotifyResult.accepted("WECHAT_SUBSCRIBE"));
        TeamGroupServiceImpl service = newService(cache, wx, ticket(7L), 100L);
        NotificationDeliveryReceiptService receiptService =
                (NotificationDeliveryReceiptService) ReflectionTestUtils.getField(service, "notificationReceiptService");
        NotificationDeliveryReceiptMapper receipts =
                (NotificationDeliveryReceiptMapper) ReflectionTestUtils.getField(receiptService, "mapper");
        when(receipts.complete(anyString(), anyLong(), anyLong(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("db result write failed"));

        assertEquals(0, service.notifyBeforeStartBatch().getDelivered());
        assertTrue(cache.keys.isEmpty(), "收据失败不得写成功键");
        cache.keys.clear(); // 模拟 Redis 丢键，持久 PROCESSING 仍必须拦住第二 worker
        assertEquals(1, service.notifyBeforeStartBatch().getSkipped());
        verify(wx, times(1)).notifyBeforeStartResult(eq(100L), anyString(), anyString());
    }
}
