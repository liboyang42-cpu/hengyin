package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ITopicContentOwnershipService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.constant.RoleEntityType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 内容槽改判的生命周期和主体有效性闸门；底层服务保留旧槽而不删除，负责值变化审计。 */
@Service
public class TopicContentOwnershipServiceImpl implements ITopicContentOwnershipService {

    private final ICmsTopicService topicService;
    private final IProductRoleService productRoleService;
    private final ClubMapper clubMapper;
    private final UmsMemberMapper memberMapper;

    private final ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    @Autowired
    public TopicContentOwnershipServiceImpl(ICmsTopicService topicService,
                                            IProductRoleService productRoleService,
                                            ClubMapper clubMapper,
                                            UmsMemberMapper memberMapper,
                                            ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer) {
        this.topicService = topicService;
        this.productRoleService = productRoleService;
        this.clubMapper = clubMapper;
        this.memberMapper = memberMapper;
        this.topicOperationsScopeAuthorizer = topicOperationsScopeAuthorizer;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean reassignContentSlot(Long topicId, String entityType, Long entityId) {
        if (topicId == null) {
            throw new IllegalArgumentException("主题ID不能为空");
        }
        if (!topicOperationsScopeAuthorizer.canViewTopic(topicId)) {
            throw new IllegalArgumentException("主题不存在或当前账号无操作范围");
        }
        CmsTopic topic = topicService.selectCmsTopicById(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
            throw new IllegalArgumentException("主题不存在或已删除");
        }
        if (topic.getLifecycle() == null || topic.getLifecycle() >= 4) {
            throw new IllegalArgumentException("执行或结算阶段不得改判内容归属；请走财务治理流程");
        }
        validateContentSubject(entityType, entityId);
        return productRoleService.reassignContentSlot(topicId, entityType, entityId);
    }

    private void validateContentSubject(String entityType, Long entityId) {
        if (RoleEntityType.PLATFORM.equals(entityType) && entityId == null) {
            return;
        }
        if (RoleEntityType.CLUB.equals(entityType) && entityId != null) {
            Club club = clubMapper.selectClubById(entityId);
            if (club == null || !Long.valueOf(1L).equals(club.getStatus())
                    || Integer.valueOf(2).equals(club.getDelFlag())) {
                throw new IllegalArgumentException("内容主体俱乐部不存在、未启用或已删除");
            }
            return;
        }
        if (RoleEntityType.MEMBER.equals(entityType) && entityId != null) {
            UmsMember member = memberMapper.selectUmsMemberById(entityId);
            if (member == null || !Long.valueOf(1L).equals(member.getStatus())
                    || Integer.valueOf(2).equals(member.getDelFlag())) {
                throw new IllegalArgumentException("内容主体会员不存在、已停用或已删除");
            }
            return;
        }
        throw new IllegalArgumentException("内容主体须为 PLATFORM/MEMBER/CLUB，且 PLATFORM 不得带 entityId、其余必须带 entityId");
    }
}
