package com.chengyinhub.business.service.impl;

import java.util.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.support.CircleThemePublishPolicy;
import com.chengyinhub.business.service.ITopicCommercialReviewService;
import com.chengyinhub.common.constant.ProductType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.service.support.TopicPublicationContentGuard;
import com.chengyinhub.business.service.support.TopicSelfHostActivityService;

/** Owns publication state and review orchestration; joins the original caller transaction. */
@Service
public class TopicPublicationService {
    @Autowired
    private CmsTopicMapper cmsTopicMapper;
    @Autowired
    private com.chengyinhub.business.service.IAuditTaskService auditTaskService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.support.TopicSupplyReviewService supplyReviewService;
    @Autowired
    private com.chengyinhub.business.service.support.TopicBundleReviewService topicBundleReviewService;
    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.chengyinhub.business.service.IQuotaService quotaService;
    @Autowired
    private ITopicCommercialReviewService topicCommercialReviewService;
    @Autowired
    private com.chengyinhub.business.service.support.CommercialTopicPolicy commercialTopicPolicy;
    @Autowired
    private CmsTopicChapterMapper cmsTopicChapterMapper;
    @Autowired
    private MerchantCircleSupplyProfileMapper merchantCircleSupplyProfileMapper;
    @Autowired
    private com.chengyinhub.business.service.support.SettlementDebtLedger settlementDebtLedger;
    @Autowired
    private com.chengyinhub.business.service.IUmsCreativeSquareService umsCreativeSquareService;
    @Autowired
    private com.chengyinhub.business.service.support.AppMediaPublicationGuard mediaPublicationGuard;
    @Autowired private TopicPublicationContentGuard publicationContentGuard;
    @Autowired private TopicSelfHostActivityService selfHostActivityService;
    @Autowired private com.chengyinhub.business.service.ITopicRouteRuntimeService topicRouteRuntimeService;
    @Autowired private com.chengyinhub.business.service.support.ThemeStateKeyService themeStateKeyService;
    private static <T> List<T> nullToEmpty(List<T> rows) { return rows == null ? Collections.<T>emptyList() : rows; }

    public boolean canResubmitSupplyReview(Long id, Long ownerId) {
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(id);
        return topic != null && java.util.Objects.equals(ownerId, topic.getMemberId())
                && supplyReviewService.canResubmit(topic);
    }

