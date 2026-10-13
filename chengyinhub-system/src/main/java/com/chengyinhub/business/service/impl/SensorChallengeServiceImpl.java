package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.ISensorChallengeService;
import com.chengyinhub.business.service.support.SensorChallengePolicy;
import com.chengyinhub.common.constant.ValidationMethod;

@Service
public class SensorChallengeServiceImpl implements ISensorChallengeService
{
    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;

    @Override
    public String validateAndAcquire(Long memberId, Long activityId, CmsTopicNode node, CmsMemberTemplate template,
            String sensorType, Map<String, Object> payload)
    {
        if (template == null || template.getValidationMethod() == null
                || template.getValidationMethod() != ValidationMethod.APP_SENSOR) {
            return "该节点不是 App 传感器玩法";
        }

        try {
            SensorChallengePolicy.validateSubmissionAndFingerprint(sensorType, payload,
                    template.getSensorType(), template.getSensorConfig());
        } catch (IllegalArgumentException e) {
            return e.getMessage() == null ? "传感器数据无效" : e.getMessage();
        }

        try {
            if (alreadyCompleted(memberId, activityId, node)) return null;
            return "传感器可信证明缺失，暂不能完成挑战";
        } catch (Exception e) {
            return "上报服务暂不可用，请稍后重试";
        }
    }

    private boolean alreadyCompleted(Long memberId, Long activityId, CmsTopicNode node)
    {
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        probe.setActivityId(activityId);
        probe.setTopicId(node.getTopicId());
        probe.setNodeId(node.getId());
        List<PlayerNodeProgress> rows = playerNodeProgressService.selectPlayerNodeProgressList(probe);
        if (rows == null) return false;
        for (PlayerNodeProgress row : rows) {
            if (row != null && Integer.valueOf(1).equals(row.getStatus())) return true;
        }
        return false;
    }
}
