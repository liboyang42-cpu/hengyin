package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.vo.PublicationProblem;
import com.chengyinhub.business.exception.TopicPublicationContentException;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** f-money3:存量草稿带着自由探索 + 收费自玩票时,试检查列出、发布闸拒绝,两处同一条 issue。 */
class FreeExploreSelfPlayPublishReadinessTest {

    private TopicPublicationContentGuard guard() {
        TopicPublicationContentGuard guard = new TopicPublicationContentGuard();
        OmsTicketMapper tickets = mock(OmsTicketMapper.class);
        when(tickets.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(guard, "omsTicketMapper", tickets);
        return guard;
    }

    private static CmsTopic topic(Integer productType, Integer selfPlay, BigDecimal price) {
        CmsTopic topic = new CmsTopic();
        topic.setId(7001L);
        topic.setName("自由探索草稿");
        topic.setPublishMode("pro");
        topic.setProductType(productType);
        topic.setRecruitDeadline(new Date());
        topic.setSelfPlay(selfPlay);
        topic.setSelfPlayPrice(price);
        return topic;
    }

    @Test
    void precheckListsTheIssueAndPublishGateRefusesWithTheSameIssue() {
        TopicPublicationContentGuard guard = guard();
        CmsTopic legacy = topic(ProductType.FREE_EXPLORE, 1, new BigDecimal("39.90"));

        List<? extends PublicationProblem> issues = guard.publishReadinessProblems(legacy);
        assertEquals(1, issues.size());
        assertEquals("FREE_EXPLORE_SELF_PLAY_PRICED", issues.get(0).getCode());
        assertEquals("selfPlayPrice", issues.get(0).getField());

        TopicPublicationContentException refused = assertThrows(TopicPublicationContentException.class,
                () -> guard.assertPublishReady(legacy));
        assertTrue(refused.getMessage().contains("自由探索主题不能设置收费自玩票"));
        assertEquals("FREE_EXPLORE_SELF_PLAY_PRICED", refused.getData().getIssues().get(0).getCode());
    }

    @Test
    void freeSelfPlayOrClassicPaidSelfPlayIsNotAnIssue() {
        TopicPublicationContentGuard guard = guard();
        assertTrue(guard.publishReadinessProblems(topic(ProductType.FREE_EXPLORE, 0, new BigDecimal("39.90"))).isEmpty());
        assertTrue(guard.publishReadinessProblems(topic(ProductType.FREE_EXPLORE, 1, BigDecimal.ZERO)).isEmpty());
        assertTrue(guard.publishReadinessProblems(topic(ProductType.ORIENTEERING, 1, new BigDecimal("39.90"))).isEmpty());
    }
}
