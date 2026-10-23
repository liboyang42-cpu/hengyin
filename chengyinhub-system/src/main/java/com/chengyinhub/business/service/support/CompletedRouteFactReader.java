package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CompletedRouteFactMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 登录会员完成列表的路线来源。memberId 必须取自调用入口的已认证主体。
 * 仅供候选发现；不授予主题读取、奖励、报名、核销或结算权限。
 */
@Service
public class CompletedRouteFactReader {
    private final CompletedRouteFactMapper mapper;

    public CompletedRouteFactReader(CompletedRouteFactMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<TopicRouteSession> findCurrentCompletedRoutes(Long memberId) {
        if (memberId == null || memberId <= 0) {
            throw new IllegalArgumentException("完成事实查询必须指定已登录会员");
        }
        return mapper.selectCurrentCompletedRoutes(memberId);
    }
}
