package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.TreeSet;

import com.chengyinhub.business.domain.dto.MerchantSharingRateDTO;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商家主题报名Service业务层处理
 * 
 * @author webxh
 * @date 2025-09-14
 */
@Service
public class CmsRegistrationMerchantServiceImpl implements ICmsRegistrationMerchantService 
{
    @Autowired
    private CmsRegistrationMerchantMapper cmsRegistrationMerchantMapper;

    @Autowired
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    /**
     * 查询商家主题报名
     * 
     * @param id 商家主题报名主键
     * @return 商家主题报名
     */
    @Override
    public CmsRegistrationMerchant selectCmsRegistrationMerchantById(Long id)
    {
        return cmsRegistrationMerchantMapper.selectCmsRegistrationMerchantById(id);
    }

    /**
     * 查询商家主题报名列表
     * 
     * @param cmsRegistrationMerchant 商家主题报名
     * @return 商家主题报名
     */
    @Override
    public List<CmsRegistrationMerchant> selectCmsRegistrationMerchantList(CmsRegistrationMerchant cmsRegistrationMerchant)
    {
        return cmsRegistrationMerchantMapper.selectCmsRegistrationMerchantList(cmsRegistrationMerchant);
    }

    /**
     * 新增商家主题报名
     * 
     * @param cmsRegistrationMerchant 商家主题报名
     * @return 结果
     */
    @Override
    public int insertCmsRegistrationMerchant(CmsRegistrationMerchant cmsRegistrationMerchant)
    {
        cmsRegistrationMerchant.setCreateTime(DateUtils.getNowDate());
        return cmsRegistrationMerchantMapper.insertCmsRegistrationMerchant(cmsRegistrationMerchant);
    }

    /**
     * 修改商家主题报名
     * 
     * @param cmsRegistrationMerchant 商家主题报名
     * @return 结果
     */
    @Override
    public int updateCmsRegistrationMerchant(CmsRegistrationMerchant cmsRegistrationMerchant)
    {
        cmsRegistrationMerchant.setUpdateTime(DateUtils.getNowDate());
        return cmsRegistrationMerchantMapper.updateCmsRegistrationMerchant(cmsRegistrationMerchant);
    }

    @Override
    public int updateEditableFieldsIfStateMatches(CmsRegistrationMerchant patch, Long memberId,
                                                  Long expectedStatus, Long expectedAuditStatus,
                                                  boolean rejected)
    {
        return cmsRegistrationMerchantMapper.updateEditableFieldsIfStateMatches(
                patch, memberId, expectedStatus, expectedAuditStatus, rejected);
    }

    @Override
    public int confirmWinnerIfPending(Long id)
    {
        return cmsRegistrationMerchantMapper.confirmWinnerIfPending(id);
    }

    @Override
    public int rejectIfPending(Long id, String reason)
    {
        return cmsRegistrationMerchantMapper.rejectIfPending(id, reason);
    }

    /**
     * [G6] 禁止把落选商家的 status 改回 1(进行/通过)。
     *
     * 双轴:status=后台审核/进行态,audit_status=择优结果(0待调配 1中标 2落选)。二者漂移会让
     * 「拿 status=1 当中标」的下游全判错——落选商家能核销玩家的票、能进分账。择优要改走 select/reject,
     * 不走这里;这里只负责审核态与分润比例。
     */
    private void assertStatusConsistentWithAudit(CmsRegistrationMerchant record, Integer newStatus) {
        if (newStatus == null || newStatus != 1) {
            return;
        }
        if (record.getAuditStatus() != null && record.getAuditStatus() == 2) {
            throw new IllegalStateException("该商家已落选(audit_status=2),不能把审核态改回进行中;"
                    + "如需重新启用请走择优流程 registrationId=" + record.getId());
        }
    }

    /**
     * 批量更新分润比例（优化版本）
     */
    @Override
    @Transactional
    public int updateSharingRates(MerchantSharingRateDTO sharingRateDTO) {
        int successCount = 0;

        // 1. 更新主记录状态
        if (sharingRateDTO.getId() != null) {
            CmsRegistrationMerchant mainRecord = cmsRegistrationMerchantMapper.selectCmsRegistrationMerchantById(sharingRateDTO.getId());
            if (mainRecord != null) {
                assertStatusConsistentWithAudit(mainRecord, sharingRateDTO.getStatus());
                mainRecord.setStatus(Long.valueOf(sharingRateDTO.getStatus()));
                if (sharingRateDTO.getReason() != null) {
                    mainRecord.setReason(sharingRateDTO.getReason());
                }
                mainRecord.setUpdateBy(SecurityUtils.getUsername());
                successCount += cmsRegistrationMerchantMapper.updateCmsRegistrationMerchant(mainRecord);
            }
        }

        // 2. 批量更新分润比例
        if (sharingRateDTO.getSharingRates() != null && !sharingRateDTO.getSharingRates().isEmpty()) {
            String updateBy = SecurityUtils.getUsername();

            for (MerchantSharingRateDTO.SharingRateItem item : sharingRateDTO.getSharingRates()) {
                // 验证分润比例有效性
                if (item.getSharingRate() == null || item.getSharingRate() < 0 || item.getSharingRate() > 100) {
                    continue; // 跳过无效的分润比例
                }

                // 优先使用ID更新，如果没有ID则使用memberId和topicId组合
                if (item.getId() != null) {
                    CmsRegistrationMerchant record = cmsRegistrationMerchantMapper.selectCmsRegistrationMerchantById(item.getId());
                    if (record != null) {
                        record.setSharingRate(BigDecimal.valueOf(item.getSharingRate()));
                        record.setUpdateBy(updateBy);
                        successCount += cmsRegistrationMerchantMapper.updateCmsRegistrationMerchant(record);
                    }
                } else if (item.getMemberId() != null && item.getTopicId() != null) {
                    // 创建查询条件
                    CmsRegistrationMerchant query = new CmsRegistrationMerchant();
                    query.setMemberId(item.getMemberId());
                    query.setTopicId(item.getTopicId());

                    List<CmsRegistrationMerchant> records = cmsRegistrationMerchantMapper.selectCmsRegistrationMerchantList(query);
                    if (records != null && !records.isEmpty()) {
                        for (CmsRegistrationMerchant record : records) {
                            record.setSharingRate(BigDecimal.valueOf(item.getSharingRate()));
                            record.setUpdateBy(updateBy);
                            successCount += cmsRegistrationMerchantMapper.updateCmsRegistrationMerchant(record);
                        }
                    }
                }
            }
        }

        return successCount;
    }

