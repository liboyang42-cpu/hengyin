package com.chengyinhub.business.service.impl;

import java.util.List;

import com.chengyinhub.business.domain.vo.TemplateLibraryVO;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.service.ICmsTemplateLibraryService;

/**
 * 模板库Service业务层处理
 * 
 * @author webxh
 * @date 2025-09-02
 */
@Service
public class CmsTemplateLibraryServiceImpl implements ICmsTemplateLibraryService 
{
    @Autowired
    private CmsTemplateLibraryMapper cmsTemplateLibraryMapper;

    /**
     * 模板市场热度:被引用发布一次 use_num +1(原子自增)。
     * 调用点在 TemplatePublishServiceImpl —— 「从模板起草→发布」链路带 originalTemplateId 时计数。
     */
    @Override
    public int incrementUseNum(Long id)
    {
        return cmsTemplateLibraryMapper.incrementUseNum(id);
    }

    /**
     * 查询模板库
     * 
     * @param id 模板库主键
     * @return 模板库
     */
    @Override
    public CmsTemplateLibrary selectCmsTemplateLibraryById(Long id)
    {
        return cmsTemplateLibraryMapper.selectCmsTemplateLibraryById(id);
    }

    /**
     * 查询模板库列表
     * 
     * @param cmsTemplateLibrary 模板库
     * @return 模板库
     */
    @Override
    public List<CmsTemplateLibrary> selectCmsTemplateLibraryList(CmsTemplateLibrary cmsTemplateLibrary)
    {
        return cmsTemplateLibraryMapper.selectCmsTemplateLibraryList(cmsTemplateLibrary);
    }

    /**
     * 按分类查询分类下的模版
     * @param categoryIds 分类数组
     * @return   按分类分组查询分类下的模版
     */
    @Override
    public List<TemplateLibraryVO> selectTemplatesByCategoryIds(List<Long> categoryIds) {
        return cmsTemplateLibraryMapper.selectTemplatesByCategoryIds(categoryIds);
    }

    /**
     * 新增模板库
     * 
     * @param cmsTemplateLibrary 模板库
     * @return 结果
     */
    @Override
    public int insertCmsTemplateLibrary(CmsTemplateLibrary cmsTemplateLibrary)
    {
        cmsTemplateLibrary.setCreateTime(DateUtils.getNowDate());
        return cmsTemplateLibraryMapper.insertCmsTemplateLibrary(cmsTemplateLibrary);
    }

    /**
     * 修改模板库
     * 
     * @param cmsTemplateLibrary 模板库
     * @return 结果
     */
    @Override
    public int updateCmsTemplateLibrary(CmsTemplateLibrary cmsTemplateLibrary)
    {
        cmsTemplateLibrary.setUpdateTime(DateUtils.getNowDate());
        return cmsTemplateLibraryMapper.updateCmsTemplateLibrary(cmsTemplateLibrary);
    }

    /**
     * 批量删除模板库
     * 
     * @param ids 需要删除的模板库主键
     * @return 结果
     */
    @Override
    public int deleteCmsTemplateLibraryByIds(Long[] ids)
    {
        return cmsTemplateLibraryMapper.deleteCmsTemplateLibraryByIds(ids);
    }

    /**
     * 删除模板库信息
     * 
     * @param id 模板库主键
     * @return 结果
     */
    @Override
    public int deleteCmsTemplateLibraryById(Long id)
    {
        return cmsTemplateLibraryMapper.deleteCmsTemplateLibraryById(id);
    }
}
