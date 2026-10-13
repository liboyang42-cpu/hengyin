package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.ContentDraft;
import com.chengyinhub.business.domain.dto.ContentDraftCommand;

public interface IContentDraftService {
    ContentDraft save(Long ownerMemberId, ContentDraftCommand command);
    ContentDraft restore(Long ownerMemberId, Long draftId);
    List<ContentDraft> list(Long ownerMemberId, String businessType);
    ContentDraft delete(Long ownerMemberId, ContentDraftCommand command);
    ContentDraft publish(Long ownerMemberId, ContentDraftCommand command);
}