    public TopicBundleSubmitResultVO publishDraftInternal(Long id, Long expectedOwnerId, boolean allowSupplyResubmission) {
        CmsTopic topic = supplyReviewService.lockTopic(id);
        if (expectedOwnerId != null && (topic == null || !expectedOwnerId.equals(topic.getMemberId())))
            throw new ServiceException("主题归属已变化，无权提交", 403);
        boolean supplyResubmission = allowSupplyResubmission && supplyReviewService.canResubmit(topic);
        if (topic != null) mediaPublicationGuard.requireTopicPublication(id);
        if (cmsTopicMapper.clearDraftIfDraft(id) <= 0 && !supplyResubmission) return null;
        // D8:逐章/逐节点增量写入的路径(后台章节节点 CRUD、商家补节点)不经整包保存,发布口按库内实数再拦一次
        publicationContentGuard.assertTopicSizeWithinLimits(id);
        if (supplyResubmission) {
            publicationContentGuard.assertOrdinarySupplyRestored(topic);
            publicationContentGuard.assertPublishReady(topic);
            com.chengyinhub.business.service.support.TopicBundleReviewService.Submission submission =
                    prepareTopicBundle(id, topic.getMemberId()).submission;
            Long taskId = auditTaskService.submitOrRefreshTopicBundle(id, topic.getMemberId(), submission.getSnapshotJson());
            if (Integer.valueOf(TopicLifecycleState.PENDING_REVIEW.getCode()).equals(topic.getLifecycle())) {
                AuditTask oldProject = supplyReviewService.cancelledReview(id, AuditTask.BIZ_TOPIC_PROJECT_REVIEW);
                if (oldProject != null) {
                    try {
                        String target = new com.fasterxml.jackson.databind.ObjectMapper().readTree(oldProject.getSnapshotJson())
                                .path("requestedTarget").asText();
                        topicCommercialReviewService.submitProjectReview(id, topic.getMemberId(), TopicLifecycleState.valueOf(target));
                    } catch (Exception ex) { throw new IllegalStateException("旧立项审核目标无法核对，请联系平台", ex); }
                }
            } else if (Integer.valueOf(TopicLifecycleState.PRICING.getCode()).equals(topic.getLifecycle())
                    && supplyReviewService.cancelledReview(id, AuditTask.BIZ_TOPIC_SALE_REVIEW) != null) {
                topicCommercialReviewService.submitSaleReview(id, topic.getMemberId());
            }
            TopicBundleSubmitResultVO result = new TopicBundleSubmitResultVO(id, taskId, "PENDING", submission.getBundledTemplateIds());
            result.setPublished(false);
            return result;
        }
        // R19-DRAFT2:草稿保存放宽的那批完整度校验,在这里补回来 —— 半成品可以存,不能发。
        // 直接发布路径的同批校验在 createTopicInternal/rebuildTopicContent 的非草稿分支(它们从未放宽)。
        publicationContentGuard.assertPublishReady(topic);
        publicationContentGuard.assertBranchGraphOnlyForOrienteering(topic.getRouteMode(), topic.getProductType());
        boolean simple = isSimplePublishMode(topic.getPublishMode());
        // 两种发布方式都立即可玩，必须在公开前完成引用校验。
        List<ThemeStateKey> declarations = prepareDeclarations(topic);
        publicationContentGuard.assertStateKeysRegistered(topic, declarations);
        publicationContentGuard.assertThoughtRefsResolvable(topic);
        // R8-STORY:剧情配置的章节 / NPC 引用在可见前同样要能对上主题现码
        publicationContentGuard.assertJourneyStoryReferences(topic);
        publicationContentGuard.assertThemeRuntimeReferences(topic, declarations);
        // R11-A:节点叙事段(enter / outcome)的 npcId 同一道归属闸
        publicationContentGuard.assertBlockNarrativeNpcReferences(topic);
        // R14:块配置条件里 sys.* 引用的 nodeId 同一道归属闸
        publicationContentGuard.assertBlockSysVarReferences(topic);
        String publishedRouteSnapshot = simple ? publicationContentGuard.normalizedBranchRouteForPublish(topic, declarations) : null;
        if (!StringUtils.isEmpty(topic.getCircleThemeCode())) {
            CmsTopicChapter circleQuery = new CmsTopicChapter();
            circleQuery.setTopicId(id);
            List<CmsTopicChapter> circleChapters = cmsTopicChapterMapper.selectCmsTopicChapterList(circleQuery);
            CircleThemePublishPolicy.assertSupplied(topic, circleChapters,
                    merchantCircleSupplyProfileMapper.selectEffectiveForPublishByTopicId(id));
        }
        // 同步 master(#1078)后总控裁定 C:发布草稿是自办主题的上架提交口,欠款逾期在这里就拦(放在路线/圈层校验之后、
        // 任何状态写入之前:内容本身不合法先报内容错误,顺序与合并前一致)(pro 待审期不建壳,
        // 闸若只留在建壳处,主理人要等审核通过才碰到)。进商家池主题不建自办壳,不在此拦。
        if (topic.getMerchantStatus() == null || topic.getMerchantStatus() != 1) {
            settlementDebtLedger.requireNoOverdueDebt(topic.getMemberId());
        }
        // AUDIT-G2-1:内容安全下架留置中的草稿,发布只保存内容并送人工复核,不回到在架。
        boolean contentSafetyHeld = com.chengyinhub.business.util.TopicContentSafetyHold.isHeld(topic);
        // #380 草稿缺口:草稿期机审存疑(review_flag=1)的文本发布时不能记成「机审通过」,改走人工抽检。
        boolean draftReviewFlagged = simple && !contentSafetyHeld
                && Integer.valueOf(1).equals(cmsTopicMapper.selectReviewFlag(id));
        CmsTopic upd = new CmsTopic();
        upd.setId(id);
        if (!StringUtils.isEmpty(topic.getCircleThemeCode())) {
            upd.setCircleReviewedAt(DateUtils.getNowDate());
        }
        if (simple) {
            if (Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
                publicationContentGuard.assertOrdinarySupplyRestored(cmsTopicMapper.selectCmsTopicByIdForUpdate(id));
            }
            upd.setStatus(1);      // 简易版免审直发
            if (!contentSafetyHeld) {
                upd.setUserStatus(1);
                // 机审通过/免审,与后审中的 0 区分(见 createTopic 同口径);草稿期机审存疑的记 0 = 后审中
                upd.setCheckStatus(draftReviewFlagged ? 0 : 1);
            }
        }
        if ((simple || upd.getCircleReviewedAt() != null) && cmsTopicMapper.updateCmsTopic(upd) != 1) {
            throw new IllegalStateException("主题发布状态保存失败");
        }
        // 与直接发布同源：带招募截止的草稿发布后必须进入既有立项流程，不能停在 DRAFT 关闭招商。
        submitPublishedProjectReview(id, topic.getMemberId(), topic.getRecruitDeadline());
        TopicBundleSubmitResultVO reviewResult = simple ? null : submitTopicBundle(id, topic.getMemberId());
        // 发布草稿后同步恢复公开帖；专业版审核任务留在后台。留置中不公开。
        if (!contentSafetyHeld) syncCreativeSquarePostVisibility(id, true);
        if (simple && persistRouteSnapshot(id, publishedRouteSnapshot, new Date()) != 1) {
            throw new IllegalStateException("路线发布快照保存失败");
        }
        // R18-DRAFT:草稿期不再预建自办活动壳(草稿不得出现在公开活动列表)⇒ 自办主题在这里补建,
        // 否则「草稿 → 发布」之后没有活动壳,玩家无票可买。商家池主题不建(由中标流程生成)。
        // 专业版已在 publishBeforeReview 中补建，简易版在这里补建。留置中由复核过审时补建。
        if (simple && contentSafetyHeld) {
            Long relistTaskId = requestContentSafetyRelistReview(id, topic.getMemberId());
            TopicBundleSubmitResultVO held = new TopicBundleSubmitResultVO(
                    id, relistTaskId, "PENDING", Collections.<Long>emptyList());
            held.setPublished(false);
            return held;
        }
        if (simple) selfHostActivityService.ensureSelfHostActivityShell(id);
        if (draftReviewFlagged) submitDraftSpotCheck(topic);
        return reviewResult != null ? reviewResult : noBundleReview(id, "NOT_REQUIRED");
    }

