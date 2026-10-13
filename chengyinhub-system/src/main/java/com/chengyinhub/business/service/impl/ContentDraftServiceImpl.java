package com.chengyinhub.business.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.ContentDraft;
import com.chengyinhub.business.domain.dto.ContentDraftCommand;
import com.chengyinhub.business.mapper.ContentDraftMapper;
import com.chengyinhub.business.service.IContentDraftService;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.common.exception.ServiceException;

@Service
public class ContentDraftServiceImpl implements IContentDraftService {
    @Autowired
    private ContentDraftMapper draftMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ContentDraft save(Long ownerMemberId, ContentDraftCommand command) {
        requireOwner(ownerMemberId);
        requireCommand(command);
        String type = requireType(command.getBusinessType());
        String payload = canonicalPayload(command.getPayloadJson());
        String hash = CanonicalDigest.sha256Hex(payload);
        long expected = command.getExpectedVersion() == null ? -1L : command.getExpectedVersion();
        if (expected < 0) throw badRequest("expectedVersion 不能为空");

        if (expected == 0) {
            requireKey(command.getClientDraftKey(), "clientDraftKey");
            ContentDraft draft = new ContentDraft();
            draft.setOwnerMemberId(ownerMemberId);
            draft.setBusinessType(type);
            draft.setClientDraftKey(command.getClientDraftKey().trim());
            draft.setSubjectId(command.getSubjectId());
            draft.setPayloadJson(payload);
            draft.setPayloadHash(hash);
            draft.setUpdatedByDevice(requireDevice(command.getDeviceId()));
            try {
                if (draftMapper.insertDraft(draft) != 1) throw new ServiceException("草稿保存失败", 409);
            } catch (DuplicateKeyException e) {
                ContentDraft existing = draftMapper.selectByBusinessKey(ownerMemberId, type,
                        draft.getClientDraftKey());
                if (sameDraft(existing, hash, command.getSubjectId())) return existing;
                throw conflict("clientDraftKey 已被不同草稿占用");
            }
            return draftMapper.selectOwnedById(draft.getId(), ownerMemberId);
        }

        if (command.getId() == null || command.getId() <= 0) throw badRequest("draftId 不能为空");
        ContentDraft update = new ContentDraft();
        update.setId(command.getId());
        update.setOwnerMemberId(ownerMemberId);
        update.setSubjectId(command.getSubjectId());
        update.setPayloadJson(payload);
        update.setPayloadHash(hash);
        update.setUpdatedByDevice(requireDevice(command.getDeviceId()));
        if (draftMapper.updateDraftCas(update, expected) == 1) {
            return draftMapper.selectOwnedById(command.getId(), ownerMemberId);
        }
        ContentDraft current = draftMapper.selectOwnedById(command.getId(), ownerMemberId);
        if (current != null && ContentDraft.STATUS_DRAFT.equals(current.getStatus())
                && Long.valueOf(expected + 1).equals(current.getVersion())
                && hash.equals(current.getPayloadHash())
                && (command.getSubjectId() == null ? current.getSubjectId() == null
                        : command.getSubjectId().equals(current.getSubjectId()))) return current;
        throw conflict("草稿版本冲突，请先恢复云端最新版本");
    }

    @Override
    public ContentDraft restore(Long ownerMemberId, Long draftId) {
        requireOwner(ownerMemberId);
        ContentDraft draft = draftId == null ? null : draftMapper.selectOwnedById(draftId, ownerMemberId);
        if (draft == null || !ContentDraft.STATUS_DRAFT.equals(draft.getStatus())) {
            throw new ServiceException("草稿不存在或已结束", 404);
        }
        return draft;
    }

