package com.chengyinhub.business.service.impl;

import java.util.Date;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import com.chengyinhub.common.utils.StringUtils;
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertCmsInfomation(CmsInfomation cmsInfomation, String auditReason,
            Long operatorUserId, String operatorUsername)
    {
        String reason = requireAudit(cmsInfomation, auditReason, operatorUserId, operatorUsername);
        Date now = DateUtils.getNowDate();
        cmsInfomation.setId(null);
        cmsInfomation.setCreateBy(operatorUsername);
        cmsInfomation.setCreateTime(now);
        cmsInfomation.setUpdateBy(null);
        cmsInfomation.setUpdateTime(null);
        int rows = cmsInfomationMapper.insertCmsInfomation(cmsInfomation);
        if (rows != 1 || cmsInfomation.getId() == null)
        {
            throw new ServiceException("新增玩法说明失败");
        }
        writeAudit(cmsInfomation.getId(), "CREATE", operatorUserId, operatorUsername, reason, now);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateCmsInfomation(CmsInfomation cmsInfomation, String auditReason,
            Long operatorUserId, String operatorUsername)
    {
        String reason = requireAudit(cmsInfomation, auditReason, operatorUserId, operatorUsername);
        if (cmsInfomation.getId() == null || cmsInfomation.getId() <= 0)
        {
            throw new ServiceException("缺少玩法说明 ID");
        }
        Date now = DateUtils.getNowDate();
        cmsInfomation.setCreateBy(null);
        cmsInfomation.setCreateTime(null);
        cmsInfomation.setUpdateBy(operatorUsername);
        cmsInfomation.setUpdateTime(now);
        int rows = cmsInfomationMapper.updateCmsInfomation(cmsInfomation);
        if (rows != 1)
        {
            throw new ServiceException("玩法说明不存在或更新失败");
        }
        writeAudit(cmsInfomation.getId(), "UPDATE", operatorUserId, operatorUsername, reason, now);
        return rows;
    }

    private String requireAudit(CmsInfomation guide, String auditReason,
            Long operatorUserId, String operatorUsername)
    {
        if (guide == null || operatorUserId == null || operatorUserId <= 0
                || StringUtils.isBlank(operatorUsername) || operatorUsername.length() > 64)
        {
            throw new ServiceException("缺少有效的后台操作人");
        }
        if (StringUtils.isBlank(auditReason) || auditReason.trim().length() > 500)
        {
            throw new ServiceException("请填写不超过 500 字的变更说明");
        }
        return auditReason.trim();
    }

    private void writeAudit(Long guideId, String action, Long operatorUserId,
            String operatorUsername, String reason, Date now)
    {
        if (cmsInfomationMapper.insertCmsInfomationChangeAudit(guideId, action,
                operatorUserId, operatorUsername, reason, now) != 1)
        {
            throw new ServiceException("玩法说明审计写入失败");
        }
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
