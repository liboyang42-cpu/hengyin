package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MemberSpoilerReveal;
import com.chengyinhub.business.domain.vo.SpoilerAccessSnapshot;
import com.chengyinhub.business.mapper.SpoilerProtectionMapper;
import com.chengyinhub.business.service.ISpoilerProtectionService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SpoilerProtectionServiceImpl implements ISpoilerProtectionService {
    public static final String ERROR_REQUEST_REUSED = "SPOILER_REQUEST_REUSED";
    public static final String ERROR_INVALID_TARGET = "SPOILER_INVALID_TARGET";

    @Autowired private SpoilerProtectionMapper mapper;

    @Override
    public SpoilerAccessSnapshot snapshot(Long memberId, String contentType) {
        requireTarget(memberId, contentType, 1L);
        Boolean revealAll = mapper.selectRevealAll(memberId);
        List<Long> revealed = mapper.selectRevealedContentIds(memberId, contentType);
        return new SpoilerAccessSnapshot(Boolean.TRUE.equals(revealAll),
                revealed == null ? null : new HashSet<>(revealed));
    }

    @Override
    public boolean updatePreference(Long memberId, boolean revealAll) {
        if (memberId == null || memberId <= 0) throw invalidTarget();
        // MySQL upsert may affect 0 (unchanged), 1 (insert), or 2 (update) rows.
        mapper.upsertPreference(memberId, revealAll);
        return true;
    }

    @Override
    public void reveal(Long memberId, String contentType, Long contentId, String requestId) {
        requireTarget(memberId, contentType, contentId);
        if (requestId == null || requestId.trim().isEmpty() || requestId.length() > 64) {
            throw invalidTarget();
        }
        MemberSpoilerReveal replay = mapper.selectRevealByRequestId(memberId, requestId);
        if (replay != null) {
            if (!contentType.equals(replay.getContentType()) || !contentId.equals(replay.getContentId())) {
                throw new ServiceException("同一请求键不能揭晓不同内容", HttpStatus.CONFLICT)
                        .setDetailMessage(ERROR_REQUEST_REUSED);
            }
            return;
        }
        MemberSpoilerReveal reveal = new MemberSpoilerReveal();
        reveal.setMemberId(memberId);
        reveal.setContentType(contentType);
        reveal.setContentId(contentId);
        reveal.setRequestId(requestId);
        reveal.setRevealedAt(new Date());
        if (mapper.insertReveal(reveal) == 1) return;
        // insert ignore 只把并发唯一键竞争转成 0；回读请求键，区分内容幂等与键复用冲突。
        MemberSpoilerReveal after = mapper.selectRevealByRequestId(memberId, requestId);
        if (after != null && (!contentType.equals(after.getContentType())
                || !contentId.equals(after.getContentId()))) {
            throw new ServiceException("同一请求键不能揭晓不同内容", HttpStatus.CONFLICT)
                    .setDetailMessage(ERROR_REQUEST_REUSED);
        }
    }

    @Override
    public Map<String, Object> project(SpoilerAccessSnapshot snapshot, Long contentId, String answerReveal) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spoilerProtected", true);
        boolean revealed = snapshot != null && snapshot.shouldReveal(contentId);
        out.put("revealed", revealed);
        if (revealed && answerReveal != null && !answerReveal.trim().isEmpty()) {
            out.put("answerReveal", answerReveal);
        }
        return out;
    }

    private void requireTarget(Long memberId, String contentType, Long contentId) {
        if (memberId == null || memberId <= 0 || contentId == null || contentId <= 0
                || !CONTENT_NODE.equals(contentType)) throw invalidTarget();
    }

    private ServiceException invalidTarget() {
        return new ServiceException("剧透保护目标无效", HttpStatus.BAD_REQUEST)
                .setDetailMessage(ERROR_INVALID_TARGET);
    }
}
