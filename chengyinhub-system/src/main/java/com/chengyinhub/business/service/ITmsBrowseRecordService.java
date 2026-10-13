package com.chengyinhub.business.service;

import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.TmsBrowseRecord;

/**
 * 主题/活动浏览记录Service接口
 * 
 * @author webxh
 * @date 2025-10-20
 */
public interface ITmsBrowseRecordService 
{
    /**
     * 查询主题/活动浏览记录
     * 
     * @param id 主题/活动浏览记录主键
     * @return 主题/活动浏览记录
     */
    public TmsBrowseRecord selectTmsBrowseRecordById(Long id);

    /**
     * 查询主题/活动浏览记录列表
     * 
     * @param tmsBrowseRecord 主题/活动浏览记录
     * @return 主题/活动浏览记录集合
     */
    public List<TmsBrowseRecord> selectTmsBrowseRecordList(TmsBrowseRecord tmsBrowseRecord);

    /**
     * 新增主题/活动浏览记录
     * 
     * @param tmsBrowseRecord 主题/活动浏览记录
     * @return 结果
     */
    public int insertTmsBrowseRecord(TmsBrowseRecord tmsBrowseRecord);

    /**
     * 修改主题/活动浏览记录
     * 
     * @param tmsBrowseRecord 主题/活动浏览记录
     * @return 结果
     */
    public int updateTmsBrowseRecord(TmsBrowseRecord tmsBrowseRecord);

    /**
     * 批量删除主题/活动浏览记录
     * 
     * @param ids 需要删除的主题/活动浏览记录主键集合
     * @return 结果
     */
    public int deleteTmsBrowseRecordByIds(Long[] ids);

    /**
     * 删除主题/活动浏览记录信息
     * 
     * @param id 主题/活动浏览记录主键
     * @return 结果
     */
    public int deleteTmsBrowseRecordById(Long id);


    /**
     * 按browse_type和targetId统计浏览次数（支持时间范围）
     * @param params 包含browseType、targetId、startTime、endTime的Map
     * @return 统计结果列表（每个元素为一组类型+目标的浏览次数）
     */
    public List<Map<String, Object>> countBrowseByTypeAndTarget(Map<String, Object> params);

    public List<Map<String, Object>> countBrowseByTypeAndTargets(Integer browseType, List<Long> targetIds);
}
