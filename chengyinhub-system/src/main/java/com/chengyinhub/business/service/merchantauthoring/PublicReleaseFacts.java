package com.chengyinhub.business.service.merchantauthoring;

/** Approved public copy for an existing NPC reader. Null when nothing is published for that scope. */
public interface PublicReleaseFacts {
    String publicPrompt(Long merchantId, Long themeId, Long releaseId, String runId, String bindingKey);

    /** Shop node carries the merchant member id, not the merchant row id. */
    String publicPromptForShopMember(Long merchantMemberId, Long themeId);
}
