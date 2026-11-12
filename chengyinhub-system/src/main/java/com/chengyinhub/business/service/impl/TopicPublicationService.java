package com.chengyinhub.business.service.impl;

import java.util.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO;
import com.chengyinhub.business.domain.vo.TopicPublicationStatusVO;
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
import com.chengyinhub.business.service.support.SaleAvailabilityPolicy;
import com.chengyinhub.business.service.support.TopicContentGeography;
import com.chengyinhub.business.util.TopicEditWindow;

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
    /** BE-06:正式发布事务内把精确冻结内容落成不可变修订;发布头推进失败时随事务一起回滚。 */
    @Autowired private com.chengyinhub.business.service.ITopicContentRevisionService contentRevisionService;
    private static <T> List<T> nullToEmpty(List<T> rows) { return rows == null ? Collections.<T>emptyList() : rows; }

    public boolean canResubmitSupplyReview(Long id, Long ownerId) {
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(id);
        return topic != null && java.util.Objects.equals(ownerId, topic.getMemberId())
                && supplyReviewService.canResubmit(topic);
    }

    /**
     * F1 发布入口重试:同一作者 + 同一发布意图号 + 同一 payload,在原发布已经推过状态之后重放,
     * 返回当时那条不可变修订,<b>不</b>再走额度/审核/身份/发布头写入任何一步。
     *
     * <p>为什么必须与「是不是草稿」解耦:发布成功后 {@code is_draft} 已被清掉,原始客户端超时重发
     * 会被草稿闸当成「不是草稿」而失败,作者看到失败但内容其实已经上线。幂等键是
     * {@code publishRequestId} + payload,不是内容 hash,也不是「当前是否草稿」。
     *
     * @param expectedContentSha256 客户端从首次响应读回的 {@code contentSha256};提供且不一致 = 409
     * @return null = 该意图没有既有修订(按新发布继续原流程);非 null = 重放结果,调用方直接返回
     */
    public TopicBundleSubmitResultVO replayPublishedRevision(Long topicId, Long expectedOwnerId,
                                                             String publishRequestId, String expectedContentSha256) {
        // 没有发布意图号的旧调用一律在此短路:不读主题、不加锁、不与既有发布路径产生任何交互。
        if (topicId == null || topicId <= 0L || publishRequestId == null || publishRequestId.trim().isEmpty())
            return null;
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        if (topic == null) return null;
        return replayPublishedRevision(topic, expectedOwnerId, publishRequestId, expectedContentSha256);
    }

    private TopicBundleSubmitResultVO replayPublishedRevision(CmsTopic topic, Long expectedOwnerId,
                                                              String publishRequestId, String expectedContentSha256) {
        if (topic == null || topic.getId() == null
                || publishRequestId == null || publishRequestId.trim().isEmpty()) return null;
        // 归属先于一切:重放同样只能由主题归属者发起,不能被他人的请求号探测内容。
        if (expectedOwnerId == null || !expectedOwnerId.equals(topic.getMemberId()))
            throw new ServiceException("主题归属已变化，无权提交", 403);
        CmsTopicContentRevision revision = contentRevisionService.publishedByRequestId(
                topic.getId(), publishRequestId, expectedContentSha256);
        return revision == null ? null : publishedReplayResult(topic, revision);
    }

    /**
     * 重放回执:只读当前公开事实 + 那一条修订的身份。
     * {@code attachCommercialPublicationState} 填的是「当前最新修订」,这里必须用本次意图的修订覆盖 ——
     * 两者在「A 发布 → B 发布 → 重试 A」时不同。
     */
    private TopicBundleSubmitResultVO publishedReplayResult(CmsTopic topic, CmsTopicContentRevision revision) {
        AuditTask open = openTopicReviewTask(topic.getId());
        String reviewState = open == null ? "NOT_REQUIRED"
                : (AuditTask.STATUS_ESCALATED.equals(open.getStatus()) ? "ESCALATED" : "PENDING");
        TopicBundleSubmitResultVO result = new TopicBundleSubmitResultVO(
                topic.getId(), open == null ? null : open.getId(), reviewState, Collections.<Long>emptyList());
        result.setPublished(Integer.valueOf(1).equals(topic.getStatus())
                && Integer.valueOf(1).equals(topic.getUserStatus()));
        attachCommercialPublicationState(result);
        result.setContentRevisionId(revision.getId());
        result.setContentRevisionNo(revision.getRevisionNo());
        result.setContentSchemaVersion(revision.getSchemaVersion());
        result.setContentSha256(revision.getContentSha256());
        return result;
    }

    /** 旧调用:没有发布请求号,每次正式发布各产生一条新修订。 */
    public TopicBundleSubmitResultVO publishDraftInternal(Long id, Long expectedOwnerId, boolean allowSupplyResubmission) {
        return publishDraftInternal(id, expectedOwnerId, allowSupplyResubmission, null);
    }

    /**
     * @param publishRequestId 可选的发布意图号;同号同 payload = 重试回同一条修订,
     *                         同号异 payload = 409。为 null 时保留原有「每次发布一条」语义。
     */
    public TopicBundleSubmitResultVO publishDraftInternal(Long id, Long expectedOwnerId, boolean allowSupplyResubmission,
                                                          String publishRequestId) {
        CmsTopic topic = supplyReviewService.lockTopic(id);
        if (expectedOwnerId != null && (topic == null || !expectedOwnerId.equals(topic.getMemberId())))
            throw new ServiceException("主题归属已变化，无权提交", 403);
        // F1:发布已完成后的同意图重试先在这里收口(上传前的草稿闸不会再挡它),且不写任何行。
        TopicBundleSubmitResultVO replayed = replayPublishedRevision(topic, expectedOwnerId, publishRequestId, null);
        if (replayed != null) return replayed;
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
        // B2 地点合同:必填槽未绑定 / 需重绑 / 城市不一致才拦;旧模板沿用只是提醒,不新阻断
        publicationContentGuard.assertLocationBindingsPublishable(topic);
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
        TopicBundleSubmitResultVO reviewResult = simple ? null : submitTopicBundle(id, topic.getMemberId(), publishRequestId);
        // 发布草稿后同步恢复公开帖；专业版审核任务留在后台。留置中不公开。
        if (!contentSafetyHeld) syncCreativeSquarePostVisibility(id, true);
        if (simple && persistRouteSnapshot(id, publishedRouteSnapshot, new Date(), publishRequestId) != 1) {
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

    /** 旧调用:没有发布请求号。 */
    public TopicBundleSubmitResultVO submitTopicBundle(Long topicId, Long memberId) {
        return submitTopicBundle(topicId, memberId, null);
    }

    public TopicBundleSubmitResultVO submitTopicBundle(Long topicId, Long memberId, String publishRequestId) {
        PreparedBundle prepared = prepareTopicBundle(topicId, memberId);
        com.chengyinhub.business.service.support.TopicBundleReviewService.Submission submission = prepared.submission;
        publicationContentGuard.assertTopicSizeWithinLimits(topicId);
        Long taskId = auditTaskService.submitOrRefreshTopicBundle(topicId, memberId, submission.getSnapshotJson());
        AuditTask task = auditTaskService.selectAuditTaskById(taskId);
        if (task == null || (!AuditTask.STATUS_PENDING.equals(task.getStatus())
                && !AuditTask.STATUS_ESCALATED.equals(task.getStatus()))) {
            throw new IllegalStateException("审核任务状态读取失败");
        }
        boolean published = publishBeforeReview(topicId, memberId, prepared.declarations, publishRequestId);
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
        snap.put("holdReason", StringUtils.isEmpty(topic.getReason())
                ? com.chengyinhub.business.util.TopicContentSafetyHold.REASON : topic.getReason());
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
        AuditTask task = openTopicReviewTask(topicId);
        return task == null ? null : task.getId();
    }

    /** 未结的主题审核单(待审/升级人工);状态投影与复核上架单复用同一份挑选口径。 */
    private AuditTask openTopicReviewTask(Long topicId) {
        AuditTask query = new AuditTask();
        query.setBizType(AuditTask.BIZ_TOPIC);
        query.setBizId(topicId);
        List<AuditTask> tasks = auditTaskService.selectAuditTaskList(query);
        if (tasks != null) for (AuditTask task : tasks) {
            // AUDIT-INT-5:举报单在独立槽,不能顶替复核上架单(举报不成立会顺手解除留置)。
            if ((AuditTask.STATUS_PENDING.equals(task.getStatus())
                    || AuditTask.STATUS_ESCALATED.equals(task.getStatus())) && !task.isOpenReport()) {
                return task;
            }
        }
        return null;
    }

    public boolean publishBeforeReview(Long topicId, Long memberId) {
        return publishBeforeReview(topicId, memberId, null, null);
    }

    private boolean publishBeforeReview(Long topicId, Long memberId, List<ThemeStateKey> declarations,
                                        String publishRequestId) {
        mediaPublicationGuard.requireTopicPublication(topicId);
        // Re-publishing an existing topic does not pass through publishDraftInternal.
        publicationContentGuard.assertTopicSizeWithinLimits(topicId);
        CmsTopic current = cmsTopicMapper.selectCmsTopicById(topicId);
        if (com.chengyinhub.business.util.TopicContentSafetyHold.isHeld(current)) {
            // 已发布过的主题照常刷新路线快照,复核通过上架时就是最新内容;未发布的由过审时统一发布。
            if (Integer.valueOf(1).equals(current.getStatus()))
                publishValidatedRouteSnapshot(current, declarations, publishRequestId);
            requestContentSafetyRelistReview(topicId, memberId);
            return false;
        }
        String quotaError = quotaService.lockAndCheckThemeOnline(memberId, topicId);
        if (quotaError != null) throw new ServiceException(quotaError, com.chengyinhub.common.constant.HttpStatus.BAD_REQUEST);
        publishValidatedRouteSnapshot(cmsTopicMapper.selectCmsTopicById(topicId), declarations, publishRequestId);
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

    /**
     * 作者端发布状态窄投影:draft / review / visibility / sale / playable 五段事实,彼此独立。
     *
     * <p>只读,不写库、不加锁、不改任何发布态。存在的理由只有一个:专业主题是「先公开后审」,
     * {@code status=1,userStatus=1,checkStatus=0} 同时表示「玩家看得见」和「还没过审」——
     * 压成一个 published 布尔,作者会以为已过审,运营会以为已验收。所以过审只看 review.passed。
     *
     * <p>这不是第二套 readiness:售卖原因直接读 {@link SaleAvailabilityPolicy#sellableBlockReason},
     * 可玩原因用运行期同一句「路线尚未发布」,审核事实读审核单与 checkStatus —— 都是既有真源。
     */
    public TopicPublicationStatusVO publicationStatus(CmsTopic topic) {
        if (topic == null || topic.getId() == null || topic.getId() <= 0L) throw new ServiceException("主题不存在");
        Long topicId = topic.getId();
        Integer isDraft = cmsTopicMapper.selectTopicIsDraft(topicId);
        boolean draft = isDraft != null && isDraft.intValue() == 1;

        // 可编辑档位与主题编辑接口同一份判定(TopicEditWindow),只读投影,不另判一次。
        boolean editable = false;
        String editBlocked = null;
        try {
            TopicEditWindow.Scope window = TopicEditWindow.resolve(topic, isDraft, DateUtils.getNowDate());
            editable = window == TopicEditWindow.Scope.FULL;
            if (window == TopicEditWindow.Scope.WHITELIST) {
                editBlocked = "主题已上架，只能修改文案等白名单字段";
            } else if (window == TopicEditWindow.Scope.SELLING_FROZEN) {
                editBlocked = "主题已进入售卖/履约阶段，商业结构已冻结";
            }
        } catch (IllegalStateException refused) {
            editBlocked = refused.getMessage();
        }

        AuditTask openTask = openTopicReviewTask(topicId);
        Integer checkStatus = topic.getCheckStatus();
        // 内容安全留置(check_status=2 但 status≠2)是「等人工复核」,不是「被驳回」——
        // 算成驳回会让作者以为内容被否了,而实际只要换图复核通过就自动上架。
        boolean held = com.chengyinhub.business.util.TopicContentSafetyHold.isHeld(topic);
        boolean rejected = Integer.valueOf(2).equals(topic.getStatus());
        // 过审的唯一判据是 checkStatus=1(机审通过/免审/人工通过);「公开」不参与判定。
        boolean passed = !rejected && !held && Integer.valueOf(1).equals(checkStatus);
        // 状态不猜:checkStatus=0 本身不代表存在审核单,pending 只认真正未结的那张单。
        boolean pending = !rejected && !passed && !draft && openTask != null;
        String reviewState;
        String reviewLabel;
        if (rejected) {
            reviewState = TopicPublicationStatusVO.REVIEW_REJECTED;
            reviewLabel = "未通过";
        } else if (draft) {
            reviewState = TopicPublicationStatusVO.REVIEW_DRAFT;
            reviewLabel = "草稿";
        } else if (held) {
            reviewState = TopicPublicationStatusVO.REVIEW_PENDING;
            reviewLabel = "内容安全复核中";
        } else if (passed) {
            reviewState = TopicPublicationStatusVO.REVIEW_PASSED;
            reviewLabel = "已通过";
        } else if (openTask != null && AuditTask.STATUS_ESCALATED.equals(openTask.getStatus())) {
            reviewState = TopicPublicationStatusVO.REVIEW_ESCALATED;
            reviewLabel = "人工复审中";
        } else if (openTask != null) {
            reviewState = TopicPublicationStatusVO.REVIEW_PENDING;
            reviewLabel = "审核中";
        } else {
            // 后审通道:只到「后审中」,不假装有一张审核单在跑。
            reviewState = TopicPublicationStatusVO.REVIEW_AWAITING;
            reviewLabel = "后审中（当前无未结审核单）";
        }
        TopicPublicationStatusVO.Review review = new TopicPublicationStatusVO.Review(
                reviewState, reviewLabel, passed, pending,
                openTask == null ? null : openTask.getId(),
                rejected ? topic.getReason() : null);

        boolean listed = Integer.valueOf(1).equals(topic.getStatus());
        boolean visible = listed && Integer.valueOf(1).equals(topic.getUserStatus()) && !held;
        String visibleReason = visible ? null
                : held ? "内容安全复核中，暂不公开"
                : !listed ? (rejected ? "审核未通过，玩家看不到" : "尚未发布，玩家看不到")
                : "已下架，玩家看不到";
        TopicPublicationStatusVO.Visibility visibility = new TopicPublicationStatusVO.Visibility(visible, visibleReason);

        Integer lifecycle = topic.getLifecycle();
        TopicLifecycleState saleState = null;
        try { saleState = TopicLifecycleState.fromCode(lifecycle); } catch (IllegalArgumentException unknown) { saleState = null; }
        boolean lifecycleSaleOpen = saleState != null && saleState.isDirectSaleOpen();
        String saleBlockReason = SaleAvailabilityPolicy.sellableBlockReason(topic, null, DateUtils.getNowDate());
        // 「在售」= 生命周期放行 且 售卖闸放行,两者不可分开成立:
        // 否则会出现「在售」同时「saleEndAt 已到,不可再售」的自相矛盾。
        TopicPublicationStatusVO.Sale sale = new TopicPublicationStatusVO.Sale(
                lifecycleSaleOpen && saleBlockReason == null,
                lifecycleSaleOpen,
                isCommercialAwaitingReview(lifecycle),
                commercialNextStepOf(lifecycle),
                saleBlockReason);

        Long publishedConfigVersion = topic.getPublishedConfigVersion();
        boolean snapshotReady = snapshotPublished(topic);
        TopicPublicationStatusVO.Playable play = new TopicPublicationStatusVO.Playable(
                snapshotReady,
                snapshotReady ? null : "路线尚未发布",
                publishedConfigVersion,
                Integer.valueOf(1).equals(topic.getBetaFlag()));

        return new TopicPublicationStatusVO(
                new TopicPublicationStatusVO.Draft(draft, editable, editBlocked),
                review, visibility, sale, play);
    }

    /**
     * 开局所需的已发布内容事实 —— 判据取自运行期入口本身,不按 publishMode 猜。
     *
     * <p>证据链:{@code PlayEntryApplication#routeState} → {@code ITopicRouteRuntimeService#state} →
     * {@code requireScope} → {@code currentScope},那里对<b>所有</b> routeMode 一律要求
     * {@code publishedConfigVersion>0}(或本局行已存在),否则抛「路线尚未发布」。
     * 所以 simple / ai_simple 没有任何豁免:
     *
     * <p>{@code ensureBranchSession} 额外要求 {@code publishedRouteGraphJson} 非空;
     * {@code ensureJourneySession} 只需 {@code publishedConfigVersion>0}(无图时用 linearSnapshot 现编)。
     * 是否建旅程局由 {@code journeyConfigured} 按<b>内容</b>判定
     * (publishedGraph.runtimeSnapshotVersion==1 / openingChapter / publishedThemeRuntimeConfig /
     * journeyRules.hasRuntimeState / 块需要本局状态),与 publishMode 无关;纯线性老主题不建局,
     * 但仍要过 currentScope 的版本闸。
     *
     * <p>这里只答「内容侧已发布快照能否用于开局」,不含报名/权益/可玩时段 —— 那些由
     * {@code CurrentPlayRegistration.require} 与 {@code TopicPlaySetupService.requireOpen} 在开局时判定。
     */
    private static boolean snapshotPublished(CmsTopic topic) {
        Long version = topic.getPublishedConfigVersion();
        if (version == null || version.longValue() <= 0L) return false;
        return !"BRANCH_GRAPH".equalsIgnoreCase(topic.getRouteMode())
                || !StringUtils.isEmpty(topic.getPublishedRouteGraphJson());
    }

    private void attachCommercialPublicationState(TopicBundleSubmitResultVO result) {
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(result.getTopicId());
        if (topic == null) throw new IllegalStateException("主题发布后状态读取失败");
        TopicPublicationStatusVO status = publicationStatus(topic);
        result.setPublicationStatus(status);
        result.setReviewPassed(status.getReview().isPassed());
        result.setCheckStatus(topic.getCheckStatus());
        result.setReviewConfigVersion(topic.getConfigVersion());
        result.setCommercialAwaitingReview(status.getSale().isAwaitingReview());
        result.setCommercialNextStep(status.getSale().getNextStep());
        result.setPublicVisible(status.getVisibility().isVisible());
        result.setPublicReason(status.getVisibility().getReason());
        result.setSaleOpen(status.getSale().isOnSale());
        result.setSaleBlockReason(status.getSale().getBlockReason());
        result.setPlayable(status.getPlayable().isPlayable());
        result.setPlayableReason(status.getPlayable().getReason());
        // BE-06:发布响应带上本次落成的修订引用。老主题没有修订行(未导入)时为 null,不伪造。
        com.chengyinhub.business.domain.vo.TopicContentRevisionView revision =
                contentRevisionService.currentPublished(topic.getId());
        if (revision != null) {
            result.setContentRevisionId(revision.getId());
            result.setContentRevisionNo(revision.getRevisionNo());
            result.setContentSchemaVersion(revision.getSchemaVersion());
            result.setContentSha256(revision.getContentSha256());
        }
    }

    /** 立项/招募/定价三态统一口径:作者端文案与发布结果商业段共用这一份,不各写一遍。 */
    private static boolean isCommercialAwaitingReview(Integer lifecycle) {
        return Integer.valueOf(TopicLifecycleState.PENDING_REVIEW.getCode()).equals(lifecycle)
                || Integer.valueOf(TopicLifecycleState.RECRUITING.getCode()).equals(lifecycle)
                || Integer.valueOf(TopicLifecycleState.PRICING.getCode()).equals(lifecycle);
    }

    private static String commercialNextStepOf(Integer lifecycle) {
        if (!isCommercialAwaitingReview(lifecycle)) return null;
        if (Integer.valueOf(TopicLifecycleState.PENDING_REVIEW.getCode()).equals(lifecycle)) {
            return "已提交立项审核，审核与定价完成前尚未开售";
        }
        if (Integer.valueOf(TopicLifecycleState.RECRUITING.getCode()).equals(lifecycle)) {
            return "请先完成招募，招募结束后完成定价并自动提交开售审核";
        }
        return "请在定价工作台保存完整开售参数，保存后自动提交开售审核";
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
        publishValidatedRouteSnapshot(topic, declarations, null);
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
        // B2 地点合同与首次发布同一口径:error 级缺口才拦,LEGACY 只提醒
        publicationContentGuard.assertLocationBindingsPublishable(topic);
        return publicationContentGuard.normalizedBranchRouteForPublish(topic, declarations);
    }

    private void publishValidatedRouteSnapshot(CmsTopic topic, List<ThemeStateKey> declarations,
                                               String publishRequestId) {
        String snapshot = compileValidatedRouteSnapshot(topic, declarations);
        if (persistRouteSnapshot(topic.getId(), snapshot, new Date(), publishRequestId) != 1)
            throw new IllegalStateException("路线发布快照保存失败");
    }

    /** Existing direct-create callers retain their original lifecycle/permission and rowcount handling. */
    public int persistCreatedRouteSnapshot(Long topicId) {
        CmsTopic current = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (current == null) throw new IllegalStateException("发布主题当前版本不存在");
        String snapshot = compileValidatedRouteSnapshot(current, null);
        return persistRouteSnapshot(topicId, snapshot, new Date());
    }

    /** 旧调用:没有发布请求号。 */
    public int persistRouteSnapshot(Long topicId, String snapshot, Date publishedAt) {
        return persistRouteSnapshot(topicId, snapshot, publishedAt, null);
    }

    /**
     * The publication owner is the single route-publication write port; callers retain original guards and rowcount checks.
     *
     * <p>BE-06:先把本次<b>精确冻结 payload</b>落成不可变修订,再推进发布头,两者同一事务。
     * 发布头失败会抛出 → 修订随事务回滚,不会留下「有修订但不可见」的可下载孤立版本;
     * 同号同 payload 的重试短路返回 1 —— 发布头已经推进过,不能再 +1 一次。
     *
     * <p>F1:同一份字节里补 {@code themeConfig.contentGeography}(作者本次发布的真实国家/城市),
     * 修订与发布头必须写<b>同一个</b>字符串,否则「修订 payload == 当前发布头」就不成立。
     * 冻结图里已有地理或没有 themeConfig 时原串不变(理由见 {@link TopicContentGeography#decoratePublication})。
     */
    public int persistRouteSnapshot(Long topicId, String snapshot, Date publishedAt, String publishRequestId) {
        // 主题行已被本事务的发布入口锁住,这里只做只读取属性;没有主题时按原串继续。
        String publishSnapshot = TopicContentGeography.decoratePublication(
                snapshot, cmsTopicMapper.selectCmsTopicById(topicId));
        com.chengyinhub.business.domain.dto.TopicContentRevisionWriteResult revision =
                contentRevisionService.recordPublishedRevision(
                        com.chengyinhub.business.domain.dto.TopicContentRevisionWrite.of(
                                topicId, publishSnapshot, publishRequestId));
        if (revision != null && revision.isIdempotentReplay()) return 1;
        return cmsTopicMapper.publishRouteSnapshot(topicId, publishSnapshot, publishedAt);
    }
}
