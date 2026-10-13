package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.UmsCrowdfunding;

/**
 * 用户众筹Service接口
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
public interface IUmsCrowdfundingService 
{
    /**
     * 查询用户众筹
     * 
     * @param id 用户众筹主键
     * @return 用户众筹
     */
    public UmsCrowdfunding selectUmsCrowdfundingById(Long id);

    /**
     * 查询用户众筹列表
     * 
     * @param umsCrowdfunding 用户众筹
     * @return 用户众筹集合
     */
    public List<UmsCrowdfunding> selectUmsCrowdfundingList(UmsCrowdfunding umsCrowdfunding);

    /**
     * 新增用户众筹
     * 
     * @param umsCrowdfunding 用户众筹
     * @return 结果
     */
    public int insertUmsCrowdfunding(UmsCrowdfunding umsCrowdfunding);

    /**
     * 修改用户众筹
     * 
     * @param umsCrowdfunding 用户众筹
     * @return 结果
     */
    public int updateUmsCrowdfunding(UmsCrowdfunding umsCrowdfunding);

    /**
     * 批量删除用户众筹
     * 
     * @param ids 需要删除的用户众筹主键集合
     * @return 结果
     */
    public int deleteUmsCrowdfundingByIds(Long[] ids);

    /**
     * 删除用户众筹信息
     * 
     * @param id 用户众筹主键
     * @return 结果
     */
    public int deleteUmsCrowdfundingById(Long id);
}
