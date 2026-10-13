package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.utils.SecurityUtils;
import org.springframework.stereotype.Service;

/**
 * P0 的 fail-closed 读取闸门。
 *
 * <p>当前仓库没有“后台账号 → 主题受托范围”的权威数据源。系统超级管理员（RuoYi 固定 userId=1）
 * 拥有平台全局范围；其余账号在该真源落地前不得仅凭菜单权限枚举主题并读取商家或俱乐部关系摘要。
 * 后续替换本实现时必须同时补齐撤销和审计回读。</p>
 */
@Service
public class TopicOperationsScopeAuthorizer implements ITopicOperationsScopeAuthorizer {

    @Override
    public boolean hasGlobalTopicScope() {
        return SecurityUtils.isAdmin(SecurityUtils.getUserId());
    }

    @Override
    public boolean canViewTopic(Long topicId) {
        return topicId != null && hasGlobalTopicScope();
    }

    @Override
    public boolean canEditTopic(Long topicId) {
        return topicId != null && hasGlobalTopicScope();
    }
}
