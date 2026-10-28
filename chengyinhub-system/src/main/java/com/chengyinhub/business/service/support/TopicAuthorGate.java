package com.chengyinhub.business.service.support;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.util.TopicEditWindow;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.OwnershipUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 文档二 L8 新增创作者接口(可玩时段、属性、按名字建键)的归属闸与编辑窗口闸。
 *
 * <p>口径与 {@code ApiTopicStateKeyController} 一字不差:登录 → 可选商家 Owner 代管
 * (scope=MERCHANT,需 PROJECT_MANAGE)→ 主题本人;写接口另走 {@link TopicEditWindow},售卖冻结时拒。
 */
@Component
public class TopicAuthorGate {

    public static final String MSG_FROZEN = "这个主题现在不能修改。";
    public static final String ERR_FROZEN = "TOPIC_FROZEN";

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    @Autowired
    private IUmsMemberService umsMemberService;

    /** 通过返回 null;否则返回拒绝原话。memberId 为空 = 未登录。 */
    public String denial(Long memberId, CmsTopic topic, String scope) {
        if (memberId == null || memberId <= 0L) {
            return "请先登录";
        }
        Long owner = memberId;
        if (MerchantOperationScope.isMerchant(scope)) {
            try {
                owner = merchantAccessService.require(memberId, MerchantPermission.PROJECT_MANAGE).getOwnerMemberId();
            } catch (ServiceException e) {
                return e.getMessage();
            }
        }
        if (topic == null || (topic.getDelFlag() != null && topic.getDelFlag() == 2)) {
            return "主题不存在";
        }
        return OwnershipUtils.notOwner(topic.getMemberId(), owner) ? "无权操作该主题" : null;
    }

    /** 售卖冻结返回 true(写接口拒,原话 {@link #MSG_FROZEN});生命周期判不出时按冻结处理。 */
    public boolean frozen(CmsTopic topic) {
        try {
            return TopicEditWindow.resolve(topic, cmsTopicService.selectTopicIsDraft(topic.getId()), new Date())
                    == TopicEditWindow.Scope.SELLING_FROZEN;
        } catch (IllegalStateException e) {
            return true;
        }
    }

    public CmsTopic topic(Long topicId) {
        return topicId == null || topicId <= 0L ? null : cmsTopicService.selectCmsTopicById(topicId);
    }

    /** 内容安全送检用的 openid;查不到返回 null(由安全服务降级为 scene 检测)。 */
    public String openid(Long memberId) {
        if (memberId == null || memberId <= 0L) return null;
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        return member == null ? null : member.getWxOpenId();
    }
}