    private void submitDraftSpotCheck(CmsTopic topic) {
        AuditTask q = new AuditTask();
        q.setBizType(AuditTask.BIZ_TOPIC);
        q.setBizId(topic.getId());
        for (AuditTask t : nullToEmpty(auditTaskService.selectAuditTaskList(q))) {
            if ((AuditTask.STATUS_PENDING.equals(t.getStatus()) || AuditTask.STATUS_ESCALATED.equals(t.getStatus()))
                    && !t.isOpenReport()) {
                return;
            }
        }
        String name = topic.getName() == null ? "" : topic.getName().replace("\"", "'");
        String snap = "{\"bizType\":\"TOPIC\",\"bizId\":" + topic.getId()
                + ",\"flagReason\":\"draft_review_flag\",\"name\":\"" + name + "\"}";
        auditTaskService.submit(AuditTask.BIZ_TOPIC, topic.getId(), topic.getMemberId(), "post_review_flag", snap);
    }

    private static final class PreparedBundle {
        final com.chengyinhub.business.service.support.TopicBundleReviewService.Submission submission;
        final List<ThemeStateKey> declarations;
        PreparedBundle(com.chengyinhub.business.service.support.TopicBundleReviewService.Submission submission,
                List<ThemeStateKey> declarations) { this.submission = submission; this.declarations = declarations; }
    }

