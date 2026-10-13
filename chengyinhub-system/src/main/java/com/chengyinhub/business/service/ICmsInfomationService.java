package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CmsInfomation;

/**
 * 玩法说明Service接口
 * 
 * @author ruoyi
 * @date 2025-11-24
 */
public interface ICmsInfomationService 
{
    /**
     * 查询玩法说明
     * 
     * @param id 玩法说明主键
     * @return 玩法说明
     */
    public CmsInfomation selectCmsInfomationById(Long id);

    /**
     * 查询玩法说明列表
     * 
     * @param cmsInfomation 玩法说明
     * @return 玩法说明集合
     */
    public List<CmsInfomation> selectCmsInfomationList(CmsInfomation cmsInfomation);

    /**
     * 新增玩法说明
     * 
     * @param cmsInfomation 玩法说明
     * @return 结果
     */
    public int insertCmsInfomation(CmsInfomation cmsInfomation);

    /**
     * 修改玩法说明
     * 
     * @param cmsInfomation 玩法说明
     * @return 结果
     */
    public int updateCmsInfomation(CmsInfomation cmsInfomation);

    /**
     * 批量删除玩法说明
     * 
     * @param ids 需要删除的玩法说明主键集合
     * @return 结果
     */
    public int deleteCmsInfomationByIds(Long[] ids);

    /**
     * 删除玩法说明信息
     * 
     * @param id 玩法说明主键
     * @return 结果
     */
    public int deleteCmsInfomationById(Long id);
}
