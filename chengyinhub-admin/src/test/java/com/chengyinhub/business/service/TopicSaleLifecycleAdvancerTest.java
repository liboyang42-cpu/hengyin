package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.TopicSaleLifecycleAdvancer;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 售罄不改变最大时刻；相等边界才推进生命周期。 */
class TopicSaleLifecycleAdvancerTest {

    @Test
    void advancesAtEqualSaleAndFulfillmentBoundaries() {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        ITopicLifecycleService lifecycleService = mock(ITopicLifecycleService.class);
        TopicSaleLifecycleAdvancer advancer = new TopicSaleLifecycleAdvancer(topicMapper, ticketMapper, lifecycleService, txm());
        CmsTopic selling = topic(TopicLifecycleState.SELLING.getCode());
        when(topicMapper.selectTopicsForSaleLifecycleAdvance()).thenReturn(Collections.singletonList(selling));
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(selling);
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Collections.singletonList(ticket()));
        when(lifecycleService.transition(9L, TopicLifecycleState.SELLING, TopicLifecycleState.RUNNING)).thenReturn(true);
        when(lifecycleService.transition(9L, TopicLifecycleState.RUNNING, TopicLifecycleState.SETTLING)).thenReturn(true);

        int advanced = advancer.advance(Date.from(Instant.parse("2026-08-13T00:00:00Z")));

