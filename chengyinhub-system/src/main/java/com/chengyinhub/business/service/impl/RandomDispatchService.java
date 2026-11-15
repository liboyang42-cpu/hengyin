package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.NodeTaskDispatch;
import com.chengyinhub.business.domain.NodeTaskPool;
import com.chengyinhub.business.domain.NodeTaskPoolItem;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.NodeTaskPoolMapper;
import com.chengyinhub.business.service.IGameRandomService;
import com.chengyinhub.business.service.IRandomDispatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 盲盒随机任务下发实现。
 *
 * <p>特性：
 * <ul>
 *   <li>到店即从店铺 {@code node_task_pool} 抽取 1 条固定奖励任务，无转盘/概率嵌套；</li>
 *   <li>同一会员 + 任务池 + 自然日（Asia/Shanghai）严格去重；</li>
 *   <li>池中有 >=2 条启用条目时，跨日抽取优先避开上一次已下发的节点。</li>
 * </ul>
 */
@Service
public class RandomDispatchService implements IRandomDispatchService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final NodeTaskPoolMapper poolMapper;
    private final CmsTopicNodeMapper topicNodeMapper;
    private final GameRandom gameRandom;
    private final Clock clock;

    @Autowired
    public RandomDispatchService(NodeTaskPoolMapper poolMapper,
                                 CmsTopicNodeMapper topicNodeMapper,
                                 @Autowired(required = false) GameRandom gameRandom) {
        this(poolMapper, topicNodeMapper, gameRandom, Clock.system(SHANGHAI));
    }

    public RandomDispatchService(NodeTaskPoolMapper poolMapper,
                                 CmsTopicNodeMapper topicNodeMapper,
                                 GameRandom gameRandom,
                                 Clock clock) {
        this.poolMapper = poolMapper;
        this.topicNodeMapper = topicNodeMapper;
        this.gameRandom = gameRandom != null ? gameRandom : new GameRandom();
        this.clock = clock != null ? clock : Clock.system(SHANGHAI);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> dispatchForEntryNode(Long memberId, CmsTopicNode entryNode) {
        if (memberId == null || entryNode == null || entryNode.getId() == null) {
            return null;
        }
        NodeTaskPool pool = poolMapper.selectActivePoolByEntryNodeOrMerchant(
                entryNode.getId(), entryNode.getMerchantMemberId(), entryNode.getTopicId());
        if (pool == null || pool.getId() == null) {
            return null;
        }
        String today = LocalDate.now(clock.withZone(SHANGHAI)).toString();
        NodeTaskDispatch existing = poolMapper.selectDispatchByMemberPoolDate(memberId, pool.getId(), today);
        if (existing != null && existing.getNodeId() != null) {
            CmsTopicNode node = topicNodeMapper != null ? topicNodeMapper.selectCmsTopicNodeById(existing.getNodeId()) : null;
            return toPayload(pool, existing, node, null, true);
        }

        List<NodeTaskPoolItem> activeItems = poolMapper.selectActiveItemsByPoolId(pool.getId());
        if (activeItems == null || activeItems.isEmpty()) {
            return null;
        }

        List<NodeTaskPoolItem> candidates = activeItems;
        if (activeItems.size() > 1) {
            NodeTaskDispatch previous = poolMapper.selectLatestDispatchBeforeDate(memberId, pool.getId(), today);
            if (previous != null && previous.getNodeId() != null) {
                List<NodeTaskPoolItem> filtered = new ArrayList<>(activeItems.size());
                for (NodeTaskPoolItem item : activeItems) {
                    if (!previous.getNodeId().equals(item.getNodeId())) {
                        filtered.add(item);
                    }
                }
                if (!filtered.isEmpty()) {
                    candidates = filtered;
                }
            }
        }

        String receiptId = "dispatch:" + pool.getId() + ":" + memberId + ":" + today;
        IGameRandomService.PickResult<NodeTaskPoolItem> pick = gameRandom.weightedPick(
                IGameRandomService.DOMAIN_TASK_DISPATCH,
                candidates,
                item -> item.getWeight() == null ? 0 : Math.max(0, item.getWeight()),
                receiptId,
                memberId,
                "node_task_pool",
                String.valueOf(pool.getId()),
                item -> String.valueOf(item.getNodeId()));
        if (pick == null || pick.item() == null || pick.item().getNodeId() == null) {
            return null;
        }

        NodeTaskPoolItem picked = pick.item();
        NodeTaskDispatch dispatch = new NodeTaskDispatch();
        dispatch.setMemberId(memberId);
        dispatch.setPoolId(pool.getId());
        dispatch.setDispatchDate(today);
        dispatch.setNodeId(picked.getNodeId());
        dispatch.setRandomReceiptId(pick.draw().receiptId());
        try {
            poolMapper.insertDispatch(dispatch);
        } catch (DuplicateKeyException dup) {
            NodeTaskDispatch replay = poolMapper.selectDispatchByMemberPoolDate(memberId, pool.getId(), today);
            if (replay != null && replay.getNodeId() != null) {
                CmsTopicNode node = topicNodeMapper != null ? topicNodeMapper.selectCmsTopicNodeById(replay.getNodeId()) : null;
                return toPayload(pool, replay, node, picked.getNodeName(), true);
            }
            throw dup;
        }

        CmsTopicNode taskNode = topicNodeMapper != null ? topicNodeMapper.selectCmsTopicNodeById(picked.getNodeId()) : null;
        return toPayload(pool, dispatch, taskNode, picked.getNodeName(), false);
    }

    @Override
    public Map<String, Object> findTodayDispatch(Long memberId, Long entryNodeId) {
        if (memberId == null || entryNodeId == null || topicNodeMapper == null) {
            return null;
        }
        CmsTopicNode entryNode = topicNodeMapper.selectCmsTopicNodeById(entryNodeId);
        if (entryNode == null) {
            return null;
        }
        NodeTaskPool pool = poolMapper.selectActivePoolByEntryNodeOrMerchant(
                entryNode.getId(), entryNode.getMerchantMemberId(), entryNode.getTopicId());
        if (pool == null || pool.getId() == null) {
            return null;
        }
        String today = LocalDate.now(clock.withZone(SHANGHAI)).toString();
        NodeTaskDispatch existing = poolMapper.selectDispatchByMemberPoolDate(memberId, pool.getId(), today);
        if (existing == null || existing.getNodeId() == null) {
            return null;
        }
        CmsTopicNode taskNode = topicNodeMapper.selectCmsTopicNodeById(existing.getNodeId());
        return toPayload(pool, existing, taskNode, null, true);
    }

    @Override
    public List<NodeTaskPool> selectPoolList(NodeTaskPool filter) {
        return poolMapper.selectPoolList(filter == null ? new NodeTaskPool() : filter);
    }

    @Override
    public NodeTaskPool selectPoolById(Long id) {
        return id == null ? null : poolMapper.selectPoolById(id);
    }

    @Override
    public NodeTaskPool savePool(NodeTaskPool pool) {
        if (pool == null || pool.getPoolName() == null || pool.getPoolName().isBlank()) {
            throw new IllegalArgumentException("poolName 不能为空");
        }
        pool.setPoolName(pool.getPoolName().trim());
        if (pool.getStatus() == null) {
            pool.setStatus(1);
        }
        if (pool.getId() == null) {
            poolMapper.insertPool(pool);
        } else {
            poolMapper.updatePool(pool);
        }
        return poolMapper.selectPoolById(pool.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deletePoolById(Long id) {
        if (id == null) {
            return 0;
        }
        poolMapper.deleteItemsByPoolId(id);
        return poolMapper.deletePoolById(id);
    }

    @Override
    public List<NodeTaskPoolItem> selectItemsByPoolId(Long poolId) {
        if (poolId == null) {
            return List.of();
        }
        return poolMapper.selectItemsByPoolId(poolId);
    }

    @Override
    public NodeTaskPoolItem savePoolItem(NodeTaskPoolItem item) {
        if (item == null || item.getPoolId() == null || item.getNodeId() == null) {
            throw new IllegalArgumentException("poolId 与 nodeId 不能为空");
        }
        if (item.getWeight() == null || item.getWeight() <= 0) {
            throw new IllegalArgumentException("weight 必须大于 0");
        }
        if (item.getStatus() == null) {
            item.setStatus(1);
        }
        if (item.getId() == null) {
            poolMapper.insertPoolItem(item);
        } else {
            poolMapper.updatePoolItem(item);
        }
        return poolMapper.selectItemById(item.getId());
    }

    @Override
    public int deletePoolItemById(Long id) {
        return id == null ? 0 : poolMapper.deletePoolItemById(id);
    }

    @Override
    public List<NodeTaskDispatch> selectDispatchList(NodeTaskDispatch filter) {
        return poolMapper.selectDispatchList(filter == null ? new NodeTaskDispatch() : filter);
    }

    private Map<String, Object> toPayload(NodeTaskPool pool,
                                          NodeTaskDispatch dispatch,
                                          CmsTopicNode taskNode,
                                          String fallbackNodeName,
                                          boolean alreadyDispatched) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("poolId", pool.getId());
        m.put("poolName", pool.getPoolName());
        m.put("nodeId", dispatch.getNodeId());
        String resolvedName = firstNonBlank(taskNode == null ? null : taskNode.getName(), fallbackNodeName);
        if (resolvedName != null) {
            m.put("nodeName", resolvedName);
        }
        m.put("dispatchDate", dispatch.getDispatchDate());
        if (taskNode != null) {
            String ruleText = firstNonBlank(taskNode.getHookText(), taskNode.getDescription());
            if (ruleText != null) {
                m.put("rule", ruleText);
            }
            if (taskNode.getXpValue() != null) {
                m.put("xpValue", taskNode.getXpValue());
            }
        }
        if (dispatch.getRandomReceiptId() != null) {
            m.put("randomReceiptId", dispatch.getRandomReceiptId());
        }
        m.put("alreadyDispatched", alreadyDispatched);
        return m;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }
}
