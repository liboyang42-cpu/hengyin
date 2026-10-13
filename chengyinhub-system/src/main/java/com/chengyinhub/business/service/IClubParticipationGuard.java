package com.chengyinhub.business.service;

/** 俱乐部参与准入单一 seam：入会、群聊、发帖和评论都必须调用。 */
public interface IClubParticipationGuard {
    void requireAllowed(Long clubId, Long memberId);
}
