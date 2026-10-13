package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicContentPublisherDTO;
import com.chengyinhub.business.mapper.ProductRoleMapper;
import com.chengyinhub.business.service.impl.TopicContentPublisherProjectionServiceImpl;
import com.chengyinhub.common.constant.RoleEntityType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * app 端「来源」栏投影。
 *
 * <p>这些断言钉的是三件容易回退的事：无槽不许冒充官方、异常主体不许静默通过、
 * 以及批量查询只发一次（不能退化成 N+1）。
 */
class TopicContentPublisherProjectionServiceImplTest {

    @Test
    void project_mapsFiveStatesAndNeverGuessesOfficial() {
        ProductRoleMapper mapper = mock(ProductRoleMapper.class);
        ITopicContentPublisherProjectionService service =
                new TopicContentPublisherProjectionServiceImpl(mapper);

        CmsTopic official = topic(1L);
        CmsTopic club = topic(2L);
        CmsTopic member = topic(3L);
        CmsTopic historical = topic(4L);
        CmsTopic abnormal = topic(5L);
        CmsTopic clubWithoutName = topic(6L);
        List<CmsTopic> topics = new ArrayList<CmsTopic>(
                Arrays.asList(official, club, member, historical, abnormal, clubWithoutName));

        when(mapper.selectActiveContentPublisherByTopicIds(Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L)))
                .thenReturn(Arrays.asList(
                        row(1L, RoleEntityType.PLATFORM, null, null),
                        row(2L, RoleEntityType.CLUB, "夜航俱乐部", null),
                        row(3L, RoleEntityType.MEMBER, null, "阿岚"),
                        // 4L 刻意缺行 —— 历史待归属主题在库里就是没有 CONTENT 槽
                        row(5L, RoleEntityType.MERCHANT, null, null),
                        row(6L, RoleEntityType.CLUB, "   ", null)));

        service.project(topics);

        assertEquals(RoleEntityType.PLATFORM, official.getContentPublisherType());
        assertEquals("官方", official.getContentPublisherName());

        assertEquals(RoleEntityType.CLUB, club.getContentPublisherType());
        assertEquals("夜航俱乐部", club.getContentPublisherName());

        assertEquals(RoleEntityType.MEMBER, member.getContentPublisherType());
        assertEquals("阿岚", member.getContentPublisherName());

        // 无槽两个字段都必须留空。回退成「官方」= 把一批未归属主题冒充成平台自营。
        assertNull(historical.getContentPublisherType(), "无生效 CONTENT 槽不能猜成任何主体");
        assertNull(historical.getContentPublisherName(), "无槽时显示名必须留空，不能兜底成官方");

        // 生效 CONTENT 槽不许是 MERCHANT（库里有 ck_role_active_content_owner）。
        // 真出现脏行时要显式标出来，不能静默当成官方，也不能打挂整个列表。
        assertEquals(RoleEntityType.MERCHANT, abnormal.getContentPublisherType(),
                "异常主体类型必须原样带出，供前端渲染中性态");
        assertEquals("主体待确认", abnormal.getContentPublisherName());

        // 名字是空白串（不是 null）时也要走兜底，否则前端渲染出一个没有来源的卡。
        assertEquals("俱乐部信息待确认", clubWithoutName.getContentPublisherName());
    }

    @Test
    void project_queriesOnceForWholePageAndKeepsSameListInstance() {
        ProductRoleMapper mapper = mock(ProductRoleMapper.class);
        ITopicContentPublisherProjectionService service =
                new TopicContentPublisherProjectionServiceImpl(mapper);

        List<CmsTopic> topics = new ArrayList<CmsTopic>(
                Arrays.asList(topic(1L), topic(2L), topic(3L)));
        when(mapper.selectActiveContentPublisherByTopicIds(anyList()))
                .thenReturn(Collections.<TopicContentPublisherDTO>emptyList());

        List<CmsTopic> returned = service.project(topics);

        // 三个主题只能触发一次查询；退化成逐主题查会在列表页放大成 N+1。
        verify(mapper, times(1)).selectActiveContentPublisherByTopicIds(anyList());
        // PageHelper 把 total 挂在原始 List 容器上，换实例会丢分页总数。
        assertSame(topics, returned, "必须原样返回同一个 List 实例，否则 PageHelper 的 total 丢失");
    }

    @Test
    void project_skipsQueryWhenNothingToProject() {
        ProductRoleMapper mapper = mock(ProductRoleMapper.class);
        ITopicContentPublisherProjectionService service =
                new TopicContentPublisherProjectionServiceImpl(mapper);

        assertNull(service.project(null));
        service.project(new ArrayList<CmsTopic>());

        verifyNoInteractions(mapper);
    }

    private static CmsTopic topic(Long id) {
        CmsTopic topic = new CmsTopic();
        topic.setId(id);
        return topic;
    }

    private static TopicContentPublisherDTO row(Long topicId, String entityType,
                                                String clubName, String memberNickname) {
        TopicContentPublisherDTO row = new TopicContentPublisherDTO();
        row.setTopicId(topicId);
        row.setEntityType(entityType);
        row.setClubName(clubName);
        row.setMemberNickname(memberNickname);
        return row;
    }
}
