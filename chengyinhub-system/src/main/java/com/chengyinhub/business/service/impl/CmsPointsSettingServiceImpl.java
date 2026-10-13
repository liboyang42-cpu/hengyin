package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsPointsSettingMapper;
import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.service.ICmsPointsSettingService;

/**
 * 积分规则与分润设置Service业务层处理
 * 
 * @author ruoyi
 * @date 2026-01-31
 */
@Service
public class CmsPointsSettingServiceImpl implements ICmsPointsSettingService 
{
    private static final Long POINTS_RULE_TYPE = 1L;
    private static final Long PUBLISHER_SETTLEMENT_EVENT_TYPE = 15L;

    @Autowired
    private CmsPointsSettingMapper cmsPointsSettingMapper;

    /**
     * 查询积分规则与分润设置
     * 
     * @param id 积分规则与分润设置主键
     * @return 积分规则与分润设置
     */
    @Override
    public CmsPointsSetting selectCmsPointsSettingById(Long id)
    {
        return cmsPointsSettingMapper.selectCmsPointsSettingById(id);
    }

    /**
     * 查询积分规则与分润设置列表
     * 
     * @param cmsPointsSetting 积分规则与分润设置
     * @return 积分规则与分润设置
     */
    @Override
    public List<CmsPointsSetting> selectCmsPointsSettingList(CmsPointsSetting cmsPointsSetting)
    {
        return cmsPointsSettingMapper.selectCmsPointsSettingList(cmsPointsSetting);
    }

    /**
     * 新增积分规则与分润设置
     * 
     * @param cmsPointsSetting 积分规则与分润设置
     * @return 结果
     */
    @Override
    public int insertCmsPointsSetting(CmsPointsSetting cmsPointsSetting)
    {
        assertPointRule(cmsPointsSetting, "新增", true);
        cmsPointsSetting.setCreateTime(DateUtils.getNowDate());
        return cmsPointsSettingMapper.insertCmsPointsSetting(cmsPointsSetting);
    }

    /**
     * 修改积分规则与分润设置
     * 
     * @param cmsPointsSetting 积分规则与分润设置
     * @return 结果
     */
    @Override
    public int updateCmsPointsSetting(CmsPointsSetting cmsPointsSetting)
    {
        assertPointRule(cmsPointsSetting, "修改", false);
        if (cmsPointsSetting != null && cmsPointsSetting.getId() != null)
        {
            assertPointRule(cmsPointsSettingMapper.selectCmsPointsSettingById(cmsPointsSetting.getId()), "修改", true);
        }
        cmsPointsSetting.setUpdateTime(DateUtils.getNowDate());
        return cmsPointsSettingMapper.updateCmsPointsSetting(cmsPointsSetting);
    }

    private void assertPointRule(CmsPointsSetting cmsPointsSetting, String action, boolean typeRequired)
    {
        if (cmsPointsSetting == null)
        {
            return;
        }
        if (PUBLISHER_SETTLEMENT_EVENT_TYPE.equals(cmsPointsSetting.getEventType()))
        {
            throw new ServiceException("作品结算积分规则(eventType=15)由资金迁移专用入口维护，通用积分规则接口不能" + action);
        }
        if (cmsPointsSetting.getType() == null && !typeRequired)
        {
            return;
        }
        if (!POINTS_RULE_TYPE.equals(cmsPointsSetting.getType()))
        {
            throw new ServiceException("积分规则接口只允许处理 type=1 积分规则，不能" + action + "其它类型");
        }
    }

    /**
     * 批量删除积分规则与分润设置
     * 
     * @param ids 需要删除的积分规则与分润设置主键
     * @return 结果
     */
    @Override
    public int deleteCmsPointsSettingByIds(Long[] ids)
    {
        assertOnlyPointRules(ids, "删除");
        return cmsPointsSettingMapper.deleteCmsPointsSettingByIds(ids);
    }

    /**
     * 删除积分规则与分润设置信息
     * 
     * @param id 积分规则与分润设置主键
     * @return 结果
     */
    @Override
    public int deleteCmsPointsSettingById(Long id)
    {
        if (id != null)
        {
            assertPointRule(cmsPointsSettingMapper.selectCmsPointsSettingById(id), "删除", true);
        }
        return cmsPointsSettingMapper.deleteCmsPointsSettingById(id);
    }

    private void assertOnlyPointRules(Long[] ids, String action)
    {
        if (ids == null)
        {
            return;
        }
        for (Long id : ids)
        {
            if (id != null)
            {
                assertPointRule(cmsPointsSettingMapper.selectCmsPointsSettingById(id), action, true);
            }
        }
    }
}
