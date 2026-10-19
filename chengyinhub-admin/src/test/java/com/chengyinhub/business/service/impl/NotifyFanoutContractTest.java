package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.notify.NotifyChannel;
import com.chengyinhub.business.notify.NotifyEvent;
import com.chengyinhub.business.notify.NotifyMessage;
import com.chengyinhub.business.notify.WxSubscribeChannel;
import com.chengyinhub.business.service.IWxSubscribeMsgService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知 fanout 层的契约(2026-08-20 P0-1 通道解耦)。
 *
 * 锁四条,每条都对应一个真实会咬人的失效模式:
 * ① 通道抛异常绝不穿透 —— 穿透会回滚上游 @Transactional 里已生效的退款 = 资损;
 * ② 返回值 = 「至少一条通道真的送达」,不是「调用没报错」;
 * ③ 不短路 —— 一条成功不代表其余通道可以不发(push 成功 ≠ 站内信不用写);
 * ④ 语义字段名两侧必须对上 —— NotifyServiceImpl 写 "topicName"、WxSubscribeChannel 读 "topicName",
 *    一个 typo 就会静默传空串、零报错,这是本次重构最可能引入的 bug,所以逐事件断言原样透传。
 */
public class NotifyFanoutContractTest {

    /** 只抛 Error 的通道:catch(RuntimeException) 接不住它,catch(Throwable) 才行。 */
    private static class ErrorChannel implements NotifyChannel {
        @Override
        public boolean send(NotifyMessage message) {
            throw new NoClassDefFoundError("cn/jpush/api/JPushClient");
        }
    }

    /** 可编程假通道:记录收到的消息,并按构造参数决定返回成败或抛异常。 */
    private static class FakeChannel implements NotifyChannel {
        final List<NotifyMessage> seen = new ArrayList<>();
        private final boolean result;
        private final boolean explode;

        FakeChannel(boolean result, boolean explode) {
            this.result = result;
            this.explode = explode;
        }

        @Override
        public boolean send(NotifyMessage message) {
            seen.add(message);
            if (explode) throw new IllegalStateException("通道内部炸了");
            return result;
        }
    }

    private NotifyServiceImpl serviceWith(List<NotifyChannel> channels) {
        NotifyServiceImpl svc = new NotifyServiceImpl();
        ReflectionTestUtils.setField(svc, "channels", channels);
        return svc;
    }

    // —— ① 异常不穿透 ——

    @Test
    public void 通道抛异常绝不穿透到业务事务() {
        FakeChannel boom = new FakeChannel(false, true);
        FakeChannel ok = new FakeChannel(true, false);
        NotifyServiceImpl svc = serviceWith(Arrays.asList(boom, ok));

        // 不 try/catch:一旦抛出,本测试直接 error —— 这正是要防的资损路径。
        boolean delivered = svc.notifyRefund(100L, "上海骑行", "12.00");

        assertTrue(delivered, "一条通道炸了,但另一条送达了,整体必须算送达");
        assertEquals(1, ok.seen.size(), "前一条通道炸掉不得中断后续通道");
    }

    @Test
    public void 唯一通道抛异常时返回未送达而不是抛出() {
        FakeChannel boom = new FakeChannel(false, true);
        NotifyServiceImpl svc = serviceWith(Collections.<NotifyChannel>singletonList(boom));

        assertFalse(svc.notifyRefund(100L, "上海骑行", "12.00"),
                "全炸 = 没送达,必须返回 false 让调用方能重试,而不是抛异常回滚业务");
    }

    @Test
    public void 通道抛Error同样不得穿透() {
        // P0-2 要接的 JPush SDK 缺类时抛的正是 NoClassDefFoundError。
        // 只接 RuntimeException 会让它一路穿到 RefundServiceImpl 的 @Transactional 里 = 资损。
        FakeChannel ok = new FakeChannel(true, false);
        NotifyServiceImpl svc = serviceWith(Arrays.<NotifyChannel>asList(new ErrorChannel(), ok));

        assertTrue(svc.notifyRefund(100L, "上海骑行", "12.00"),
                "Error 必须被 fanout 接住,且不影响后一条通道送达");
        assertEquals(1, ok.seen.size(), "前一条抛 Error 不得中断后续通道");
    }

    // —— ② 布尔语义 ——

    @Test
    public void 全部通道失败必须返回false供调用方重试() {
        FakeChannel a = new FakeChannel(false, false);
        FakeChannel b = new FakeChannel(false, false);
        NotifyServiceImpl svc = serviceWith(Arrays.asList(a, b));

        assertFalse(svc.notifyBeforeStart(100L, "上海骑行", "2026-08-21 09:00"),
                "全失败却返回 true ⇒ TeamGroupServiceImpl 会写去重键,24h 内永不重试 = 永久错过");
    }

    @Test
    public void 任一通道成功即算送达() {
        FakeChannel fail = new FakeChannel(false, false);
        FakeChannel ok = new FakeChannel(true, false);
        assertTrue(serviceWith(Arrays.asList(fail, ok)).notifyBeforeStart(100L, "上海骑行", "2026-08-21 09:00"));
    }

