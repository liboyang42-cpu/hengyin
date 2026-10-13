package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsInfomationMapper;
import com.chengyinhub.business.domain.CmsInfomation;
import com.chengyinhub.business.service.ICmsInfomationService;

/**
 * 玩法说明Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-11-24
 */
@Service
public class CmsInfomationServiceImpl implements ICmsInfomationService 
{
    @Autowired
    private CmsInfomationMapper cmsInfomationMapper;

    /**
     * 查询玩法说明
     * 
     * @param id 玩法说明主键
     * @return 玩法说明
     */
    @Override
    public CmsInfomation selectCmsInfomationById(Long id)
    {
        return cmsInfomationMapper.selectCmsInfomationById(id);
    }

    /**
     * 查询玩法说明列表
     * 
     * @param cmsInfomation 玩法说明
     * @return 玩法说明
     */
    @Override
    public List<CmsInfomation> selectCmsInfomationList(CmsInfomation cmsInfomation)
    {
        return cmsInfomationMapper.selectCmsInfomationList(cmsInfomation);
    }

    /**
     * 新增玩法说明
     * 
     * @param cmsInfomation 玩法说明
     * @return 结果
     */
    @Override
    public int insertCmsInfomation(CmsInfomation cmsInfomation)
    {
        cmsInfomation.setCreateTime(DateUtils.getNowDate());
        return cmsInfomationMapper.insertCmsInfomation(cmsInfomation);
    }

    /**
     * 修改玩法说明
     * 
     * @param cmsInfomation 玩法说明
     * @return 结果
     */
    @Override
    public int updateCmsInfomation(CmsInfomation cmsInfomation)
    {
        cmsInfomation.setUpdateTime(DateUtils.getNowDate());
        return cmsInfomationMapper.updateCmsInfomation(cmsInfomation);
    }

    /**
     * 批量删除玩法说明
     * 
     * @param ids 需要删除的玩法说明主键
     * @return 结果
     */
    @Override
    public int deleteCmsInfomationByIds(Long[] ids)
    {
        throw new ServiceException("玩法说明仍被小程序展示链路消费，当前不支持物理删除");
    }

    /**
     * 删除玩法说明信息
     * 
     * @param id 玩法说明主键
     * @return 结果
     */
    @Override
    public int deleteCmsInfomationById(Long id)
    {
        throw new ServiceException("玩法说明仍被小程序展示链路消费，当前不支持物理删除");
    }
}
