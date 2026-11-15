package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.NodeTaskDispatch;
import com.chengyinhub.business.domain.NodeTaskPool;
import com.chengyinhub.business.domain.NodeTaskPoolItem;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.NodeTaskPoolMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RandomDispatchServiceTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    @Test
    void dispatchesOncePerDayAndAvoidsRepeatNextDay() {
        InMemoryPoolMapper poolMapper = new InMemoryPoolMapper();
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);

        NodeTaskPool pool = new NodeTaskPool();
        pool.setId(10L);
        pool.setPoolName("风物咖啡盲盒池");
        pool.setEntryNodeId(100L);
        pool.setStatus(1);
        poolMapper.pools.add(pool);

        NodeTaskPoolItem item1 = item(1L, 10L, 101L, "手冲闻香", 100);
        NodeTaskPoolItem item2 = item(2L, 10L, 102L, "咖啡拉花打卡", 100);
        poolMapper.items.add(item1);
        poolMapper.items.add(item2);

        CmsTopicNode entry = node(100L, "入口点", 10);
        CmsTopicNode taskA = node(101L, "手冲闻香", 30);
        CmsTopicNode taskB = node(102L, "咖啡拉花打卡", 30);
        when(nodeMapper.selectCmsTopicNodeById(100L)).thenReturn(entry);
        when(nodeMapper.selectCmsTopicNodeById(101L)).thenReturn(taskA);
        when(nodeMapper.selectCmsTopicNodeById(102L)).thenReturn(taskB);

        Clock day1Clock = Clock.fixed(Instant.parse("2026-11-23T04:00:00Z"), SH);
        RandomDispatchService day1Service = new RandomDispatchService(poolMapper, nodeMapper, new GameRandom(42L), day1Clock);

        Map<String, Object> first = day1Service.dispatchForEntryNode(88L, entry);
        assertNotNull(first);
        assertEquals(10L, first.get("poolId"));
        assertEquals("2026-11-23", first.get("dispatchDate"));
        assertEquals(false, first.get("alreadyDispatched"));
        Long pickedDay1 = ((Number) first.get("nodeId")).longValue();

        // 同日再次进入不重新抽取
        Map<String, Object> repeat = day1Service.dispatchForEntryNode(88L, entry);
        assertNotNull(repeat);
        assertEquals(pickedDay1, ((Number) repeat.get("nodeId")).longValue());
        assertEquals(true, repeat.get("alreadyDispatched"));

        // 次日进入避开昨日已下发节点
        Clock day2Clock = Clock.fixed(Instant.parse("2026-11-24T04:00:00Z"), SH);
        RandomDispatchService day2Service = new RandomDispatchService(poolMapper, nodeMapper, new GameRandom(42L), day2Clock);
        Map<String, Object> day2 = day2Service.dispatchForEntryNode(88L, entry);
        assertNotNull(day2);
        assertEquals("2026-11-24", day2.get("dispatchDate"));
        assertEquals(false, day2.get("alreadyDispatched"));
        assertNotEquals(pickedDay1, ((Number) day2.get("nodeId")).longValue());
    }

    @Test
    void returnsNullWhenNoActivePoolMatches() {
        InMemoryPoolMapper poolMapper = new InMemoryPoolMapper();
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        RandomDispatchService service = new RandomDispatchService(poolMapper, nodeMapper, new GameRandom(1L));

        CmsTopicNode entry = node(999L, "普通节点", 20);
        assertNull(service.dispatchForEntryNode(88L, entry));
    }

    private static NodeTaskPoolItem item(long id, long poolId, long nodeId, String name, int weight) {
        NodeTaskPoolItem i = new NodeTaskPoolItem();
        i.setId(id);
        i.setPoolId(poolId);
        i.setNodeId(nodeId);
        i.setNodeName(name);
        i.setWeight(weight);
        i.setStatus(1);
        return i;
    }

    private static CmsTopicNode node(long id, String name, int xp) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setName(name);
        n.setXpValue(xp);
        n.setHookText("到店完成 " + name);
        return n;
    }

    private static final class InMemoryPoolMapper implements NodeTaskPoolMapper {
        final List<NodeTaskPool> pools = new ArrayList<>();
        final List<NodeTaskPoolItem> items = new ArrayList<>();
        final List<NodeTaskDispatch> dispatches = new ArrayList<>();

        @Override public List<NodeTaskPool> selectPoolList(NodeTaskPool filter) { return List.copyOf(pools); }
        @Override public NodeTaskPool selectPoolById(Long id) {
            return pools.stream().filter(p -> id.equals(p.getId())).findFirst().orElse(null);
        }
        @Override public NodeTaskPool selectActivePoolByEntryNodeOrMerchant(Long entryNodeId, Long merchantMemberId, Long topicId) {
            return pools.stream()
                    .filter(p -> Integer.valueOf(1).equals(p.getStatus()))
                    .filter(p -> (entryNodeId != null && entryNodeId.equals(p.getEntryNodeId()))
                            || (merchantMemberId != null && merchantMemberId.equals(p.getMerchantMemberId()))
                            || (topicId != null && topicId.equals(p.getTopicId())))
                    .findFirst().orElse(null);
        }
        @Override public int insertPool(NodeTaskPool pool) { pools.add(pool); return 1; }
        @Override public int updatePool(NodeTaskPool pool) { return 1; }
        @Override public int deletePoolById(Long id) { return 1; }
        @Override public List<NodeTaskPoolItem> selectItemsByPoolId(Long poolId) {
            return items.stream().filter(i -> poolId.equals(i.getPoolId())).toList();
        }
        @Override public List<NodeTaskPoolItem> selectActiveItemsByPoolId(Long poolId) {
            return items.stream().filter(i -> poolId.equals(i.getPoolId()) && Integer.valueOf(1).equals(i.getStatus())).toList();
        }
        @Override public NodeTaskPoolItem selectItemById(Long id) {
            return items.stream().filter(i -> id.equals(i.getId())).findFirst().orElse(null);
        }
        @Override public int insertPoolItem(NodeTaskPoolItem item) { items.add(item); return 1; }
        @Override public int updatePoolItem(NodeTaskPoolItem item) { return 1; }
        @Override public int deletePoolItemById(Long id) { return 1; }
        @Override public int deleteItemsByPoolId(Long poolId) { return 1; }
        @Override public NodeTaskDispatch selectDispatchByMemberPoolDate(Long memberId, Long poolId, String dispatchDate) {
            return dispatches.stream()
                    .filter(d -> memberId.equals(d.getMemberId()) && poolId.equals(d.getPoolId()) && dispatchDate.equals(d.getDispatchDate()))
                    .findFirst().orElse(null);
        }
        @Override public NodeTaskDispatch selectLatestDispatchBeforeDate(Long memberId, Long poolId, String dispatchDate) {
            return dispatches.stream()
                    .filter(d -> memberId.equals(d.getMemberId()) && poolId.equals(d.getPoolId()) && d.getDispatchDate().compareTo(dispatchDate) < 0)
                    .reduce((a, b) -> b).orElse(null);
        }
        @Override public List<NodeTaskDispatch> selectDispatchList(NodeTaskDispatch filter) { return List.copyOf(dispatches); }
        @Override public int insertDispatch(NodeTaskDispatch dispatch) {
            if (selectDispatchByMemberPoolDate(dispatch.getMemberId(), dispatch.getPoolId(), dispatch.getDispatchDate()) != null) {
                throw new DuplicateKeyException("dup");
            }
            dispatch.setId((long) (dispatches.size() + 1));
            dispatches.add(dispatch);
            return 1;
        }
    }
}