    /** Both original derivations finish before the one final author-declaration copy. */
    private List<ThemeStateKey> prepareDeclarations(CmsTopic current) {
        // Version-bound authors confirm registry declarations before publishing. Deriving a
        // new declaration here would advance the confirmed registryVersion after attribute freeze.
        if (!com.chengyinhub.business.service.support.AuthorAttributeBinding.hasBinding(current.getJourneyRulesJson())) {
            topicRouteRuntimeService.deriveTemplateCluesForPublication(current);
            publicationContentGuard.deriveOptionTagsForPublication(current);
        }
        return com.chengyinhub.business.service.support.ThemeStateKeyDeclarationSnapshot.copy(current.getId(),
                themeStateKeyService.listCurrentDeclarations(current.getId()));
    }

    private PreparedBundle prepareTopicBundle(Long topicId, Long memberId) {
        CmsTopic current = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (current == null || memberId == null || !memberId.equals(current.getMemberId()))
            throw new IllegalStateException("无权提交该主题审核");
        List<ThemeStateKey> declarations = prepareDeclarations(current);
        publicationContentGuard.assertStateKeysRegistered(current, declarations);
        publicationContentGuard.assertThemeRuntimeReferences(current, declarations);
        return new PreparedBundle(topicBundleReviewService.freezeForSubmission(topicId, memberId, declarations), declarations);
    }

    public TopicBundleSubmitResultVO submitTopicBundle(Long topicId, Long memberId) {
        PreparedBundle prepared = prepareTopicBundle(topicId, memberId);
        com.chengyinhub.business.service.support.TopicBundleReviewService.Submission submission = prepared.submission;
        publicationContentGuard.assertTopicSizeWithinLimits(topicId);
        Long taskId = auditTaskService.submitOrRefreshTopicBundle(topicId, memberId, submission.getSnapshotJson());
        AuditTask task = auditTaskService.selectAuditTaskById(taskId);
        if (task == null || (!AuditTask.STATUS_PENDING.equals(task.getStatus())
                && !AuditTask.STATUS_ESCALATED.equals(task.getStatus()))) {
            throw new IllegalStateException("审核任务状态读取失败");
        }
        boolean published = publishBeforeReview(topicId, memberId, prepared.declarations);
        topicBundleReviewService.assertDeclarationsUnchanged(topicId, submission.getSnapshotJson());
        String reviewState = AuditTask.STATUS_ESCALATED.equals(task.getStatus()) ? "ESCALATED" : "PENDING";
        TopicBundleSubmitResultVO result = new TopicBundleSubmitResultVO(
                topicId, taskId, reviewState, submission.getBundledTemplateIds());
        result.setPublished(published);
        attachCommercialPublicationState(result);
        return result;
    }

    public Long requestContentSafetyRelistReview(Long topicId, Long submitterId) {
        if (topicId == null || topicId <= 0L) throw new IllegalArgumentException("主题ID不能为空");
        Long open = findOpenTopicTaskId(topicId);
        if (open != null) return open;
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        if (topic == null) throw new IllegalStateException("主题不存在");
        java.util.Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("bizType", AuditTask.BIZ_TOPIC);
        snap.put("bizId", topicId);
        snap.put("holdReason", com.chengyinhub.business.util.TopicContentSafetyHold.REASON);
        snap.put("name", topic.getName());
        snap.put("imgUrl", topic.getImgUrl());
        snap.put("imgArr", topic.getImgArr());
        String snapshot;
        try {
            snapshot = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(snap);
        } catch (Exception e) {
            throw new IllegalStateException("复核快照生成失败", e);
        }
        Long owner = submitterId != null && submitterId > 0L ? submitterId : topic.getMemberId();
        try {
            return auditTaskService.submit(AuditTask.BIZ_TOPIC, topicId, owner,
                    com.chengyinhub.business.util.TopicContentSafetyHold.SOURCE_RELIST, snapshot);
        } catch (org.springframework.dao.DuplicateKeyException concurrent) {
            // uk_audit_biz_open:并发下另一张未结单先落库,复用它即可
            Long again = findOpenTopicTaskId(topicId);
            if (again != null) return again;
            throw concurrent;
        }
    }

