package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.vo.TemplateLibraryVO;
import org.apache.ibatis.annotations.Param;

/**
 * 模板库Service接口
 * 
 * @author webxh
 * @date 2025-09-02
 */
public interface ICmsTemplateLibraryService 
{
    /**
     * 查询模板库
     * 
     * @param id 模板库主键
     * @return 模板库
     */
    public CmsTemplateLibrary selectCmsTemplateLibraryById(Long id);

    /**
     * 查询模板库列表
     * 
     * @param cmsTemplateLibrary 模板库
     * @return 模板库集合
     */
    public List<CmsTemplateLibrary> selectCmsTemplateLibraryList(CmsTemplateLibrary cmsTemplateLibrary);

    /**
     * 按分类查询分类下的模版
     * @param categoryIds 分类数组
     * @return   按分类分组查询分类下的模版
     */
    public List<TemplateLibraryVO> selectTemplatesByCategoryIds(@Param("categoryIds") List<Long> categoryIds);

    /**
     * 新增模板库
     * 
     * @param cmsTemplateLibrary 模板库
     * @return 结果
     */
    public int insertCmsTemplateLibrary(CmsTemplateLibrary cmsTemplateLibrary);

    /**
     * 修改模板库
     * 
     * @param cmsTemplateLibrary 模板库
     * @return 结果
     */
    public int updateCmsTemplateLibrary(CmsTemplateLibrary cmsTemplateLibrary);

    /**
     * 模板市场热度:会员首次「从模板起草→发布」引用该库模板时 use_num +1(原子自增,按会员幂等)。
     *
     * @param libraryId 模板库主键
     * @param memberId 采用者
     * @param memberTemplateId 本次新建的个人玩法
     * @return 结果(0 = 该会员此前已采用过,不重复计数)
     */
    public int incrementUseNumOnFirstAdoption(Long libraryId, Long memberId, Long memberTemplateId);

    /**
     * 批量删除模板库
     * 
     * @param ids 需要删除的模板库主键集合
     * @return 结果
     */
    public int deleteCmsTemplateLibraryByIds(Long[] ids);

    /**
     * 删除模板库信息
     * 
     * @param id 模板库主键
     * @return 结果
     */
    public int deleteCmsTemplateLibraryById(Long id);
}
