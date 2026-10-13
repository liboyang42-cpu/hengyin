package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺陷④ 主题购买闸:违规下架的主题必须买不到。
 *
 * 病根是「写入侧做完了、读取侧没接」——内容安全命中违规时只写 user_status=0
 * (WxContentSecurityServiceImpl:304),而购买闸 validateAndGetTopic 只看 status,
 * 于是列表页不出现、但带 topicId 直接下单照样成功。
 *
 * user_status 口径:0/null=未上架、1=已上架(cms_topic 建表默认 0);
 * 玩家侧列表本就按 status=1 AND user_status=1 过滤(ApiTopicController:771-773),
 * 购买闸与可见性对齐才不留旁路。
 */
class TopicPurchaseGateTest {

    private static final Long TOPIC_ID = 500L;
    private static final Long MEMBER_ID = 88L;
    private static final Long TICKET_ID = 300L;

    /** 造一个只有 user_status 不同的主题:其余字段都处在「可买」状态,单一变量。 */
    private CmsRegistrationServiceImpl serviceWithTopicUserStatus(Integer userStatus) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setStatus(1);      // 已发布
        topic.setDelFlag(0);
        topic.setLifecycle(3); // 唯一可直售态 SELLING
        topic.setUserStatus(userStatus);

        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(0L);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member);

        OmsTicket ticket = new OmsTicket();
        ticket.setId(TICKET_ID);
        ticket.setOwnerId(TOPIC_ID);
        ticket.setOwnerType(1L);
        ticket.setDelFlag(0);
        ticket.setRemainingInventory(9999L);
        ticket.setPrice(new BigDecimal("100.00"));
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        when(ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(ticket);

        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        com.chengyinhub.business.service.IProductRoleService productRoles =
                mock(com.chengyinhub.business.service.IProductRoleService.class);
        when(productRoles.isPlatformOwned(TOPIC_ID)).thenReturn(true);
        ReflectionTestUtils.setField(service, "productRoleService", productRoles);
        return service;
    }

    /** 报价与下单共用 validateAndGetTopic,拿它当购买闸的公共入口。 */
    private void quoteTopic(CmsRegistrationServiceImpl s) {
        s.quoteRegistration(MEMBER_ID, 1, TOPIC_ID, TICKET_ID, null, 0);
    }

    @Test
    void violationTakenDownTopicCannotBePurchased() {
        // 内容安全下架写的就是这个:status 仍为 1,只有 user_status 被打成 0
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> quoteTopic(serviceWithTopicUserStatus(0)));
        assertTrue(ex.getMessage().contains("下架"),
                "违规下架的主题必须拒绝成单,实际拿到:" + ex.getMessage());
    }

    @Test
    void topicNeverOnlineCannotBePurchased() {
        // user_status 为 null(建表默认 0 的等价语义)同样是未上架,与 ApiProjectController:159 口径一致
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> quoteTopic(serviceWithTopicUserStatus(null)));
        assertTrue(ex.getMessage().contains("下架"));
    }

    @Test
    void onlineTopicStillPurchasable() {
        // 负控的另一半:别把在架主题一起拦了
        assertEquals(0, new BigDecimal("100.00").compareTo(
                serviceWithTopicUserStatus(1)
                        .quoteRegistration(MEMBER_ID, 1, TOPIC_ID, TICKET_ID, null, 0)
                        .getPayAmount()));
    }
}
