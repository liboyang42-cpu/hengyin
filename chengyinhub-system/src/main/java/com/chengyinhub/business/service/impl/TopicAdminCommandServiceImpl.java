package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.dto.CmsTopicEditDTO;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ITopicAdminCommandService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 受托范围内主题编辑的服务端状态机闸门。
 *
 * <p>Controller 仅负责权限与 HTTP 响应；字段白名单、内容主体和生命周期限制必须在此处
 * 统一执行。官方主题可走普通编辑白名单；俱乐部/会员主题只开放逐期漫游导流开关。</p>
 */
@Service
public class TopicAdminCommandServiceImpl implements ITopicAdminCommandService {

    private final ICmsTopicService topicService;
    private final IProductRoleService productRoleService;
    private final ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    @Autowired
    public TopicAdminCommandServiceImpl(ICmsTopicService topicService,
                                        IProductRoleService productRoleService,
                                        ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer) {
        this.topicService = topicService;
        this.productRoleService = productRoleService;
        this.topicOperationsScopeAuthorizer = topicOperationsScopeAuthorizer;
    }

    @Override
    public int editTopicWithinScope(CmsTopicEditDTO request) {
        if (request == null || request.getId() == null) {
            throw new IllegalArgumentException("主题ID不能为空");
        }
        ensureTopicWriteScope(request.getId());
        CmsTopic existing = topicService.selectCmsTopicById(request.getId());
        if (existing == null || Integer.valueOf(2).equals(existing.getDelFlag())) {
            throw new IllegalArgumentException("主题不存在或已删除");
        }
        if (!validBooleanFlag(request.getRoamReferralEnabled())) {
            throw new IllegalArgumentException("漫游导流开关只能为关闭或开启");
        }
        if (!productRoleService.isPlatformOwned(request.getId())) {
            ProductRole contentSlot = productRoleService.activeContentSlot(request.getId());
            if (!isRoamReferralOnly(request)
                    || contentSlot == null || !isPlazaContentPublisher(contentSlot.getEntityType())) {
                throw new IllegalArgumentException("非官方主题仅允许调整漫游导流开关；历史待归属主题请走归属流程");
            }
        }

        CmsTopic patch = editablePatch(request);
        if (!hasEditableTopicFields(patch)) {
            throw new IllegalArgumentException("未提供可编辑的主题字段；审核决定请在统一审核台处理");
        }
        if (!isEditAllowedAtLifecycle(existing.getLifecycle(), patch)) {
            throw new IllegalArgumentException("当前主题阶段不允许该编辑；阵容与结算请走对应运营流程");
        }
        return topicService.updateCmsTopic(patch);
    }