        assertEquals(2, advanced, "两个半开区间边界相等时必须连贯推进");
        verify(lifecycleService).transition(9L, TopicLifecycleState.SELLING, TopicLifecycleState.RUNNING);
        verify(lifecycleService).transition(9L, TopicLifecycleState.RUNNING, TopicLifecycleState.SETTLING);
    }

    @Test
    void soldOutTicketRemainsInCatalogAndDoesNotAdvanceBeforeSaleEnd() {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        ITopicLifecycleService lifecycleService = mock(ITopicLifecycleService.class);
        TopicSaleLifecycleAdvancer advancer = new TopicSaleLifecycleAdvancer(topicMapper, ticketMapper, lifecycleService, txm());
        CmsTopic selling = topic(TopicLifecycleState.SELLING.getCode());
        OmsTicket soldOut = ticket();
        soldOut.setRemainingInventory(0L);
        when(topicMapper.selectTopicsForSaleLifecycleAdvance()).thenReturn(Collections.singletonList(selling));
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(selling);
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Collections.singletonList(soldOut));

        int advanced = advancer.advance(Date.from(Instant.parse("2026-08-11T23:59:59Z")));

        assertEquals(0, advanced, "售罄只能拒购买，不能把冻结目录提前移出生命周期计算");
        verify(lifecycleService, never()).transition(eq(9L), any(TopicLifecycleState.class), any(TopicLifecycleState.class));
    }

    @Test
    void selfPlayTopicDoesNotParticipateInScheduledSaleLifecycle() {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        ITopicLifecycleService lifecycleService = mock(ITopicLifecycleService.class);
        TopicSaleLifecycleAdvancer advancer = new TopicSaleLifecycleAdvancer(
                topicMapper, ticketMapper, lifecycleService, txm());
        CmsTopic selfPlay = topic(TopicLifecycleState.SELLING.getCode());
        selfPlay.setSelfPlay(1);
        selfPlay.setStartDate(null);
        selfPlay.setEndDate(null);
        when(topicMapper.selectTopicsForSaleLifecycleAdvance())
                .thenReturn(Collections.singletonList(selfPlay));

        assertEquals(0, advancer.advance(new Date()));

        verify(topicMapper, never()).selectCmsTopicByIdForUpdate(any());
        verify(ticketMapper, never()).selectOmsTicketList(any());
        verify(lifecycleService, never()).transition(any(), any(), any());
    }

    private static CmsTopic topic(int lifecycle) {
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setLifecycle(lifecycle);
        topic.setProductType(ProductType.ORIENTEERING);
        topic.setSaleEndAt(Date.from(Instant.parse("2026-08-12T00:00:00Z")));
        topic.setStartDate(Date.from(Instant.parse("2026-08-12T00:00:00Z")));
        topic.setEndDate(Date.from(Instant.parse("2026-08-12T00:00:00Z")));
        return topic;
    }

    /**
     * ★ 生产实证(2026-08-28):一条脏 topic 让整批时间轴推进停摆。
     *
     * sys-error.log 里每次触发都有:
     *   IllegalStateException: topic.startDate 与 topic.endDate 必须同时存在
     *   at TopicRecruitDeadlineTask.run(TopicRecruitDeadlineTask.java:28)
     *
     * 抛点是 FulfillmentWindowResolver:29,经 TopicSaleCatalogWindows.resolve() 进来。
     * advance() 的循环【没有逐条隔离】⇒ 第一条脏数据抛出后,后面所有 topic 都推进不到。
     *
     * 对照:孪生方法 CmsTopicServiceImpl.processRecruitDeadlines() 早就修过同一个形态 ——
     * 逐条 TransactionTemplate + poisoned 计数 + continue。本 case 把那个形态钉到这里。
     */
    @Test
    void 一条脏topic不该让整批推进停摆() {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        ITopicLifecycleService lifecycleService = mock(ITopicLifecycleService.class);
        TopicSaleLifecycleAdvancer advancer = new TopicSaleLifecycleAdvancer(topicMapper, ticketMapper, lifecycleService, txm());

        // 脏的排在前面。生产上的形态是【自玩 topic + startDate/endDate 为空】——
        // selfPlay=1 会走 FulfillmentWindowResolver.resolve(topic, null)(ticket 传 null),
        // 于是落到 topic.startDate 那条检查上抛出。
        // ⚠️ 这个形态是跑了一遍测试才找对的:第一版只置空日期、不设 selfPlay,
        //    结果先撞上「冻结售卖目录没有 active sellable SKU」,复现的不是生产那个异常。
        CmsTopic poisoned = topic(TopicLifecycleState.SELLING.getCode());
        poisoned.setId(1L);
        poisoned.setSelfPlay(1);
        poisoned.setSelfPlayPrice(BigDecimal.ZERO);
        poisoned.setStartDate(null);
        poisoned.setEndDate(null);
        // 干净的排在后面:它【必须】仍被处理
        CmsTopic healthy = topic(TopicLifecycleState.SELLING.getCode());

        when(topicMapper.selectTopicsForSaleLifecycleAdvance())
                .thenReturn(java.util.Arrays.asList(poisoned, healthy));
        when(topicMapper.selectCmsTopicByIdForUpdate(1L)).thenReturn(poisoned);
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(healthy);
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.singletonList(ticket()));
        when(lifecycleService.transition(9L, TopicLifecycleState.SELLING, TopicLifecycleState.RUNNING)).thenReturn(true);
        when(lifecycleService.transition(9L, TopicLifecycleState.RUNNING, TopicLifecycleState.SETTLING)).thenReturn(true);

        int advanced = advancer.advance(Date.from(Instant.parse("2026-08-13T00:00:00Z")));

        // ★ 判据:脏的被跳过,干净的照常推进 —— 不是「整批抛出去」
        assertEquals(2, advanced, "脏 topic 之后的健康 topic 必须仍被推进(当前生产是整批停摆)");
        verify(lifecycleService).transition(9L, TopicLifecycleState.SELLING, TopicLifecycleState.RUNNING);
        verify(lifecycleService).transition(9L, TopicLifecycleState.RUNNING, TopicLifecycleState.SETTLING);
        // 脏的那条一次状态都不许动
        verify(lifecycleService, never()).transition(eq(1L), any(), any());
    }


    /** 单测用的假事务管理器:直接执行,不真的开事务 —— 本类的判据是【隔离行为】,不是事务语义。 */
    private static PlatformTransactionManager txm() {
        PlatformTransactionManager m = mock(PlatformTransactionManager.class);
        when(m.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        return m;
    }

    private static OmsTicket ticket() {
        OmsTicket ticket = new OmsTicket();
        ticket.setPrice(BigDecimal.ZERO);
        ticket.setTotalInventory(10L);
        ticket.setRemainingInventory(10L);
        ticket.setStartTime(Date.from(Instant.parse("2026-08-12T00:00:00Z")));
        ticket.setEndTime(Date.from(Instant.parse("2026-08-13T00:00:00Z")));
        ticket.setDelFlag(0);
        return ticket;
    }
}
