package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.Objects;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsSettingMapper;
import com.chengyinhub.business.domain.CmsSetting;
import com.chengyinhub.business.service.ICmsSettingService;

/**
 * 参数设置Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-11-17
 */
@Service
public class CmsSettingServiceImpl implements ICmsSettingService 
{
    private static final Long SETTLEMENT_CONFIG_ID = 1L;

    @Autowired
    private CmsSettingMapper cmsSettingMapper;

    /**
     * 查询参数设置
     * 
     * @param id 参数设置主键
     * @return 参数设置
     */
    @Override
    public CmsSetting selectCmsSettingById(Long id)
    {
        return cmsSettingMapper.selectCmsSettingById(id);
    }

    /**
     * 查询参数设置列表
     * 
     * @param cmsSetting 参数设置
     * @return 参数设置
     */
    @Override
    public List<CmsSetting> selectCmsSettingList(CmsSetting cmsSetting)
    {
        return cmsSettingMapper.selectCmsSettingList(cmsSetting);
    }

    /**
     * 新增参数设置
     * 
     * @param cmsSetting 参数设置
     * @return 结果
     */
    @Override
    public int insertCmsSetting(CmsSetting cmsSetting)
    {
        rejectPlatformSettlementAccountInsert(cmsSetting);
        cmsSetting.setCreateTime(DateUtils.getNowDate());
        return cmsSettingMapper.insertCmsSetting(cmsSetting);
    }

    /**
     * 修改参数设置
     * 
     * @param cmsSetting 参数设置
     * @return 结果
     */
    @Override
    public int updateCmsSetting(CmsSetting cmsSetting)
    {
        stripUnchangedPlatformSettlementAccount(cmsSetting);
        cmsSetting.setUpdateTime(DateUtils.getNowDate());
        return cmsSettingMapper.updateCmsSetting(cmsSetting);
    }

    /**
     * platform_member_id 是活动平台分润的真实收款人，只能走 settlementrule 的专用权限和会员状态校验。
     * 保留旧 settings URI 的其它兼容字段，但不能保留资金收款人的写入旁路。
     */
    private void rejectPlatformSettlementAccountInsert(CmsSetting cmsSetting)
    {
        if (cmsSetting != null && (cmsSetting.getPlatformMemberId() != null
                || SETTLEMENT_CONFIG_ID.equals(cmsSetting.getId())))
        {
            throw new ServiceException("平台收款账户请在结算规则中维护");
        }
    }

    /**
     * 旧页会把 GET /business/setting/1 得到的完整对象原样 PUT 回来。
     * 原值回传时剥离该字段，既不误伤其它遗留字段，也不会让旧入口覆盖专用入口刚写入的收款人。
     */
    private void stripUnchangedPlatformSettlementAccount(CmsSetting cmsSetting)
    {
        if (cmsSetting == null || cmsSetting.getPlatformMemberId() == null)
        {
            return;
        }
        if (!SETTLEMENT_CONFIG_ID.equals(cmsSetting.getId()))
        {
            throw new ServiceException("平台收款账户请在结算规则中维护");
        }
        CmsSetting current = cmsSettingMapper.selectCmsSettingById(SETTLEMENT_CONFIG_ID);
        if (current == null || !Objects.equals(current.getPlatformMemberId(), cmsSetting.getPlatformMemberId()))
        {
            throw new ServiceException("平台收款账户请在结算规则中维护");
        }
        cmsSetting.setPlatformMemberId(null);
    }

    /**
     * 批量删除参数设置
     * 
     * @param ids 需要删除的参数设置主键
     * @return 结果
     */
    @Override
    public int deleteCmsSettingByIds(Long[] ids)
    {
        rejectSettlementConfigDeletion(ids);
        return cmsSettingMapper.deleteCmsSettingByIds(ids);
    }

    /**
     * 删除参数设置信息
     * 
     * @param id 参数设置主键
     * @return 结果
     */
    @Override
    public int deleteCmsSettingById(Long id)
    {
        rejectSettlementConfigDeletion(new Long[] { id });
        return cmsSettingMapper.deleteCmsSettingById(id);
    }

    private void rejectSettlementConfigDeletion(Long[] ids)
    {
        if (ids == null)
        {
            return;
        }
        for (Long id : ids)
        {
            if (SETTLEMENT_CONFIG_ID.equals(id))
            {
                throw new ServiceException("活动结算配置不能通过旧设置入口删除");
            }
        }
    }
}
