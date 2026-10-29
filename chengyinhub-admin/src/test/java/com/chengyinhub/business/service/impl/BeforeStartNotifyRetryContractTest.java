package com.chengyinhub.business.service.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.BeforeStartNotifyResult;
import com.chengyinhub.business.domain.dto.BeforeStartNotifyResult.Delivery;
import com.chengyinhub.business.service.support.CommittedOfficialNoticeService;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
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

/** Current eligible-ticket/paid-member producer adapter. No provider/storage proof here; the real
 * durable owner/Mapper/transaction scenarios are in BeforeStartNoticeMySqlIT. The old false->retry
 * expectation is split into NOT_READY (known unsent) and UNKNOWN (only read original, never resend). */
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
        return service;
    }

    @Test
    public void 投递Owner抛Error不得穿透批量提醒且必须按未知告警() {
        FakeCache cache=new FakeCache();
        NotifyServiceImpl real=new NotifyServiceImpl();
        CommittedOfficialNoticeService owner=mock(CommittedOfficialNoticeService.class);
        org.mockito.Mockito.doThrow(new NoClassDefFoundError("provider response unavailable"))
                .when(owner).beforeStart(anyLong(),anyLong(),anyString(),any(Date.class));
        ReflectionTestUtils.setField(real,"committedNotices",owner);
        TeamGroupServiceImpl service=newService(cache,real,ticket(7L),100L,101L);
        BeforeStartNotifyResult result=service.notifyBeforeStartBatch();
        assertEquals(0,result.getDelivered());assertEquals(2,result.getFailed());
        assertTrue(cache.keys.isEmpty(),"UNKNOWN不能被写成已送达");
        verify(owner).beforeStart(eq(7L),eq(100L),anyString(),any(Date.class));
        verify(owner).beforeStart(eq(7L),eq(101L),anyString(),any(Date.class));
    }

    private static OmsTicket ticket(long id) {
        OmsTicket t=new OmsTicket();t.setId(id);t.setName("上海滨江10KM骑行挑战赛");
        t.setStartTime(new Date(System.currentTimeMillis()+3600000));return t;
    }

    @Test
    public void 网络未知不写去重键且下一轮只能唤醒同一持久身份() {
        FakeCache cache=new FakeCache();INotifyService notices=mock(INotifyService.class);
        when(notices.notifyBeforeStart(anyLong(),anyLong(),anyString(),any(Date.class)))
                .thenReturn(new Delivery(17L,"UNKNOWN",false));
        OmsTicket ticket=ticket(7L);TeamGroupServiceImpl service=newService(cache,notices,ticket,100L,101L);
        BeforeStartNotifyResult first=service.notifyBeforeStartBatch(),second=service.notifyBeforeStartBatch();
        assertEquals(0,first.getDelivered());assertEquals(2,first.getScanned());assertEquals(2,first.getFailed());
        assertEquals(0,second.getDelivered());assertEquals(2,second.getFailed());assertEquals(0,second.getSkipped());
        assertTrue(cache.keys.isEmpty(),"Redis没有权限把UNKNOWN改作成功或另一次尝试");
        verify(notices,times(2)).notifyBeforeStart(7L,100L,ticket.getName(),ticket.getStartTime());
        verify(notices,times(2)).notifyBeforeStart(7L,101L,ticket.getName(),ticket.getStartTime());
        // These are wake calls, not provider sends. Single external attempt is asserted with actual MySQL owner.
        org.mockito.Mockito.verify(notices,org.mockito.Mockito.never()).notifyBeforeStart(anyLong(),anyString(),anyString());
    }

    @Test
    public void 已送达人只读原回执未知人不能因下一轮恢复而重发() {
        FakeCache cache=new FakeCache();INotifyService notices=mock(INotifyService.class);
        when(notices.notifyBeforeStart(eq(7L),eq(100L),anyString(),any(Date.class)))
                .thenReturn(new Delivery(17L,"ACCEPTED",true),new Delivery(17L,"ACCEPTED",false));
        when(notices.notifyBeforeStart(eq(7L),eq(101L),anyString(),any(Date.class)))
                .thenReturn(new Delivery(18L,"UNKNOWN",false));
        TeamGroupServiceImpl service=newService(cache,notices,ticket(7L),100L,101L);
        BeforeStartNotifyResult first=service.notifyBeforeStartBatch(),second=service.notifyBeforeStartBatch();
        assertEquals(1,first.getDelivered());assertEquals(1,first.getFailed());
        assertEquals(0,second.getDelivered());assertEquals(1,second.getSkipped());assertEquals(1,second.getFailed());
        assertTrue(cache.keys.isEmpty(),"新回执永久保留在DB而非2日缓存");
        verify(notices,times(2)).notifyBeforeStart(eq(7L),eq(100L),anyString(),any(Date.class));
        verify(notices,times(2)).notifyBeforeStart(eq(7L),eq(101L),anyString(),any(Date.class));
    }

    @Test
    public void 原票乘收件人分别透传而不是整票一次() {
        FakeCache cache=new FakeCache();INotifyService notices=mock(INotifyService.class);
        when(notices.notifyBeforeStart(anyLong(),anyLong(),anyString(),any(Date.class)))
                .thenReturn(new Delivery(17L,"ACCEPTED",true));
        TeamGroupServiceImpl service=newService(cache,notices,ticket(7L),100L,101L);
        assertEquals(2,service.notifyBeforeStartBatch().getDelivered());
        verify(notices).notifyBeforeStart(eq(7L),eq(100L),anyString(),any(Date.class));
        verify(notices).notifyBeforeStart(eq(7L),eq(101L),anyString(),any(Date.class));
        assertFalse(cache.keys.contains("team:beforenotify:7"));assertEquals(0,cache.keys.size());
    }

    @Test
    public void 原计划毫秒值透传到冻结Owner而不是改用当前时间() {
        FakeCache cache=new FakeCache();INotifyService notices=mock(INotifyService.class);
        when(notices.notifyBeforeStart(anyLong(),anyLong(),anyString(),any(Date.class)))
                .thenReturn(new Delivery(17L,"ACCEPTED",true));
        OmsTicket ticket=ticket(7L);long originalStart=ticket.getStartTime().getTime();
        TeamGroupServiceImpl service=newService(cache,notices,ticket,100L);service.notifyBeforeStartBatch();
        ArgumentCaptor<Date> planned=ArgumentCaptor.forClass(Date.class);
        verify(notices).notifyBeforeStart(eq(7L),eq(100L),eq(ticket.getName()),planned.capture());
        assertEquals(originalStart,planned.getValue().getTime());
        assertTrue(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(planned.getValue()).matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}"));
    }

    @Test
    public void 明确尚未准备微信输入可在就绪后首次发送而非把未知重发() {
        FakeCache cache=new FakeCache();INotifyService notices=mock(INotifyService.class);
        when(notices.notifyBeforeStart(anyLong(),anyLong(),anyString(),any(Date.class)))
                .thenReturn(new Delivery(null,"NOT_READY",false),new Delivery(17L,"ACCEPTED",true));
        TeamGroupServiceImpl service=newService(cache,notices,ticket(7L),100L);
        assertEquals(1,service.notifyBeforeStartBatch().getFailed());
        assertEquals(1,service.notifyBeforeStartBatch().getDelivered());assertTrue(cache.keys.isEmpty());
    }

    @Test
    public void 保留旧正向成功缓存且不伪造新的持久回执或重发() {
        FakeCache cache=new FakeCache();cache.keys.add("team:beforenotify:7:100");
        INotifyService notices=mock(INotifyService.class);
        TeamGroupServiceImpl service=newService(cache,notices,ticket(7L),100L);
        BeforeStartNotifyResult result=service.notifyBeforeStartBatch();
        assertEquals(1,result.getScanned());assertEquals(1,result.getSkipped());
        assertEquals(0,result.getDelivered());assertEquals(0,result.getFailed());
        org.mockito.Mockito.verifyNoInteractions(notices);assertEquals(1,cache.keys.size());
    }
}
