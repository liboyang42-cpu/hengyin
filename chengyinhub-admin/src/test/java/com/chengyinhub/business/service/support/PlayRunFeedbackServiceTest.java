package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayRunFeedbackMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** qx-c1 #7: owner-checked, traceable, idempotent, content-checked post-game feedback. */
class PlayRunFeedbackServiceTest {
    private static final long ME = 9004L, OTHER = 9005L, RUN = 31L, TOPIC = 990014L, NODE = 77L;
    private PlayRunFeedbackMapper feedback;
    private TopicRouteSessionMapper runs;
    private CmsTopicNodeMapper nodes;
    private IWxContentSecurityService security;
    private PlayRunFeedbackService service;
    private final Map<String, Map<String, Object>> rows = new HashMap<>();

    @BeforeEach
    void setUp() {
        feedback = mock(PlayRunFeedbackMapper.class);
        runs = mock(TopicRouteSessionMapper.class);
        nodes = mock(CmsTopicNodeMapper.class);
        security = mock(IWxContentSecurityService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setWxOpenId("openid-me");
        when(members.selectUmsMemberById(ME)).thenReturn(member);
        service = new PlayRunFeedbackService(feedback, runs, nodes, security, members);

        TopicRouteSession run = new TopicRouteSession();
        run.setId(RUN); run.setTopicId(TOPIC); run.setActivityId(13L); run.setMemberId(ME);
        run.setPlayInstanceId("pi-1"); run.setConfigVersion(4L); run.setConfigSnapshotJson("{\"frozen\":true}");
        when(runs.selectAccessibleById(RUN, ME)).thenReturn(run);
        when(runs.selectAccessibleById(RUN, OTHER)).thenReturn(null);
        CmsTopicNode node = new CmsTopicNode(); node.setId(NODE); node.setTopicId(TOPIC);
        CmsTopicNode foreign = new CmsTopicNode(); foreign.setId(78L); foreign.setTopicId(1L);
        when(nodes.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(nodes.selectCmsTopicNodeById(78L)).thenReturn(foreign);
        when(feedback.selectRevisionIdBySnapshot(TOPIC, CanonicalDigest.sha256Hex("{\"frozen\":true}"))).thenReturn(501L);
        when(feedback.insertFeedback(any())).thenAnswer(call -> {
            Map<String, Object> row = new HashMap<>(call.getArgument(0));
            row.put("id", 1000L + rows.size());
            row.put("runId", row.get("runId"));
            rows.put(row.get("memberId") + "|" + row.get("requestId"), row);
            return 1;
        });
        when(feedback.selectByRequest(anyLong(), anyString())).thenAnswer(call -> rows.get(call.getArgument(0) + "|" + call.getArgument(1)));
    }

    private PlayRunFeedbackService.Submission place(String content, String requestId) {
        return new PlayRunFeedbackService.Submission(RUN, NODE, "place", content, requestId);
    }

    @Test
    void ownerFeedbackIsTracedToTheRunNodeAndRevision() {
        Map<String, Object> receipt = service.submit(ME, place("店门口的二维码被撕掉了", "fb-req-0001"));
        assertEquals(RUN, receipt.get("runId"));
        assertEquals(TOPIC, receipt.get("topicId"));
        assertEquals(NODE, receipt.get("nodeId"));
        assertEquals(4L, receipt.get("configVersion"));
        assertEquals(501L, receipt.get("contentRevisionId"));
        assertEquals("PLACE", receipt.get("category"));
        assertEquals("RECEIVED", receipt.get("state"));
        assertEquals(false, receipt.get("replayed"));
        verify(security).checkText("店门口的二维码被撕掉了", "openid-me");
    }

    @Test
    void sameRequestReplaysAndChangedContentIsRejected() {
        Object first = service.submit(ME, place("题目里的年份写错了", "fb-req-0002")).get("feedbackId");
        Map<String, Object> replay = service.submit(ME, place("题目里的年份写错了", "fb-req-0002"));
        assertEquals(first, replay.get("feedbackId"));
        assertEquals(true, replay.get("replayed"));
        ServiceException changed = assertThrows(ServiceException.class, () -> service.submit(ME, place("改了内容", "fb-req-0002")));
        assertEquals(409, changed.getCode());
        verify(feedback, times(1)).insertFeedback(any());
    }

    @Test
    void someoneElsesRunIsNotFoundAndWritesNothing() {
        ServiceException refused = assertThrows(ServiceException.class, () -> service.submit(OTHER, place("别人的局", "fb-req-0003")));
        assertEquals(404, refused.getCode());
        verify(feedback, never()).insertFeedback(any());
        verify(security, never()).checkText(anyString(), any());
    }

    @Test
    void aNodeFromAnotherTopicIsRejected() {
        ServiceException refused = assertThrows(ServiceException.class, () -> service.submit(ME,
                new PlayRunFeedbackService.Submission(RUN, 78L, "QUESTION", "节点不对", "fb-req-0004")));
        assertEquals(400, refused.getCode());
        verify(feedback, never()).insertFeedback(any());
    }

    @Test
    void contentSafetyRefusalIsMirroredAndWritesNothing() {
        when(security.checkText(eq("违规内容"), any())).thenReturn("内容含有违规信息，请修改后再试");
        ServiceException refused = assertThrows(ServiceException.class, () -> service.submit(ME, place("违规内容", "fb-req-0005")));
        assertEquals("内容含有违规信息，请修改后再试", refused.getMessage());
        verify(feedback, never()).insertFeedback(any());
    }

    @Test
    void inputIsValidatedBeforeAnyRead() {
        assertEquals(400, assertThrows(ServiceException.class, () -> service.submit(ME, place("x", "short"))).getCode());
        assertEquals(400, assertThrows(ServiceException.class, () -> service.submit(ME,
                new PlayRunFeedbackService.Submission(RUN, null, "PRAISE", "好玩", "fb-req-0006"))).getCode());
        assertEquals(400, assertThrows(ServiceException.class, () -> service.submit(ME, place("   ", "fb-req-0007"))).getCode());
        assertEquals(400, assertThrows(ServiceException.class, () -> service.submit(ME, place("长".repeat(501), "fb-req-0008"))).getCode());
        assertEquals(401, assertThrows(ServiceException.class, () -> service.submit(null, place("x", "fb-req-0009"))).getCode());
        verify(runs, never()).selectAccessibleById(anyLong(), anyLong());
    }

    @Test
    void unmatchedRevisionStaysUnknown() {
        when(feedback.selectRevisionIdBySnapshot(anyLong(), anyString())).thenReturn(null);
        Map<String, Object> receipt = service.submit(ME, new PlayRunFeedbackService.Submission(RUN, null, "OTHER", "整体太难", "fb-req-0010"));
        assertNull(receipt.get("contentRevisionId"), "unknown revision is null, never 0");
        assertNull(receipt.get("nodeId"));
    }
}