    /**
     * 批量删除商家主题报名
     * 
     * @param ids 需要删除的商家主题报名主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteCmsRegistrationMerchantByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0) {
            throw new ServiceException("商家报名ID不能为空", HttpStatus.CONFLICT);
        }
        TreeSet<Long> ordered = new TreeSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                throw new ServiceException("商家报名ID无效", HttpStatus.CONFLICT);
            }
            ordered.add(id);
        }
        List<CmsRegistrationMerchant> locked = new ArrayList<>();
        for (Long id : ordered) {
            locked.add(cmsRegistrationMerchantMapper.selectForDeletionByIdForUpdate(id));
        }
        // 所有目标锁定后再复验。与 Controller 原 scope 规则相同，不把快照预检当删除授权。
        for (CmsRegistrationMerchant current : locked) {
            if (current == null || current.getTopicId() == null
                    || !topicOperationsScopeAuthorizer.canViewTopic(current.getTopicId())) {
                throw new ServiceException("商家报名不存在或当前账号无操作范围", HttpStatus.CONFLICT);
            }
            if (Long.valueOf(1L).equals(current.getAuditStatus())) {
                throw new ServiceException("已中标报名不可直接删除，请先取消承接", HttpStatus.CONFLICT);
            }
        }
        int deleted = cmsRegistrationMerchantMapper.deleteCmsRegistrationMerchantByIds(ordered.toArray(new Long[0]));
        if (deleted != ordered.size()) {
            throw new ServiceException("商家报名状态已变化，整批删除已取消", HttpStatus.CONFLICT);
        }
        return deleted;
    }

    /**
     * 删除商家主题报名信息
     * 
     * @param id 商家主题报名主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteCmsRegistrationMerchantById(Long id)
    {
        return deleteCmsRegistrationMerchantByIds(new Long[]{id});
    }

    @Override
    public int countByTopic(Long topicId)
    {
        return cmsRegistrationMerchantMapper.countByTopicId(topicId);
    }

    @Override
    public java.util.Map<Long, Integer> countByTopics(java.util.List<Long> topicIds)
    {
        java.util.Map<Long, Integer> result = new java.util.HashMap<>();
        if (topicIds == null || topicIds.isEmpty()) {
            return result;
        }
        for (java.util.Map<String, Object> row : cmsRegistrationMerchantMapper.countByTopicIds(topicIds)) {
            Number topicId = (Number) row.get("topicId");
            Number count = (Number) row.get("merchantCount");
            if (topicId != null) {
                result.put(topicId.longValue(), count == null ? 0 : count.intValue());
            }
        }
        return result;
    }

    /**
     * [C5] 商家自行取消承接 —— 归属校验 + <b>已中标不得取消</b>。
     *
     * <p><b>为什么要拦已中标</b>:中标时 {@code CmsRegistrationMerchantController.select} 会建一个
     * {@code CmsActivity} + 复制一套票。承接记录被软删后<b>它们还挂在那里</b> ⇒ 活动与票成孤儿:
     * 玩家照样看得到、买得到,但承接关系已经没了。
     *
     * <p><b>口径不是新造的</b>:后台驳回路径({@code CmsRegistrationMerchantController:396-399},
     * HF-06/A-37)早有同款拦截,理由一字不差。C 端取消一直漏着这道,本方法把两边拉平。
     *
     * <p>⚠️ <b>「撤销中标流程」全仓无实现</b>(只活在那句报错文案里,施工图 C5 记着)。所以这里只能
     * <b>拒绝 + 引导</b>,不能代替它 —— 商家中标后到底怎么退出,要等 P0-29 真做出来。
     * 堵住孤儿口子,不假装解决了退出问题。
     *
     * @param registrationId 承接报名 id
     * @param memberId       操作者(必须是本人)
     * @param username       操作者名,写 update_by
     * @return 是否取消成功
     */
    @Override
    public boolean cancelByMerchant(Long registrationId, Long memberId, String username) {
        CmsRegistrationMerchant registration =
                cmsRegistrationMerchantMapper.selectCmsRegistrationMerchantById(registrationId);
        if (registration == null) {
            throw new RuntimeException("报名记录不存在");
        }
        if (registration.getMemberId() == null || !registration.getMemberId().equals(memberId)) {
            throw new RuntimeException("无权取消他人报名");
        }
        // 只拦 ==1(已中标),与 :397 的既有口径一致:null/0(待审)/2(驳回)都没建过活动,放行
        if (registration.getAuditStatus() != null && registration.getAuditStatus() == 1L) {
            throw new RuntimeException("该报名已中标,不能直接取消;已发布的活动与票需一并处理,请走撤销中标流程");
        }

        // The read above only supplies a useful denial. Owner/review/deletion must still match at the write.
        return cmsRegistrationMerchantMapper.cancelUnwonByMember(
                registrationId, memberId, username, new java.util.Date()) == 1;
    }
}
