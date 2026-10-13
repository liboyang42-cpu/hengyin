package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ViewCreativeSquare;

/** 广场 Feed、Block 可见性和举报输入的统一社区策略。 */
public interface ICreativeSquareCommunityPolicyService
{
    /** 把客户端 Feed 选择收口成可交给 Mapper 的查询条件。 */
    void applyFeedPolicy(ViewCreativeSquare query, String feedMode, Long viewerMemberId);

    /** 当查看者与作者任一方屏蔽对方时，不对外暴露帖文。 */
    boolean isHiddenByBlock(Long viewerMemberId, Long authorMemberId);

    /** 规范化举报原因；无效输入抛出可安全展示的业务提示。 */
    String normalizeReportReason(String reason);
}
