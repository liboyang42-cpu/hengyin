package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CmsPointsSetting;

/**
 * 积分规则与分润设置Mapper接口
 * 
 * @author ruoyi
 * @date 2026-01-31
 */
public interface CmsPointsSettingMapper 
{
    /**
     * 查询积分规则与分润设置
     * 
     * @param id 积分规则与分润设置主键
     * @return 积分规则与分润设置
     */
    public CmsPointsSetting selectCmsPointsSettingById(Long id);

    /**
     * 查询积分规则与分润设置列表
     * 
     * @param cmsPointsSetting 积分规则与分润设置
     * @return 积分规则与分润设置集合
     */
    public List<CmsPointsSetting> selectCmsPointsSettingList(CmsPointsSetting cmsPointsSetting);

    /**
     * H8 专用：读作品结算积分规则，带 rule_version 快照列。
     *
     * <p>通用 {@code selectCmsPointsSettingList} 刻意不查 rule_version（迁移未落库时会 ERROR 1054，
     * 而它被小程序积分页与后台规则页共用）。本方法是唯一读该列的地方，
     * 只允许 {@code PointsAwardServiceImpl.awardPublisherSettlement} 调用；
     * 列不存在时必须原样抛 1054，不得降级。
     */
    public List<CmsPointsSetting> selectPublisherSettlementRules(@Param("eventType") Long eventType);

    /**
     * 新增积分规则与分润设置
     * 
     * @param cmsPointsSetting 积分规则与分润设置
     * @return 结果
     */
    public int insertCmsPointsSetting(CmsPointsSetting cmsPointsSetting);

    /**
     * 修改积分规则与分润设置
     * 
     * @param cmsPointsSetting 积分规则与分润设置
     * @return 结果
     */
    public int updateCmsPointsSetting(CmsPointsSetting cmsPointsSetting);

    /**
     * 删除积分规则与分润设置
     * 
     * @param id 积分规则与分润设置主键
     * @return 结果
     */
    public int deleteCmsPointsSettingById(Long id);

    /**
     * 批量删除积分规则与分润设置
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteCmsPointsSettingByIds(Long[] ids);
}
