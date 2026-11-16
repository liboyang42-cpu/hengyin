package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.NodeTaskDispatch;
import com.chengyinhub.business.domain.NodeTaskPool;
import com.chengyinhub.business.domain.NodeTaskPoolItem;

import java.util.List;
import java.util.Map;

/**
 * M3 盲盒随机任务下发与任务池管理服务。
 */
public interface IRandomDispatchService {

    /**
     * 到店扫码 / 签到命中任务池时，从池中按权重下发 1 条当日任务（按人/池/日去重，固定奖励，无转盘）。
     * 未配置可用任务池时返回 {@code null}。
     */
    Map<String, Object> dispatchForEntryNode(Long memberId, CmsTopicNode entryNode);

    /**
     * 只读查询会员当日在指定入口门店已领取的盲盒任务；未领取返回 {@code null}。
     */
    Map<String, Object> findTodayDispatch(Long memberId, Long entryNodeId);

    List<NodeTaskPool> selectPoolList(NodeTaskPool filter);

    NodeTaskPool selectPoolById(Long id);

    NodeTaskPool savePool(NodeTaskPool pool);

    int deletePoolById(Long id);

    List<NodeTaskPoolItem> selectItemsByPoolId(Long poolId);

    NodeTaskPoolItem savePoolItem(NodeTaskPoolItem item);

    int deletePoolItemById(Long id);

    List<NodeTaskDispatch> selectDispatchList(NodeTaskDispatch filter);
}