    private Long findOpenTopicTaskId(Long topicId) {
        AuditTask query = new AuditTask();
        query.setBizType(AuditTask.BIZ_TOPIC);
        query.setBizId(topicId);
        List<AuditTask> tasks = auditTaskService.selectAuditTaskList(query);
        if (tasks != null) for (AuditTask task : tasks) {
            // AUDIT-INT-5:举报单在独立槽,不能顶替复核上架单(举报不成立会顺手解除留置)。
            if ((AuditTask.STATUS_PENDING.equals(task.getStatus())
                    || AuditTask.STATUS_ESCALATED.equals(task.getStatus())) && !task.isOpenReport()) {
                return task.getId();
            }
        }
        return null;
    }

    public boolean publishBeforeReview(Long topicId, Long memberId) {
        return publishBeforeReview(topicId, memberId, null);
    }

    private boolean publishBeforeReview(Long topicId, Long memberId, List<ThemeStateKey> declarations) {
        mediaPublicationGuard.requireTopicPublication(topicId);
        // Re-publishing an existing topic does not pass through publishDraftInternal.
        publicationContentGuard.assertTopicSizeWithinLimits(topicId);
        CmsTopic current = cmsTopicMapper.selectCmsTopicById(topicId);
        if (com.chengyinhub.business.util.TopicContentSafetyHold.isHeld(current)) {
            // 已发布过的主题照常刷新路线快照,复核通过上架时就是最新内容;未发布的由过审时统一发布。
            if (Integer.valueOf(1).equals(current.getStatus())) publishValidatedRouteSnapshot(current, declarations);
            requestContentSafetyRelistReview(topicId, memberId);
            return false;
        }
        String quotaError = quotaService.lockAndCheckThemeOnline(memberId, topicId);
        if (quotaError != null) throw new ServiceException(quotaError);
        publishValidatedRouteSnapshot(cmsTopicMapper.selectCmsTopicById(topicId), declarations);
        CmsTopic published = new CmsTopic();
        published.setId(topicId);
        published.setStatus(1);
        published.setUserStatus(1);
        published.setCheckStatus(0);
        published.setReason("");
        if (cmsTopicMapper.updateCmsTopic(published) != 1) {
            throw new IllegalStateException("主题发布状态保存失败");
        }
        syncCreativeSquarePostVisibility(topicId, true);
        selfHostActivityService.ensureSelfHostActivityShell(topicId);
        return true;
    }

    public void submitPublishedProjectReview(Long topicId, Long memberId, Date recruitDeadline) {
        boolean commercial = commercialTopicPolicy.evaluate(topicId).isCommercial();
        if (!commercial && recruitDeadline == null) return;
        topicCommercialReviewService.submitProjectReview(topicId, memberId,
                recruitDeadline != null ? TopicLifecycleState.RECRUITING : TopicLifecycleState.PRICING);
    }

    private void attachCommercialPublicationState(TopicBundleSubmitResultVO result) {
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(result.getTopicId());
        if (topic == null) throw new IllegalStateException("主题发布后状态读取失败");
        Integer lifecycle = topic.getLifecycle();
        boolean awaiting = Integer.valueOf(TopicLifecycleState.PENDING_REVIEW.getCode()).equals(lifecycle)
                || Integer.valueOf(TopicLifecycleState.RECRUITING.getCode()).equals(lifecycle)
                || Integer.valueOf(TopicLifecycleState.PRICING.getCode()).equals(lifecycle);
        result.setCommercialAwaitingReview(awaiting);
        if (awaiting) result.setCommercialNextStep(Integer.valueOf(TopicLifecycleState.PENDING_REVIEW.getCode()).equals(lifecycle)
                ? "已提交立项审核，审核与定价完成前尚未开售"
                : Integer.valueOf(TopicLifecycleState.RECRUITING.getCode()).equals(lifecycle)
                ? "请先完成招募，招募结束后完成定价并自动提交开售审核"
                : "请在定价工作台保存完整开售参数，保存后自动提交开售审核");
    }

