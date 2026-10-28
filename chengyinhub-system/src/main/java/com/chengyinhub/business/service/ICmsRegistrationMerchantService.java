package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.dto.MerchantSharingRateDTO;

/**
 * 商家主题报名Service接口
 * 
 * @author webxh
 * @date 2025-09-14
 */
public interface ICmsRegistrationMerchantService 
{
    /**
     * 查询商家主题报名
     * 
     * @param id 商家主题报名主键
     * @return 商家主题报名
     */
    public CmsRegistrationMerchant selectCmsRegistrationMerchantById(Long id);

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

    /** 商家编辑承接信息的原子状态/归属 CAS；返回 0 表示读取后状态已变化。 */
    int updateEditableFieldsIfStateMatches(CmsRegistrationMerchant patch, Long memberId,
                                           Long expectedStatus, Long expectedAuditStatus,
                                           boolean rejected);

    /**
     * A-13 防双中标:原子占槽(仅待定 audit_status=0/null 可置中标),返回受影响行数。
     */
    public int confirmWinnerIfPending(Long id);

    /** 调配中枢原子驳回，仅待调配记录可落选。 */
    int rejectIfPending(Long id, String reason);


    /**
     * 批量更新分润比例
     *
     * @param sharingRateDTO 分润比例DTO
     * @return 结果
     */
    public int updateSharingRates(MerchantSharingRateDTO sharingRateDTO);

    /**
     * 后台批量删除商家主题报名；同事务按主键升序锁定当前行并复验现有 topic scope/中标态。
     * 任一记录缺失、越范围、中标或删除行数失配时整批回滚。
     * 
     * @param ids 需要删除的商家主题报名主键集合
     * @return 结果
     */
    public int deleteCmsRegistrationMerchantByIds(Long[] ids);

    /**
     * 删除商家主题报名信息；与批量入口使用相同事务和当前状态校验。
     * 
     * @param id 商家主题报名主键
     * @return 结果
     */
    public int deleteCmsRegistrationMerchantById(Long id);

    /** 统计某主题下报名通过的商家数 */
    public int countByTopic(Long topicId);

    /** 批量统计一页主题的报名通过商家数。 */
    java.util.Map<Long, Integer> countByTopics(java.util.List<Long> topicIds);

    /**
     * [C5] 商家自行取消承接:归属校验 + <b>已中标(audit_status=1)不得取消</b>。
     *
     * <p>拦已中标的理由与后台驳回路径(HF-06/A-37)一致:中标时建的活动+复制票会成孤儿。
     * 撤销中标须走专用流程(⚠️ 该流程全仓尚无实现,本方法只拒绝+引导)。
     *
     * @throws RuntimeException 记录不存在 / 非本人 / 已中标
     */
    boolean cancelByMerchant(Long registrationId, Long memberId, String username);
}
