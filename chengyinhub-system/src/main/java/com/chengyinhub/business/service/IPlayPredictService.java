package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.PlayPredictResult;

import java.util.Date;
import java.util.List;
import java.util.Map;

/** 竞猜结算。下注在 AdvancedGameRuntimeService 里,这里只管商家侧的结算与超时兜底。 */
public interface IPlayPredictService {

    /**
     * 商家给竞猜答案的宽限小时数:超过它仍未结算的轮次由 {@code PredictVoidExpiredTask} 作废。
     *
     * <p>作废任务、结算接口的「还剩几天」、商家待办汇总三处必须是同一个值 ——
     * 各写一个字面量,改口径时必然只改一半。
     */
    int ANSWER_GRACE_HOURS = 48;

    /**
     * 商家给出正确答案并结算这一轮。
     *
     * @param nodeId        竞猜所在节点
     * @param playDay       轮次日期
     * @param settledOption 正确选项;必须是配置里存在的 key
     * @param operatorId    谁结的,记进 settled_by 备查
     * @return 发出去的奖的摘要
     * @throws IllegalArgumentException 选项不存在、节点不是竞猜、轮次不存在
     * @throws IllegalStateException    这一轮已经结过了
     */
    Map<String, Object> settle(Long nodeId, Date playDay, String settledOption, Long operatorId);

    /**
     * 超时兜底:把过了截止仍未结算的轮次作废。
     *
     * <p>作废不发奖 —— 奖是商家配的券,平台不能替商家发。玩家的核心收益
     * (节点通关、探索值)在下注那一刻已经拿到。
     *
     * @param graceHours 过了截止多少小时才作废
     * @return 作废了几轮
     */
    int voidExpiredRounds(int graceHours);

    /** 商家待办:这些节点下还没结算的轮次。 */
    List<PlayPredictResult> pendingRounds(List<Long> nodeIds);

    /**
     * 商家待办(全量):这个商家承接的**所有**点位上、还等着他给答案的轮次。
     *
     * <p>与 {@link #pendingRounds(List)} 的差别不只是范围,还有内容:那一个只回轮次本身
     * (节点 id + 日期),而商家要给答案得看见题面和选项,还要知道再不给就要作废了。
     * 这些散在节点、模板、下注三张表里,拼装放在服务层,别让页面自己去凑。
     *
     * @param ownerMemberId 商家主体的 memberId
     * @param graceHours    超过截止多少小时作废 —— 用来算「还剩几天」,与兜底任务同一个值
     */
    List<Map<String, Object>> pendingInbox(Long ownerMemberId, int graceHours);
}
