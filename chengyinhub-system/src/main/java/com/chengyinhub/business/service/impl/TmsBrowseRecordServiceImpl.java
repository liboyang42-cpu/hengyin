package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.Map;

import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.TmsBrowseRecordMapper;
import com.chengyinhub.business.domain.TmsBrowseRecord;
import com.chengyinhub.business.service.ITmsBrowseRecordService;

/**
 * 主题/活动浏览记录Service业务层处理
 * 
 * @author webxh
 * @date 2025-10-20
 */
@Service
public class TmsBrowseRecordServiceImpl implements ITmsBrowseRecordService 
{
    @Autowired
    private TmsBrowseRecordMapper tmsBrowseRecordMapper;

    /**
     * 查询主题/活动浏览记录
     * 
     * @param id 主题/活动浏览记录主键
     * @return 主题/活动浏览记录
     */
    @Override
    public TmsBrowseRecord selectTmsBrowseRecordById(Long id)
    {
        return tmsBrowseRecordMapper.selectTmsBrowseRecordById(id);
    }

    /**
     * 查询主题/活动浏览记录列表
     * 
     * @param tmsBrowseRecord 主题/活动浏览记录
     * @return 主题/活动浏览记录
     */
    @Override
    public List<TmsBrowseRecord> selectTmsBrowseRecordList(TmsBrowseRecord tmsBrowseRecord)
    {
        return tmsBrowseRecordMapper.selectTmsBrowseRecordList(tmsBrowseRecord);
    }

    /**
     * 新增主题/活动浏览记录
     * 
     * @param tmsBrowseRecord 主题/活动浏览记录
     * @return 结果
     */
    @Override
    public int insertTmsBrowseRecord(TmsBrowseRecord tmsBrowseRecord)
    {
        tmsBrowseRecord.setCreateTime(DateUtils.getNowDate());
        return tmsBrowseRecordMapper.insertTmsBrowseRecord(tmsBrowseRecord);
    }

    /**
     * 修改主题/活动浏览记录
     * 
     * @param tmsBrowseRecord 主题/活动浏览记录
     * @return 结果
     */
    @Override
    public int updateTmsBrowseRecord(TmsBrowseRecord tmsBrowseRecord)
    {
        tmsBrowseRecord.setUpdateTime(DateUtils.getNowDate());
        return tmsBrowseRecordMapper.updateTmsBrowseRecord(tmsBrowseRecord);
    }

    /**
     * 批量删除主题/活动浏览记录
     * 
     * @param ids 需要删除的主题/活动浏览记录主键
     * @return 结果
     */
    @Override
    public int deleteTmsBrowseRecordByIds(Long[] ids)
    {
        return tmsBrowseRecordMapper.deleteTmsBrowseRecordByIds(ids);
    }

    /**
     * 删除主题/活动浏览记录信息
     * 
     * @param id 主题/活动浏览记录主键
     * @return 结果
     */
    @Override
    public int deleteTmsBrowseRecordById(Long id)
    {
        return tmsBrowseRecordMapper.deleteTmsBrowseRecordById(id);
    }

    @Override
    public List<Map<String, Object>> countBrowseByTypeAndTargets(Integer browseType, List<Long> targetIds) {
        return tmsBrowseRecordMapper.countBrowseByTypeAndTargets(browseType, targetIds);
    }

    /**
     * 按browse_type和targetId统计浏览次数（支持时间范围）
     * @param params 包含browseType、targetId、startTime、endTime的Map
     * @return 统计结果列表（每个元素为一组类型+目标的浏览次数）
     */
    public List<Map<String, Object>> countBrowseByTypeAndTarget(Map<String, Object> params){
        return tmsBrowseRecordMapper.countBrowseByTypeAndTarget(params);
    }
}
