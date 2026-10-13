package com.chengyinhub.system.service.impl;

import java.util.List;

import com.chengyinhub.system.domain.SysBanner;
import com.chengyinhub.system.mapper.SysBannerMapper;
import com.chengyinhub.system.service.ISysBannerService;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


/**
 * 轮播图Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-21
 */
@Service
public class SysBannerServiceImpl implements ISysBannerService
{
    @Autowired
    private SysBannerMapper sysBannerMapper;

    /**
     * 查询轮播图
     * 
     * @param id 轮播图主键
     * @return 轮播图
     */
    @Override
    public SysBanner selectSysBannerById(Integer id)
    {
        return sysBannerMapper.selectSysBannerById(id);
    }

    /**
     * 查询轮播图列表
     * 
     * @param sysBanner 轮播图
     * @return 轮播图
     */
    @Override
    public List<SysBanner> selectSysBannerList(SysBanner sysBanner)
    {
        return sysBannerMapper.selectSysBannerList(sysBanner);
    }

    /**
     * 新增轮播图
     * 
     * @param sysBanner 轮播图
     * @return 结果
     */
    @Override
    public int insertSysBanner(SysBanner sysBanner)
    {
        sysBanner.setCreateTime(DateUtils.getNowDate());
        return sysBannerMapper.insertSysBanner(sysBanner);
    }

    /**
     * 修改轮播图
     * 
     * @param sysBanner 轮播图
     * @return 结果
     */
    @Override
    public int updateSysBanner(SysBanner sysBanner)
    {
        sysBanner.setUpdateTime(DateUtils.getNowDate());
        return sysBannerMapper.updateSysBanner(sysBanner);
    }

    /**
     * 批量删除轮播图
     * 
     * @param ids 需要删除的轮播图主键
     * @return 结果
     */
    @Override
    public int deleteSysBannerByIds(Integer[] ids)
    {
        return sysBannerMapper.deleteSysBannerByIds(ids);
    }

    /**
     * 删除轮播图信息
     * 
     * @param id 轮播图主键
     * @return 结果
     */
    @Override
    public int deleteSysBannerById(Integer id)
    {
        return sysBannerMapper.deleteSysBannerById(id);
    }
}
