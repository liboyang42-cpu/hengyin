package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.List;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;

/**
 * 用户积分明细Service接口
 * 
 * @author ruoyi
 * @date 2025-08-26
 */
public interface IUmsMemberPointsDetailService 
{
    public int selectUmsMemberPointsDetailCount(UmsMemberPointsDetail umsMemberPointsDetail);


    /**
     * 获取用户本周积分收入
     * @param memberId 用户ID
     * @return 本周积分收入
     */
    BigDecimal getWeekPointsIncome(Long memberId);

    /**
     * 查询用户积分明细
     * 
     * @param id 用户积分明细主键
     * @return 用户积分明细
     */
    public UmsMemberPointsDetail selectUmsMemberPointsDetailById(Long id);

    /**
     * 查询用户积分明细列表
     * 
     * @param umsMemberPointsDetail 用户积分明细
     * @return 用户积分明细集合
     */
    public List<UmsMemberPointsDetail> selectUmsMemberPointsDetailList(UmsMemberPointsDetail umsMemberPointsDetail);

    /**
     * 新增用户积分明细
     * 
     * @param umsMemberPointsDetail 用户积分明细
     * @return 结果
     */
    public int insertUmsMemberPointsDetail(UmsMemberPointsDetail umsMemberPointsDetail);

    /**
     * 修改用户积分明细
     * 
     * @param umsMemberPointsDetail 用户积分明细
     * @return 结果
     */
    public int updateUmsMemberPointsDetail(UmsMemberPointsDetail umsMemberPointsDetail);

    /**
     * 批量删除用户积分明细
     * 
     * @param ids 需要删除的用户积分明细主键集合
     * @return 结果
     */
    public int deleteUmsMemberPointsDetailByIds(Long[] ids);

    /**
     * 删除用户积分明细信息
     * 
     * @param id 用户积分明细主键
     * @return 结果
     */
    public int deleteUmsMemberPointsDetailById(Long id);
}
