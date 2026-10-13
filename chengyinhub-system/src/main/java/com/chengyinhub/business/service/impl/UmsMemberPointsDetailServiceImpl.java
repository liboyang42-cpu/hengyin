package com.chengyinhub.business.service.impl;

import java.util.List;
import java.math.BigDecimal;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.common.exception.ServiceException;

/**
 * 用户积分明细Service业务层处理
 *
 * @author ruoyi
 * @date 2025-08-26
 */
@Service
public class UmsMemberPointsDetailServiceImpl implements IUmsMemberPointsDetailService
{
    @Autowired
    private UmsMemberPointsDetailMapper umsMemberPointsDetailMapper;

    @Override
    public int selectUmsMemberPointsDetailCount(UmsMemberPointsDetail umsMemberPointsDetail){
        return umsMemberPointsDetailMapper.selectUmsMemberPointsDetailCount(umsMemberPointsDetail);
    }


    /**
     * 查询用户积分明细
     *
     * @param id 用户积分明细主键
     * @return 用户积分明细
     */
    @Override
    public UmsMemberPointsDetail selectUmsMemberPointsDetailById(Long id)
    {
        return umsMemberPointsDetailMapper.selectUmsMemberPointsDetailById(id);
    }

    /**
     * 查询用户积分明细列表
     *
     * @param umsMemberPointsDetail 用户积分明细
     * @return 用户积分明细
     */
    @Override
    public List<UmsMemberPointsDetail> selectUmsMemberPointsDetailList(UmsMemberPointsDetail umsMemberPointsDetail)
    {
        return umsMemberPointsDetailMapper.selectUmsMemberPointsDetailList(umsMemberPointsDetail);
    }

    /**
     * 新增用户积分明细
     *
     * @param umsMemberPointsDetail 用户积分明细
     * @return 结果
     */
    @Override
    public int insertUmsMemberPointsDetail(UmsMemberPointsDetail umsMemberPointsDetail)
    {
        rejectPublisherSettlementMutation(umsMemberPointsDetail);
        umsMemberPointsDetail.setCreateTime(DateUtils.getNowDate());
        return umsMemberPointsDetailMapper.insertUmsMemberPointsDetail(umsMemberPointsDetail);
    }

    /**
     * 修改用户积分明细
     *
     * @param umsMemberPointsDetail 用户积分明细
     * @return 结果
     */
    @Override
    public int updateUmsMemberPointsDetail(UmsMemberPointsDetail umsMemberPointsDetail)
    {
        rejectPublisherSettlementMutation(umsMemberPointsDetail);
        rejectPublisherSettlementMutation(umsMemberPointsDetail == null ? null
                : umsMemberPointsDetailMapper.selectUmsMemberPointsDetailById(umsMemberPointsDetail.getId()));
        umsMemberPointsDetail.setUpdateTime(DateUtils.getNowDate());
        return umsMemberPointsDetailMapper.updateUmsMemberPointsDetail(umsMemberPointsDetail);
    }

    /**
     * 批量删除用户积分明细
     *
     * @param ids 需要删除的用户积分明细主键
     * @return 结果
     */
    @Override
    public int deleteUmsMemberPointsDetailByIds(Long[] ids)
    {
        if (ids != null) {
            for (Long id : ids) {
                rejectPublisherSettlementMutation(
                        umsMemberPointsDetailMapper.selectUmsMemberPointsDetailById(id));
            }
        }
        return umsMemberPointsDetailMapper.deleteUmsMemberPointsDetailByIds(ids);
    }

    /**
     * 删除用户积分明细信息
     *
     * @param id 用户积分明细主键
     * @return 结果
     */
    @Override
    public int deleteUmsMemberPointsDetailById(Long id)
    {
        rejectPublisherSettlementMutation(umsMemberPointsDetailMapper.selectUmsMemberPointsDetailById(id));
        return umsMemberPointsDetailMapper.deleteUmsMemberPointsDetailById(id);
    }

    /** event 15 是结算事务内的资金替代凭据，通用 CRUD 不得伪造、改写或删除。 */
    private void rejectPublisherSettlementMutation(UmsMemberPointsDetail detail)
    {
        if (detail != null
                && (IPointsAwardService.EVENT_PUBLISHER_SETTLEMENT.equals(detail.getEventType())
                    || detail.getPublisherSettlementTopicId() != null)) {
            throw new ServiceException("作品结算积分流水只读，禁止通用增删改");
        }
    }

    /**
     * 获取用户本周积分收入
     * @param memberId 用户ID
     * @return 本周积分收入
     */
    @Override
    public BigDecimal getWeekPointsIncome(Long memberId) {
        return umsMemberPointsDetailMapper.selectWeekPointsIncome(memberId);
    }
}
