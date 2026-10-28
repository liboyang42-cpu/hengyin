package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.util.Date;

/**
 * 商家主题报名Mapper接口
 * 
 * @author webxh
 * @date 2025-09-14
 */
public interface CmsRegistrationMerchantMapper 
{
    /**
     * 查询商家主题报名
     * 
     * @param id 商家主题报名主键
     * @return 商家主题报名
     */
    public CmsRegistrationMerchant selectCmsRegistrationMerchantById(Long id);

    /** 多据点核销事务内锁定所选记录,防止复核后被并发改归属或撤销。 */
    CmsRegistrationMerchant selectCmsRegistrationMerchantByIdForUpdate(Long id);

    /** 后台整批删除的当前读；调用方按主键升序锁住全部目标后复验 scope/状态。 */
    CmsRegistrationMerchant selectForDeletionByIdForUpdate(Long id);

    /**
     * 查询商家主题报名列表
     * 
     * @param cmsRegistrationMerchant 商家主题报名
     * @return 商家主题报名集合
     */
    public List<CmsRegistrationMerchant> selectCmsRegistrationMerchantList(CmsRegistrationMerchant cmsRegistrationMerchant);

    /**
     * 新增商家主题报名
     * 
     * @param cmsRegistrationMerchant 商家主题报名
     * @return 结果
     */
    public int insertCmsRegistrationMerchant(CmsRegistrationMerchant cmsRegistrationMerchant);

    /**
     * 修改商家主题报名
     * 
     * @param cmsRegistrationMerchant 商家主题报名
     * @return 结果
     */
    public int updateCmsRegistrationMerchant(CmsRegistrationMerchant cmsRegistrationMerchant);

    /** Only the current owner may cancel an active, unwon registration; never write a stale review snapshot. */
    int cancelUnwonByMember(@Param("id") Long id, @Param("memberId") Long memberId,
                           @Param("username") String username, @Param("updateTime") Date updateTime);

    /**
     * 商家编辑承接信息的原子 CAS：归属、删除态、报名态和择优态必须仍与读取时一致。
     * limit_num 固定参与 SET，允许把原有数字显式清回 NULL。
     */
    int updateEditableFieldsIfStateMatches(@Param("patch") CmsRegistrationMerchant patch,
                                           @Param("memberId") Long memberId,
                                           @Param("expectedStatus") Long expectedStatus,
                                           @Param("expectedAuditStatus") Long expectedAuditStatus,
                                           @Param("rejected") boolean rejected);

    /**
     * A-13 防双中标:compare-and-set 原子占槽——仅未删除且待定(audit_status=0/null)可置中标(=1)。
     * 返回受影响行数;并发/重复确认下只有一方得 1,其余得 0,不再靠先读后写。
     */
    public int confirmWinnerIfPending(Long id);

    /** 调配中枢原子驳回：仅待调配记录可转落选，避免与选中 CAS 并发互相覆盖。 */
    public int rejectIfPending(@Param("id") Long id, @Param("reason") String reason);

    /**
     * 删除商家主题报名
     * 
     * @param id 商家主题报名主键
     * @return 结果
     */
    public int deleteCmsRegistrationMerchantById(Long id);

    /**
     * 批量删除商家主题报名
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteCmsRegistrationMerchantByIds(Long[] ids);

    /** 统计某主题下报名的商家数(status=1通过) */
    public int countByTopicId(Long topicId);

    /** 一次聚合一页主题的报名数，避免主题列表 N+1。 */
    List<Map<String, Object>> countByTopicIds(@Param("topicIds") List<Long> topicIds);
}
