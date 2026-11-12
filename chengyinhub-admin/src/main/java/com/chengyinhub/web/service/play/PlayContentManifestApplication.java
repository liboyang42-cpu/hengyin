package com.chengyinhub.web.service.play;

import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.vo.TopicContentRevisionView;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ITopicContentRevisionService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.ContentManifestProjection;
import com.chengyinhub.business.service.support.CurrentPlayRegistration;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.chengyinhub.common.core.domain.AjaxResult.error;

/**
 * 内容清单只读入口(BE-10)。
 *
 * <p>三条硬边界,谁都不要为了「预取方便」放宽:
 * <ul>
 *   <li><b>不写任何状态</b>:不建局、不记到达、不发奖、不推进路线。清单是读投影。</li>
 *   <li><b>不做仅凭 revisionId 的读取</b>:先用既有报名/通行证准入解析出合法 topicId,
 *       再用 {@code (topicId, revisionId)} 取修订。别人的修订 id 猜对了也只能拿到「不存在」。</li>
 *   <li><b>已开局按本局快照</b>:有进行中的局时清单来自这一局自己的冻结内容,
 *       绝不用当前发布修订覆盖,也不拿 live 模板拼假修订。</li>
 * </ul>
 *
 * <p>授权沿用原事实:cms_registration 的成功报名(活动)或有效自玩通行证(主题)。
 * 本基线没有独立的「礼品受赠人」权益表 —— 受赠人只有拿到平台签发的成功报名才有这份内容,
 * 这里不为「礼品」另造一条准入。经济与结算事实不参与本入口。
 */
@Service
public class PlayContentManifestApplication {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ICmsActivityService cmsActivityService;
    @Autowired
    private PlayCompletionFacts playCompletionFacts;
    @Autowired
    private PlayNodeContext playNodeContext;
    @Autowired
    private com.chengyinhub.business.mapper.CmsActivityMapper currentPlayActivityMapper;
    @Autowired
    private com.chengyinhub.business.mapper.CmsRegistrationMapper cmsRegistrationMapper;
    @Autowired(required = false)
    private ITopicRouteRuntimeService topicRouteRuntimeService;
    /** 共享局(activity 维度)是否已开局;只读状态,不建局、不刷新。 */
    @Autowired(required = false)
    private com.chengyinhub.business.service.IGameSessionRuntimeService gameSessionRuntimeService;
    /** BE-06 只读合同;没有修订索引时整条链路按「老内容继续在线」处理,不阻断游玩。 */
    @Autowired(required = false)
    private ITopicContentRevisionService contentRevisionService;
    /** 礼品可玩资格（只读自玩域）；必需注入，避免礼品行退化成"买家可玩"。 */
    @Autowired
    private com.chengyinhub.business.service.IMarketingGiftPlayEligibility giftPlayEligibility;
    /** 付费内含席位受让人事实（只读）；与礼品分开，合并点仍在 CurrentPlayRegistration。 */
    @Autowired
    private com.chengyinhub.business.service.IRegistrationSeatRecipientEligibility seatRecipientEligibility;

