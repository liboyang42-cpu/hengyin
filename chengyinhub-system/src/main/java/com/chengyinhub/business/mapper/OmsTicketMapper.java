package com.chengyinhub.business.mapper;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.vo.ActivityShellTicketInventoryVO;

/**
 * 主题/活动票务Mapper接口
 * 
 * @author webxh
 * @date 2025-09-02
 */
public interface OmsTicketMapper 
{
    /**
     * 查询主题/活动票务
     * 
     * @param id 主题/活动票务主键
     * @return 主题/活动票务
     */
    public OmsTicket selectOmsTicketById(Long id);

    /**
     * 锁定票务行后读取，供部分字段更新时拼接有效时间窗口和库存约束。
     * 调用方必须处于事务内。
     */
    public OmsTicket selectOmsTicketByIdForUpdate(Long id);

    public BigDecimal selectMinTicketPrice(OmsTicket omsTicket);

    public List<Map<String, Object>> selectMinTicketPrices(@Param("ownerType") Long ownerType,
                                                           @Param("ownerIds") List<Long> ownerIds);

    /** 探店日公开主题票卡对应的活动壳余席；返回 activityId 供 service 对多壳整体判歧义。 */
    public List<ActivityShellTicketInventoryVO> selectActivityShellTicketRemainingByTopic(
            @Param("topicId") Long topicId);



    /**
     * 查询主题/活动票务列表
     * 
     * @param omsTicket 主题/活动票务
     * @return 主题/活动票务集合
     */
    public List<OmsTicket> selectOmsTicketList(OmsTicket omsTicket);

    /** 出发前提醒:start_time 在 [from,to] 窗口内的经典定向(mode=1)票种 */
    public List<OmsTicket> selectTeamFormedStartingSoon(@Param("from") Date from, @Param("to") Date to);

    /**
     * 新增主题/活动票务
     * 
     * @param omsTicket 主题/活动票务
     * @return 结果
     */
    public int insertOmsTicket(OmsTicket omsTicket);

    /**
     * 修改主题/活动票务
     * 
     * @param omsTicket 主题/活动票务
     * @return 结果
     */
    public int updateOmsTicket(OmsTicket omsTicket);

    /**
     * 主题编辑整体重建时的主题票原地更新：保住 id（活动壳票的 source_ticket_id 钉在它上面），
     * 编辑器拥有的列一律无条件写入（清空的字段要真清空），并把归属判定放进 where。
     *
     * @return 1 = 命中；0 = 该行已不属于本主题/已删/库存被并发改动，调用方必须 fail-closed
     */
    public int updateTopicTicketOnRebuild(OmsTicket omsTicket);

    /**
     * 删除主题/活动票务
     * 
     * @param id 主题/活动票务主键
     * @return 结果
     */
    public int deleteOmsTicketById(Long id);

    /** 仅在活动壳孤儿票仍完整未售时物理删除；返回 0 表示库存或归属已变化。 */
    public int deleteUnsoldActivityOrphanTicket(@Param("id") Long id,
                                                @Param("activityId") Long activityId,
                                                @Param("sourceTicketId") Long sourceTicketId);

    /**
     * 批量删除主题/活动票务
     *
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteOmsTicketByIds(Long[] ids);

    /**
     * 原子扣减剩余库存(remaining_inventory >= qty 才扣,返回 0 = 库存不足)
     *
     * @param id  票 ID
     * @param qty 扣减张数
     * @return 影响行数
     */
    public int deductInventory(@Param("id") Long id, @Param("qty") Long qty);

    /**
     * 原子扣减非成员可售库存：成员保留名额不可被普通报名占用。
     *
     * @return 影响行数，0 表示剩余库存仅够会员保留名额
     */
    public int deductInventoryKeepingReserved(@Param("id") Long id, @Param("qty") Long qty,
                                              @Param("reservedQuota") Integer reservedQuota);

    /**
     * 原子恢复剩余库存(取消/关单回补)
     *
     * @param id  票 ID
     * @param qty 恢复张数
     * @return 影响行数
     */
    public int restoreInventory(@Param("id") Long id, @Param("qty") Long qty);
}
