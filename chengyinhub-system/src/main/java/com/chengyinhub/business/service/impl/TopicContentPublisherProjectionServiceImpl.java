package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicContentPublisherDTO;
import com.chengyinhub.business.mapper.ProductRoleMapper;
import com.chengyinhub.business.service.ITopicContentPublisherProjectionService;
import com.chengyinhub.common.constant.RoleEntityType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** app 端主题列表「来源」栏的批量只读投影。 */
@Service
public class TopicContentPublisherProjectionServiceImpl implements ITopicContentPublisherProjectionService {

    /** PLATFORM 槽的对外称谓。平台不是某个 member，没有可读的账号名，只能是固定文案。 */
    static final String PLATFORM_DISPLAY_NAME = "官方";

    /** 槽指向的俱乐部查不到名字（已删或脏数据）时的兜底。不能留空，前端会渲染成无来源。 */
    static final String CLUB_NAME_UNAVAILABLE = "俱乐部信息待确认";

    /** 槽指向的会员查不到昵称时的兜底。 */
    static final String MEMBER_NAME_UNAVAILABLE = "会员信息待确认";

    /**
     * 主体类型不在 PLATFORM/MEMBER/CLUB 三态内时的兜底。
     * 库里有 CHECK 约束 ck_role_active_content_owner 挡着（生效 CONTENT 槽不许是 MERCHANT），
     * 正常到不了这里；但约束是 2026-07-23 才加的，历史脏行仍可能存在，
     * 所以宁可显式标「待确认」，也不要静默当成官方或直接抛异常打挂整个列表。
     */
    static final String ENTITY_UNAVAILABLE = "主体待确认";

    private final ProductRoleMapper productRoleMapper;

    public TopicContentPublisherProjectionServiceImpl(ProductRoleMapper productRoleMapper) {
        this.productRoleMapper = productRoleMapper;
    }

    @Override
    public List<CmsTopic> project(List<CmsTopic> topics) {
        if (topics == null || topics.isEmpty()) {
            return topics;
        }

        Set<Long> topicIds = new LinkedHashSet<Long>();
        for (CmsTopic topic : topics) {
            if (topic != null && topic.getId() != null) {
                topicIds.add(topic.getId());
            }
        }
        if (topicIds.isEmpty()) {
            return topics;
        }

        List<TopicContentPublisherDTO> rows =
                productRoleMapper.selectActiveContentPublisherByTopicIds(new ArrayList<Long>(topicIds));
        Map<Long, TopicContentPublisherDTO> byTopicId = new LinkedHashMap<Long, TopicContentPublisherDTO>();
        if (rows != null) {
            for (TopicContentPublisherDTO row : rows) {
                if (row != null && row.getTopicId() != null) {
                    byTopicId.put(row.getTopicId(), row);
                }
            }
        }

        for (CmsTopic topic : topics) {
            if (topic == null || topic.getId() == null) {
                continue;
            }
            TopicContentPublisherDTO row = byTopicId.get(topic.getId());
            // 无槽时两个字段都保持 null —— 不写入任何兜底值，历史待归属不能冒充官方。
            if (row == null) {
                continue;
            }
            topic.setContentPublisherType(row.getEntityType());
            topic.setContentPublisherName(displayName(row));
        }

        // PageHelper 把 total 挂在原始 List 容器上，必须原样返回同一个实例。
        return topics;
    }

    /**
     * 五态显示名映射 —— 与后台 TopicOperationalSummaryMapper 的 CASE 同口径。
     * 这里是 app 侧唯一算显示名的地方，改口径只改这一个方法。
     */
    static String displayName(TopicContentPublisherDTO row) {
        String entityType = row.getEntityType();
        if (RoleEntityType.PLATFORM.equals(entityType)) {
            return PLATFORM_DISPLAY_NAME;
        }
        if (RoleEntityType.CLUB.equals(entityType)) {
            return blankTo(row.getClubName(), CLUB_NAME_UNAVAILABLE);
        }
        if (RoleEntityType.MEMBER.equals(entityType)) {
            return blankTo(row.getMemberNickname(), MEMBER_NAME_UNAVAILABLE);
        }
        return ENTITY_UNAVAILABLE;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