    @Override
    public int updatePlazaRecommendation(Long topicId, Integer isRecommend) {
        if (topicId == null) {
            throw new IllegalArgumentException("主题ID不能为空");
        }
        if (!Integer.valueOf(0).equals(isRecommend) && !Integer.valueOf(1).equals(isRecommend)) {
            throw new IllegalArgumentException("推荐状态只能为取消推荐或设为推荐");
        }
        ensureTopicWriteScope(topicId);

        CmsTopic existing = topicService.selectCmsTopicById(topicId);
        if (existing == null || Integer.valueOf(2).equals(existing.getDelFlag())) {
            throw new IllegalArgumentException("主题不存在或已删除");
        }

        ProductRole contentSlot = productRoleService.activeContentSlot(topicId);
        if (contentSlot == null || !isPlazaContentPublisher(contentSlot.getEntityType())) {
            throw new IllegalArgumentException("主题广场推荐只适用于俱乐部或会员发布主题");
        }

        CmsTopic patch = new CmsTopic();
        patch.setId(topicId);
        patch.setIsRecommend(isRecommend);
        return topicService.updateCmsTopic(patch);
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteOfficialTopics(Long[] topicIds) {
        if (topicIds == null || topicIds.length == 0) {
            throw new IllegalArgumentException("主题ID不能为空");
        }
        for (Long topicId : topicIds) {
            if (topicId == null) {
                throw new IllegalArgumentException("主题ID不能为空");
            }
            ensureTopicWriteScope(topicId);
            CmsTopic existing = topicService.selectCmsTopicById(topicId);
            if (existing == null || Integer.valueOf(2).equals(existing.getDelFlag())) {
                throw new IllegalArgumentException("主题不存在或已删除");
            }
            if (!productRoleService.isPlatformOwned(topicId)) {
                throw new IllegalArgumentException("删除只适用于官方主题；广场主题请走治理流程");
            }
        }

        for (Long topicId : topicIds) {
            CmsTopic patch = new CmsTopic();
            patch.setId(topicId);
            patch.setDelFlag(2);
            if (topicService.updateCmsTopic(patch) != 1) {
                throw new IllegalArgumentException("主题状态已变化，请刷新后重试");
            }
            // [6-09] 后台删除 = 不公开:自动同步的广场帖一起收起。
            topicService.syncCreativeSquarePostVisibility(topicId, false);
        }
        return topicIds.length;
    }

    private static boolean isPlazaContentPublisher(String entityType) {
        return "CLUB".equals(entityType) || "MEMBER".equals(entityType);
    }


    private void ensureTopicWriteScope(Long topicId) {
        if (!topicOperationsScopeAuthorizer.canEditTopic(topicId)) {
            throw new IllegalArgumentException("主题不存在或当前账号无操作范围");
        }
    }

    private static CmsTopic editablePatch(CmsTopicEditDTO request) {
        CmsTopic patch = new CmsTopic();
        patch.setId(request.getId());
        patch.setName(request.getName());
        patch.setSubtitle(request.getSubtitle());
        patch.setDescription(request.getDescription());
        patch.setImgUrl(request.getImgUrl());
        patch.setImgArr(request.getImgArr());
        patch.setStartDate(request.getStartDate());
        patch.setEndDate(request.getEndDate());
        patch.setMerchantSignUpStartDate(request.getMerchantSignUpStartDate());
        patch.setMerchantSignUpEndDate(request.getMerchantSignUpEndDate());
        patch.setIsRecommend(request.getIsRecommend());
        patch.setRoamReferralEnabled(request.getRoamReferralEnabled());
        return patch;
    }

    private static boolean hasEditableTopicFields(CmsTopic patch) {
        return patch.getName() != null || patch.getSubtitle() != null
                || patch.getDescription() != null || patch.getImgUrl() != null
                || patch.getImgArr() != null
                || patch.getStartDate() != null || patch.getEndDate() != null
                || patch.getMerchantSignUpStartDate() != null
                || patch.getMerchantSignUpEndDate() != null
                || patch.getIsRecommend() != null
                || patch.getRoamReferralEnabled() != null;
    }

    private static boolean validBooleanFlag(Integer value) {
        return value == null || Integer.valueOf(0).equals(value) || Integer.valueOf(1).equals(value);
    }

    /** 第三方内容仍不可由 CMS 改写；逐期运营开关只复用已有权限与对象级写授权。 */
    private static boolean isRoamReferralOnly(CmsTopicEditDTO request) {
        return request.getRoamReferralEnabled() != null
                && request.getName() == null && request.getSubtitle() == null
                && request.getDescription() == null && request.getImgUrl() == null
                && request.getImgArr() == null && request.getStartDate() == null
                && request.getEndDate() == null && request.getMerchantSignUpStartDate() == null
                && request.getMerchantSignUpEndDate() == null && request.getIsRecommend() == null;
    }

    /**
     * 普通编辑不负责推进状态机。生命周期未知和结算/关闭阶段默认拒绝；锁阵容及执行阶段仅保留低风险内容勘误。
     */
    private static boolean isEditAllowedAtLifecycle(Integer lifecycle, CmsTopic patch) {
        if (lifecycle == null || lifecycle >= 5) {
            return false;
        }
        if (lifecycle < 2) {
            return true;
        }
        return patch.getStartDate() == null && patch.getEndDate() == null
                && patch.getMerchantSignUpStartDate() == null
                && patch.getMerchantSignUpEndDate() == null
                && patch.getIsRecommend() == null;
    }
}