    /**
     * @param revisionId 可选的明确修订;必须是**本主题**已发布的修订。不传 = 当前发布修订。
     */
    public AjaxResult manifest(Long actorMemberId, Long activityId, Long topicId, Long revisionId) {
        Long memberId = actorMemberId;
        if (memberId == null || memberId <= 0L) return error("请先登录");
        if (revisionId != null && revisionId <= 0L) return error("参数有误");

        Long tId;
        Long progActId = 0L;
        if (activityId != null && activityId > 0L) {
            CmsActivity activity = cmsActivityService.selectCmsActivityById(activityId);
            if (activity == null || activity.getTopicId() == null) return error("活动或主题不存在");
            tId = activity.getTopicId();
            progActId = activityId;
            if (topicId != null && topicId > 0L && !topicId.equals(tId)) return error("该主题不属于本次活动");
            // 活动路径的准入与原 /nodes 同源:报名成功=已支付。未报名者读不到清单。
            if (!playCompletionFacts.isRegistered(memberId, activityId))
                return new AjaxResult(402, "请先报名并完成支付");
            String timeErr = playNodeContext.activityTimeError(activity);
            if (timeErr != null) return error(timeErr);
        } else {
            if (topicId == null || topicId <= 0L) return error("参数有误");
            tId = topicId;
            if (playCompletionFacts.validSelfPlayPass(memberId, tId) == null) {
                // 已开局的自玩局:通行证开局时有效即可打完,与原 /nodes 同一判据。
                CmsRegistration pass = null;
                if (topicRouteRuntimeService != null
                        && topicRouteRuntimeService.hasRunningSession(memberId, tId, 0L)) {
                    try {
                        pass = CurrentPlayRegistration.requireToContinue(
                                currentPlayActivityMapper, cmsRegistrationMapper, memberId, 1L, tId, null, new Date(),
                                giftPlayEligibility, seatRecipientEligibility);
                    } catch (IllegalStateException denied) {
                        pass = null;
                    }
                }
                if (pass == null) return new AjaxResult(402, "请先购买自玩通行证");
            }
        }

        // ① 已开局:清单只来自本局自己的冻结内容,绝不用当前发布顶替。
        //    判据两条都要看:个人/队伍路线局看会话;共享局(activity 维度)看原共享运行状态。
        //    「线性路线只记过节点进度」也算 continuing,但这种局没有自己的内容快照 ——
        //    它的权威读面仍是 online,所以如实回 ONLINE_LEGACY,不把当前发布冒充成本局修订。
        boolean runStarted = topicRouteRuntimeService != null
                && topicRouteRuntimeService.hasRunningSession(memberId, tId, progActId);
        if (!runStarted && progActId > 0L && gameSessionRuntimeService != null)
            runStarted = "RUNNING".equals(gameSessionRuntimeService.routeSessionStatus(progActId));
        if (runStarted) {
            if (topicRouteRuntimeService == null)
                return AjaxResult.success(ContentManifestProjection.onlineLegacy(tId, progActId));
            JsonNode runSnapshot;
            try {
                runSnapshot = topicRouteRuntimeService.queryNodeContentSnapshot(memberId, tId, progActId, null);
            } catch (RuntimeException denied) {
                // 共享局作用域/队伍不符、快照不完整等:如实失败,不用当前发布顶替。
                return error(denied.getMessage() == null ? "本局内容快照不可用" : denied.getMessage());
            }
            if (isRunOwnedSnapshot(runSnapshot))
                return AjaxResult.success(ContentManifestProjection.fromRunSnapshot(tId, progActId, runSnapshot, null));
            // 已开局但没有本局快照:没有可下发的修订级清单,明确继续在线。
            return AjaxResult.success(ContentManifestProjection.onlineLegacy(tId, progActId));
        }
        // ② 新局/未开局:取本主题明确修订。
        if (contentRevisionService == null) {
            return AjaxResult.success(ContentManifestProjection.onlineLegacy(tId, progActId));
        }
        TopicContentRevisionView revision = revisionId == null
                ? contentRevisionService.currentPublished(tId)
                : contentRevisionService.publishedRevision(tId, revisionId);
        if (revision == null) {
            if (revisionId != null) return error("内容修订不存在");
            return AjaxResult.success(ContentManifestProjection.onlineLegacy(tId, progActId));
        }
        if (!tId.equals(revision.getTopicId())) return error("内容修订不存在");
        String frozen = contentRevisionService.frozenSnapshot(tId, revision.getId());
        if (StringUtils.isBlank(frozen)) return error("内容修订不可用");
        try {
            JsonNode frozenTree = JSON.readTree(frozen);
            com.chengyinhub.business.domain.vo.ContentManifestVO manifest =
                    ContentManifestProjection.fromRevision(revision, frozenTree);
            // 只是读回身份,便于端侧把「同一修订、不同场次」分开缓存;不参与准入。
            if (progActId > 0L) manifest.setActivityId(progActId);
            return AjaxResult.success(manifest);
        } catch (IllegalStateException unusable) {
            return error(unusable.getMessage() == null ? "内容修订不可用" : unusable.getMessage());
        } catch (Exception unreadable) {
            return error("内容修订不可用");
        }
    }

    /**
     * 只有带本局身份的冻结快照才是「本局自己的」。发布内容回退路径会去掉
     * {@code contentRouteSessionId}/{@code gameSessionId},所以缺这两个键就说明快照不是本局的。
     */
    private static boolean isRunOwnedSnapshot(JsonNode snapshot) {
        return snapshot != null && snapshot.isObject()
                && (snapshot.hasNonNull("contentRouteSessionId") || snapshot.hasNonNull("gameSessionId"));
    }
}
