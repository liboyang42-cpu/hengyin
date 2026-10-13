package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.UmsCrowdfundingMapper;
import com.chengyinhub.business.domain.UmsCrowdfunding;
import com.chengyinhub.business.service.IUmsCrowdfundingService;

/**
 * 用户众筹Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
@Service
public class UmsCrowdfundingServiceImpl implements IUmsCrowdfundingService 
{
    @Autowired
    private UmsCrowdfundingMapper umsCrowdfundingMapper;

    /**
     * 查询用户众筹
     * 
     * @param id 用户众筹主键
     * @return 用户众筹
     */
    @Override
    public UmsCrowdfunding selectUmsCrowdfundingById(Long id)
    {
        return umsCrowdfundingMapper.selectUmsCrowdfundingById(id);
    }

    /**
     * 查询用户众筹列表
     * 
     * @param umsCrowdfunding 用户众筹
     * @return 用户众筹
     */
    @Override
    public List<UmsCrowdfunding> selectUmsCrowdfundingList(UmsCrowdfunding umsCrowdfunding)
    {
        return umsCrowdfundingMapper.selectUmsCrowdfundingList(umsCrowdfunding);
    }

    /**
     * 新增用户众筹
     * 
     * @param umsCrowdfunding 用户众筹
     * @return 结果
     */
    @Override
    public int insertUmsCrowdfunding(UmsCrowdfunding umsCrowdfunding)
    {
        umsCrowdfunding.setCreateTime(DateUtils.getNowDate());
        return umsCrowdfundingMapper.insertUmsCrowdfunding(umsCrowdfunding);
    }

    /**
     * 修改用户众筹
     * 
     * @param umsCrowdfunding 用户众筹
     * @return 结果
     */
    @Override
    public int updateUmsCrowdfunding(UmsCrowdfunding umsCrowdfunding)
    {
        umsCrowdfunding.setUpdateTime(DateUtils.getNowDate());
        return umsCrowdfundingMapper.updateUmsCrowdfunding(umsCrowdfunding);
    }

    /**
     * 批量删除用户众筹
     * 
     * @param ids 需要删除的用户众筹主键
     * @return 结果
     */
    @Override
    public int deleteUmsCrowdfundingByIds(Long[] ids)
    {
        return umsCrowdfundingMapper.deleteUmsCrowdfundingByIds(ids);
    }

    /**
     * 删除用户众筹信息
     * 
     * @param id 用户众筹主键
     * @return 结果
     */
    @Override
    public int deleteUmsCrowdfundingById(Long id)
    {
        return umsCrowdfundingMapper.deleteUmsCrowdfundingById(id);
    }
}
