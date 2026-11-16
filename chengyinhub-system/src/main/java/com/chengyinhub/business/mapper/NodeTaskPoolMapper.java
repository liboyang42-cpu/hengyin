package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.NodeTaskDispatch;
import com.chengyinhub.business.domain.NodeTaskPool;
import com.chengyinhub.business.domain.NodeTaskPoolItem;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * M3 盲盒任务池及每日下发记录 Mapper。
 */
public interface NodeTaskPoolMapper {

    List<NodeTaskPool> selectPoolList(NodeTaskPool filter);

    NodeTaskPool selectPoolById(@Param("id") Long id);

    NodeTaskPool selectActivePoolByEntryNodeOrMerchant(@Param("entryNodeId") Long entryNodeId,
                                                       @Param("merchantMemberId") Long merchantMemberId,
                                                       @Param("topicId") Long topicId);

    int insertPool(NodeTaskPool pool);

    int updatePool(NodeTaskPool pool);

    int deletePoolById(@Param("id") Long id);

    List<NodeTaskPoolItem> selectItemsByPoolId(@Param("poolId") Long poolId);

    List<NodeTaskPoolItem> selectActiveItemsByPoolId(@Param("poolId") Long poolId);

    NodeTaskPoolItem selectItemById(@Param("id") Long id);

    int insertPoolItem(NodeTaskPoolItem item);

    int updatePoolItem(NodeTaskPoolItem item);

    int deletePoolItemById(@Param("id") Long id);

    int deleteItemsByPoolId(@Param("poolId") Long poolId);

    NodeTaskDispatch selectDispatchByMemberPoolDate(@Param("memberId") Long memberId,
                                                    @Param("poolId") Long poolId,
                                                    @Param("dispatchDate") String dispatchDate);

    NodeTaskDispatch selectLatestDispatchBeforeDate(@Param("memberId") Long memberId,
                                                    @Param("poolId") Long poolId,
                                                    @Param("dispatchDate") String dispatchDate);

    List<NodeTaskDispatch> selectDispatchList(NodeTaskDispatch filter);

    int insertDispatch(NodeTaskDispatch dispatch);
}