    @Override
    public List<ContentDraft> list(Long ownerMemberId, String businessType) {
        requireOwner(ownerMemberId);
        return draftMapper.selectActiveList(ownerMemberId,
                businessType == null || businessType.trim().isEmpty() ? null : requireType(businessType));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ContentDraft delete(Long ownerMemberId, ContentDraftCommand command) {
        requireOwner(ownerMemberId);
        requireVersioned(command);
        long expected = command.getExpectedVersion();
        if (draftMapper.deleteDraftCas(command.getId(), ownerMemberId, expected) == 1) {
            return draftMapper.selectOwnedById(command.getId(), ownerMemberId);
        }
        ContentDraft current = draftMapper.selectOwnedById(command.getId(), ownerMemberId);
        if (current != null && ContentDraft.STATUS_DELETED.equals(current.getStatus())
                && Long.valueOf(expected + 1).equals(current.getVersion())) return current;
        throw conflict("草稿版本冲突或已结束");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ContentDraft publish(Long ownerMemberId, ContentDraftCommand command) {
        requireOwner(ownerMemberId);
        requireVersioned(command);
        if (command.getPublishedResourceId() == null || command.getPublishedResourceId() <= 0) {
            throw badRequest("publishedResourceId 不能为空");
        }
        ContentDraft current = draftMapper.selectOwnedById(command.getId(), ownerMemberId);
        if (current == null) throw new ServiceException("草稿不存在", 404);
        if (draftMapper.countOwnedResource(current.getBusinessType(), command.getPublishedResourceId(),
                ownerMemberId) != 1) {
            throw new ServiceException("发布资源不存在或不属于当前用户", 403);
        }
        long expected = command.getExpectedVersion();
        if (draftMapper.publishDraftCas(command.getId(), ownerMemberId, expected,
                command.getPublishedResourceId()) == 1) {
            return draftMapper.selectOwnedById(command.getId(), ownerMemberId);
        }
        current = draftMapper.selectOwnedById(command.getId(), ownerMemberId);
        if (current != null && ContentDraft.STATUS_PUBLISHED.equals(current.getStatus())
                && Long.valueOf(expected + 1).equals(current.getVersion())
                && command.getPublishedResourceId().equals(current.getPublishedResourceId())) return current;
        throw conflict("草稿版本冲突或已结束");
    }

    private String canonicalPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.trim().isEmpty()) throw badRequest("payloadJson 不能为空");
        try {
            return JSON.toJSONString(JSON.parse(payloadJson));
        } catch (Exception e) {
            throw badRequest("payloadJson 不是合法 JSON");
        }
    }

    private boolean sameDraft(ContentDraft existing, String hash, Long subjectId) {
        return existing != null && ContentDraft.STATUS_DRAFT.equals(existing.getStatus())
                && hash.equals(existing.getPayloadHash())
                && (subjectId == null ? existing.getSubjectId() == null : subjectId.equals(existing.getSubjectId()));
    }

    private String requireType(String type) {
        String value = type == null ? "" : type.trim().toUpperCase();
        if (!ContentDraft.TYPE_ACTIVITY.equals(value) && !ContentDraft.TYPE_TOPIC.equals(value)) {
            throw badRequest("businessType 仅支持 ACTIVITY、TOPIC");
        }
        return value;
    }

    private String requireDevice(String deviceId) {
        requireKey(deviceId, "deviceId");
        return deviceId.trim();
    }

    private void requireCommand(ContentDraftCommand command) {
        if (command == null) throw badRequest("请求不能为空");
    }

    private void requireVersioned(ContentDraftCommand command) {
        requireCommand(command);
        if (command.getId() == null || command.getId() <= 0
                || command.getExpectedVersion() == null || command.getExpectedVersion() <= 0) {
            throw badRequest("draftId 与 expectedVersion 必须为正数");
        }
    }

    private void requireKey(String value, String label) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 64) {
            throw badRequest(label + " 必须为 1-64 个字符");
        }
    }

    private void requireOwner(Long ownerMemberId) {
        if (ownerMemberId == null || ownerMemberId <= 0) throw new ServiceException("请先登录", 401);
    }

    private ServiceException badRequest(String message) { return new ServiceException(message, 400); }
    private ServiceException conflict(String message) { return new ServiceException(message, 409); }
}
