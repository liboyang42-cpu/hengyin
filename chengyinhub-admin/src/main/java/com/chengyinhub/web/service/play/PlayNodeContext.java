package com.chengyinhub.web.service.play;

import org.springframework.beans.factory.annotation.Autowired;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.support.SensorChallengePolicy;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;

import org.springframework.stereotype.Service;
import static com.chengyinhub.common.core.domain.AjaxResult.error;

/** Shared node admission and frozen-input adaptation; delegates access rules to PlayNodeAccessService. */
@Service
public class PlayNodeContext {

    @Autowired
    private com.chengyinhub.business.service.support.PlayNodeAccessService playNodeAccessService;
    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;
    @Autowired(required = false)
    private ITopicRouteRuntimeService topicRouteRuntimeService;

    static class PlayCtx {
        Long actId;
        Long topicId;
        boolean selfPlay;
        boolean nightWarning;
        boolean continuing;
        CmsRegistration registration;
        AjaxResult error;
    }

    public static com.chengyinhub.business.service.support.PlayNodeAccessService.NodeScope nodeScope(CmsTopicNode node) {
        return node == null ? null : new com.chengyinhub.business.service.support.PlayNodeAccessService.NodeScope(
                node.getId(), node.getTopicId(), node.getChapterId(), node.getSortId(), node.getStatus(), node.getUnlockAfterNodeId());
    }

    public static PlayCtx playContext(com.chengyinhub.business.service.support.PlayNodeAccessService.Context view) {
        PlayCtx ctx=new PlayCtx(); ctx.actId=view.activityId(); ctx.topicId=view.topicId();
        ctx.selfPlay=view.selfPlay(); ctx.nightWarning=view.nightWarning(); ctx.continuing=view.continuing();
        if(view.registrationId()!=null) {
            ctx.registration=new CmsRegistration(); ctx.registration.setId(view.registrationId());
            ctx.registration.setPurchaseKind(view.purchaseKind());
        }
        if(view.failure()!=null) ctx.error=new AjaxResult(view.failure().originalAjaxCode(),view.failure().message());
        return ctx;
    }

    /** 规则16:普通下架/停用(status 显式非 1)节点不得再产生新完成;无 status 的存量行不在此列。 */
    public boolean isDelistedNode(CmsTopicNode node)
    {
        return playNodeAccessService.isDelistedNode(nodeScope(node));
    }

    /**
     * 规则16 下架闸的幂等豁免:本人对该节点已有正式完成行(status=1)时,重试走既有幂等回执。
     * 只用于「已完成节点」的重复提交(回读同一事实、不产生新完成);未完成的首次写入仍被下架闸拦住。
     * 到店/凭证行(status=0,如 FREE_COLLECT 的 recordedVoucher)不算完成,不豁免(维持 §7-1 现状)。
     */
    public boolean hasCompletedNodeFact(Long memberId, String activityId, CmsTopicNode node)
    {
        return playNodeAccessService.hasCompletedNodeFact(memberId, activityId, nodeScope(node));
    }

    /** 已完成事实(Long 版):路线 seam 豁免与入口下架闸必须同源,见 {@link #routeAccessError}。 */
    public boolean hasCompletedNodeFact(Long memberId, Long actId, CmsTopicNode node)
    {
        return playNodeAccessService.hasCompletedNodeFact(memberId, actId, nodeScope(node));
    }

