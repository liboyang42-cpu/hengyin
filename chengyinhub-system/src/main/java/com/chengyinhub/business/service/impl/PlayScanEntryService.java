package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.PlayScanEntry;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/** 门口静态游戏码扫完：没票去主题购买页，有票进游玩。 */
@Service
public class PlayScanEntryService {

    private final ICmsTopicNodeService nodes;
    private final ICmsRegistrationService registrations;
    private final ICmsActivityService activities;

    public PlayScanEntryService(ICmsTopicNodeService nodes,
                                ICmsRegistrationService registrations,
                                ICmsActivityService activities) {
        this.nodes = nodes;
        this.registrations = registrations;
        this.activities = activities;
    }

    public PlayScanEntry resolve(Long memberId, String code) {
        if (StringUtils.isBlank(code)) {
            throw new ServiceException("参数有误");
        }
        CmsTopicNode probe = new CmsTopicNode();
        probe.setCheckinCode(code.trim());
        List<CmsTopicNode> found = nodes.selectCmsTopicNodeList(probe);
        if (found == null || found.isEmpty() || found.get(0) == null || found.get(0).getTopicId() == null) {
            throw new ServiceException("无效的打卡码");
        }
        CmsTopicNode node = found.get(0);
        Long topicId = node.getTopicId();
        Long activityId = paidActivityId(memberId, topicId);
        if (activityId != null) {
            return PlayScanEntry.play(topicId, node.getId(), activityId);
        }
        if (hasValidSelfPlayPass(memberId, topicId)) {
            return PlayScanEntry.play(topicId, node.getId(), null);
        }
        return PlayScanEntry.purchase(topicId, node.getId());
    }

    private Long paidActivityId(Long memberId, Long topicId) {
        if (memberId == null || memberId <= 0) {
            return null;
        }
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(2L);
        probe.setRegistrationStatus(2);
        List<CmsRegistration> regs = registrations.selectCmsRegistrationList(probe);
        if (regs == null) {
            return null;
        }
        for (CmsRegistration reg : regs) {
            if (reg == null || reg.getOwnerId() == null) {
                continue;
            }
            CmsActivity activity = activities.selectCmsActivityById(reg.getOwnerId());
            if (activity == null || !topicId.equals(activity.getTopicId())) {
                continue;
            }
            if (activityTimeError(activity) != null) {
                continue;
            }
            return activity.getId();
        }
        return null;
    }

    /** 与游玩页同一口径：未开始 / 已结束的场次不能当作门口码入口。 */
    private String activityTimeError(CmsActivity activity) {
        Date now = new Date();
        if (activity.getStartDate() != null && now.before(activity.getStartDate())) {
            return "活动未开始";
        }
        if (activity.getEndDate() != null && now.after(activity.getEndDate())) {
            return "活动已结束";
        }
        return null;
    }

    private boolean hasValidSelfPlayPass(Long memberId, Long topicId) {
        if (memberId == null || memberId <= 0) {
            return false;
        }
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(1L);
        probe.setOwnerId(topicId);
        probe.setRegistrationStatus(2);
        List<CmsRegistration> regs = registrations.selectCmsRegistrationList(probe);
        if (regs == null) {
            return false;
        }
        Date now = new Date();
        for (CmsRegistration reg : regs) {
            if (reg != null && (reg.getExpiresAt() == null || reg.getExpiresAt().after(now))) {
                return true;
            }
        }
        return false;
    }
}
