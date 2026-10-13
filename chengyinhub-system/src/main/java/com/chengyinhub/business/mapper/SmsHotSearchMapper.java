package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.SmsHotSearch;

/**
 * 热门搜索词Mapper接口
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
public interface SmsHotSearchMapper 
{
    /**
     * 查询热门搜索词
     * 
     * @param id 热门搜索词主键
     * @return 热门搜索词
     */
    public SmsHotSearch selectSmsHotSearchById(Long id);

    /**
     * 查询热门搜索词列表
     * 
     * @param smsHotSearch 热门搜索词
     * @return 热门搜索词集合
     */
    public List<SmsHotSearch> selectSmsHotSearchList(SmsHotSearch smsHotSearch);

    /**
     * 新增热门搜索词
     * 
     * @param smsHotSearch 热门搜索词
     * @return 结果
     */
    public int insertSmsHotSearch(SmsHotSearch smsHotSearch);

    /**
     * 修改热门搜索词
     * 
     * @param smsHotSearch 热门搜索词
     * @return 结果
     */
    public int updateSmsHotSearch(SmsHotSearch smsHotSearch);

    /**
     * 删除热门搜索词
     * 
     * @param id 热门搜索词主键
     * @return 结果
     */
    public int deleteSmsHotSearchById(Long id);

    /**
     * 批量删除热门搜索词
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteSmsHotSearchByIds(Long[] ids);
}