    /** 文字答案归一化：去全部空白 + 全角字符→半角，降低误判(选项题不走此归一，保持精确) */
    public String normalizeAnswer(String s)
    {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", "");
        StringBuilder sb = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (c >= '！' && c <= '～') sb.append((char) (c - 0xFEE0)); // 全角ASCII→半角
            else if (c == '　') sb.append(' '); // 全角空格(已被strip,兜底)
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 本局开局的模板判定字段快照(R18-FREEZE):对错 / 揭示 / 偏好 / 传感器配置只看建局冻结值,
     * 创作者之后改模板不影响进行中的局。没有本局(非旅程主题)或老局快照没有 nodeAnswers 键时
     * 返回 null,调用方回退实时模板(兼容,老局行为不变)。
     */
    public com.fasterxml.jackson.databind.JsonNode frozenAnswer(Long memberId, Long topicId, Long activityId, Long nodeId)
    {
        if (topicRouteRuntimeService == null || topicId == null || nodeId == null) return null;
        return topicRouteRuntimeService.nodeAnswers(memberId, topicId, activityId, nodeId);
    }

    /**
     * 写命令只消费入口已锁定的原个人局；有答案冻结的局缺答案不能补当前作者模板。
     * D1:答案冻结上线前开的老个人局(快照无 nodeAnswers 键)返回 null,回退当前模板继续玩。
     */
    public com.fasterxml.jackson.databind.JsonNode frozenAnswer(Long memberId, Long topicId,
            Long activityId, Long nodeId, PlayOriginalRun originalRun) {
        if (originalRun == null) return frozenAnswer(memberId, topicId, activityId, nodeId);
        if (originalRun.session() != null) {
            String snapshotJson = originalRun.session().getConfigSnapshotJson();
            if (com.chengyinhub.business.service.support.NodeJourneySnapshot.predatesAnswerFreeze(snapshotJson)) return null;
            com.fasterxml.jackson.databind.JsonNode answer = com.chengyinhub.business.service.support.NodeJourneySnapshot
                    .answer(snapshotJson, nodeId);
            return answer == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : answer;
        }
        if (!originalRun.hasRuntime()) return null;
        com.fasterxml.jackson.databind.JsonNode snapshot = topicRouteRuntimeService
                .queryNodeContentSnapshot(memberId, topicId, activityId, nodeId);
        if (snapshot == null || !originalRun.runtimeInstance().equals(snapshot.path("runtimeInstance").asText())
                || !java.util.Objects.equals(originalRun.authorityEpoch(),
                    snapshot.hasNonNull("authorityEpoch") ? snapshot.path("authorityEpoch").intValue() : null))
            throw new IllegalStateException("原共享局内容身份已变化");
        com.fasterxml.jackson.databind.JsonNode answer = com.chengyinhub.business.service.support.NodeJourneySnapshot
                .answerFromSnapshot(snapshot, nodeId);
        return answer == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : answer;
    }

    /**
     * 用冻结判定字段重建只读模板视图(仅内存对象,不落库):冻结里没有的字段保持 null =
     * 建局时就没有,不回落实时。validationMethod 缺失时留 null,由调用方按原校验拒绝。
     */
    public CmsMemberTemplate frozenTemplateView(CmsTopicNode node,
            com.fasterxml.jackson.databind.JsonNode frozenAnswer)
    {
        if (hasFrozenContent(frozenAnswer)) {
            com.chengyinhub.business.service.support.NodeJourneySnapshot.contentNode(frozenAnswer, node);
            return com.chengyinhub.business.service.support.NodeJourneySnapshot.contentTemplate(frozenAnswer);
        }
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setId(node.getTemplateId());
        if (frozenAnswer.hasNonNull("validationMethod")) {
            tpl.setValidationMethod(Integer.valueOf(frozenAnswer.path("validationMethod").asInt()));
        }
        if (frozenAnswer.hasNonNull("photoReview")) {
            tpl.setPhotoReview(Integer.valueOf(frozenAnswer.path("photoReview").asInt()));
        }
        tpl.setQuestionAnswer(frozenAnswer.path("questionAnswer").asText(null));
        tpl.setCorrectAnswer(frozenAnswer.path("correctAnswer").asText(null));
        tpl.setAnswerReveal(frozenAnswer.path("answerReveal").asText(null));
        tpl.setQuestionA(frozenAnswer.path("questionA").asText(null));
        tpl.setQuestionB(frozenAnswer.path("questionB").asText(null));
        tpl.setQuestionC(frozenAnswer.path("questionC").asText(null));
        tpl.setQuestionD(frozenAnswer.path("questionD").asText(null));
        tpl.setPreferenceJson(frozenAnswer.path("preferenceJson").asText(null));
        tpl.setSensorType(frozenAnswer.path("sensorType").asText(null));
        tpl.setSensorConfig(frozenAnswer.path("sensorConfig").asText(null));
        return tpl;
    }

    /** 本局适用的模板判定视图:开局冻结优先,老局快照没有该键时回退实时模板。 */
    public CmsMemberTemplate effectiveTemplate(Long memberId, Long topicId, Long activityId, CmsTopicNode node)
    {
        return effectiveTemplate(memberId, topicId, activityId, node, null);
    }

    public CmsMemberTemplate effectiveTemplate(Long memberId, Long topicId, Long activityId,
            CmsTopicNode node, PlayOriginalRun originalRun) {
        if (node == null) return null;
        com.fasterxml.jackson.databind.JsonNode frozen = frozenAnswer(memberId, topicId, activityId, node.getId(), originalRun);
        if (hasFrozenContent(frozen)) return frozenTemplateView(node, frozen);
        if (node.getTemplateId() == null) return null;
        return frozen == null ? cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId())
                : frozenTemplateView(node, frozen);
    }

