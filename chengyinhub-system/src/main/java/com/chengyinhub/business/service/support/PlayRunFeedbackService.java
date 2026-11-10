package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayRunFeedbackMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * qx-c1 #7 (U08-02): a player's post-game problem feedback about their own run.
 *
 * <ul>
 *   <li>Owner/participant: the run is read with {@code member_id = caller}; someone else's run id is "not found".</li>
 *   <li>Trace: topic, activity, play instance, frozen config version and content revision come from the run, never
 *       from the client; {@code nodeId} must belong to that topic. An unmatched revision stays null (unknown).</li>
 *   <li>Idempotent per (member, requestId): same input returns the original receipt, different input is 409.</li>
 *   <li>Content safety: the text goes through the member-scoped text check before anything is written.</li>
 * </ul>
 */
@Service
public class PlayRunFeedbackService {
    public static final Set<String> CATEGORIES = Set.of("PLACE", "QUESTION", "OTHER");
    public static final int MAX_CONTENT = 500;

    private final PlayRunFeedbackMapper feedback;
    private final TopicRouteSessionMapper runs;
    private final CmsTopicNodeMapper nodes;
    private final IWxContentSecurityService security;
    private final IUmsMemberService members;

    public PlayRunFeedbackService(PlayRunFeedbackMapper feedback, TopicRouteSessionMapper runs, CmsTopicNodeMapper nodes,
                                  IWxContentSecurityService security, IUmsMemberService members) {
        this.feedback = feedback;
        this.runs = runs;
        this.nodes = nodes;
        this.security = security;
        this.members = members;
    }

    public record Submission(Long runId, Long nodeId, String category, String content, String requestId) { }

    public Map<String, Object> submit(Long memberId, Submission input) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录", 401);
        if (input == null) throw new ServiceException("反馈内容不能为空", 400);
        String requestId = input.requestId() == null ? "" : input.requestId().trim();
        if (!requestId.matches("[A-Za-z0-9._:-]{8,64}")) throw new ServiceException("请求号无效", 400);
        String category = input.category() == null ? "" : input.category().trim().toUpperCase(java.util.Locale.ROOT);
        if (!CATEGORIES.contains(category)) throw new ServiceException("请选择问题类型", 400);
        String content = input.content() == null ? "" : input.content().trim();
        if (content.isEmpty()) throw new ServiceException("请写下遇到的问题", 400);
        if (content.codePointCount(0, content.length()) > MAX_CONTENT)
            throw new ServiceException("反馈不能超过 " + MAX_CONTENT + " 字", 400);
        if (input.runId() == null || input.runId() <= 0) throw new ServiceException("缺少原局", 400);
        Long nodeId = input.nodeId() == null || input.nodeId() <= 0 ? null : input.nodeId();
        String hash = CanonicalDigest.sha256Hex(input.runId() + "\n" + (nodeId == null ? "" : nodeId) + "\n" + category + "\n" + content);

        Map<String, Object> existing = feedback.selectByRequest(memberId, requestId);
        if (existing != null) return replay(existing, hash);

        TopicRouteSession run = runs.selectAccessibleById(input.runId(), memberId);
        if (run == null || run.getTopicId() == null) throw new ServiceException("这一局不存在", 404);
        if (nodeId != null) {
            CmsTopicNode node = nodes.selectCmsTopicNodeById(nodeId);
            if (node == null || !Objects.equals(node.getTopicId(), run.getTopicId()))
                throw new ServiceException("问题节点不属于这一局", 400);
        }
        String blocked = IWxContentSecurityService.checkTextAsMember(security, members, memberId, content);
        if (blocked != null) throw new ServiceException(blocked, 400);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("memberId", memberId);
        row.put("runId", run.getId());
        row.put("topicId", run.getTopicId());
        row.put("activityId", run.getActivityId() == null ? 0L : run.getActivityId());
        row.put("playInstanceId", run.getPlayInstanceId());
        row.put("configVersion", run.getConfigVersion());
        row.put("contentRevisionId", revisionOf(run));
        row.put("nodeId", nodeId);
        row.put("category", category);
        row.put("content", content);
        row.put("requestId", requestId);
        row.put("requestHash", hash);
        try {
            if (feedback.insertFeedback(row) != 1) throw new IllegalStateException("反馈保存失败");
        } catch (DuplicateKeyException concurrent) {
            Map<String, Object> raced = feedback.selectByRequest(memberId, requestId);
            if (raced == null) throw new IllegalStateException("反馈保存失败", concurrent);
            return replay(raced, hash);
        }
        return receipt(feedback.selectByRequest(memberId, requestId), false);
    }

    private Long revisionOf(TopicRouteSession run) {
        String snapshot = run.getConfigSnapshotJson();
        if (snapshot == null || snapshot.isBlank()) return null;
        try {
            return feedback.selectRevisionIdBySnapshot(run.getTopicId(), CanonicalDigest.sha256Hex(snapshot));
        } catch (RuntimeException unmatched) {
            return null;
        }
    }

    private static Map<String, Object> replay(Map<String, Object> existing, String hash) {
        if (!hash.equals(existing.get("requestHash"))) throw new ServiceException("原请求号对应的内容不一致", 409);
        return receipt(existing, true);
    }

    private static Map<String, Object> receipt(Map<String, Object> row, boolean replayed) {
        if (row == null) throw new IllegalStateException("反馈保存失败");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("feedbackId", row.get("id"));
        out.put("runId", row.get("runId"));
        out.put("topicId", row.get("topicId"));
        out.put("activityId", row.get("activityId"));
        out.put("configVersion", row.get("configVersion"));
        out.put("contentRevisionId", row.get("contentRevisionId"));
        out.put("nodeId", row.get("nodeId"));
        out.put("category", row.get("category"));
        out.put("state", "RECEIVED");
        out.put("replayed", replayed);
        return out;
    }
}
