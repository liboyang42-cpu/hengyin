package com.chengyinhub.business.service;

import java.util.Map;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;

public interface ISensorChallengeService
{
    /** 校验 vm=7 上报；未接入可信设备证明前仅允许已完成节点幂等回读。 */
    String validateAndAcquire(Long memberId, Long activityId, CmsTopicNode node, CmsMemberTemplate template,
            String sensorType, Map<String, Object> payload);
}