    /** 新内容标志具有权威性，缺结构不降级成 live；旧判定快照缺标志保持原行为。 */
    public boolean hasFrozenContent(com.fasterxml.jackson.databind.JsonNode content) {
        return com.chengyinhub.business.service.support.NodeJourneySnapshot.hasContent(content);
    }

    /** Current status/QR/access fields remain original; only original content is projected. */
    public CmsTopicNode frozenNodeView(CmsTopicNode original, com.fasterxml.jackson.databind.JsonNode content) {
        return com.chengyinhub.business.service.support.NodeJourneySnapshot.contentNode(content, original);
    }

    /**
     * 当前用户在该节点上的权威进度行。自玩(activity_id=0)必须按 topic+node 一并收窄,
     * 防多主题共享同一 node 探针互相污染;活动行由 activityId 天然绑定主题。
     */
    public PlayerNodeProgress findNodeProgressRow(Long memberId, Long actId, Long topicId, Long nodeId)
    {
        var fact = playNodeAccessService.findNodeProgressRow(memberId, actId, topicId, nodeId);
        if (fact == null) return null;
        PlayerNodeProgress row = new PlayerNodeProgress(); row.setId(fact.id());
        row.setStatus(fact.status()); row.setPicUrl(fact.picUrl()); return row;
    }

    public PlayerNodeProgress findNodeProgressRow(Long memberId, Long actId, Long topicId, Long nodeId,
            PlayOriginalRun originalRun) {
        var fact = playNodeAccessService.findNodeProgressRow(memberId, actId, topicId, nodeId,
                originalRun.progressInstance());
        if (fact == null) return null;
        PlayerNodeProgress row = new PlayerNodeProgress(); row.setId(fact.id());
        row.setStatus(fact.status()); row.setPicUrl(fact.picUrl()); return row;
    }

    /**
     * 纯线上节点 = 无承接商家 + 无坐标。判据必须三条同时看:
     * 商家节点即使暂缺坐标也不算(到店事实归 /checkin);主办方节点只要有坐标就仍走扫码/围栏。
     */
    public boolean isPureOnlineNode(CmsTopicNode node) {
        return node.getMerchantMemberId() == null
                && StringUtils.isBlank(node.getLongitude())
                && StringUtils.isBlank(node.getLatitude());
    }

    /** 判定方式只看本局模板视图(开局冻结优先,由 effectiveTemplate 解析),不另读实时模板。 */
    public boolean isAppSensorNode(CmsTopicNode node, CmsMemberTemplate template) {
        if (node == null || node.getTemplateId() == null && (template == null || template.getId() == null)) return false;
        return template != null && template.getValidationMethod() != null
                && template.getValidationMethod() == ValidationMethod.APP_SENSOR;
    }

    public boolean isPhotoFilterNode(CmsTopicNode node, CmsMemberTemplate template) {
        if (node == null || node.getTemplateId() == null && (template == null || template.getId() == null)) return false;
        return template != null && SensorChallengePolicy.isPhotoFilter(
                template.getValidationMethod(), template.getSensorType());
    }

