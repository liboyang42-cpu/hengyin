package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.PlayerTopicTag;
import com.chengyinhub.business.mapper.PlayerTopicTagMapper;
import com.chengyinhub.business.service.IPlayerTopicTagService;
import com.chengyinhub.business.service.support.PreferenceResultEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class PlayerTopicTagServiceImpl implements IPlayerTopicTagService {
    @Autowired
    private PlayerTopicTagMapper mapper;

    @Override
    public PlayerTopicTag upsertPending(Long memberId, Long topicId, String tagCode, String tagValue, Long sourceNodeId) {
        if (memberId == null || memberId <= 0 || topicId == null || topicId <= 0 || sourceNodeId == null || sourceNodeId <= 0) {
            throw new IllegalArgumentException("偏好标签归属无效");
        }
        if (!PreferenceResultEvaluator.isAllowedTagCode(tagCode)) {
            throw new IllegalArgumentException("偏好标签不在白名单:" + tagCode);
        }
        if (tagValue == null || tagValue.trim().isEmpty() || tagValue.length() > 255) {
            throw new IllegalArgumentException("偏好标签档位无效");
        }
        PlayerTopicTag tag = new PlayerTopicTag();
        tag.setMemberId(memberId);
        tag.setTopicId(topicId);
        tag.setTagCode(tagCode);
        tag.setTagValue(tagValue.trim());
        tag.setSourceNodeId(sourceNodeId);
        tag.setStatus(0);
        Date now = new Date();
        tag.setCreateTime(now);
        tag.setUpdateTime(now);
        mapper.upsertPending(tag);
        return mapper.selectOwnedById(tag.getId(), memberId);
    }

    @Override
    public PlayerTopicTag ownedById(Long memberId, Long tagId) {
        return mapper.selectOwnedById(tagId, memberId);
    }

    @Override
    public PlayerTopicTag confirm(Long memberId, Long tagId) {
        PlayerTopicTag current = mapper.selectOwnedById(tagId, memberId);
        if (current == null || Integer.valueOf(2).equals(current.getStatus())) {
            throw new IllegalArgumentException("标签不存在或已撤回");
        }
        if (Integer.valueOf(1).equals(current.getStatus())) return current;
        if (mapper.confirmOwned(tagId, memberId) != 1) {
            current = mapper.selectOwnedById(tagId, memberId);
            if (current != null && Integer.valueOf(1).equals(current.getStatus())) return current;
            throw new IllegalArgumentException("标签不存在或不是待确认状态");
        }
        return mapper.selectOwnedById(tagId, memberId);
    }

    @Override
    public PlayerTopicTag revoke(Long memberId, Long tagId) {
        PlayerTopicTag current = mapper.selectOwnedById(tagId, memberId);
        if (current == null) throw new IllegalArgumentException("标签不存在");
        if (Integer.valueOf(2).equals(current.getStatus())) return current;
        if (mapper.revokeOwned(tagId, memberId) != 1) {
            current = mapper.selectOwnedById(tagId, memberId);
            if (current != null && Integer.valueOf(2).equals(current.getStatus())) return current;
            throw new IllegalArgumentException("标签撤回失败");
        }
        return mapper.selectOwnedById(tagId, memberId);
    }

    @Override
    public PlayerTopicTag correctPending(Long memberId, Long tagId, String tagValue, Set<String> allowedValues) {
        String value = tagValue == null ? null : tagValue.trim();
        if (value == null || allowedValues == null || !allowedValues.contains(value)) {
            throw new IllegalArgumentException("标签档位不在本站结果范围");
        }
        if (mapper.updatePendingValueOwned(tagId, memberId, value) != 1) {
            throw new IllegalArgumentException("只能修改自己尚未确认的标签");
        }
        return mapper.selectOwnedById(tagId, memberId);
    }

    @Override
    public List<PlayerTopicTag> pendingForNode(Long memberId, Long topicId, Long sourceNodeId) {
        return mapper.selectPendingByNode(memberId, topicId, sourceNodeId);
    }

    @Override
    public List<PlayerTopicTag> confirmedForConsumer(Long memberId, Long topicId, Set<String> declaredCodes) {
        if (declaredCodes == null || declaredCodes.isEmpty()) return Collections.emptyList();
        List<String> allowed = new ArrayList<String>();
        for (String code : declaredCodes) if (PreferenceResultEvaluator.isAllowedTagCode(code)) allowed.add(code);
        if (allowed.isEmpty()) return Collections.emptyList();
        List<PlayerTopicTag> rows = mapper.selectConfirmedByCodes(memberId, topicId, allowed);
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        Set<String> declared = new HashSet<String>(allowed);
        List<PlayerTopicTag> out = new ArrayList<PlayerTopicTag>();
        for (PlayerTopicTag row : rows) {
            if (row != null && Integer.valueOf(1).equals(row.getStatus()) && declared.contains(row.getTagCode())) out.add(row);
        }
        return out;
    }

    @Override
    public List<PlayerTopicTag> confirmedForOwner(Long memberId, Long topicId) {
        return mapper.selectConfirmedByTopic(memberId, topicId);
    }
}
