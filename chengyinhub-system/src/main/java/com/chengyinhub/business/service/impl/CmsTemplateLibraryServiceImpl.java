package com.chengyinhub.business.service.impl;

import java.util.List;

import com.chengyinhub.business.domain.vo.TemplateLibraryVO;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.constant.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
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
     * 模板市场热度：初次采用创建草稿/个人玩法时按会员幂等自增。
     * 调用者必须在插入个人玩法之前持有来源库行锁。
     */
    @Override
    public int incrementUseNumOnFirstAdoption(Long libraryId, Long memberId, Long memberTemplateId)
    {
        return cmsTemplateLibraryMapper.incrementUseNumOnFirstAdoption(libraryId, memberId, memberTemplateId);
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
        requireUniqueTitle(cmsTemplateLibrary, null);
        cmsTemplateLibrary.setUseNum(0);
        try {
            return cmsTemplateLibraryMapper.insertCmsTemplateLibrary(cmsTemplateLibrary);
        } catch (DuplicateKeyException duplicate) {
            throw titleConflict(duplicate);
        }
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
        CmsTemplateLibrary stored = cmsTemplateLibraryMapper.selectCmsTemplateLibraryById(cmsTemplateLibrary.getId());
        requireUniqueTitle(cmsTemplateLibrary, stored);
        try {
            return cmsTemplateLibraryMapper.updateCmsTemplateLibrary(cmsTemplateLibrary);
        } catch (DuplicateKeyException duplicate) {
            throw titleConflict(duplicate);
        }
    }

    private void requireUniqueTitle(CmsTemplateLibrary patch, CmsTemplateLibrary stored) {
        Integer deleted = patch.getDelFlag() != null ? patch.getDelFlag()
                : stored == null ? 0 : stored.getDelFlag();
        if (!Integer.valueOf(0).equals(deleted)) return;
        Long owner = stored == null ? patch.getMemberId()
                : patch.getMemberId() == null ? stored.getMemberId() : patch.getMemberId();
        String title = patch.getTitle() == null && stored != null ? stored.getTitle() : patch.getTitle();
        if (owner == null || title == null) return;
        String normalized = title.trim();
        if (normalized.isEmpty()) return;
        List<String> titles = cmsTemplateLibraryMapper.selectAuthorLiveTitles(owner, patch.getId());
        if (titles != null) for (String other : titles) {
            if (other != null && normalized.equals(other.trim()))
                throw new ServiceException("你已经有同名模板了", HttpStatus.BAD_REQUEST);
        }
    }

    private RuntimeException titleConflict(DuplicateKeyException duplicate) {
        if (duplicate.getMessage() != null && duplicate.getMessage().contains("uk_template_author_live_title"))
            return new ServiceException("你已经有同名模板了", HttpStatus.BAD_REQUEST);
        return duplicate;
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
