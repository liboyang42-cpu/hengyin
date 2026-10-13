package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ViewCreativeSquare;
import com.chengyinhub.business.service.ICreativeSquareCommunityPolicyService;
import com.chengyinhub.business.service.IImService;
import org.springframework.stereotype.Service;

/** 广场社区策略真源，避免列表、详情和举报各自解释一套规则。 */
@Service
public class CreativeSquareCommunityPolicyServiceImpl implements ICreativeSquareCommunityPolicyService
{
    private static final String FEED_LATEST = "latest";
    private static final String FEED_FOLLOWING = "following";
    private static final int MAX_REPORT_REASON_LENGTH = 64;

    private final IImService imService;

    public CreativeSquareCommunityPolicyServiceImpl(IImService imService)
    {
        this.imService = imService;
    }

    @Override
    public void applyFeedPolicy(ViewCreativeSquare query, String feedMode, Long viewerMemberId)
    {
        String normalizedMode = feedMode == null || feedMode.trim().length() == 0
                ? FEED_LATEST : feedMode.trim();
        if (!FEED_LATEST.equals(normalizedMode) && !FEED_FOLLOWING.equals(normalizedMode))
        {
            throw new IllegalArgumentException("不支持的广场流");
        }
        if (viewerMemberId != null && viewerMemberId > 0)
        {
            query.setViewerMemberId(viewerMemberId);
        }
        if (FEED_FOLLOWING.equals(normalizedMode))
        {
            if (viewerMemberId == null || viewerMemberId <= 0)
            {
                throw new IllegalArgumentException("请先登录");
            }
            query.setFollowingMemberId(viewerMemberId);
        }
    }

    @Override
    public boolean isHiddenByBlock(Long viewerMemberId, Long authorMemberId)
    {
        return viewerMemberId != null && viewerMemberId > 0
                && authorMemberId != null && authorMemberId > 0
                && !viewerMemberId.equals(authorMemberId)
                && imService.isBlockedBetween(viewerMemberId, authorMemberId);
    }

    @Override
    public String normalizeReportReason(String reason)
    {
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.length() == 0 || normalizedReason.length() > MAX_REPORT_REASON_LENGTH)
        {
            throw new IllegalArgumentException("请选择举报原因");
        }
        return normalizedReason;
    }
}
