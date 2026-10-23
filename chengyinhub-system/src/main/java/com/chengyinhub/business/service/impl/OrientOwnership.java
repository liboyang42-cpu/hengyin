package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.support.OrientRejection;
import com.chengyinhub.common.constant.ProductType;

/** 城市定向写操作的归属校验:只有主题发布者本人能改自己主题的定向配置。 */
final class OrientOwnership {

    private OrientOwnership() {
    }

    static void requireLogin(Long memberId) {
        if (memberId == null || memberId <= 0) {
            throw new OrientRejection(OrientRejection.LOGIN_REQUIRED, "请先登录");
        }
    }

    static CmsTopic requireTopic(ICmsTopicService topicService, Long topicId) {
        CmsTopic topic = topicId == null || topicId <= 0 ? null : topicService.selectCmsTopicById(topicId);
        if (topic == null) throw new OrientRejection(OrientRejection.NOT_FOUND, "主题不存在");
        return topic;
    }

    /** 主题存在、是城市定向(product_type=1)、且属于该成员。老主题(非定向)一律不受影响。 */
    static CmsTopic requireTopicOwner(ICmsTopicService topicService, Long memberId, Long topicId) {
        requireLogin(memberId);
        CmsTopic topic = requireTopic(topicService, topicId);
        if (!memberId.equals(topic.getMemberId())) {
            throw new OrientRejection(OrientRejection.NOT_OWNER, "只能管理自己发布的主题");
        }
        requireOrienteering(topic);
        return topic;
    }

    static void requireOrienteering(CmsTopic topic) {
        if (!Integer.valueOf(ProductType.ORIENTEERING).equals(topic.getProductType())) {
            throw new OrientRejection(OrientRejection.NOT_ORIENTEERING, "该主题不是城市定向主题");
        }
    }
}
