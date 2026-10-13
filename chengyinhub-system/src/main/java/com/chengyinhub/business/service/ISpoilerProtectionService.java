package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.SpoilerAccessSnapshot;

import java.util.Map;

public interface ISpoilerProtectionService {
    String CONTENT_NODE = "NODE";

    SpoilerAccessSnapshot snapshot(Long memberId, String contentType);
    boolean updatePreference(Long memberId, boolean revealAll);
    void reveal(Long memberId, String contentType, Long contentId, String requestId);
    Map<String, Object> project(SpoilerAccessSnapshot snapshot, Long contentId, String answerReveal);
}
