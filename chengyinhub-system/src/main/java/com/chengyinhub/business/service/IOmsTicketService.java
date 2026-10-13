package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.vo.PublicTicketVO;

/**
 * 主题/活动票务Service接口
 * 
 * @author webxh
 * @date 2025-09-02
 */
public interface IOmsTicketService 
{
    /**
     * 查询主题/活动票务
     * 
     * @param id 主题/活动票务主键
     * @return 主题/活动票务
     */
    public OmsTicket selectOmsTicketById(Long id);

    public BigDecimal selectMinTicketPrice(OmsTicket omsTicket);

    public List<Map<String, Object>> selectMinTicketPrices(Long ownerType, List<Long> ownerIds);

    /** 用活动壳真库存覆盖探店日公开票卡。 */
    public void applyActivityShellRemaining(Long topicId, List<PublicTicketVO> tickets);

    /**
     * 查询主题/活动票务列表
     * 
     * @param omsTicket 主题/活动票务
     * @return 主题/活动票务集合
     */
    public List<OmsTicket> selectOmsTicketList(OmsTicket omsTicket);

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
     * 批量删除主题/活动票务
     * 
     * @param ids 需要删除的主题/活动票务主键集合
     * @return 结果
     */
    public int deleteOmsTicketByIds(Long[] ids);

    /**
     * 删除主题/活动票务信息
     * 
     * @param id 主题/活动票务主键
     * @return 结果
     */
    public int deleteOmsTicketById(Long id);

    /**
     * 把主题票(ownerType=1)复制为某活动的活动票(ownerType=2)，重置剩余库存。
     * 自办主题发布与进池调配中标生成活动后调用，否则活动无票可售。
     *
     * @param topicId    源主题id
     * @param activityId 目标活动id
     * @param memberId   票务归属(活动承接方)
     * @return 复制的票数
     */
    public int copyTopicTicketsToActivity(Long topicId, Long activityId, Long memberId);

    /** 刷新主题票面到既有活动壳，按 sourceTicketId 配对并保持已售库存。 */
    public int syncTopicTicketsToActivity(Long topicId, Long activityId, Long memberId);
}
