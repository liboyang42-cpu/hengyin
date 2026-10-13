package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.TmsBrowseRecord;

/**
 * 主题/活动浏览记录Mapper接口
 * 
 * @author webxh
 * @date 2025-10-20
 */
public interface TmsBrowseRecordMapper 
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
     * 删除主题/活动浏览记录
     * 
     * @param id 主题/活动浏览记录主键
     * @return 结果
     */
    public int deleteTmsBrowseRecordById(Long id);

    /**
     * 批量删除主题/活动浏览记录
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteTmsBrowseRecordByIds(Long[] ids);

    /**
     * 按browse_type和targetId统计浏览次数（支持时间范围）
     * @param params 包含browseType、targetId、startTime、endTime的Map
     * @return 统计结果列表（每个元素为一组类型+目标的浏览次数）
     */
    public List<Map<String, Object>> countBrowseByTypeAndTarget(Map<String, Object> params);

    public List<Map<String, Object>> countBrowseByTypeAndTargets(@Param("browseType") Integer browseType,
                                                                 @Param("targetIds") List<Long> targetIds);

    /**
     * 留存清理:删除 browse_time/create_time 早于 cutoff 的浏览记录(含 IP/UA),单批 LIMIT。
     * 合规《隐私政策》数据留存条款,由 dataRetentionCleanupTask 分批调用。
     *
     * @param cutoff 保留期截止时间(早于此的删)
     * @param limit  单批删除上限
     * @return 实际删除行数
     */
    public int deleteBrowseRecordsBefore(@Param("cutoff") Date cutoff, @Param("limit") int limit);
}
