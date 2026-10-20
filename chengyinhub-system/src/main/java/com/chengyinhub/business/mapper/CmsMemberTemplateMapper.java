package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import org.apache.ibatis.annotations.Param;

/**
 * 我的模板Mapper接口
 * 
 * @author webxh
 * @date 2025-09-02
 */
public interface CmsMemberTemplateMapper 
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

    /** 主题组合审核按 id 升序锁定全部引用玩法。调用方必须传升序 id。 */
    List<CmsMemberTemplate> selectCmsMemberTemplateByIdsForUpdate(@Param("ids") List<Long> ids);

    /** 只把仍属于 owner 的私有草稿变为个人可用，不创建模板广场投影。 */
    int approveBundledDraft(@Param("id") Long id, @Param("memberId") Long memberId);

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
     * @return我的模板集合
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

    int applyAuditDecision(@Param("row") CmsMemberTemplate row, @Param("ownerId") Long ownerId);

    /** AUDIT-G2-2:举报成立下架已公开的个人玩法(只动公开中的行,幂等) */
    int takeDownReported(@Param("id") Long id, @Param("reason") String reason);

    /**
     * 删除我的模板
     * 
     * @param id 我的模板主键
     * @return 结果
     */
    public int deleteCmsMemberTemplateById(Long id);

    /**
     * 批量删除我的模板
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteCmsMemberTemplateByIds(Long[] ids);

    /** 审核撤销/申诉复核：把审核决定写下的状态 CAS 回待审(0/0/0)，与 applyAuditDecision 的待审前置对称。 */
    int reopenAuditDecision(@Param("id") Long id, @Param("ownerId") Long ownerId,
                            @Param("fromStatus") Long fromStatus, @Param("fromCheckStatus") Integer fromCheckStatus,
                            @Param("fromPublishStatus") Long fromPublishStatus);

    /** 举报下架申诉成立(AUDIT-INT-4):从举报下架态(2/2/0)原样恢复到下架前记录的状态。 */
    int restoreReportTakedown(@Param("id") Long id, @Param("ownerId") Long ownerId, @Param("status") Long status,
                              @Param("checkStatus") Integer checkStatus, @Param("publishStatus") Long publishStatus,
                              @Param("reason") String reason);
}