    // —— ③ 不短路 ——

    @Test
    public void 第一条成功后其余通道仍必须收到消息() {
        FakeChannel first = new FakeChannel(true, false);
        FakeChannel second = new FakeChannel(true, false);
        serviceWith(Arrays.asList(first, second)).notifySignupSuccess(100L, "上海骑行");

        assertEquals(1, first.seen.size());
        assertEquals(1, second.seen.size(),
                "短路会让站内信在 push 成功时被跳过 —— 用户关了通知就永远看不到这条");
    }

    // —— ④ 语义字段两侧对齐:逐事件断言原样透传到微信通道 ——

    private static class Fixture {
        final IWxSubscribeMsgService wx = mock(IWxSubscribeMsgService.class);
        final NotifyServiceImpl svc;

        Fixture() {
            WxSubscribeChannel channel = new WxSubscribeChannel();
            ReflectionTestUtils.setField(channel, "wx", wx);
            svc = new NotifyServiceImpl();
            ReflectionTestUtils.setField(svc, "channels", Collections.<NotifyChannel>singletonList(channel));
            ReflectionTestUtils.setField(svc, "wxChannel", channel);
        }
    }

    @Test
    public void 退款字段原样透传() {
        Fixture f = new Fixture();
        f.svc.notifyRefund(100L, "上海骑行", "12.00");
        verify(f.wx).notifyRefund(100L, "上海骑行", "12.00");
    }

    @Test
    public void 报名成功字段原样透传() {
        Fixture f = new Fixture();
        f.svc.notifySignupSuccess(100L, "上海骑行");
        verify(f.wx).notifySignupSuccess(100L, "上海骑行");
    }

    @Test
    public void 出发前提醒字段原样透传() {
        Fixture f = new Fixture();
        f.svc.notifyBeforeStart(100L, "上海骑行", "2026-08-21 09:00");
        verify(f.wx).notifyBeforeStartResult(100L, "上海骑行", "2026-08-21 09:00");
    }

    @Test
    public void 合作邀约字段原样透传() {
        Fixture f = new Fixture();
        f.svc.notifyCoopInvited(100L, "上海骑行", "老王");
        verify(f.wx).notifyCoopInvited(100L, "上海骑行", "老王");
    }

    @Test
    public void 邀约状态五个字段顺序不能错位() {
        Fixture f = new Fixture();
        f.svc.notifyCoopStatus(100L, "上海骑行", "老王", "已接受", "已进入合作");
        // 五个 String 参数相邻,错位是最容易犯又最难看出的错 —— 逐个钉死。
        verify(f.wx).notifyCoopStatus(100L, "上海骑行", "老王", "已接受", "已进入合作");
    }

    @Test
    public void 俱乐部承接意向字段原样透传() {
        Fixture f = new Fixture();
        f.svc.notifyClubInterested(100L, "上海骑行", "夜骑俱乐部");
        verify(f.wx).notifyClubInterested(100L, "上海骑行", "夜骑俱乐部");
    }

    @Test
    public void 招募字段原样透传() {
        Fixture f = new Fixture();
        f.svc.notifyRecruit(100L, "上海骑行", "2026-08-21", "招募已结束");
        verify(f.wx).notifyRecruit(100L, "上海骑行", "2026-08-21", "招募已结束");
    }

    @Test
    public void 结算五个字段顺序不能错位() {
        Fixture f = new Fixture();
        f.svc.notifyCoopSettle(7102L, "88.50", "2026-08-20 10:00", "探店日结算", "NO20260820001");
        verify(f.wx).notifyCoopSettle(7102L, "88.50", "2026-08-20 10:00", "探店日结算", "NO20260820001");
    }

    @Test
    public void 官方广播字段原样透传() {
        Fixture f = new Fixture();
        f.svc.notifyOfficialBroadcast(100L, "中秋城市漫游", "本周六开跑");
        verify(f.wx).notifyOfficialBroadcastResult(100L, "中秋城市漫游", "本周六开跑");
    }

    @Test
    public void 漫游召回的两个整数不能被字符串化丢失() {
        Fixture f = new Fixture();
        f.svc.notifyRoamRecall(100L, 3200, 7);
        // int 走的是 NotifyMessage 的 int 重载 + getInt 还原,链路比 String 长,单独钉。
        verify(f.wx).notifyRoamRecall(100L, 3200, 7);
    }

    @Test
    public void 漫游召回就绪查询转发给通道() {
        Fixture f = new Fixture();
        when(f.wx.isRoamRecallReady()).thenReturn(true);
        assertTrue(f.svc.isRoamRecallReady());
        verify(f.wx).isRoamRecallReady();
    }

    // —— 空值不得变成字面量 "null" ——

    @Test
    public void 空字段透传为空串而不是null字符串() {
        Fixture f = new Fixture();
        f.svc.notifyRefund(100L, null, null);
        // NotifyMessage.with 丢弃 null、get 兜底空串 ⇒ 通道拿到 "",不是 "null"。
        // 传 "null" 进微信模板会真的显示给用户看。
        verify(f.wx).notifyRefund(100L, "", "");
    }
}
