package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.vo.TemplateLibraryVO;
import com.github.pagehelper.Page;
import org.apache.ibatis.annotations.Param;

/**
 * 模板库Mapper接口
 * 
 * @author webxh
 * @date 2025-09-02
 */
public interface CmsTemplateLibraryMapper 
{
    /**
     * 查询模板库
     * 
     * @param id 模板库主键
     * @return 模板库
     */
    public CmsTemplateLibrary selectCmsTemplateLibraryById(Long id);

    List<CmsTemplateLibrary> selectByMemberTemplateForUpdate(Long memberTemplateId);

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
     * 按分类查询分类下的模版  带分页
     * @param categoryIds 分类数组
     * @param page
     * @return  按分类分组查询分类下的模版
     */
    public List<TemplateLibraryVO> selectTemplatesByCategoryIdsPage(
            @Param("categoryIds") List<Long> categoryIds,
            @Param("page") Page<TemplateLibraryVO> page);
    /**
     * 新增模板库
     * 
     * @param cmsTemplateLibrary 模板库
     * @return 结果
     */
    public int insertCmsTemplateLibrary(CmsTemplateLibrary cmsTemplateLibrary);

    /** O5b-5:模板广场在架条目的 id/title(排除指定作者),按 id 倒序取前 limit 条,供标题相似度提示。 */
    List<CmsTemplateLibrary> selectPublicTitlesForSimilarity(@Param("excludeMemberId") Long excludeMemberId,
                                                             @Param("limit") int limit);

    /**
     * 修改模板库
     * 
     * @param cmsTemplateLibrary 模板库
     * @return 结果
     */
    public int updateCmsTemplateLibrary(CmsTemplateLibrary cmsTemplateLibrary);

    int applyAuditDecision(@Param("row") CmsTemplateLibrary row, @Param("ownerId") Long ownerId);

    /** AUDIT-G2-2:举报成立下架这条广场记录(只动公开中的行) */
    int takeDownReported(@Param("id") Long id, @Param("reason") String reason);

    /** AUDIT-G2-2:个人玩法举报成立时,连同其仍公开的广场投影一起下架 */
    int takeDownReportedByMemberTemplate(@Param("memberTemplateId") Long memberTemplateId, @Param("reason") String reason);

    /**
     * 模板市场热度:会员首次采用该库模板时 use_num +1(单条原子 UPDATE,避免读改写竞态)。
     * 该会员已有其它出自本库模板的个人玩法(含已删除)时命中 0 行,不重复计数。
     *
     * @param libraryId 模板库主键
     * @param memberId 采用者
     * @param memberTemplateId 本次新建的个人玩法(判重时排除自身)
     * @return 结果
     */
    public int incrementUseNumOnFirstAdoption(@Param("libraryId") Long libraryId, @Param("memberId") Long memberId,
                                              @Param("memberTemplateId") Long memberTemplateId);

    /**
     * 删除模板库
     * 
     * @param id 模板库主键
     * @return 结果
     */
    public int deleteCmsTemplateLibraryById(Long id);

    /**
     * 批量删除模板库
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteCmsTemplateLibraryByIds(Long[] ids);

    /** 审核撤销/申诉复核：把模板广场审核决定写下的状态 CAS 回待审(0/0)。 */
    int reopenAuditDecision(@Param("id") Long id, @Param("ownerId") Long ownerId,
                            @Param("memberTemplateId") Long memberTemplateId,
                            @Param("fromStatus") Long fromStatus, @Param("fromPublishStatus") Long fromPublishStatus);

    /** 举报下架申诉成立(AUDIT-INT-4):从举报下架态(2/0)原样恢复到下架前记录的状态。 */
    int restoreReportTakedown(@Param("id") Long id, @Param("status") Long status,
                              @Param("publishStatus") Long publishStatus, @Param("reason") String reason);
}