    public TopicBundleSubmitResultVO noBundleReview(Long topicId, String state) {
        TopicBundleSubmitResultVO result = new TopicBundleSubmitResultVO(
                topicId, null, state, Collections.<Long>emptyList());
        if (!"DRAFT".equals(state)) {
            CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
            result.setPublished(topic != null && Integer.valueOf(1).equals(topic.getStatus())
                    && Integer.valueOf(1).equals(topic.getUserStatus()));
        }
        if (!"DRAFT".equals(state)) attachCommercialPublicationState(result);
        return result;
    }

    public static boolean isSimplePublishMode(String publishMode) {
        return "simple".equalsIgnoreCase(publishMode) || "ai_simple".equalsIgnoreCase(publishMode);
    }

    private void syncCreativeSquarePostVisibility(Long topicId, boolean visible) {
        if (umsCreativeSquareService == null) {
            return;
        }
        umsCreativeSquareService.syncTopicPostVisibility(topicId, visible);
    }


    public void publishApprovedRouteSnapshot(Long topicId) {
        CmsTopic topic = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || !Integer.valueOf(1).equals(topic.getStatus())) {
            throw new IllegalStateException("主题尚未成功通过审核，拒绝推进路线发布快照");
        }
        publicationContentGuard.assertTopicSizeWithinLimits(topicId);
        // Approval has already bound the declaration hash. Consume it; never derive new rows here.
        List<ThemeStateKey> declarations = com.chengyinhub.business.service.support.ThemeStateKeyDeclarationSnapshot.copy(topicId,
                themeStateKeyService.listCurrentDeclarations(topicId));
        publishValidatedRouteSnapshot(topic, declarations);
    }

    private void publishValidatedRouteSnapshot(CmsTopic topic) {
        publishValidatedRouteSnapshot(topic, null);
    }

    private String compileValidatedRouteSnapshot(CmsTopic topic, List<ThemeStateKey> declarations) {
        if (topic == null) throw new IllegalStateException("主题不存在");
        Long topicId = topic.getId();
        CmsTopic current = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (current == null || !topicId.equals(current.getId())) throw new IllegalStateException("发布主题当前版本不存在");
        topic = current;
        if (declarations == null) declarations = prepareDeclarations(current);
        publicationContentGuard.assertStateKeysRegistered(topic, declarations);
        publicationContentGuard.assertThoughtRefsResolvable(topic);
        // R8-STORY:剧情配置的章节 / NPC 引用同上
        publicationContentGuard.assertJourneyStoryReferences(topic);
        publicationContentGuard.assertThemeRuntimeReferences(topic, declarations);
        // R11-A:节点叙事段(enter / outcome)的 npcId 同一道归属闸
        publicationContentGuard.assertBlockNarrativeNpcReferences(topic);
        // R14:块配置条件里 sys.* 引用的 nodeId 同一道归属闸
        publicationContentGuard.assertBlockSysVarReferences(topic);
        return publicationContentGuard.normalizedBranchRouteForPublish(topic, declarations);
    }

    private void publishValidatedRouteSnapshot(CmsTopic topic, List<ThemeStateKey> declarations) {
        String snapshot = compileValidatedRouteSnapshot(topic, declarations);
        if (persistRouteSnapshot(topic.getId(), snapshot, new Date()) != 1)
            throw new IllegalStateException("路线发布快照保存失败");
    }

    /** Existing direct-create callers retain their original lifecycle/permission and rowcount handling. */
    public int persistCreatedRouteSnapshot(Long topicId) {
        CmsTopic current = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (current == null) throw new IllegalStateException("发布主题当前版本不存在");
        String snapshot = compileValidatedRouteSnapshot(current, null);
        return persistRouteSnapshot(topicId, snapshot, new Date());
    }

    /** The publication owner is the single route-publication write port; callers retain original guards and rowcount checks. */
    public int persistRouteSnapshot(Long topicId, String snapshot, Date publishedAt) {
        return cmsTopicMapper.publishRouteSnapshot(topicId, snapshot, publishedAt);
    }
}
