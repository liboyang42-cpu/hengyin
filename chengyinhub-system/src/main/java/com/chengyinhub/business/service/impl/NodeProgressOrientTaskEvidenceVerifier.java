package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.OrientSessionMapper;
import com.chengyinhub.business.service.OrientTaskEvidenceVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 默认证据:既有已校验的推进入口(checkin/arrive/answer/photo…)在本局开赛之后写下的节点完成。
 * 这里不重复做定位/答案校验,也不采信客户端时间。
 */
@Component
public class NodeProgressOrientTaskEvidenceVerifier implements OrientTaskEvidenceVerifier {

    @Autowired private OrientSessionMapper orientMapper;

    @Override
    public Date verifiedCompletion(Context c) {
        if (c == null || c.startedAt == null) return null;
        return orientMapper.selectVerifiedCompletion(c.memberId, c.activityId, c.topicId, c.taskId,
                c.playInstanceId, c.startedAt);
    }
}
