package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import org.apache.ibatis.annotations.Param;

/**
 * 我的模板Service接口
 * 
 * @author webxh
 * @date 2025-09-02
 */
public interface ICmsMemberTemplateService 
{

    public int selectNumByTitle(CmsMemberTemplate cmsMemberTemplate);


    /**
     * 查询我的模板
     * 
     * @param id 我的模板主键
     * @return 我的模板
     */
    public CmsMemberTemplate selectCmsMemberTemplateById(Long id);

    /**
     * 查询我的模板
     * @param ids  List<Long>
     * @return
     */
    public List<CmsMemberTemplate> selectCmsMemberTemplateByIds(@Param("ids") List<Long> ids);
    /**
     * 查询我的模板列表
     * 
     * @param cmsMemberTemplate 我的模板
     * @return 我的模板集合
     */
    public List<CmsMemberTemplate> selectCmsMemberTemplateList(CmsMemberTemplate cmsMemberTemplate);

    /**
     * 从模板库关联查询我的模版
     * @param memberTemplateVO
     * @return 我的模板集合
     */
    public List<MemberTemplateVO> selectMemberTemplateList(MemberTemplateVO memberTemplateVO);


    /**
     * 新增我的模板
     * 
     * @param cmsMemberTemplate 我的模板
     * @return 结果
     */
    public int insertCmsMemberTemplate(CmsMemberTemplate cmsMemberTemplate);

    /**
     * 修改我的模板
     * 
     * @param cmsMemberTemplate 我的模板
     * @return 结果
     */
    public int updateCmsMemberTemplate(CmsMemberTemplate cmsMemberTemplate);

    /**
     * 批量删除我的模板
     * 
     * @param ids 需要删除的我的模板主键集合
     * @return 结果
     */
    public int deleteCmsMemberTemplateByIds(Long[] ids);

    /**
     * 删除我的模板信息
     * 
     * @param id 我的模板主键
     * @return 结果
     */
    public int deleteCmsMemberTemplateById(Long id);
    /** Clone a private content template for a target owner, joining the caller's transaction. Does not publish or submit review. */
    Long copyForContentOwner(Long templateId, Long ownerMemberId, boolean forceCopy);
}
