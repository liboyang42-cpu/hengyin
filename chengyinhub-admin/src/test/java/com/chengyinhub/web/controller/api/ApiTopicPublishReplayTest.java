package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.impl.MemberIdentityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F1 发布入口真实调用链:同作者 + 同 publishRequestId + 同 payload 的已完成发布,
 * 在 is_draft 已被清掉之后重发必须回同一条不可变修订,且不再占额度/重开审核/重查身份。
 *
 * <p>这里走控制器本身(不是 store),因为原缺口就在控制器:草稿闸在重试时先返回
 * 「该主题不是草稿」,服务层的幂等根本没机会跑。
 */
class ApiTopicPublishReplayTest {

    private static final long TOPIC = 7001L;
    private static final long OWNER = 901L;

    private ApiTopicController controller;
    private ICmsTopicService topics;
    private IOmsTicketService tickets;
    private MemberIdentityService identity;
    private IQuotaService quota;
    private CmsTopic topic;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiTopicController());
        doReturn(OWNER).when(controller).getAppUserId();
        topics = mock(ICmsTopicService.class);
        tickets = mock(IOmsTicketService.class);
        identity = mock(MemberIdentityService.class);
        quota = mock(IQuotaService.class);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
        ReflectionTestUtils.setField(controller, "omsTicketService", tickets);
        ReflectionTestUtils.setField(controller, "memberIdentityService", identity);
        ReflectionTestUtils.setField(controller, "quotaService", quota);

        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(OWNER);
        topic.setPublishMode("simple");
        topic.setStatus(1);
        topic.setUserStatus(1);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        // 已发布:不再是草稿,也不能再走「供给重提」通道 —— 正是原缺口的状态。
        when(topics.selectTopicIsDraft(TOPIC)).thenReturn(0);
        when(topics.canResubmitSupplyReview(eq(TOPIC), anyLong())).thenReturn(false);
        when(tickets.selectOmsTicketList(any())).thenReturn(Collections.emptyList());
    }

    private AjaxResult publish(String requestId, String expectedSha) {
        return controller.publishDraft(String.valueOf(TOPIC), null, null, null, requestId, expectedSha);
    }

    private TopicBundleSubmitResultVO replayedRevision() {
        TopicBundleSubmitResultVO replay = new TopicBundleSubmitResultVO(
                TOPIC, null, "NOT_REQUIRED", Collections.emptyList());
        replay.setPublished(true);
        replay.setContentRevisionId(55L);
        replay.setContentRevisionNo(3L);
        replay.setContentSchemaVersion(1);
        replay.setContentSha256("sha-of-frozen-payload");
        return replay;
    }

    @Test
    void publishedSimpleTopicRetryReturnsItsRevisionWithoutQuotaIdentityOrReview() {
        when(topics.replayPublishedRevision(TOPIC, OWNER, "req-1", null)).thenReturn(replayedRevision());

        AjaxResult result = publish("req-1", null);

        assertEquals(200, result.get("code"));
        assertEquals("已发布", result.get("msg"));
        verifyNoInteractions(quota, identity);
        verify(topics, never()).publishDraft(anyLong(), anyLong(), anyString());
        verify(topics, never()).publishDraftWithReview(anyLong(), anyLong(), anyString());
        verify(topics, never()).publishDraftWithBinding(anyLong(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    void publishedProTopicRetryReturnsTheRevisionIdentityAsObjectResult() {
        topic.setPublishMode("pro");
        when(topics.replayPublishedRevision(TOPIC, OWNER, "req-1", null)).thenReturn(replayedRevision());

        AjaxResult result = publish("req-1", null);

        assertEquals(200, result.get("code"));
        Object data = result.get("data");
        assertInstanceOf(TopicBundleSubmitResultVO.class, data);
        TopicBundleSubmitResultVO replay = (TopicBundleSubmitResultVO) data;
        assertEquals(Long.valueOf(55L), replay.getContentRevisionId());
        assertEquals(Long.valueOf(3L), replay.getContentRevisionNo());
        assertEquals("sha-of-frozen-payload", replay.getContentSha256());
        verifyNoInteractions(quota, identity);
    }

    @Test
    void reusedRequestIdWithADifferentPayloadIsAConflictNotASecondPublication() {
        when(topics.replayPublishedRevision(TOPIC, OWNER, "req-1", "sha-of-something-else"))
                .thenThrow(new ServiceException("发布请求号已用于不同内容，请使用新的请求号重新发布", 409));

        AjaxResult result = publish("req-1", "sha-of-something-else");

        assertEquals(409, result.get("code"));
        assertEquals("发布请求号已用于不同内容，请使用新的请求号重新发布", result.get("msg"));
        verifyNoInteractions(quota, identity);
        verify(topics, never()).publishDraft(anyLong(), anyLong(), anyString());
    }

    @Test
    void publishedTopicWithNoMatchingIntentKeepsTheOriginalDraftGate() {
        when(topics.replayPublishedRevision(TOPIC, OWNER, "req-unknown", null)).thenReturn(null);

        AjaxResult result = publish("req-unknown", null);

        assertEquals(500, result.get("code"), "没有该发布意图的修订时不能假装已发布");
        assertEquals("该主题不是草稿，无法发布", result.get("msg"));
        verifyNoInteractions(quota, identity);
    }

    @Test
    void retryWithoutARequestIdNeverConsultsTheRevisionIndex() {
        AjaxResult result = publish(null, null);

        assertEquals(500, result.get("code"));
        verify(topics, never()).replayPublishedRevision(anyLong(), anyLong(), anyString(), anyString());
        verifyNoInteractions(quota);
    }

    @Test
    void anonymousOrForeignOwnerCannotProbeAnotherTopicsRequestId() {
        topic.setMemberId(902L);

        AjaxResult result = publish("req-1", null);

        assertEquals(500, result.get("code"));
        assertEquals("无权操作该主题", result.get("msg"));
        verify(topics, never()).replayPublishedRevision(anyLong(), anyLong(), anyString(), anyString());
        verifyNoInteractions(quota, identity);
    }

    @Test
    void genuineDraftPublicationStillRunsItsOwnGatesAfterAMiss() {
        topic.setPublishMode("pro");
        when(topics.selectTopicIsDraft(TOPIC)).thenReturn(1);
        when(topics.replayPublishedRevision(TOPIC, OWNER, "req-2", null)).thenReturn(null);
        when(identity.checkRegistered(OWNER)).thenReturn(null);
        when(topics.publishDraftWithReview(TOPIC, OWNER, "req-2")).thenReturn(replayedRevision());

        AjaxResult result = publish("req-2", null);

        assertEquals(200, result.get("code"));
        // 没有命中重放:草稿发布的身份/审核链路照原样执行,没有被跳过。
        verify(identity).checkRegistered(OWNER);
        verify(topics).publishDraftWithReview(TOPIC, OWNER, "req-2");
    }
}
