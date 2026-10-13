package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.SmsHotSearchMapper;
import com.chengyinhub.business.domain.SmsHotSearch;
import com.chengyinhub.business.service.ISmsHotSearchService;

/**
 * 热门搜索词Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
@Service
public class SmsHotSearchServiceImpl implements ISmsHotSearchService 
{
    @Autowired
    private SmsHotSearchMapper smsHotSearchMapper;

    /**
     * 查询热门搜索词
     * 
     * @param id 热门搜索词主键
     * @return 热门搜索词
     */
    @Override
    public SmsHotSearch selectSmsHotSearchById(Long id)
    {
        return smsHotSearchMapper.selectSmsHotSearchById(id);
    }

    /**
     * 查询热门搜索词列表
     * 
     * @param smsHotSearch 热门搜索词
     * @return 热门搜索词
     */
    @Override
    public List<SmsHotSearch> selectSmsHotSearchList(SmsHotSearch smsHotSearch)
    {
        return smsHotSearchMapper.selectSmsHotSearchList(smsHotSearch);
    }

    /**
     * 新增热门搜索词
     * 
     * @param smsHotSearch 热门搜索词
     * @return 结果
     */
    @Override
    public int insertSmsHotSearch(SmsHotSearch smsHotSearch)
    {
        smsHotSearch.setCreateTime(DateUtils.getNowDate());
        return smsHotSearchMapper.insertSmsHotSearch(smsHotSearch);
    }

    /**
     * 修改热门搜索词
     * 
     * @param smsHotSearch 热门搜索词
     * @return 结果
     */
    @Override
    public int updateSmsHotSearch(SmsHotSearch smsHotSearch)
    {
        smsHotSearch.setUpdateTime(DateUtils.getNowDate());
        return smsHotSearchMapper.updateSmsHotSearch(smsHotSearch);
    }

    /**
     * 批量删除热门搜索词
     * 
     * @param ids 需要删除的热门搜索词主键
     * @return 结果
     */
    @Override
    public int deleteSmsHotSearchByIds(Long[] ids)
    {
        return smsHotSearchMapper.deleteSmsHotSearchByIds(ids);
    }

    /**
     * 删除热门搜索词信息
     * 
     * @param id 热门搜索词主键
     * @return 结果
     */
    @Override
    public int deleteSmsHotSearchById(Long id)
    {
        return smsHotSearchMapper.deleteSmsHotSearchById(id);
    }
}