    /** mode=1 线性：该节点之前(sortId 更小)的同主题节点是否都已完成（防扫后面的码跳关） */
    public boolean isPrevDone(Long memberId, Long actId, CmsTopicNode node)
    {
        return playNodeAccessService.isPrevDone(memberId, actId, nodeScope(node));
    }

    /** mode=1 的统一准入 seam：LINEAR 适配旧 sortId，BRANCH_GRAPH 只认服务端 routeState。 */
    public String routeAccessError(Long memberId, Long actId, Long topicId, CmsTopicNode node,
                                    String routeActionId)
    {
        return playNodeAccessService.routeAccessError(memberId, actId, topicId, nodeScope(node), routeActionId);
    }

    /**
     * 节点级前置解锁(unlock_after_node_id):配置了前置的节点,必须先把前置节点玩到「完成」
     * (player_node_progress.status=1;mode2 扫码进店建的 status=0 行不算完成)才能推进本节点。
     * 与 isPrevDone(mode=1 整题线性)互补,任意模式生效。返回 null=放行,非 null=拦截文案。
     */
    public String unlockGateError(Long memberId, Long actId, CmsTopicNode node)
    {
        return playNodeAccessService.unlockGateError(memberId, actId, nodeScope(node));
    }

    /**
     * 经纬度文本 → 有限且在范围内的 double;NaN、Infinity、越界、坏文本一律 null(调用方失败关闭)。
     * R10-10:Java 的 {@code Double.parseDouble} 接受 "NaN"/"Infinity",而 NaN 的距离比较恒 false。
     */
    public static Double parseGeoCoordinate(String value, double min, double max)
    {
        if (StringUtils.isBlank(value)) return null;
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed) || parsed < min || parsed > max) return null;
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 活动时间闸：未到开始/已过结束返回提示语，可玩返回 null（C5 有效期 gating） */
    public String activityTimeError(CmsActivity activity)
    {
        return playNodeAccessService.activityTimeError(activity == null ? null :
                new com.chengyinhub.business.service.support.PlayNodeAccessService.ActivityWindow(
                        activity.getStartDate() == null ? null : activity.getStartDate().getTime(),
                        activity.getEndDate() == null ? null : activity.getEndDate().getTime()));
    }

    /**
     * 解析主题模式：1=经典定向(线性闯关) 2=自由定向(全开)。
     *
     * <p>[P0-02] 真源是 {@code cms_topic.product_type}。旧实现取「第一张有 mode 的票」,而票列表
     * 当时无 order by ⇒ 玩法模式实际由 InnoDB 扫描路径决定,混票主题下 mode=2 的买家可能被线性闸拦。
     *
     * <p>过渡期双读:product_type 为空(存量未回填 / 混票待人工定型,迁移刻意不静默回填这批)时
     * 回落旧的票 mode 推断,故本方法在迁移落库前后行为都正确。存量清完后可删 fallback。
     */
    public int resolveMode(Long activityId, Long topicId)
    {
        return playNodeAccessService.resolveMode(activityId, topicId);
    }

    /**
     * 统一解析播放上下文(M2 topicId 二选一,activityId 优先)。
     * 活动分支:完整复用原活动会话校验(存在/归属/报名/时效)。
     * 自玩分支:校验主题通行证(未过期),actId=0,夜间打提示位。
     */
    public PlayCtx resolveCtx(Long memberId, String activityId, String topicId, CmsTopicNode node)
    {
        return playContext(playNodeAccessService.resolveContext(memberId, activityId, topicId, nodeScope(node)));
    }

    public PlayCtx resolveCtx(Long memberId, String activityId, String topicId, CmsTopicNode node,
            Long registrationId)
    {
        return playContext(playNodeAccessService.resolveContext(memberId, activityId, topicId, nodeScope(node), registrationId));
    }

    /**
     * P1 带领场推进闸(服务端强校验,不靠前端禁按钮):
     * 集合中(status=1)一律不可推进;行进中(status=2)节点所属章节须已被队长放行(sortId ≤ 当前章)。
     * 非带领场(无 state)/已结算(3)/自玩会话(actId=0)不拦。
     */
    public String leadGateError(Long actId, CmsTopicNode node) {
        return playNodeAccessService.leadGateError(actId, nodeScope(node));
    }
}
