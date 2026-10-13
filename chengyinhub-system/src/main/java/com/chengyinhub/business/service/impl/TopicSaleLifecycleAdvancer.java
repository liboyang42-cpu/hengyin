package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.ITopicLifecycleService;
import com.chengyinhub.business.service.support.TopicSaleCatalogWindows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;

/** 按冻结售卖目录的最大售止/履约结束推进商业阶段。 */
@Service
public class TopicSaleLifecycleAdvancer {

    private final CmsTopicMapper topicMapper;
    private final OmsTicketMapper ticketMapper;
    private static final Logger logger = LoggerFactory.getLogger(TopicSaleLifecycleAdvancer.class);

    private final ITopicLifecycleService lifecycleService;
    private final TransactionTemplate transactionTemplate;

    public TopicSaleLifecycleAdvancer(CmsTopicMapper topicMapper, OmsTicketMapper ticketMapper,
                                      ITopicLifecycleService lifecycleService,
                                     PlatformTransactionManager transactionManager) {
        this.topicMapper = topicMapper;
        this.ticketMapper = ticketMapper;
        this.lifecycleService = lifecycleService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * ★ 2026-08-28:方法级 @Transactional → 逐条 TransactionTemplate。
     *
     * 生产实证:sys-error.log 里每次触发都抛
     *   IllegalStateException: topic.startDate 与 topic.endDate 必须同时存在
     * (自玩 topic 且 startDate/endDate 为空 ⇒ 走 FulfillmentWindowResolver.resolve(topic, null))。
     * 原来整个方法一个事务、循环里没有逐条隔离,后果有两层:
     *   ① 第一条脏数据抛出 ⇒ 后面所有 topic 都推进不到(整批停摆);
     *   ② 就算不抛,任何一条失败都会把【前面已成功的状态推进一起回滚】。
     *
     * 孪生方法 CmsTopicServiceImpl.processRecruitDeadlines() 早就是逐条事务 + poisoned 计数 +
     * continue —— 同一个缺陷修了一处、漏了这里。本次照抄那个已验证的形态。
     *
     * 顺带:逐条事务也把 selectCmsTopicByIdForUpdate 的行锁范围从「整批」收窄到「单条」。
     */
    public int advance(Date now) {
        if (now == null) throw new IllegalArgumentException("now 不能为空");
        int advanced = 0;
        int poisoned = 0;
        List<CmsTopic> candidates = topicMapper.selectTopicsForSaleLifecycleAdvance();
        if (candidates == null) return 0;
        for (CmsTopic candidate : candidates) {
            if (candidate == null || candidate.getId() == null) continue;
            // 自玩主题没有票务场次，SELLING 仅代表可购买，不参与按场次时间推进。
            if (Integer.valueOf(1).equals(candidate.getSelfPlay())) continue;
            Integer moved;
            try {
                moved = transactionTemplate.execute(status -> advanceOne(candidate.getId(), now));
            } catch (RuntimeException e) {
                // ★ 就地隔离:一条脏数据不该让整批停摆,也不该回滚前面已成功的推进。
                poisoned++;
                logger.error("时间轴推进-主题 {} 处理失败,已跳过该主题继续扫描:{}",
                        candidate.getId(), e.toString());
                continue;
            }
            if (moved != null) advanced += moved;
        }
        if (poisoned > 0) {
            logger.warn("时间轴推进完成:推进 {},跳过脏数据 {} 条 —— 脏数据要单独查,不能只看 advanced",
                    advanced, poisoned);
        }
        return advanced;
    }

    /** 单条推进,跑在自己的事务里。返回本条推进了几次状态。 */
    private int advanceOne(Long topicId, Date now) {
        int advanced = 0;
        CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || topic.getLifecycle() == null) return 0;
        OmsTicket query = new OmsTicket();
        query.setOwnerType(1L);
        query.setOwnerId(topic.getId());
        TopicSaleCatalogWindows.Windows windows = TopicSaleCatalogWindows.resolve(topic,
                ticketMapper.selectOmsTicketList(query));
        if (Integer.valueOf(TopicLifecycleState.SELLING.getCode()).equals(topic.getLifecycle())
                && !now.before(windows.getEffectiveSaleEndAt())) {
            if (lifecycleService.transition(topic.getId(), TopicLifecycleState.SELLING, TopicLifecycleState.RUNNING)) {
                advanced++;
                topic.setLifecycle(TopicLifecycleState.RUNNING.getCode());
            }
        }
        if (Integer.valueOf(TopicLifecycleState.RUNNING.getCode()).equals(topic.getLifecycle())
                && !now.before(windows.getEffectiveFulfillmentEndAt())
                && lifecycleService.transition(topic.getId(), TopicLifecycleState.RUNNING, TopicLifecycleState.SETTLING)) {
            advanced++;
        }
        return advanced;
    }
}
