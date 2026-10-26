package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.annotation.CriticalPath;
import com.chengyinhub.common.observability.CriticalPathCode;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.service.support.TemplateAuditSnapshot;
import com.chengyinhub.business.service.support.AuditProblemService;
import com.chengyinhub.business.util.TopicContentSafetyHold;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostComment;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.UmsComment;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.domain.dto.AuditRevertDTO;
import com.chengyinhub.business.domain.vo.AuditRevertResultVO;
import com.chengyinhub.business.domain.vo.AuditTaskDetailVO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ITopicLifecycleService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IClubPostCommentService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IUmsCommentService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.support.TopicBundleReviewService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.utils.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * P3 统一审核任务服务实现。
 */
@Service
public class AuditTaskServiceImpl implements IAuditTaskService {

    public static final String REJECT_REASON_REQUIRED = "拒绝理由不能为空";
    public static final int REVERT_WINDOW_MINUTES = 10;
    public static final String ERROR_REVERT_BAD_REQUEST = "REVERT_BAD_REQUEST";
    public static final String ERROR_REVERT_KEY_REUSED = "REVERT_IDEMPOTENCY_KEY_REUSED";
    public static final String ERROR_REVERT_STALE = "REVERT_STALE";
    public static final String ERROR_REVERT_STATE = "REVERT_STATE_UNSUPPORTED";
    public static final String ERROR_REVERT_TYPE = "REVERT_TYPE_UNSUPPORTED";
    public static final String ERROR_REVERT_DECISION_MISSING = "REVERT_DECISION_MISSING";
    public static final String ERROR_REVERT_FORBIDDEN = "REVERT_FORBIDDEN";
    public static final String ERROR_REVERT_EXPIRED = "REVERT_EXPIRED";
    public static final String ERROR_REVIEW_KEY_REUSED = "AUDIT_REVIEW_IDEMPOTENCY_KEY_REUSED";
    public static final String ERROR_REVIEW_FORBIDDEN = "AUDIT_REVIEW_REPLAY_FORBIDDEN";
    public static final String ERROR_REVIEW_STALE_LEVEL = "AUDIT_REVIEW_STALE_LEVEL";
    public static final String ERROR_APPEAL_BAD_REQUEST = "APPEAL_BAD_REQUEST";
    public static final String ERROR_APPEAL_FORBIDDEN = "APPEAL_FORBIDDEN";
    public static final String ERROR_APPEAL_NOT_ELIGIBLE = "APPEAL_NOT_ELIGIBLE";
    public static final String ERROR_APPEAL_DUPLICATE = "APPEAL_DUPLICATE";
    public static final String ERROR_APPEAL_STALE = "APPEAL_STALE";
    public static final String ERROR_CONTENT_SAFETY_HOLD = "CONTENT_SAFETY_HOLD";
    /** O5b-2:申诉附件开关关闭时带了附件。 */
    public static final String ERROR_APPEAL_ATTACHMENTS_DISABLED = "APPEAL_ATTACHMENTS_DISABLED";
    /** O5b-2:申诉附件不是本人上传、或尚未通过内容安全审核。 */
    public static final String ERROR_APPEAL_ATTACHMENT_INVALID = "APPEAL_ATTACHMENT_INVALID";
    static final int MAX_APPEAL_ATTACHMENTS = 6;
    /** 作者申诉生成的审核任务来源；裁决 ACCEPTED 走既有通过路径恢复内容。 */
    public static final String SOURCE_APPEAL = "appeal";
    private static final int MAX_APPEAL_REASON_LENGTH = 500;
    private static final int MAX_APPEAL_EVIDENCE_LENGTH = 4000;
    /** 申诉无紧急举报原因时的处理时限，与举报标准首响 24h 同口径。 */
    private static final long APPEAL_STANDARD_DUE_MINUTES = 24 * 60L;
    private static final int MAX_REASON_LENGTH = 200;

    private static final Logger logger = LoggerFactory.getLogger(AuditTaskServiceImpl.class);

    /** 举报来源类型(仅此来源受 submitterId 限频;auto_gate/user_publish 不限)。 */
    private static final String SOURCE_REPORT = "report";
    private static final String SOURCE_AUTO_GATE = "auto_gate";
    /** 举报限频时间窗(秒):同一提交人此窗口内新建举报单计数。 */
    private static final long REPORT_RATE_LIMIT_WINDOW_SECONDS = 3600L;
    /** 举报限频阈值:窗口内新建举报单达到此数即拒绝后续举报。 */
    private static final int REPORT_RATE_LIMIT_MAX = 20;

    /**
     * 新建审核单的处理时限(小时),deadline_at = submitted_at + 时限;仅作用于新单,存量行不回填。
     * 商业立项/开售审核恒标 HIGH,按主题类 48h 计,不走高风险 4h。
     */
    @Value("${chengyin.audit.deadline-hours.high-risk:4}")
    private int highRiskDeadlineHours = 4;
    @Value("${chengyin.audit.deadline-hours.community:24}")
    private int communityDeadlineHours = 24;
    @Value("${chengyin.audit.deadline-hours.activity-topic:48}")
    private int activityTopicDeadlineHours = 48;
    @Value("${chengyin.audit.deadline-hours.default:24}")
    private int defaultDeadlineHours = 24;

    /**
     * O-05 #2 风险随内容升级开关(默认关):新举报 / 重提换了快照 / 门禁再次进件时按内容重算开放单 risk_level,只升不降。
     * 只改 risk_level 不改 deadline_at(处理时限口径另行处理)。
     */
    @Value("${chengyin.audit.risk-escalation.enabled:false}")
    private boolean riskEscalationEnabled = false;
    /** 同一内容开放审核单上的去重举报人数达到此值即升 HIGH。 */
    @Value("${chengyin.audit.risk-escalation.report-threshold:3}")
    private int riskEscalationReportThreshold = 3;

    /** O-05 #4 逐项驳回原因开关(默认关):主题/玩法驳回可附逐项问题,落 audit_problem,作者时间线可读。 */
    @Value("${chengyin.audit.item-problems.enabled:false}")
    private boolean itemProblemsEnabled = false;
    @Autowired(required = false)
    private com.chengyinhub.business.service.support.AuditProblemService auditProblemService;

    @Autowired(required = false)
    private com.chengyinhub.business.service.GuardArrivalEvidenceReader guardArrivalEvidenceReader;
    @Autowired(required = false)
    private com.chengyinhub.business.mapper.PlayArriveGuardLogMapper guardAppealLogMapper;
    @Autowired(required = false)
    private com.chengyinhub.business.service.support.GuardAppealReviewerScope guardAppealReviewerScope;


    /** O5b-2 开关(默认关):申诉可附证据图片(复用 OSS 上传 + 媒体审核管线,附件须本人上传且已过内容安全)。 */
    @Value("${chengyin.audit.o5b.appeal-attachments.enabled:false}")
    private boolean appealAttachmentsEnabled = false;

    /** O5b-3 开关(默认关):举报的首响时钟(举报时刻 + 原因首响时限)带进处置它的审核单,只收紧不放宽。 */
    @Value("${chengyin.audit.o5b.report-sla-inherit.enabled:false}")
    private boolean reportSlaInheritEnabled = false;

    @Autowired
    private AuditTaskMapper auditTaskMapper;

    @Autowired
    private com.chengyinhub.business.mapper.CommunityPostMapper communityPostMapper;

    @Autowired
    private com.chengyinhub.business.mapper.RoamHangoutMapper roamHangoutMapper;

    @Autowired
    private com.chengyinhub.business.mapper.PlayTeamMapper playTeamMapper;

    @Autowired
    private AuditRecordMapper auditRecordMapper;

    @Autowired
    private com.chengyinhub.business.mapper.AuditAppealMapper auditAppealMapper;

    /** 审批写路径必须与不可变审计同事务提交；组件缺失时应用启动失败。 */
    @Autowired
    private BusinessAuditEventMapper businessAuditEventMapper;

    @Autowired
    private ObjectProvider<com.chengyinhub.business.service.IImService> imServiceProvider;

    @Autowired
    private com.chengyinhub.business.mapper.ImConversationMemberMapper imConversationMemberMapper;

    private Clock clock = Clock.systemDefaultZone();

    // 内容下架分发依赖(软封,可逆)
    @Autowired
    private IUmsCreativeSquareService umsCreativeSquareService;
    @Autowired
    private IClubPostService clubPostService;
    @Autowired
    private IClubPostCommentService clubPostCommentService;
    @Autowired
    private IUmsCommentService umsCommentService;
    @Autowired
    private ImMessageMapper imMessageMapper;
    @Autowired
    private CmsTopicMapper cmsTopicMapper;
    @Autowired
    private CmsActivityMapper cmsActivityMapper;
    @Autowired
    private TopicBundleReviewService topicBundleReviewService;
    @Autowired
    private com.chengyinhub.business.service.ICmsMemberTemplateService cmsMemberTemplateService;
    @Autowired
    private com.chengyinhub.business.service.ICmsTemplateLibraryService cmsTemplateLibraryService;
    @Autowired
    private CmsMemberTemplateMapper templateMapper;
    @Autowired
    private CmsTemplateLibraryMapper libraryMapper;
    // 过审自动上架需过配额闸(锁+复查,单一实现在 QuotaService),防并发批准绕过 maxThemes
    @Autowired
    @Lazy
    private IQuotaService quotaService;
    @Autowired
    private ITopicLifecycleService topicLifecycleService;
    @Autowired
    private com.chengyinhub.business.service.ITopicSaleFinalizationService topicSaleFinalizationService;
    @Autowired
    private ObjectProvider<com.chengyinhub.business.service.ICoopInviteService> coopInviteServiceProvider;
    @Autowired
    private ObjectProvider<com.chengyinhub.business.service.ITopicCommercialReviewService> commercialReviewServiceProvider;
    /** 懒取以切断 CmsTopicServiceImpl → IAuditTaskService 的既有反向依赖。 */
    @Autowired
    private ObjectProvider<com.chengyinhub.business.service.ICmsTopicService> cmsTopicServiceProvider;

    /** ObjectProvider 打破评价举报上报 IAuditTaskService 与平台复核回写之间的依赖环。 */
    @Autowired
    private ObjectProvider<com.chengyinhub.business.service.IMerchantReviewService> merchantReviewServiceProvider;
    /** 懒取以切断帖文发布安全门禁与审核回写之间的依赖环。 */
    @Autowired
    private ObjectProvider<com.chengyinhub.business.service.ICommunityPostService> communityPostServiceProvider;

    /** 官方活动先审后发:服务提交审核单,过审反向回写 —— ObjectProvider 破环。 */
    @Autowired
    private ObjectProvider<com.chengyinhub.business.service.IOfficialEventService> officialEventServiceProvider;

    @Autowired
    private ObjectProvider<com.chengyinhub.business.service.ISmsCouponService> smsCouponServiceProvider;

    /** 附近的局:举报走本服务上报,处置反向调它 —— 同样用 ObjectProvider 破环。 */
    @Autowired
    private ObjectProvider<com.chengyinhub.business.service.IRoamHangoutService> roamHangoutServiceProvider;

    @Override
    public Long submit(String bizType, Long bizId, Long submitterId, String sourceType, String snapshotJson) {
        AuditTask task = new AuditTask();
        task.setBizType(bizType);
        task.setBizId(bizId);
        task.setSubmitterId(submitterId);
        task.setSourceType(sourceType);
        task.setRiskLevel("NORMAL");
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setSubmittedAt(DateUtils.getNowDate());
        task.setDeadlineAt(deadlineAt(task.getBizType(), task.getRiskLevel(), task.getSubmittedAt()));
        task.setSnapshotJson(snapshotJson);
        task.setVersion(0);
        task.setCreateTime(DateUtils.getNowDate());
        auditTaskMapper.insertAuditTask(task);
        return task.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitCommercialReview(String bizType, Long topicId, Long submitterId, String snapshotJson) {
        if (!AuditTask.BIZ_TOPIC_PROJECT_REVIEW.equals(bizType)
                && !AuditTask.BIZ_TOPIC_SALE_REVIEW.equals(bizType)) {
            throw new IllegalArgumentException("商业审核 bizType 非法");
        }
        Long open = findOpenTaskId(bizType, topicId);
        if (open != null) {
            if (auditTaskMapper.refreshPendingSnapshot(open, snapshotJson, submitterId, DateUtils.getNowDate()) != 1) {
                throw new IllegalStateException("待审任务快照刷新失败");
            }
            return open;
        }

        AuditTask task = new AuditTask();
        task.setBizType(bizType);
        task.setBizId(topicId);
        task.setSubmitterId(submitterId);
        task.setSourceType("commercial_review");
        task.setRiskLevel("HIGH");
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setSubmittedAt(DateUtils.getNowDate());
        task.setDeadlineAt(deadlineAt(task.getBizType(), task.getRiskLevel(), task.getSubmittedAt()));
        task.setSnapshotJson(snapshotJson);
        task.setVersion(0);
        task.setCreateTime(DateUtils.getNowDate());
        try {
            auditTaskMapper.insertAuditTask(task);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            Long concurrent = findOpenTaskId(bizType, topicId);
            if (concurrent != null) {
                if (auditTaskMapper.refreshPendingSnapshot(concurrent, snapshotJson, submitterId,
                        DateUtils.getNowDate()) != 1) throw new IllegalStateException("待审任务快照刷新失败");
                return concurrent;
            }
            throw dup;
        }

        AuditRecord submitted = new AuditRecord();
        submitted.setTaskId(task.getId());
        submitted.setAction("SUBMIT");
        submitted.setOperatorId(submitterId);
        submitted.setSnapshotJson(snapshotJson);
        submitted.setCreateTime(DateUtils.getNowDate());
        auditRecordMapper.insertAuditRecord(submitted);
        return task.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitOrRefreshTopicBundle(Long topicId, Long submitterId, String snapshotJson) {
        return submitOrRefresh(AuditTask.BIZ_TOPIC, topicId, submitterId, "topic_bundle_v2", snapshotJson);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitOrRefreshTemplate(Long templateId, Long submitterId, String snapshotJson) {
        return submitOrRefresh(AuditTask.BIZ_TEMPLATE, templateId, submitterId, "auto_gate", snapshotJson);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitOrRefreshTemplateLibrary(Long libraryId, Long submitterId, String snapshotJson) {
        return submitOrRefresh(AuditTask.BIZ_TEMPLATE_LIBRARY, libraryId, submitterId,
                "template_market", snapshotJson);
    }

    /** 活动内容类审核来源:同一场活动这几类单互相刷新,不并存(开放任务唯一约束)。 */
    private static final java.util.Set<String> ACTIVITY_CONTENT_SOURCES =
            java.util.Set.of("user_publish", "user_republish", "post_review_flag", "auto_gate");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitOrRefreshActivity(Long activityId, Long submitterId, String sourceType, String snapshotJson) {
        if (!ACTIVITY_CONTENT_SOURCES.contains(sourceType)) {
            throw new IllegalArgumentException("活动审核来源非法");
        }
        return submitOrRefresh(AuditTask.BIZ_ACTIVITY, activityId, submitterId, sourceType, snapshotJson);
    }

    private Long submitOrRefresh(String bizType, Long bizId, Long submitterId,
                                 String sourceType, String snapshotJson) {
        if (bizId == null || bizId <= 0L || submitterId == null || submitterId <= 0L
                || snapshotJson == null || snapshotJson.trim().isEmpty()) {
            throw new IllegalArgumentException("审核参数不完整");
        }
        AuditTask open = auditTaskMapper.selectOpenAuditTaskForUpdate(bizType, bizId);
        if (open != null && SOURCE_APPEAL.equals(open.getSourceType()) && !SOURCE_APPEAL.equals(sourceType)) {
            // AUDIT-INT-6:作者修改重提被驳回的内容 = 放弃申诉,改走新一轮审核。
            withdrawAppeal(open, APPEAL_WITHDRAWN_BY_EDIT);
            open = null;
        }
        if (open != null) {
            requireRefreshableTask(open, sourceType);
            boolean contentChanged = !java.util.Objects.equals(open.getSnapshotJson(), snapshotJson);
            if (auditTaskMapper.refreshOpenAuditSnapshot(open.getId(), bizType, refreshedSource(open, sourceType),
                    snapshotJson, submitterId, DateUtils.getNowDate()) != 1) {
                throw new IllegalStateException("待审任务快照刷新失败");
            }
            if (contentChanged) escalateOpenRisk(open.getId(), null);
            return open.getId();
        }

        AuditTask task = new AuditTask();
        task.setBizType(bizType);
        task.setBizId(bizId);
        task.setSubmitterId(submitterId);
        task.setSourceType(sourceType);
        task.setRiskLevel("NORMAL");
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setSubmittedAt(DateUtils.getNowDate());
        task.setDeadlineAt(deadlineAt(task.getBizType(), task.getRiskLevel(), task.getSubmittedAt()));
        task.setSnapshotJson(snapshotJson);
        task.setVersion(0);
        task.setCreateTime(DateUtils.getNowDate());
        try {
            auditTaskMapper.insertAuditTask(task);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            AuditTask concurrent = auditTaskMapper.selectOpenAuditTaskForUpdate(bizType, bizId);
            if (concurrent == null) throw duplicate;
            requireRefreshableTask(concurrent, sourceType);
            if (auditTaskMapper.refreshOpenAuditSnapshot(concurrent.getId(), bizType, refreshedSource(concurrent, sourceType),
                    snapshotJson, submitterId, DateUtils.getNowDate()) != 1) {
                throw duplicate;
            }
            return concurrent.getId();
        }
        AuditRecord submitted = new AuditRecord();
        submitted.setTaskId(task.getId());
        submitted.setAction("SUBMIT");
        submitted.setOperatorId(submitterId);
        submitted.setSnapshotJson(snapshotJson);
        submitted.setCreateTime(DateUtils.getNowDate());
        if (auditRecordMapper.insertAuditRecord(submitted) != 1) {
            throw new IllegalStateException("审核提交记录保存失败");
        }
        return task.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean lockOpenTopicBundleTask(Long topicId) {
        if (topicId == null || topicId <= 0L) throw new IllegalArgumentException("主题ID不能为空");
        AuditTask open = auditTaskMapper.selectOpenAuditTaskForUpdate(AuditTask.BIZ_TOPIC, topicId);
        if (open != null && SOURCE_APPEAL.equals(open.getSourceType())) {
            // AUDIT-INT-6:编辑被驳回的主题 = 放弃申诉;申诉单不是组合审核,不锁编辑。
            withdrawAppeal(open, APPEAL_WITHDRAWN_BY_EDIT);
            return false;
        }
        // 先发后审的抽检单(post_review_flag)不是组合审核:不锁编辑、不挡简易版;
        // 若之后提交组合审核,由 submitOrRefresh 把这张单原地升级为 topic_bundle_v2。
        if (open != null && (SOURCE_POST_REVIEW_FLAG.equals(open.getSourceType())
                || TopicContentSafetyHold.SOURCE_RELIST.equals(open.getSourceType()))) return false;
        if (open != null) requireRefreshableTask(open, "topic_bundle_v2");
        return open != null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean withdrawOpenAppeal(String bizType, Long bizId, String reasonText) {
        if (bizType == null || bizId == null) return false;
        AuditTask open = auditTaskMapper.selectOpenAuditTaskForUpdate(bizType, bizId);
        if (open == null || !SOURCE_APPEAL.equals(open.getSourceType())) return false;
        withdrawAppeal(open, reasonText);
        return true;
    }

    /** 作者修改被申诉内容时给作者的说明(系统消息)。 */
    static final String APPEAL_WITHDRAWN_BY_EDIT = "你修改了被驳回的内容，原申诉已自动撤回，修改后的内容将重新审核";

    /**
     * 撤回一张未结申诉单:任务 CAS 撤销(status=4) + 申诉记录 WITHDRAWN + CANCEL 时间线 + 通知作者。
     * 与调用方同事务;任务已被并发处理则 409,不静默吞掉。
     */
    private void withdrawAppeal(AuditTask appealTask, String reasonText) {
        if (auditTaskMapper.cancelOpenAuditTaskCas(appealTask.getId(), appealTask.getVersion()) != 1) {
            throw governanceError(ERROR_APPEAL_STALE, "申诉状态已变化，请刷新后重试", HttpStatus.CONFLICT);
        }
        Date now = DateUtils.getNowDate();
        auditAppealMapper.withdrawByTask(appealTask.getId(), now);
        AuditRecord cancelled = new AuditRecord();
        cancelled.setTaskId(appealTask.getId());
        cancelled.setAction("CANCEL");
        cancelled.setOperatorId(appealTask.getSubmitterId());
        cancelled.setReasonCode("APPEAL_WITHDRAWN");
        cancelled.setReasonText(reasonText);
        cancelled.setSnapshotJson(appealTask.getSnapshotJson());
        cancelled.setCreateTime(now);
        if (auditRecordMapper.insertAuditRecord(cancelled) != 1) {
            throw new ServiceException("申诉撤回记录保存失败", HttpStatus.ERROR);
        }
        Long author = appealTask.getSubmitterId();
        if (author == null || author <= 0L) return;
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("taskId", appealTask.getId());
        result.put("bizType", appealTask.getBizType());
        result.put("bizId", appealTask.getBizId());
        result.put("outcome", "申诉已撤回");
        result.put("reason", reasonText);
        java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
        card.put("title", "申诉已撤回");
        card.put("sub", reasonText);
        card.put("result", result);
        try {
            imServiceProvider.getObject().sendSystem(author, ImMessage.MSG_CARD, "申诉已撤回",
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(card));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException("申诉撤回通知序列化失败");
        }
    }

    /** 先发后审打标的非阻断抽检单来源(活动 / 主题共用) */
    private static final String SOURCE_POST_REVIEW_FLAG = "post_review_flag";

    private void requireRefreshableTask(AuditTask task, String sourceType) {
        boolean legacyTopicTask = "topic_bundle_v2".equals(sourceType)
                && ("user_publish".equals(task.getSourceType()) || "user_republish".equals(task.getSourceType())
                        || SOURCE_POST_REVIEW_FLAG.equals(task.getSourceType())
                        || TopicContentSafetyHold.SOURCE_RELIST.equals(task.getSourceType()));
        boolean activityContentTask = AuditTask.BIZ_ACTIVITY.equals(task.getBizType())
                && ACTIVITY_CONTENT_SOURCES.contains(sourceType)
                && ACTIVITY_CONTENT_SOURCES.contains(task.getSourceType());
        if (!sourceType.equals(task.getSourceType()) && !legacyTopicTask && !activityContentTask) {
            throw new IllegalStateException("存在其他开放审核任务，请先处理后再提交");
        }
    }

    /** 抽检打标只刷新快照,不把已开着的先审/重提阻断单降级成非阻断的抽检单。 */
    private static String refreshedSource(AuditTask open, String sourceType) {
        if ("post_review_flag".equals(sourceType) && AuditTask.BIZ_ACTIVITY.equals(open.getBizType())
                && open.getSourceType() != null && !"post_review_flag".equals(open.getSourceType())) {
            return open.getSourceType();
        }
        return sourceType;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitSafetyHold(String bizType, Long bizId, Long submitterId, String riskLevel, String snapshotJson) {
        Long taskId = findOrCreateOpenTask(bizType, bizId, submitterId, SOURCE_AUTO_GATE, riskLevel, snapshotJson);
        if (AuditTask.BIZ_COMMUNITY_POST.equals(bizType) && "EDIT_REVIEW".equals(riskLevel)) {
            AuditTask task = auditTaskMapper.selectAuditTaskByIdForUpdate(taskId);
            if (task == null || !bizType.equals(task.getBizType()) || !bizId.equals(task.getBizId())) {
                throw new ServiceException("推文审核任务已变化，请重试", HttpStatus.CONFLICT);
            }
            boolean contentChanged = !java.util.Objects.equals(snapshotJson, task.getSnapshotJson());
            if (contentChanged
                    && auditTaskMapper.refreshOpenAuditSnapshot(taskId, bizType, task.getSourceType(),
                            snapshotJson, task.getSubmitterId() == null ? submitterId : task.getSubmitterId(),
                            DateUtils.getNowDate()) != 1) {
                throw new ServiceException("推文审核快照刷新失败，请重试", HttpStatus.CONFLICT);
            }
            if (contentChanged) escalateOpenRisk(taskId, null);
        }
        return taskId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long reportContent(String bizType, Long bizId, Long reporterId, String reasonText) {
        String snapshot = buildReportSnapshot(bizType, bizId, reasonText);
        // 幂等:同一内容的多次举报汇聚到同一张未结举报单(独立开放槽,不并进内容审核单),避免刷单顶爆工作台;
        // 但每次举报仍 append 一条 AuditRecord(action=REPORT)留痕 → 举报数=REPORT 记录数、处置记录=记录历史,免迁移。
        Long taskId = findOrCreateOpenTask(bizType, bizId, reporterId, SOURCE_REPORT, "NORMAL", snapshot);
        if (AuditTask.BIZ_HANGOUT.equals(bizType) || AuditTask.BIZ_TEAM.equals(bizType)) {
            AuditTask locked = auditTaskMapper.selectAuditTaskByIdForUpdate(taskId);
            if (locked == null || (!AuditTask.STATUS_PENDING.equals(locked.getStatus())
                    && !AuditTask.STATUS_ESCALATED.equals(locked.getStatus()))) {
                throw new ServiceException("该轮举报已处理，请重新提交举报");
            }
        }
        inheritReportFirstResponseDeadline(taskId, reasonText);
        AuditRecord record = new AuditRecord();
        record.setTaskId(taskId);
        record.setAction("REPORT");
        record.setOperatorId(reporterId);
        record.setReasonText(reasonText);
        record.setSnapshotJson(snapshot);
        record.setCreateTime(DateUtils.getNowDate());
        int recorded = auditRecordMapper.insertAuditRecord(record);
        if ((AuditTask.BIZ_HANGOUT.equals(bizType) || AuditTask.BIZ_TEAM.equals(bizType)) && recorded != 1) {
            throw new ServiceException("举报记录保存失败，请重试");
        }
        escalateOpenRisk(taskId, null);
        return taskId;
    }

    /**
     * O5b-3:举报原因命中社区举报政策(编码或标签)时,处置它的那张审核单 deadline 不晚于
     * 「举报时刻 + 该原因首响时限」—— 不论是新建的举报单还是汇入的已开着的单。只收紧不放宽;
     * 升级(ESCALATED)不改 deadline_at,时钟随单走。未命中政策的自由文本原因不动。
     */
    private void inheritReportFirstResponseDeadline(Long taskId, String reasonText) {
        if (!reportSlaInheritEnabled || taskId == null) return;
        com.chengyinhub.business.service.support.CommunityReportPolicy.Reason reason = reportPolicyReason(reasonText);
        if (reason == null) return;
        Date reportedAt = DateUtils.getNowDate();
        Date firstResponseDue = new Date(reportedAt.getTime() + reason.getFirstResponseMinutes() * 60_000L);
        auditTaskMapper.tightenOpenDeadline(taskId, firstResponseDue);
    }

    /** 举报原因 → 社区举报政策原因:先按编码(大小写不敏感),再按中文标签全等。 */
    static com.chengyinhub.business.service.support.CommunityReportPolicy.Reason reportPolicyReason(String reasonText) {
        if (reasonText == null || reasonText.trim().isEmpty()) return null;
        String text = reasonText.trim();
        com.chengyinhub.business.service.support.CommunityReportPolicy.Reason byCode =
                com.chengyinhub.business.service.support.CommunityReportPolicy.find(text.toUpperCase(java.util.Locale.ROOT));
        if (byCode != null) return byCode;
        for (String code : com.chengyinhub.business.service.support.CommunityReportPolicy.orderedCodes()) {
            com.chengyinhub.business.service.support.CommunityReportPolicy.Reason reason =
                    com.chengyinhub.business.service.support.CommunityReportPolicy.find(code);
            if (reason != null && text.equals(reason.getLabel())) return reason;
        }
        return null;
    }

    /** 用户端举报原因长度上限(与广场举报同口径) */
    private static final int MAX_REPORT_REASON_LENGTH = 64;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean reportContentOnce(String bizType, Long bizId, Long reporterId, String reasonText) {
        String reason = reasonText == null ? "" : reasonText.trim();
        if (reason.isEmpty() || reason.length() > MAX_REPORT_REASON_LENGTH) {
            throw new IllegalArgumentException("请选择举报原因");
        }
        Long open = findOpenTaskId(bizType, bizId, true);
        if (open != null && reporterId != null) {
            AuditRecord query = new AuditRecord();
            query.setTaskId(open);
            query.setAction("REPORT");
            List<AuditRecord> reported = auditRecordMapper.selectAuditRecordList(query);
            if (reported != null) for (AuditRecord record : reported) {
                if (reporterId.equals(record.getOperatorId())) return false;
            }
        }
        reportContent(bizType, bizId, reporterId, reason);
        return true;
    }

    @Override
    public List<AuditRecord> listAuditRecords(Long taskId) {
        AuditRecord q = new AuditRecord();
        q.setTaskId(taskId);
        return auditRecordMapper.selectAuditRecordList(q);
    }

    /**
     * 幂等取或建未结审核单:同 bizType+bizId 有 PENDING/ESCALATED 单则复用其 id,否则新建。
     * 并发双插由 uk_audit_biz_open(biz_type,biz_id,open_guard) 拦下后复用已存在单,不抛错。
     */
    private Long findOrCreateOpenTask(String bizType, Long bizId, Long submitterId,
                                      String sourceType, String riskLevel, String snapshotJson) {
        boolean reportLane = SOURCE_REPORT.equals(sourceType);
        Long open = findOpenTaskId(bizType, bizId, reportLane);
        if (open != null) {
            // 门禁再次进件(如检测异常给 HIGH)不能被已开着的低风险单吞掉;举报单在 reportContent 记录落地后再重算。
            if (!reportLane) escalateOpenRisk(open, riskLevel);
            return open;
        }
        // 幂等命中(上面复用已有单)先于限频:重复举报同一目标不计入、不被限;
        // 仅"新目标举报"(source=report)走 submitterId 限频,auto_gate/publish 不受影响。
        if (SOURCE_REPORT.equals(sourceType)) {
            enforceReportRateLimit(submitterId);
        }
        AuditTask task = new AuditTask();
        task.setBizType(bizType);
        task.setBizId(bizId);
        task.setSubmitterId(submitterId);
        task.setSourceType(sourceType);
        task.setRiskLevel(riskLevel != null ? riskLevel : "NORMAL");
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setSubmittedAt(DateUtils.getNowDate());
        task.setDeadlineAt(deadlineAt(task.getBizType(), task.getRiskLevel(), task.getSubmittedAt()));
        task.setSnapshotJson(snapshotJson);
        task.setVersion(0);
        task.setCreateTime(DateUtils.getNowDate());
        try {
            auditTaskMapper.insertAuditTask(task);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            Long again = findOpenTaskId(bizType, bizId, reportLane);
            if (again != null) {
                return again;
            }
            throw dup;
        }
        return task.getId();
    }

    /**
     * O-05 #2:按内容重算开放单风险 —— 本次进件风险与「该内容开放单上的去重举报人数达阈值 → HIGH」取高,
     * 只在严格高于当前档时写回(CAS 旧值,失败即让位并发写,不抛错)。开关关闭或单已结束时什么都不做。
     */
    private void escalateOpenRisk(Long taskId, String candidateRisk) {
        if (!riskEscalationEnabled || taskId == null) return;
        AuditTask task = auditTaskMapper.selectAuditTaskById(taskId);
        if (task == null || !(AuditTask.STATUS_PENDING.equals(task.getStatus())
                || AuditTask.STATUS_ESCALATED.equals(task.getStatus()))) {
            return;
        }
        int reporters = auditTaskMapper.countOpenReportersByBiz(task.getBizType(), task.getBizId());
        String target = com.chengyinhub.business.service.support.AuditRiskLevels.recompute(
                task.getRiskLevel(), candidateRisk, reporters, riskEscalationReportThreshold);
        if (java.util.Objects.equals(target, task.getRiskLevel())) return;
        if (auditTaskMapper.escalateOpenRiskLevel(taskId, task.getRiskLevel(), target) == 1) {
            logger.info("审核单风险升级 taskId={} bizType={} bizId={} {} -> {} reporters={}",
                    taskId, task.getBizType(), task.getBizId(), task.getRiskLevel(), target, reporters);
        }
    }

    /**
     * submitterId 举报限频:同一提交人在时间窗内新建的举报单达到阈值即拒。
     * 只在"新目标举报"分支调用(幂等命中已短路,不计入不拦截);无归属提交人不限。
     */
    private void enforceReportRateLimit(Long submitterId) {
        if (submitterId == null || submitterId <= 0) {
            return;
        }
        Date since = new Date(System.currentTimeMillis() - REPORT_RATE_LIMIT_WINDOW_SECONDS * 1000L);
        int recent = auditTaskMapper.countRecentReportsBySubmitter(submitterId, since);
        if (recent >= REPORT_RATE_LIMIT_MAX) {
            throw new ServiceException("举报过于频繁，请稍后再试");
        }
    }

    /** 返回同 bizType+bizId 内容审核槽的未结(PENDING/ESCALATED)审核单 id,无则 null;举报单不算。 */
    private Long findOpenTaskId(String bizType, Long bizId) {
        return findOpenTaskId(bizType, bizId, false);
    }

    /**
     * AUDIT-INT-5:主题/玩法/模板广场/活动的举报单与内容审核单各占一个开放槽(open_guard 2 / 1),
     * 互不复用、互不吸收。reportLane=true 只找未结举报单,false 只找内容审核槽的未结单。
     * 其余类型(帖子/评论/组局等)仍一个槽,举报照旧汇入已开着的单。
     */
    private Long findOpenTaskId(String bizType, Long bizId, boolean reportLane) {
        boolean split = AuditTask.REPORT_LANE_BIZ_TYPES.contains(bizType);
        AuditTask q = new AuditTask();
        q.setBizType(bizType);
        q.setBizId(bizId);
        for (AuditTask existing : auditTaskMapper.selectAuditTaskList(q)) {
            if ((AuditTask.STATUS_PENDING.equals(existing.getStatus())
                    || AuditTask.STATUS_ESCALATED.equals(existing.getStatus()))
                    && (!split || SOURCE_REPORT.equals(existing.getSourceType()) == reportLane)) {
                return existing.getId();
            }
        }
        return null;
    }

    /** 举报快照:用 Jackson 序列化,避免手工拼 JSON 遭 reason 特殊字符(反斜杠/控制符)破坏。 */
    private String buildReportSnapshot(String bizType, Long bizId, String reasonText) {
        java.util.Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("bizType", bizType);
        snap.put("bizId", bizId);
        snap.put("reason", reasonText == null ? "" : reasonText);
        if (AuditTask.BIZ_HANGOUT.equals(bizType)) {
            java.util.Map<String, Object> content = new java.util.LinkedHashMap<>();
            com.chengyinhub.business.domain.RoamHangout hangout = roamHangoutMapper.selectById(bizId, null);
            content.put("available", hangout != null);
            content.put("evidenceStatus", hangout == null ? "MISSING" : "CAPTURED");
            content.put("investigation", "查看同组局审核历史及每次举报快照；缺少材料时升级审核，通过客服按任务编号补充调查。");
            if (hangout == null) content.put("missingReason", "举报时原组局内容不可用，尚未取得原始内容证据，不能据此认定无违规。");
            if (hangout != null) {
                content.put("memberId", hangout.getMemberId());
                content.put("ownerNickname", hangout.getOwnerNickname());
                content.put("ownerRole", hangout.getOwnerRole());
                content.put("coverUrl", hangout.getCoverUrl());
                content.put("conversationId", hangout.getConversationId());
                content.put("title", hangout.getTitle());
                content.put("description", hangout.getDescription());
                content.put("addressName", hangout.getAddressName());
                content.put("startAt", hangout.getStartAt());
                content.put("status", hangout.getStatus());
            }
            snap.put("content", content);
        }
        if (AuditTask.BIZ_TOPIC.equals(bizType) || AuditTask.BIZ_TEMPLATE.equals(bizType)
                || AuditTask.BIZ_TEMPLATE_LIBRARY.equals(bizType)) {
            // 举报时的公开面留证:作者事后改名换图,审核员仍能看到被举报的那一版。
            java.util.Map<String, Object> content = new java.util.LinkedHashMap<>();
            Object[] row = null;
            if (AuditTask.BIZ_TOPIC.equals(bizType)) {
                CmsTopic topic = cmsTopicMapper == null ? null : cmsTopicMapper.selectCmsTopicById(bizId);
                if (topic != null) row = new Object[] {topic.getMemberId(), topic.getName(), topic.getImgUrl()};
            } else if (AuditTask.BIZ_TEMPLATE.equals(bizType)) {
                CmsMemberTemplate template = templateMapper == null ? null : templateMapper.selectCmsMemberTemplateById(bizId);
                if (template != null) row = new Object[] {template.getMemberId(), template.getTitle(), template.getImgUrl()};
            } else {
                CmsTemplateLibrary library = libraryMapper == null ? null : libraryMapper.selectCmsTemplateLibraryById(bizId);
                if (library != null) row = new Object[] {library.getMemberId(), library.getTitle(), library.getImgUrl()};
            }
            content.put("available", row != null);
            if (row != null) {
                content.put("memberId", row[0]);
                content.put("title", row[1]);
                content.put("imgUrl", row[2]);
            }
            snap.put("content", content);
        }
        if (AuditTask.BIZ_TEAM.equals(bizType)) {
            // 只取审核需要的公开信息;邀请码是入队凭证,绝不进快照。
            java.util.Map<String, Object> content = new java.util.LinkedHashMap<>();
            com.chengyinhub.business.domain.PlayTeam team = playTeamMapper.selectPlayTeamById(bizId);
            content.put("available", team != null);
            content.put("evidenceStatus", team == null ? "MISSING" : "CAPTURED");
            if (team != null) {
                content.put("title", team.getTitle());
                content.put("leaderMemberId", team.getLeaderMemberId());
                content.put("ownerType", team.getOwnerType());
                content.put("ownerId", team.getOwnerId());
                content.put("joinMode", team.getJoinMode());
                content.put("status", team.getStatus());
                content.put("joinedCount", team.getJoinedCount());
                content.put("maxMembers", team.getMaxMembers());
            }
            snap.put("content", content);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(snap);
        } catch (Exception e) {
            throw new ServiceException("举报证据保存失败，请重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CriticalPath(value = CriticalPathCode.CP_AUDIT_DECISION_FAILED, op = "review")
    public int review(Long taskId, Long operatorId, AuditReviewDTO request) {
        if (taskId == null || request == null || request.getStatus() == null) {
            throw new IllegalArgumentException("审核参数不完整");
        }
        if (AuditTask.STATUS_REJECTED.equals(request.getStatus())) {
            request.setReasonText(requireReason(request.getReasonText(), REJECT_REASON_REQUIRED));
        } else if (request.getReasonText() != null) {
            request.setReasonText(trimReason(request.getReasonText()));
        }
        // 固定清单原因编码：清单外一律 400(IllegalArgumentException)，不落任何状态。
        request.setReasonCodes(com.chengyinhub.business.service.support.AuditReasonCodes.normalize(request.getReasonCodes()));
        AuditTask task = auditTaskMapper.selectAuditTaskById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在");
        }
        boolean guardReview = isGuardType(task.getBizType());
        if (guardReview) {
            requireGuardReviewScope(task, operatorId);
            if (!SOURCE_APPEAL.equals(task.getSourceType()) || request.getExpectedVersion() == null
                    || !(AuditTask.STATUS_APPROVED.equals(request.getStatus()) || AuditTask.STATUS_REJECTED.equals(request.getStatus()))) {
                throw new IllegalArgumentException("守卫申诉仅支持带原版本的成立或驳回裁决");
            }
            request.setRequestId(appealRequestId(request.getRequestId()));
            if (request.getProblems() != null && !request.getProblems().isEmpty()) {
                throw new IllegalArgumentException("守卫申诉不接受内容逐项问题");
            }
            // Read immutable decisions before checking the live run. An ended run must not hide a committed receipt.
            BusinessAuditEvent original = guardDecisionEvent(taskId, request.getRequestId());
            if (original != null) {
                requireExactReviewReplay(original, operatorId, request.getExpectedVersion(), request);
                return 1;
            }
            // Runtime lock precedes appeal/task locks. No engine mutation is performed here.
            requireActiveGuardTask(task);
        }
        if (AuditTask.BIZ_TOPIC.equals(task.getBizType()) || AuditTask.BIZ_TOPIC_PROJECT_REVIEW.equals(task.getBizType())
                || AuditTask.BIZ_TOPIC_SALE_REVIEW.equals(task.getBizType())) {
            auditTaskMapper.selectTopicPublicationTasksForUpdate(task.getBizId());
        }
        if(AuditTask.BIZ_MEDIA_ASSET.equals(task.getBizType())) {
            appMediaAssetServiceProvider.getObject().lockForAudit(task);
            AuditTask locked=auditTaskMapper.selectAuditTaskByIdForUpdate(taskId);
            if(locked==null || !AuditTask.BIZ_MEDIA_ASSET.equals(locked.getBizType()) || !task.getBizId().equals(locked.getBizId()))
                throw new ServiceException("媒体审核单已变化");
            task=locked;
        }
        boolean communityReview = AuditTask.BIZ_COMMUNITY_POST.equals(task.getBizType());
        if (communityReview) {
            // 与作者编辑共用先推文、后审核任务的锁顺序。
            if (communityPostMapper.selectByIdForUpdate(task.getBizId()) == null) {
                throw new ServiceException("推文不存在", HttpStatus.CONFLICT);
            }
            AuditTask locked = auditTaskMapper.selectAuditTaskByIdForUpdate(taskId);
            if (locked == null || !task.getBizType().equals(locked.getBizType())
                    || !task.getBizId().equals(locked.getBizId())) {
                throw new ServiceException("推文审核任务已变化，请刷新后重试", HttpStatus.CONFLICT);
            }
            task = locked;
        }
        TemplateReviewLock templateLock = isTemplateTask(task) ? lockTemplateReview(task) : null;
        if (templateLock != null) task = templateLock.task;
        com.chengyinhub.business.domain.AuditAppeal appeal = SOURCE_APPEAL.equals(task.getSourceType())
                ? lockAppeal(task) : null;
        boolean appealAccepted = appeal != null && AuditTask.STATUS_APPROVED.equals(request.getStatus());
        boolean versionedReview = guardReview || templateLock != null || isTopicBundle(task) || communityReview || AuditTask.BIZ_MEDIA_ASSET.equals(task.getBizType());
        if (versionedReview && request.getExpectedVersion() == null) {
            throw new ServiceException("请刷新审核内容后重试", HttpStatus.CONFLICT);
        }
        BusinessAuditEventMapper auditMapper = requireBusinessAuditMapper();
        int decisionVersion = versionedReview ? request.getExpectedVersion()
                : (task.getStatus().equals(request.getStatus()) ? task.getVersion() - 1 : task.getVersion());
        String auditAction = "AUDIT_DECISION_" + statusToAction(request.getStatus());
        String requestId = reviewRequestId(request.getRequestId(), taskId, decisionVersion, request.getStatus());
        request.setRequestId(requestId);
        BusinessAuditEvent replay = auditMapper.selectByDomainActionRequestForUpdate(
                "PLATFORM", taskId, auditAction, requestId);
        if (replay != null) {
            requireExactReviewReplay(replay, operatorId, decisionVersion, request);
            return 1;
        }
        if (!canTransition(task.getStatus(), request.getStatus())) {
            throw new IllegalArgumentException("当前审核状态不允许该操作");
        }
        if (versionedReview && !request.getExpectedVersion().equals(task.getVersion())) {
            throw new ServiceException("审核内容已变化，请刷新后重试", HttpStatus.CONFLICT);
        }
        // #334 F10:非版本化类型也不允许升级前那一级的迟到决定裁决已升级(ESCALATED)的单 ——
        // 裁决升级单必须带上看到的版本,且须是升级之后的版本(升级 CAS 会把 version+1)。
        if (!versionedReview && AuditTask.STATUS_ESCALATED.equals(task.getStatus())
                && !task.getVersion().equals(request.getExpectedVersion())) {
            throw governanceError(ERROR_REVIEW_STALE_LEVEL, "该审核单已升级处理，请刷新后按升级后的内容审核",
                    HttpStatus.CONFLICT);
        }
        if (appeal != null && !com.chengyinhub.business.domain.AuditAppeal.STATE_SUBMITTED.equals(appeal.getState())
                && !com.chengyinhub.business.domain.AuditAppeal.STATE_REVIEWING.equals(appeal.getState())) {
            throw governanceError(ERROR_APPEAL_STALE, "申诉已处理，请刷新后重试", HttpStatus.CONFLICT);
        }
        // AUDIT-INT-4:申诉的是模板/广场举报下架(快照带 preTakedown)= 按下架前记录的公开态原样恢复,
        // 不走「回待审 + 通过」(那会把原本未上架的也上架,且举报快照与模板快照比对恒不等)。
        com.fasterxml.jackson.databind.JsonNode reportTakedown = appealAccepted && templateLock != null
                ? preTakedownState(task.getSnapshotJson()) : null;
        if (appealAccepted && !guardReview) {
            requireNoContentSafetyHold(task.getBizType(), task.getBizId(),
                    "内容图片未通过内容安全检测，申诉不能恢复，请作者更换图片后重新提交审核");
            if (reportTakedown != null) {
                if (!restoreReportTakedown(task, templateLock, reportTakedown)) {
                    throw governanceError(ERROR_APPEAL_STALE, "内容已变化，申诉无法按原内容恢复，请重新提交审核",
                            HttpStatus.CONFLICT);
                }
            } else {
                // 申诉成立：先把内容从驳回/下架态 CAS 回待审，再走下面既有的通过路径恢复；内容已变化则整单回滚。
                if (!reopenContent(task, AuditTask.STATUS_REJECTED, null)) {
                    throw governanceError(ERROR_APPEAL_STALE, "内容已变化，申诉无法按原内容恢复，请重新提交审核",
                            HttpStatus.CONFLICT);
                }
                if (templateLock != null) templateLock = lockTemplateReview(task);
            }
        }
        // 申诉驳回/升级不改内容(维持原处理)，此时内容不在待审态，不做待审快照比对。
        // 举报单裁决的是「已公开内容是否违规」,不是作者提交的待审版本:不走待审快照/配置复核。
        if (templateLock != null && !SOURCE_REPORT.equals(task.getSourceType()) && reportTakedown == null
                && (appeal == null || appealAccepted)) {
            assertTemplateReviewCurrent(templateLock, request);
        }
        // O-05 #4:逐项问题先整单校验(非法即 400,不落任何状态),驳回记录写入后再落库。
        if (guardReview && request.getProblems() != null && !request.getProblems().isEmpty()) throw new IllegalArgumentException("守卫申诉不接受内容逐项问题");
        List<com.chengyinhub.business.domain.AuditProblem> problems = itemProblems(task, appeal, request);
        Date now = DateUtils.getNowDate();
        int rows = auditTaskMapper.updateAuditTaskReviewCas(taskId, task.getStatus(), request.getStatus(),
                operatorId, task.getVersion(), now);
        if (rows <= 0) {
            replay = auditMapper.selectByDomainActionRequestForUpdate("PLATFORM", taskId, auditAction, requestId);
            if (replay != null) {
                requireExactReviewReplay(replay, operatorId, decisionVersion, request);
                return 1;
            }
            return 0;
        }
        if (isTopicBundle(task)) {
            AuditTask locked = auditTaskMapper.selectAuditTaskByIdForUpdate(taskId);
            if (locked == null || !request.getStatus().equals(locked.getStatus())
                    || locked.getVersion() == null || locked.getVersion() != task.getVersion() + 1
                    || !java.util.Objects.equals(task.getBizId(), locked.getBizId())
                    || !java.util.Objects.equals(task.getSubmitterId(), locked.getSubmitterId())
                    || !java.util.Objects.equals(task.getSnapshotJson(), locked.getSnapshotJson())) {
                throw new ServiceException("主题审核任务已变化，请刷新后重试");
            }
        }

        AuditRecord record = new AuditRecord();
        record.setTaskId(taskId);
        record.setAction(statusToAction(request.getStatus()));
        record.setOperatorId(operatorId);
        record.setReasonCode(request.getReasonCode());
        record.setReasonText(request.getReasonText());
        record.setReasonCodesJson(com.chengyinhub.business.service.support.AuditReasonCodes.toJson(request.getReasonCodes()));
        record.setSnapshotJson(task.getSnapshotJson());
        record.setCreateTime(now);
        if (auditRecordMapper.insertAuditRecord(record) != 1) {
            throw new ServiceException("审批时间线写入失败", HttpStatus.ERROR);
        }
        if (!problems.isEmpty()) {
            ContentState owner = contentState(task.getBizType(), task.getBizId());
            auditProblemService.record(problems, record.getId(), owner == null ? null : owner.ownerId, now);
        }

        if (!guardReview) applyCommercialReviewLifecycle(task, request.getStatus());

        // 审核决定 → 内容软封/恢复(REJECT=确认违规下架,APPROVE=举报无效恢复)
        notifyReviewResult(task, request);
        // 申诉驳回 = 维持原驳回/下架，内容不动；申诉成立复用既有通过路径恢复。
        if (!guardReview && (appeal == null || (appealAccepted && reportTakedown == null))) {
            applyModeration(task, request.getStatus(), request.getReasonText());
        }
        if (appeal != null && isTerminalDecision(request.getStatus())
                && auditAppealMapper.finishAppeal(appeal.getId(), appealAccepted
                        ? com.chengyinhub.business.domain.AuditAppeal.STATE_ACCEPTED
                        : com.chengyinhub.business.domain.AuditAppeal.STATE_REJECTED, operatorId, now) != 1) {
            throw governanceError(ERROR_APPEAL_STALE, "申诉状态已变化，请刷新后重试", HttpStatus.CONFLICT);
        }
        appendDecisionAudit(task, operatorId, request, record, now);
        return rows;
    }

    /** 开关关闭 / 非驳回 / 未附逐项问题时返回空表(请求里的 problems 被忽略,与旧版一致)。 */
    private List<com.chengyinhub.business.domain.AuditProblem> itemProblems(
            AuditTask task, com.chengyinhub.business.domain.AuditAppeal appeal, AuditReviewDTO request) {
        if (!itemProblemsEnabled || auditProblemService == null
                || request.getProblems() == null || request.getProblems().isEmpty()) {
            return Collections.emptyList();
        }
        if (!AuditTask.STATUS_REJECTED.equals(request.getStatus()) || appeal != null) {
            throw new IllegalArgumentException("仅内容驳回可附逐项问题");
        }
        return auditProblemService.prepare(task, request.getProblems());
    }

    private boolean isTemplateTask(AuditTask task) {
        return AuditTask.BIZ_TEMPLATE.equals(task.getBizType()) || AuditTask.BIZ_TEMPLATE_LIBRARY.equals(task.getBizType());
    }

    /** 与编辑/撤回共用锁顺序，不能先锁审核任务再等待模板。 */
    private TemplateReviewLock lockTemplateReview(AuditTask task) {
        Long templateId = task.getBizId();
        if (AuditTask.BIZ_TEMPLATE_LIBRARY.equals(task.getBizType())) {
            CmsTemplateLibrary hint = libraryMapper.selectCmsTemplateLibraryById(task.getBizId());
            if (hint == null || hint.getMemberTemplateId() == null) throw new ServiceException("模板广场记录不存在");
            templateId = hint.getMemberTemplateId();
        }
        TemplateReviewLock locked = new TemplateReviewLock();
        java.util.List<CmsMemberTemplate> templates = templateMapper.selectCmsMemberTemplateByIdsForUpdate(
                java.util.Collections.singletonList(templateId));
        locked.template = templates == null || templates.isEmpty() ? null : templates.get(0);
        locked.projections = libraryMapper.selectByMemberTemplateForUpdate(templateId);
        locked.task = auditTaskMapper.selectAuditTaskByIdForUpdate(task.getId());
        if (locked.task == null || !java.util.Objects.equals(task.getBizType(), locked.task.getBizType())
                || !java.util.Objects.equals(task.getBizId(), locked.task.getBizId())) {
            throw new ServiceException("审核任务已变化，请刷新后重试", HttpStatus.CONFLICT);
        }
        return locked;
    }

    private void assertTemplateReviewCurrent(TemplateReviewLock locked, AuditReviewDTO request) {
        AuditTask task = locked.task;
        CmsMemberTemplate template = locked.template;
        if (!request.getExpectedVersion().equals(task.getVersion()) || template == null
                || !Integer.valueOf(0).equals(template.getDelFlag())
                || !Integer.valueOf(1).equals(template.getDraftStatus())
                || !java.util.Objects.equals(template.getMemberId(), task.getSubmitterId())) {
            throw new ServiceException("提交内容已变化，请重新提交审核", HttpStatus.CONFLICT);
        }
        String current = null;
        if (AuditTask.BIZ_TEMPLATE.equals(task.getBizType())) {
            if (!Long.valueOf(0L).equals(template.getStatus())) throw new ServiceException("模板已不在待审状态");
            current = TemplateAuditSnapshot.personal(template);
        } else {
            if (!Long.valueOf(1L).equals(template.getStatus()) || !Integer.valueOf(1).equals(template.getCheckStatus())) {
                throw new ServiceException("个人玩法审核状态已变化");
            }
            for (CmsTemplateLibrary row : locked.projections) {
                if (task.getBizId().equals(row.getId()) && template.getId().equals(row.getMemberTemplateId())
                        && template.getMemberId().equals(row.getMemberId()) && Integer.valueOf(0).equals(row.getDelFlag())
                        && Long.valueOf(0L).equals(row.getStatus()) && Long.valueOf(0L).equals(row.getPublishStatus())) {
                    current = TemplateAuditSnapshot.market(row);
                }
            }
        }
        if (current == null || !TemplateAuditSnapshot.matches(task.getSnapshotJson(), current)) {
            throw new ServiceException("提交内容已变化，请重新提交审核", HttpStatus.CONFLICT);
        }
        if (AuditTask.STATUS_APPROVED.equals(request.getStatus())) {
            requireTemplateConfigStillValid(task, template, locked.projections);
        }
    }

    /**
     * AUDIT-G2-6:过审前对落库配置重跑 schema 校验器。快照一致只说明「内容没被改」,
     * 不说明它在今天的校验规则下仍可玩(规则升级/审核期间旁路写入都会让它失效);
     * 失效就 409 打回,作者修正后重新提交。驳回不受影响。
     */
    private void requireTemplateConfigStillValid(AuditTask task, CmsMemberTemplate template,
                                                 java.util.List<CmsTemplateLibrary> projections) {
        try {
            if (AuditTask.BIZ_TEMPLATE.equals(task.getBizType())) {
                com.chengyinhub.business.service.support.TemplateConfigRevalidation.requireValid(
                        template.getValidationMethod(), template.getSensorType(), template.getSensorConfig(),
                        template.getAdvancedConfigJson());
                return;
            }
            for (CmsTemplateLibrary row : projections) {
                if (task.getBizId().equals(row.getId())) {
                    com.chengyinhub.business.service.support.TemplateConfigRevalidation.requireValid(
                            row.getValidationMethod(), row.getSensorType(), row.getSensorConfig(),
                            row.getAdvancedConfigJson());
                }
            }
        } catch (IllegalArgumentException invalid) {
            throw new ServiceException("模板配置校验未通过：" + invalid.getMessage()
                    + "，请作者修改后重新提交审核", HttpStatus.CONFLICT);
        }
    }

    private static final class TemplateReviewLock {
        private CmsMemberTemplate template;
        private java.util.List<CmsTemplateLibrary> projections;
        private AuditTask task;
    }

    /** 系统消息就是同库持久通知；与决定、处罚同事务，失败回滚，原 requestId 可安全重试。 */
    private void notifyReviewResult(AuditTask task, AuditReviewDTO request) {
        if (!isTerminalDecision(request.getStatus())) return;
        boolean upheld = AuditTask.STATUS_REJECTED.equals(request.getStatus());
        Set<Long> reporters = new java.util.LinkedHashSet<>();
        // 审核 CAS 持有任务锁；当前读包含在该锁之前已提交的合并举报，避免 RR 旧快照漏通知。
        List<AuditRecord> reports = AuditTask.BIZ_HANGOUT.equals(task.getBizType())
                ? auditRecordMapper.selectReportsForUpdate(task.getId()) : listAuditRecords(task.getId());
        for (AuditRecord record : reports) {
            if ("REPORT".equals(record.getAction()) && record.getOperatorId() != null) {
                reporters.add(record.getOperatorId());
            }
        }
        boolean report = SOURCE_REPORT.equals(task.getSourceType()) || !reporters.isEmpty();
        if (!report && (AuditTask.BIZ_TOPIC_PROJECT_REVIEW.equals(task.getBizType())
                || AuditTask.BIZ_TOPIC_SALE_REVIEW.equals(task.getBizType()))) {
            // 商业审核也可由后台代提交，后台 userId 不能当作小程序 memberId 通知。
            CmsTopic topic = cmsTopicMapper.selectCmsTopicById(task.getBizId());
            if (topic == null || topic.getMemberId() == null || topic.getMemberId() <= 0) {
                throw new ServiceException("主题主办方不存在，审核通知无法送达");
            }
            reporters.add(topic.getMemberId());
        } else if (task.getSubmitterId() != null && (SOURCE_REPORT.equals(task.getSourceType()) || !report)) {
            reporters.add(task.getSubmitterId());
        }
        boolean appeal = !report && SOURCE_APPEAL.equals(task.getSourceType());
        java.util.Map<Long, String> recipients = new java.util.LinkedHashMap<>();
        for (Long reporter : reporters) {
            recipients.put(reporter, report ? (upheld ? "举报已处理" : "举报处理完成")
                    : appeal ? (upheld ? "申诉未通过" : "申诉已通过")
                    : (upheld ? "审核未通过" : "审核通过"));
        }
        if (report && upheld && AuditTask.BIZ_HANGOUT.equals(task.getBizType())) {
            com.chengyinhub.business.domain.RoamHangout hangout = roamHangoutMapper.selectByIdForUpdate(task.getBizId());
            if (hangout == null) hangout = hangoutFromEvidence(task, reports);
            if (hangout != null) {
                recipients.putIfAbsent(hangout.getMemberId(), "你的组局已关闭");
                // 读取关闭前的成员真源，包含此前关群留下的历史行；不查询全站用户。
                if (hangout.getConversationId() != null) {
                    for (com.chengyinhub.business.domain.ImConversationMember member :
                            imConversationMemberMapper.selectByConversationForUpdate(hangout.getConversationId())) {
                        recipients.putIfAbsent(member.getMemberId(), "你参与的组局已关闭");
                    }
                }
            }
        }
        String outcome = report ? (upheld ? (AuditTask.BIZ_HANGOUT.equals(task.getBizType())
                ? "举报成立，组局聊天已关闭" : "举报成立，已按规则处理") : (AuditTask.BIZ_HANGOUT.equals(task.getBizType())
                        ? "本次举报不成立，既有处理不变" : "你举报的内容未发现违规"))
                : appeal ? (upheld ? "你的申诉未通过，原处理维持" : "你的申诉成立，内容已恢复")
                : (upheld ? "你提交的内容未通过审核" : "你提交的内容已通过审核");
        if (isGuardType(task.getBizType())) outcome = upheld ? "守卫申诉未通过，原到达判定维持"
                : "守卫申诉成立；仍有效原局可重新现场核验，仍须通过原定位与风控条件，不补记完成或奖励";
        String reason = request.getReasonText() == null || request.getReasonText().isEmpty()
                ? outcome : request.getReasonText();
        // 驳回/举报成立通知带上固定清单原因的中文标签(见 AuditReasonCodes)，自由文本照旧附后。
        List<String> reasonCodes = upheld && request.getReasonCodes() != null
                ? request.getReasonCodes() : java.util.Collections.<String>emptyList();
        List<String> reasonLabels = com.chengyinhub.business.service.support.AuditReasonCodes.labels(reasonCodes);
        String reasonLine = reasonLabels.isEmpty() ? reason : String.join("、", reasonLabels) + "；" + reason;
        for (java.util.Map.Entry<Long, String> recipient : recipients.entrySet()) {
            if (recipient.getKey() == null || recipient.getKey() <= 0) continue;
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("taskId", task.getId());
            result.put("bizType", task.getBizType());
            result.put("bizId", task.getBizId());
            result.put("outcome", outcome);
            result.put("reason", reason);
            if (!reasonCodes.isEmpty()) {
                result.put("reasonCodes", reasonCodes);
                result.put("reasonLabels", reasonLabels);
            }
            result.put("followUp", "本通知保留处理结果；如有异议，请通过客服提供审核任务编号补充材料。");
            java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
            card.put("title", recipient.getValue());
            card.put("sub", outcome + "。原因：" + reasonLine);
            card.put("result", result);
            String json;
            try {
                json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(card);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new ServiceException("审核通知序列化失败");
            }
            imServiceProvider.getObject().sendSystem(recipient.getKey(), ImMessage.MSG_CARD, recipient.getValue(), json);
        }
    }

    /** 原内容被删除后，仅回退同任务、同组局的服务器举报快照；旧缺证不能遮住后续证据。 */
    private com.chengyinhub.business.domain.RoamHangout hangoutFromEvidence(AuditTask task, List<AuditRecord> reports) {
        List<String> snapshots = new ArrayList<>();
        snapshots.add(task.getSnapshotJson());
        for (AuditRecord record : reports) {
            if (task.getId().equals(record.getTaskId()) && "REPORT".equals(record.getAction())) {
                snapshots.add(record.getSnapshotJson());
            }
        }
        com.chengyinhub.business.domain.RoamHangout hangout = new com.chengyinhub.business.domain.RoamHangout();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String snapshot : snapshots) {
            if (snapshot == null) continue;
            try {
                com.fasterxml.jackson.databind.JsonNode evidence = mapper.readTree(snapshot);
                if (evidence == null || !AuditTask.BIZ_HANGOUT.equals(evidence.path("bizType").asText())
                        || !java.util.Objects.equals(task.getBizId(), evidenceId(evidence.path("bizId")))) continue;
                com.fasterxml.jackson.databind.JsonNode content = evidence.path("content");
                if (hangout.getMemberId() == null) hangout.setMemberId(evidenceId(content.path("memberId")));
                if (hangout.getConversationId() == null) hangout.setConversationId(evidenceId(content.path("conversationId")));
                if (hangout.getMemberId() != null && hangout.getConversationId() != null) break;
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // 畸形旧证据保留在审核时间线供调查，不阻断后续可靠快照。
            }
        }
        return hangout;
    }

    private Long evidenceId(com.fasterxml.jackson.databind.JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0
                ? value.longValue() : null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CriticalPath(value = CriticalPathCode.CP_AUDIT_DECISION_FAILED, op = "revert")
    public AuditRevertResultVO revert(Long taskId, Long operatorId, boolean platformAdmin, AuditRevertDTO request) {
        if (taskId == null || operatorId == null || request == null || request.getExpectedVersion() == null) {
            throw governanceError(ERROR_REVERT_BAD_REQUEST, "撤销参数不完整", HttpStatus.BAD_REQUEST);
        }
        String requestId = requireStableKey(request.getIdempotencyKey());
        String reason = requireReason(request.getReason(), "撤销理由不能为空");
        BusinessAuditEventMapper auditMapper = requireBusinessAuditMapper();
        BusinessAuditEvent replay = auditMapper.selectByDomainActionRequestForUpdate(
                "PLATFORM", taskId, "AUDIT_DECISION_REVERTED", requestId);
        if (replay != null) {
            if (!platformAdmin && !operatorId.equals(replay.getActorMemberId())) {
                throw governanceError(ERROR_REVERT_FORBIDDEN, "只能由原审批人或平台管理员撤销", HttpStatus.FORBIDDEN);
            }
            if (!replayMatches(replay, request.getExpectedVersion(), reason)) {
                throw governanceError(ERROR_REVERT_KEY_REUSED, "idempotencyKey 已用于其他撤销请求", HttpStatus.CONFLICT);
            }
            return replayResult(taskId, requestId, replay);
        }

        AuditTask task = auditTaskMapper.selectAuditTaskById(taskId);
        if (task == null) throw governanceError(ERROR_REVERT_BAD_REQUEST, "审核任务不存在", HttpStatus.NOT_FOUND);
        if (!request.getExpectedVersion().equals(task.getVersion())) {
            throw governanceError(ERROR_REVERT_STALE, "审核状态已变化，请刷新后重试", HttpStatus.CONFLICT);
        }
        if (!AuditTask.STATUS_APPROVED.equals(task.getStatus())
                && !AuditTask.STATUS_REJECTED.equals(task.getStatus())) {
            throw governanceError(ERROR_REVERT_STATE, "当前审核状态不可撤销", HttpStatus.CONFLICT);
        }
        if (!isRevertSupported(task)) {
            throw governanceError(ERROR_REVERT_TYPE, "该审批类型或决定方向没有安全 CAS 补偿", HttpStatus.CONFLICT);
        }
        AuditRecord decision = latestDecision(task.getId(), task.getStatus());
        if (decision == null || decision.getCreateTime() == null) {
            throw governanceError(ERROR_REVERT_DECISION_MISSING, "缺少原审批决定，无法安全撤销", HttpStatus.CONFLICT);
        }
        if (!platformAdmin && !operatorId.equals(decision.getOperatorId())) {
            throw governanceError(ERROR_REVERT_FORBIDDEN, "只能由原审批人或平台管理员撤销", HttpStatus.FORBIDDEN);
        }

        Instant nowInstant = clock.instant();
        Instant deadline = decision.getCreateTime().toInstant().plus(REVERT_WINDOW_MINUTES, ChronoUnit.MINUTES);
        if (nowInstant.isAfter(deadline)) {
            throw governanceError(ERROR_REVERT_EXPIRED, "审批撤销窗口已过期", HttpStatus.CONFLICT);
        }
        Date now = Date.from(nowInstant);
        Date cutoff = Date.from(nowInstant.minus(REVERT_WINDOW_MINUTES, ChronoUnit.MINUTES));
        // 与审核/编辑共用 模板 → 投影 → 审核任务 的锁顺序，再做任务 CAS。
        if (isTemplateTask(task)) lockTemplateReview(task);
        if (auditTaskMapper.revertAuditTaskDecisionCas(taskId, task.getStatus(), task.getVersion(), cutoff, now) != 1) {
            replay = auditMapper.selectByDomainActionRequestForUpdate(
                    "PLATFORM", taskId, "AUDIT_DECISION_REVERTED", requestId);
            if (replay != null) {
                if (!platformAdmin && !operatorId.equals(replay.getActorMemberId())) {
                    throw governanceError(ERROR_REVERT_FORBIDDEN,
                            "只能由原审批人或平台管理员撤销", HttpStatus.FORBIDDEN);
                }
                if (!replayMatches(replay, request.getExpectedVersion(), reason)) {
                    throw governanceError(ERROR_REVERT_KEY_REUSED,
                            "idempotencyKey 已用于其他撤销请求", HttpStatus.CONFLICT);
                }
                return replayResult(taskId, requestId, replay);
            }
            throw governanceError(ERROR_REVERT_STALE, "审核状态已变化，请刷新后重试", HttpStatus.CONFLICT);
        }

        reverseDecisionEffects(task, decision);
        AuditRecord reverse = new AuditRecord();
        reverse.setTaskId(taskId);
        reverse.setAction(AuditRecord.ACTION_REVERT);
        reverse.setOperatorId(operatorId);
        reverse.setReasonText(reason);
        reverse.setSnapshotJson(task.getSnapshotJson());
        reverse.setRevertedRecordId(decision.getId());
        reverse.setCreateTime(now);
        if (auditRecordMapper.insertAuditRecord(reverse) != 1) {
            throw new ServiceException("撤销时间线写入失败", HttpStatus.ERROR);
        }

        BusinessAuditEvent event = decisionAuditEvent(task, operatorId, request.getActorRoleCode(), "AUDIT_DECISION_REVERTED",
                requestId, decision.getId(), reason,
                stateJson(task.getStatus(), task.getVersion()),
                stateJson(AuditTask.STATUS_PENDING, task.getVersion() + 1), now);
        if (auditMapper.insertBusinessAuditEvent(event) != 1) {
            throw new ServiceException("撤销审计写入失败", HttpStatus.ERROR);
        }
        AuditRevertResultVO result = new AuditRevertResultVO();
        result.setTaskId(taskId);
        result.setStatus(AuditTask.STATUS_PENDING);
        result.setVersion(task.getVersion() + 1);
        result.setRevertedRecordId(decision.getId());
        result.setRequestId(requestId);
        return result;
    }

    @Override
    public AuditTaskDetailVO detail(Long taskId, Long viewerId, boolean platformAdmin) {
        AuditTask task = auditTaskMapper.selectAuditTaskById(taskId);
        if (task == null) throw new ServiceException("审核任务不存在", HttpStatus.NOT_FOUND);
        List<AuditRecord> timeline = listAuditRecords(taskId);
        Set<Long> reporters = new HashSet<>();
        for (AuditRecord record : timeline) {
            if ("REPORT".equals(record.getAction()) && record.getOperatorId() != null) {
                reporters.add(record.getOperatorId());
            }
        }
        AuditRecord decision = latestDecision(timeline, task.getStatus());
        Date now = Date.from(clock.instant());
        Date deadline = decision == null || decision.getCreateTime() == null ? null
                : new Date(decision.getCreateTime().getTime() + REVERT_WINDOW_MINUTES * 60_000L);
        boolean actorAllowed = decision != null && (platformAdmin || viewerId != null
                && viewerId.equals(decision.getOperatorId()));
        boolean withinWindow = deadline != null && !now.after(deadline);

        AuditTaskDetailVO detail = new AuditTaskDetailVO();
        detail.setTask(platformAdmin ? task : applicantTask(task));
        detail.setTimeline(platformAdmin ? timeline : applicantTimeline(timeline));
        detail.setReportCount(platformAdmin ? reporters.size() : 0);
        detail.setRevertDeadline(deadline);
        detail.setServerTime(now);
        detail.setCanRevert(actorAllowed && withinWindow && isTerminalDecision(task.getStatus())
                && isRevertSupported(task));
        return detail;
    }

    /**
     * 作者对自己被驳回/下架的内容申诉(每内容每人一次)。申诉生成一张 source=appeal 的审核任务进入工作台队列，
     * 快照沿用被驳回的那一单，裁决成立时既有通过路径按同一份快照做内容 CAS。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public com.chengyinhub.business.domain.AuditAppeal submitAppeal(String bizType, Long bizId, Long memberId,
                                                                    String reason, String evidenceJson) {
        return submitAppeal(bizType, bizId, memberId, reason, evidenceJson, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public com.chengyinhub.business.domain.AuditAppeal submitAppeal(String bizType, Long bizId, Long memberId,
                                                                    String reason, String evidenceJson,
                                                                    List<String> attachments) {
        return submitAppealInternal(bizType, bizId, memberId, reason, evidenceJson, attachments, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public com.chengyinhub.business.domain.AuditAppeal submitAppeal(String bizType, Long bizId, Long memberId,
            String reason, String evidenceJson, List<String> attachments, String requestId) {
        return submitAppealInternal(bizType, bizId, memberId, reason, evidenceJson, attachments, appealRequestId(requestId));
    }

    private com.chengyinhub.business.domain.AuditAppeal submitAppealInternal(String bizType, Long bizId, Long memberId,
            String reason, String evidenceJson, List<String> attachments, String requestId) {
        return submitAppealInternal(bizType, bizId, memberId, reason, evidenceJson, attachments, requestId, null);
    }

    private com.chengyinhub.business.domain.AuditAppeal submitAppealInternal(String bizType, Long bizId, Long memberId,
            String reason, String evidenceJson, List<String> attachments, String requestId,
            com.fasterxml.jackson.databind.JsonNode guardReference) {
        boolean guardAppeal = guardReference != null && isGuardType(bizType);
        if ((!isContentDecisionType(bizType) && !guardAppeal) || bizId == null || bizId <= 0L || memberId == null || memberId <= 0L) {
            throw governanceError(ERROR_APPEAL_BAD_REQUEST, "申诉参数不完整", HttpStatus.BAD_REQUEST);
        }
        String normalizedReason = reason == null ? null : reason.trim();
        if (normalizedReason == null || normalizedReason.isEmpty()) {
            throw governanceError(ERROR_APPEAL_BAD_REQUEST, "申诉理由不能为空", HttpStatus.BAD_REQUEST);
        }
        if (normalizedReason.length() > MAX_APPEAL_REASON_LENGTH) {
            throw governanceError(ERROR_APPEAL_BAD_REQUEST, "申诉理由不能超过 500 字", HttpStatus.BAD_REQUEST);
        }
        String evidence = normalizeEvidence(evidenceJson);
        com.fasterxml.jackson.databind.JsonNode intent = requestId == null ? null
                : appealIntent(bizType, bizId, memberId, requestId, normalizedReason, evidence, attachments);
        if (guardAppeal) ((com.fasterxml.jackson.databind.node.ObjectNode) intent).set("guardReference", guardReference.deepCopy());
        if (requestId != null) {
            com.chengyinhub.business.domain.AuditAppeal prior = auditAppealMapper.selectByBizMember(bizType, bizId, memberId);
            if (prior != null) {
                if (intent.equals(storedAppealIntent(prior))) return prior;
                throw governanceError(ERROR_APPEAL_DUPLICATE, "申诉已存在，原请求或输入不一致；请读取原申诉记录", HttpStatus.CONFLICT);
            }
        }
        List<String> attachmentRefs = normalizeAppealAttachments(memberId, attachments);
        if (!attachmentRefs.isEmpty()) evidence = mergeAttachments(evidence, attachmentRefs);
        AuditTask rejected = null;
        com.fasterxml.jackson.databind.JsonNode guardSnapshot = null;
        if (guardAppeal) {
            GuardContext context = guardContext(memberId, bizId, guardReference.path("arrivalRequestId").asText(), true);
            if (!context.active) throw governanceError(ERROR_APPEAL_NOT_ELIGIBLE, "原局已结束或身份已变化，仅可查询已有申诉结果", HttpStatus.CONFLICT);
            guardSnapshot = guardSnapshot(context, guardReference.path("arrivalRequestId").asText());
        } else {
        ContentState content = contentState(bizType, bizId);
        if (content == null || content.deleted) {
            throw governanceError(ERROR_APPEAL_BAD_REQUEST, "内容不存在", HttpStatus.NOT_FOUND);
        }
        if (!memberId.equals(content.ownerId)) {
            throw governanceError(ERROR_APPEAL_FORBIDDEN, "只有内容作者可以申诉", HttpStatus.FORBIDDEN);
        }
        if (auditAppealMapper.selectByBizMember(bizType, bizId, memberId) != null) {
            throw governanceError(ERROR_APPEAL_DUPLICATE, "该内容已申诉过，每条内容只能申诉一次", HttpStatus.CONFLICT);
        }
        rejected = latestContentTask(bizType, bizId);
        if (rejected == null || !AuditTask.STATUS_REJECTED.equals(rejected.getStatus()) || !content.down) {
            throw governanceError(ERROR_APPEAL_NOT_ELIGIBLE, "只能对被驳回或下架的内容申诉", HttpStatus.CONFLICT);
        }
        requireNoContentSafetyHold(bizType, bizId, "内容图片未通过内容安全检测，不能申诉恢复，请更换图片后重新提交审核");

        }

        Date now = Date.from(clock.instant());
        Date dueAt = guardAppeal ? new Date(now.getTime() + APPEAL_STANDARD_DUE_MINUTES * 60_000L) : appealDueAt(rejected, now);
        AuditTask task = new AuditTask();
        task.setBizType(bizType);
        task.setBizId(bizId);
        task.setSubmitterId(memberId);
        task.setSourceType(SOURCE_APPEAL);
        task.setRiskLevel("NORMAL");
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setSubmittedAt(now);
        task.setDeadlineAt(dueAt);
        task.setSnapshotJson(guardAppeal ? guardSnapshot.toString() : rejected.getSnapshotJson());
        task.setVersion(0);
        task.setCreateTime(now);
        try {
            if (auditTaskMapper.insertAuditTask(task) != 1 || task.getId() == null) {
                throw new ServiceException("申诉审核任务保存失败", HttpStatus.ERROR);
            }
        } catch (org.springframework.dao.DuplicateKeyException open) {
            throw governanceError(ERROR_APPEAL_NOT_ELIGIBLE, "该内容已有待处理的审核，请稍后再试", HttpStatus.CONFLICT);
        }

        com.chengyinhub.business.domain.AuditAppeal appeal = new com.chengyinhub.business.domain.AuditAppeal();
        appeal.setBizType(bizType);
        appeal.setBizId(bizId);
        appeal.setTaskId(task.getId());
        appeal.setMemberId(memberId);
        appeal.setReason(normalizedReason);
        appeal.setEvidenceJson(evidence);
        appeal.setState(com.chengyinhub.business.domain.AuditAppeal.STATE_SUBMITTED);
        appeal.setDueAt(dueAt);
        appeal.setCreatedAt(now);
        try {
            if (auditAppealMapper.insertAuditAppeal(appeal) != 1) {
                throw new ServiceException("申诉保存失败，请重试", HttpStatus.ERROR);
            }
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw governanceError(ERROR_APPEAL_DUPLICATE, "该内容已申诉过，每条内容只能申诉一次", HttpStatus.CONFLICT);
        }

        AuditRecord submitted = new AuditRecord();
        submitted.setTaskId(task.getId());
        submitted.setAction(AuditRecord.ACTION_SUBMIT);
        submitted.setOperatorId(memberId);
        submitted.setReasonCode("APPEAL");
        submitted.setReasonText(normalizedReason);
        java.util.Map<String, Object> evidenceSnapshot = new java.util.LinkedHashMap<>();
        evidenceSnapshot.put("appealId", appeal.getId());
        if (!guardAppeal) evidenceSnapshot.put("appealOfTaskId", rejected.getId());
        evidenceSnapshot.put("evidence", evidence);
        // This server-owned SUBMIT snapshot is the trust boundary. Client evidence is never metadata.
        if (intent != null) evidenceSnapshot.put("appealIntent", intent);
        try {
            submitted.setSnapshotJson(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(evidenceSnapshot));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException("申诉记录序列化失败", HttpStatus.ERROR);
        }
        submitted.setCreateTime(now);
        if (auditRecordMapper.insertAuditRecord(submitted) != 1) {
            throw new ServiceException("申诉记录保存失败，请重试", HttpStatus.ERROR);
        }
        return appeal;
    }

    private static boolean isGuardType(String bizType) {
        return com.chengyinhub.business.service.support.ArriveLocationGuard.APPEAL_BIZ_TYPE.equals(bizType);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public com.chengyinhub.business.domain.AuditAppeal submitGuardAppeal(Long memberId,
            com.chengyinhub.business.domain.dto.GuardAppealDTO request) {
        if (request == null) throw governanceError(ERROR_APPEAL_BAD_REQUEST, "缺少守卫申诉参数", HttpStatus.BAD_REQUEST);
        com.fasterxml.jackson.databind.node.ObjectNode reference = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        reference.put("arrivalRequestId", arrivalRequestId(request.getArrivalRequestId()));
        reference.put("guardLogId", request.getGuardLogId() == null ? null : request.getGuardLogId().toString());
        return submitAppealInternal(com.chengyinhub.business.service.support.ArriveLocationGuard.APPEAL_BIZ_TYPE,
                request.getGuardLogId(), memberId, request.getReason(), request.getEvidence() == null ? null : request.getEvidence().toString(),
                request.getAttachments(), appealRequestId(request.getRequestId()), reference);
    }

    private static final class GuardContext {
        final com.chengyinhub.business.domain.PlayArriveGuardLog log;
        final com.fasterxml.jackson.databind.JsonNode receipt;
        final boolean active;
        GuardContext(com.chengyinhub.business.domain.PlayArriveGuardLog log,
                     com.fasterxml.jackson.databind.JsonNode receipt, boolean active) {
            this.log = log; this.receipt = receipt; this.active = active;
        }
    }

    private GuardContext guardContext(Long memberId, Long guardLogId, String arrivalRequestId, boolean lock) {
        if (memberId == null || memberId <= 0 || guardLogId == null || guardLogId <= 0) {
            throw governanceError(ERROR_APPEAL_BAD_REQUEST, "守卫引用无效", HttpStatus.BAD_REQUEST);
        }
        String key = arrivalRequestId(arrivalRequestId);
        if (guardAppealLogMapper == null || guardArrivalEvidenceReader == null) {
            throw new ServiceException("原局证据读取暂不可用，请保留原引用", 503);
        }
        com.chengyinhub.business.domain.PlayArriveGuardLog log = guardAppealLogMapper.selectByIdAndMemberId(guardLogId, memberId);
        if (log == null) throw governanceError(ERROR_APPEAL_FORBIDDEN, "没有本人对应的守卫记录", HttpStatus.FORBIDDEN);
        com.chengyinhub.business.service.GuardArrivalEvidenceReader.Origin origin = lock
                ? guardArrivalEvidenceReader.lockOwned(memberId, guardLogId, key)
                : guardArrivalEvidenceReader.readOwned(memberId, guardLogId, key);
        if (origin == null || log.getTopicId() == null || log.getTopicId() <= 0
                || !com.chengyinhub.business.service.support.GuardAppealEligibility.matchesOwnedRejection(memberId, log, origin.receipt())) {
            throw governanceError(ERROR_APPEAL_NOT_ELIGIBLE, "缺少本人原局的精确拒绝回执，不能补绑当前局", HttpStatus.CONFLICT);
        }
        return new GuardContext(log, origin.receipt(), origin.originalRunActive());
    }

    /** Persist only exact identifiers and rejection code; never expose raw GPS or free-form guard detail. */
    private com.fasterxml.jackson.databind.JsonNode guardSnapshot(GuardContext context, String arrivalRequestId) {
        com.fasterxml.jackson.databind.node.ObjectNode root = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode proof = root.putObject("guardOrigin");
        proof.put("memberId", context.log.getMemberId().toString());
        proof.put("topicId", context.log.getTopicId().toString());
        proof.put("activityId", context.log.getActivityId() == null ? null : context.log.getActivityId().toString());
        proof.put("arrivalRequestId", arrivalRequestId);
        for (String field : new String[]{"guardLogId", "nodeId", "routeSessionId", "errorCode", "runtimeInstance", "authorityEpoch"}) {
            if (context.receipt.has(field)) proof.set(field, context.receipt.get(field).deepCopy());
        }
        return root;
    }

    private com.fasterxml.jackson.databind.JsonNode guardTaskOrigin(AuditTask task) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(task.getSnapshotJson());
            com.fasterxml.jackson.databind.JsonNode origin = root == null ? null : root.get("guardOrigin");
            if (origin == null || !origin.isObject() || task.getSubmitterId() == null || task.getBizId() == null
                    || !task.getSubmitterId().toString().equals(origin.path("memberId").asText())
                    || !task.getBizId().toString().equals(origin.path("guardLogId").asText())) throw new IllegalArgumentException();
            return origin;
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            throw governanceError(ERROR_APPEAL_NOT_ELIGIBLE, "守卫审核单缺少可信原局绑定", HttpStatus.CONFLICT);
        }
    }

    private Long persistedGuardTopic(AuditTask task) {
        if (task == null || !isGuardType(task.getBizType()) || !SOURCE_APPEAL.equals(task.getSourceType())
                || task.getBizId() == null || task.getSubmitterId() == null || guardAppealLogMapper == null) {
            throw new ServiceException("守卫审核持久关联缺失", HttpStatus.FORBIDDEN);
        }
        com.chengyinhub.business.domain.AuditAppeal appeal = auditAppealMapper.selectByBizMember(
                task.getBizType(), task.getBizId(), task.getSubmitterId());
        com.chengyinhub.business.domain.PlayArriveGuardLog log = guardAppealLogMapper.selectByIdAndMemberId(
                task.getBizId(), task.getSubmitterId());
        if (appeal == null || !java.util.Objects.equals(task.getId(), appeal.getTaskId()) || log == null
                || log.getTopicId() == null || log.getTopicId() <= 0) {
            throw new ServiceException("守卫审核持久关联缺失", HttpStatus.FORBIDDEN);
        }
        return log.getTopicId();
    }

    private void requireGuardReviewScope(AuditTask task, Long operatorId) {
        Long topicId = persistedGuardTopic(task);
        if (guardAppealReviewerScope == null || !guardAppealReviewerScope.canReview(operatorId, topicId)) {
            throw new ServiceException("未配置本主题守卫审核处理范围", HttpStatus.FORBIDDEN);
        }
    }

    @Override
    public java.util.Map<String, Object> guardReviewReceipt(Long taskId, Long reviewerId, String requestId) {
        AuditTask task = auditTaskMapper.selectAuditTaskById(taskId);
        if (task == null || !isGuardType(task.getBizType())) throw new IllegalArgumentException("守卫审核单不存在");
        requireGuardReviewScope(task, reviewerId);
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("state", "NOT_FOUND");
        if (requestId == null || requestId.trim().isEmpty()) return result;
        String key = appealRequestId(requestId);
        BusinessAuditEvent event = guardDecisionEvent(taskId, key);
        if (event == null || !java.util.Objects.equals(reviewerId, event.getActorMemberId())) return result;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode before = mapper.readTree(event.getBeforeJson());
            com.fasterxml.jackson.databind.JsonNode after = mapper.readTree(event.getAfterJson());
            int status = after.path("status").asInt(-1);
            if (!before.path("version").isIntegralNumber() || !after.path("status").isIntegralNumber()
                    || !(status == AuditTask.STATUS_APPROVED || status == AuditTask.STATUS_REJECTED)
                    || !("AUDIT_DECISION_" + statusToAction(status)).equals(event.getAction())) {
                result.put("state", "UNKNOWN"); return result;
            }
            result.put("state", "FOUND"); result.put("taskId", taskId.toString()); result.put("requestId", key);
            result.put("expectedVersion", before.path("version").asInt()); result.put("status", status);
            result.put("reasonText", event.getReason()); result.put("applied", false);
            result.put("reverifyRule", "FRESH_ON_SITE_WITH_ALL_GUARDS");
            return result;
        } catch (java.io.IOException invalid) { result.put("state", "UNKNOWN"); return result; }
    }

    private BusinessAuditEvent guardDecisionEvent(Long taskId, String requestId) {
        List<BusinessAuditEvent> events = requireBusinessAuditMapper().selectByTarget("AUDIT_TASK", taskId);
        if (events == null) return null;
        for (BusinessAuditEvent event : events) {
            if ("PLATFORM".equals(event.getDomainType()) && java.util.Objects.equals(taskId, event.getDomainId())
                    && "AUDIT_TASK".equals(event.getTargetType()) && java.util.Objects.equals(taskId, event.getTargetId())
                    && BusinessAuditEvent.ACTOR_TYPE_SYS_USER.equals(event.getActorType())
                    && requestId.equals(event.getRequestId())
                    && ("AUDIT_DECISION_APPROVE".equals(event.getAction()) || "AUDIT_DECISION_REJECT".equals(event.getAction()))) {
                return event;
            }
        }
        return null;
    }

    private void requireActiveGuardTask(AuditTask task) {
        com.fasterxml.jackson.databind.JsonNode frozen = guardTaskOrigin(task);
        String key = frozen.path("arrivalRequestId").asText();
        GuardContext current = guardContext(task.getSubmitterId(), task.getBizId(), key, true);
        if (!current.active || !frozen.equals(guardSnapshot(current, key).get("guardOrigin"))) {
            throw governanceError(ERROR_APPEAL_NOT_ELIGIBLE, "原局已结束或原身份已变化，仅保留结果查询", HttpStatus.CONFLICT);
        }
    }

    @Override
    public boolean canReadGuardTask(AuditTask task) {
        if (task == null || !isGuardType(task.getBizType())) return true;
        try {
            return guardAppealReviewerScope != null
                    && guardAppealReviewerScope.canRead(persistedGuardTopic(task));
        } catch (ServiceException | NumberFormatException invalid) { return false; }
    }

    @Override
    public java.util.Map<String, Object> guardAppealReference(Long memberId, Long guardLogId, String arrivalRequestId) {
        GuardContext context = guardContext(memberId, guardLogId, arrivalRequestId, false);
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("guardOrigin", guardSnapshot(context, arrivalRequestId).get("guardOrigin"));
        result.put("originalRunActive", context.active);
        result.put("canSubmit", context.active && auditAppealMapper.selectByBizMember(
                com.chengyinhub.business.service.support.ArriveLocationGuard.APPEAL_BIZ_TYPE, guardLogId, memberId) == null);
        return result;
    }

    @Override
    public java.util.Map<String, Object> guardAppealReceipt(Long memberId, Long guardLogId, String requestId) {
        if (memberId == null || memberId <= 0 || guardLogId == null || guardLogId <= 0) {
            throw governanceError(ERROR_APPEAL_BAD_REQUEST, "守卫引用无效", HttpStatus.BAD_REQUEST);
        }
        java.util.Map<String, Object> result = originalAppealReceipt(
                com.chengyinhub.business.service.support.ArriveLocationGuard.APPEAL_BIZ_TYPE, guardLogId, memberId, appealRequestId(requestId));
        if (!"FOUND".equals(result.get("state"))) return result;
        com.fasterxml.jackson.databind.JsonNode intent = (com.fasterxml.jackson.databind.JsonNode) result.get("intent");
        com.fasterxml.jackson.databind.JsonNode reference = intent.path("guardReference");
        if (!guardLogId.toString().equals(reference.path("guardLogId").asText()) || !reference.path("arrivalRequestId").isTextual()) {
            result.put("state", "UNKNOWN"); return result;
        }
        result.put("originalRunActive", false); result.put("canReverify", false);
        // The result remains queryable even when its run has ended or the runtime reader is temporarily unavailable.
        try {
            GuardContext context = guardContext(memberId, guardLogId, reference.path("arrivalRequestId").asText(), false);
            AuditTask task = auditTaskMapper.selectAuditTaskById(Long.valueOf((String) result.get("taskId")));
            boolean matches = task != null && guardTaskOrigin(task).equals(guardSnapshot(context, reference.path("arrivalRequestId").asText()).get("guardOrigin"));
            result.put("originalRunActive", matches && context.active);
            result.put("canReverify", com.chengyinhub.business.service.support.GuardAppealEligibility.mayReverify(
                    (String) result.get("appealState"), context.active, matches));
            result.put("reverifyRule", "FRESH_ON_SITE_WITH_ALL_GUARDS");
        } catch (ServiceException unavailable) { result.put("reverifyUnavailableReason", unavailable.getMessage()); }
        for (AuditRecord record : listAuditRecords(Long.valueOf((String) result.get("taskId")))) {
            if (AuditRecord.ACTION_APPROVE.equals(record.getAction()) || AuditRecord.ACTION_REJECT.equals(record.getAction())) {
                result.put("decisionReason", record.getReasonText()); result.put("decidedAt", record.getCreateTime()); break;
            }
        }
        return result;
    }

    private String arrivalRequestId(String supplied) {
        if (supplied == null || !supplied.matches("[A-Za-z0-9:_-]{1,80}")) {
            throw governanceError(ERROR_APPEAL_BAD_REQUEST, "原到达请求号无效", HttpStatus.BAD_REQUEST);
        }
        return supplied;
    }

    private String appealRequestId(String supplied) {
        if (supplied == null || supplied.trim().isEmpty() || supplied.trim().length() > 64) {
            throw governanceError(ERROR_APPEAL_BAD_REQUEST, "requestId 须为1至64个字符", HttpStatus.BAD_REQUEST);
        }
        return supplied.trim();
    }

    private com.fasterxml.jackson.databind.JsonNode appealIntent(String bizType, Long bizId, Long memberId,
            String requestId, String reason, String evidence, List<String> attachments) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode intent = mapper.createObjectNode();
        intent.put("version", 1); intent.put("requestId", requestId);
        intent.put("bizType", bizType); intent.put("bizId", bizId.toString()); intent.put("memberId", memberId.toString());
        intent.put("reason", reason);
        try { intent.set("evidence", evidence == null ? mapper.nullNode() : mapper.readTree(evidence)); }
        catch (java.io.IOException e) { throw governanceError(ERROR_APPEAL_BAD_REQUEST, "补充材料格式不正确", HttpStatus.BAD_REQUEST); }
        java.util.Set<String> refs = new java.util.LinkedHashSet<>();
        if (attachments != null) for (String ref : attachments) if (ref != null && !ref.trim().isEmpty()) refs.add(ref.trim());
        com.fasterxml.jackson.databind.node.ArrayNode array = intent.putArray("attachments");
        for (String ref : refs) array.add(ref);
        return intent;
    }

    /** Read only server-created SUBMIT records for this original actor/task/appeal. Never upgrade legacy evidence. */
    private com.fasterxml.jackson.databind.JsonNode storedAppealIntent(com.chengyinhub.business.domain.AuditAppeal appeal) {
        AuditRecord query = new AuditRecord(); query.setTaskId(appeal.getTaskId()); query.setAction(AuditRecord.ACTION_SUBMIT);
        List<AuditRecord> records = auditRecordMapper.selectAuditRecordList(query);
        if (records == null) return null;
        for (AuditRecord record : records) {
            if (!AuditRecord.ACTION_SUBMIT.equals(record.getAction()) || !"APPEAL".equals(record.getReasonCode())
                    || !java.util.Objects.equals(appeal.getTaskId(), record.getTaskId())
                    || !java.util.Objects.equals(appeal.getMemberId(), record.getOperatorId())) continue;
            try {
                com.fasterxml.jackson.databind.JsonNode snapshot = new com.fasterxml.jackson.databind.ObjectMapper().readTree(record.getSnapshotJson());
                if (snapshot == null || !snapshot.isObject()) return null;
                com.fasterxml.jackson.databind.JsonNode intent = snapshot.path("appealIntent");
                if (!String.valueOf(appeal.getId()).equals(snapshot.path("appealId").asText())
                        || !snapshot.has("evidence")
                        || !(snapshot.path("evidence").isNull() || snapshot.path("evidence").isTextual())
                        || !java.util.Objects.equals(appeal.getEvidenceJson(), snapshot.path("evidence").isNull() ? null : snapshot.path("evidence").asText())
                        || intent.path("version").asInt() != 1 || !intent.path("attachments").isArray()
                        || !intent.has("evidence") || !intent.path("requestId").isTextual()
                        || !appeal.getBizType().equals(intent.path("bizType").asText())
                        || !appeal.getBizId().toString().equals(intent.path("bizId").asText())
                        || !appeal.getMemberId().toString().equals(intent.path("memberId").asText())
                        || !java.util.Objects.equals(appeal.getReason(), intent.path("reason").asText())
                        || !java.util.Objects.equals(appeal.getReason(), record.getReasonText())) continue;
                return intent;
            } catch (java.io.IOException | IllegalArgumentException invalid) {
                // A legacy/corrupt record cannot prove the original intent; never fabricate binding.
                return null;
            }
        }
        return null;
    }

    @Override
    public java.util.Map<String, Object> appealReceipt(String bizType, Long bizId, Long memberId, String requestId) {
        String key = appealRequestId(requestId);
        if (!isContentDecisionType(bizType) || bizId == null || bizId <= 0 || memberId == null || memberId <= 0) {
            throw governanceError(ERROR_APPEAL_BAD_REQUEST, "申诉参数不完整", HttpStatus.BAD_REQUEST);
        }
        return originalAppealReceipt(bizType, bizId, memberId, key);
    }

    private java.util.Map<String, Object> originalAppealReceipt(String bizType, Long bizId, Long memberId, String key) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("state", "NOT_FOUND");
        com.chengyinhub.business.domain.AuditAppeal appeal = auditAppealMapper.selectByBizMember(bizType, bizId, memberId);
        if (appeal == null) return result;
        com.fasterxml.jackson.databind.JsonNode intent = storedAppealIntent(appeal);
        if (intent == null || !key.equals(intent.path("requestId").asText())) return result;
        result.put("state", "FOUND"); result.put("intent", intent);
        result.put("appealId", appeal.getId().toString()); result.put("taskId", appeal.getTaskId().toString());
        result.put("appealState", appeal.getState()); result.put("dueAt", appeal.getDueAt());
        return result;
    }

    /**
     * 作者只读：自己内容的全部审核轮次与时间线(复用 applicantTimeline，剔除举报记录)，
     * 非作者本人的提交人/操作人 ID 一律置空，不暴露举报人与审核员身份。
     */
    @Override
    public java.util.Map<String, Object> contentTimeline(String bizType, Long bizId, Long memberId) {
        if (!isContentDecisionType(bizType) || bizId == null || bizId <= 0L || memberId == null || memberId <= 0L) {
            throw governanceError(ERROR_APPEAL_BAD_REQUEST, "参数不完整", HttpStatus.BAD_REQUEST);
        }
        ContentState content = contentState(bizType, bizId);
        if (content == null) throw new ServiceException("内容不存在", HttpStatus.NOT_FOUND);
        if (!memberId.equals(content.ownerId)) throw new ServiceException("无权查看该内容的审核记录", HttpStatus.FORBIDDEN);
        AuditTask query = new AuditTask();
        query.setBizType(bizType);
        query.setBizId(bizId);
        List<AuditTask> tasks = auditTaskMapper.selectAuditTaskList(query);
        List<java.util.Map<String, Object>> rounds = new ArrayList<>();
        boolean open = false;
        if (tasks != null) {
            for (AuditTask task : tasks) {
                if ((AuditTask.STATUS_PENDING.equals(task.getStatus())
                        || AuditTask.STATUS_ESCALATED.equals(task.getStatus())) && !task.isOpenReport()) open = true;
                AuditTask safe = applicantTask(task);
                if (!memberId.equals(task.getSubmitterId())) safe.setSubmitterId(null);
                List<AuditRecord> timeline = applicantTimeline(listAuditRecords(task.getId()));
                for (AuditRecord item : timeline) {
                    if (!memberId.equals(item.getOperatorId())) item.setOperatorId(null);
                }
                java.util.Map<String, Object> round = new java.util.LinkedHashMap<>();
                round.put("task", safe);
                round.put("kind", SOURCE_APPEAL.equals(task.getSourceType()) ? "APPEAL"
                        : SOURCE_REPORT.equals(task.getSourceType()) ? "REPORT" : "SUBMIT");
                round.put("timeline", timeline);
                if (itemProblemsEnabled && auditProblemService != null) {
                    round.put("problems", auditProblemService.listForOwner(task.getId(), memberId));
                }
                rounds.add(round);
            }
        }
        com.chengyinhub.business.domain.AuditAppeal appeal = auditAppealMapper.selectByBizMember(bizType, bizId, memberId);
        java.util.Map<String, Object> appealView = null;
        if (appeal != null) {
            appealView = new java.util.LinkedHashMap<>();
            appealView.put("id", appeal.getId());
            appealView.put("taskId", appeal.getTaskId());
            appealView.put("state", appeal.getState());
            appealView.put("reason", appeal.getReason());
            appealView.put("dueAt", appeal.getDueAt());
            appealView.put("createdAt", appeal.getCreatedAt());
            appealView.put("handledAt", appeal.getHandledAt());
        }
        // 与 submitAppeal 同口径:跳过独立槽里未结的举报单(AUDIT-INT-5)
        AuditTask latest = null;
        if (tasks != null) for (AuditTask task : tasks) {
            if (!task.isOpenReport()) {
                latest = task;
                break;
            }
        }
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("bizType", bizType);
        result.put("bizId", bizId);
        result.put("rounds", rounds);
        result.put("appeal", appealView);
        result.put("canAppeal", appeal == null && !open && !content.deleted && content.down
                && latest != null && AuditTask.STATUS_REJECTED.equals(latest.getStatus()));
        return result;
    }

    /** 申诉处理时限：被驳回那一单的举报/驳回原因命中紧急举报原因走 15 分钟，否则 24 小时(举报 SLA 同口径)。 */
    private Date appealDueAt(AuditTask rejected, Date now) {
        long minutes = APPEAL_STANDARD_DUE_MINUTES;
        List<AuditRecord> records = listAuditRecords(rejected.getId());
        if (records != null) {
            for (AuditRecord record : records) {
                if (!AuditRecord.ACTION_REPORT.equals(record.getAction())
                        && !AuditRecord.ACTION_REJECT.equals(record.getAction())) continue;
                com.chengyinhub.business.service.support.CommunityReportPolicy.Reason reason =
                        record.getReasonCode() == null ? null
                                : com.chengyinhub.business.service.support.CommunityReportPolicy.find(record.getReasonCode());
                if (reason != null) minutes = Math.min(minutes, reason.getFirstResponseMinutes());
                // 驳回时勾选的固定清单编码与举报原因同名同义(如 MINOR_SAFETY)，同样参与紧急判定。
                for (String code : record.getReasonCodes()) {
                    reason = com.chengyinhub.business.service.support.CommunityReportPolicy.find(code);
                    if (reason != null) minutes = Math.min(minutes, reason.getFirstResponseMinutes());
                }
            }
        }
        return new Date(now.getTime() + minutes * 60_000L);
    }

    /**
     * O5b-2:申诉附件走既有媒体管线 —— 只收 OSS 上传登记过的媒体(media-asset / 本站上传路径),
     * 必须是申诉人本人上传、且已通过内容安全审核(APPROVED);统一落成 media-asset:{id}。
     * 开关关闭时带附件直接拒,不静默丢弃。
     */
    private List<String> normalizeAppealAttachments(Long memberId, List<String> attachments) {
        List<String> safe = new ArrayList<>();
        if (attachments == null) return safe;
        java.util.Set<String> requested = new java.util.LinkedHashSet<>();
        for (String item : attachments) {
            if (item != null && !item.trim().isEmpty()) requested.add(item.trim());
        }
        if (requested.isEmpty()) return safe;
        if (!appealAttachmentsEnabled || appealMediaReferenceGuard == null || appMediaAssetMapper == null) {
            throw governanceError(ERROR_APPEAL_ATTACHMENTS_DISABLED, "暂不支持上传申诉附件", HttpStatus.BAD_REQUEST);
        }
        if (requested.size() > MAX_APPEAL_ATTACHMENTS) {
            throw governanceError(ERROR_APPEAL_BAD_REQUEST, "申诉附件最多 " + MAX_APPEAL_ATTACHMENTS + " 个",
                    HttpStatus.BAD_REQUEST);
        }
        for (String reference : requested) {
            String ref;
            try {
                ref = appealMediaReferenceGuard.requireAttachment(memberId, reference, true);
            } catch (ServiceException rejected) {
                throw governanceError(ERROR_APPEAL_ATTACHMENT_INVALID,
                        "申诉附件须为本人上传且已通过内容安全审核的图片", HttpStatus.BAD_REQUEST);
            }
            java.util.Map<String, Object> asset = ref == null || !ref.startsWith("media-asset:") ? null
                    : appMediaAssetMapper.select(Long.valueOf(ref.substring("media-asset:".length())));
            if (asset == null || !"APP".equals(asset.get("ownerType")) || !(asset.get("memberId") instanceof Number)
                    || ((Number) asset.get("memberId")).longValue() != memberId.longValue()
                    || !"APPROVED".equals(asset.get("reviewState"))) {
                throw governanceError(ERROR_APPEAL_ATTACHMENT_INVALID,
                        "申诉附件须为本人上传且已通过内容安全审核的图片", HttpStatus.BAD_REQUEST);
            }
            if (!safe.contains(ref)) safe.add(ref);
        }
        return safe;
    }

    /** 附件并入补充材料:{"evidence":原补充材料,"attachments":[media-asset:id...]},总长仍受上限约束。 */
    private String mergeAttachments(String evidence, List<String> attachmentRefs) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode merged = mapper.createObjectNode();
        try {
            merged.set("evidence", evidence == null ? mapper.nullNode() : mapper.readTree(evidence));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw governanceError(ERROR_APPEAL_BAD_REQUEST, "补充材料格式不正确", HttpStatus.BAD_REQUEST);
        }
        com.fasterxml.jackson.databind.node.ArrayNode refs = merged.putArray("attachments");
        for (String ref : attachmentRefs) refs.add(ref);
        String json = merged.toString();
        if (json.length() > MAX_APPEAL_EVIDENCE_LENGTH) {
            throw governanceError(ERROR_APPEAL_BAD_REQUEST, "补充材料过长", HttpStatus.BAD_REQUEST);
        }
        return json;
    }

    private String normalizeEvidence(String evidenceJson) {
        if (evidenceJson == null || evidenceJson.trim().isEmpty() || "null".equals(evidenceJson.trim())) return null;
        if (evidenceJson.length() > MAX_APPEAL_EVIDENCE_LENGTH) {
            throw governanceError(ERROR_APPEAL_BAD_REQUEST, "补充材料过长", HttpStatus.BAD_REQUEST);
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(evidenceJson);
            if (node == null || !(node.isObject() || node.isArray())) throw new IllegalArgumentException();
            return node.toString();
        } catch (Exception e) {
            throw governanceError(ERROR_APPEAL_BAD_REQUEST, "补充材料格式不正确", HttpStatus.BAD_REQUEST);
        }
    }

    /** 申诉/时间线所需的内容归属与当前是否处于驳回/下架态(status=2)。 */
    private static final class ContentState {
        private final Long ownerId;
        private final boolean down;
        private final boolean deleted;

        private ContentState(Long ownerId, Number status, Integer delFlag) {
            this.ownerId = ownerId;
            this.down = status != null && status.longValue() == 2L;
            this.deleted = delFlag != null && delFlag != 0;
        }
    }

    private ContentState contentState(String bizType, Long bizId) {
        if (AuditTask.BIZ_ACTIVITY.equals(bizType)) {
            CmsActivity activity = cmsActivityMapper.selectCmsActivityById(bizId);
            return activity == null ? null
                    : new ContentState(activity.getMemberId(), activity.getStatus(), activity.getDelFlag());
        }
        if (AuditTask.BIZ_TOPIC.equals(bizType)) {
            CmsTopic topic = cmsTopicMapper.selectCmsTopicById(bizId);
            return topic == null ? null : new ContentState(topic.getMemberId(), topic.getStatus(), topic.getDelFlag());
        }
        if (AuditTask.BIZ_TEMPLATE.equals(bizType)) {
            CmsMemberTemplate template = templateMapper.selectCmsMemberTemplateById(bizId);
            return template == null ? null
                    : new ContentState(template.getMemberId(), template.getStatus(), template.getDelFlag());
        }
        if (AuditTask.BIZ_TEMPLATE_LIBRARY.equals(bizType)) {
            CmsTemplateLibrary library = libraryMapper.selectCmsTemplateLibraryById(bizId);
            return library == null ? null
                    : new ContentState(library.getMemberId(), library.getStatus(), library.getDelFlag());
        }
        return null;
    }

    private void applyCommercialReviewLifecycle(AuditTask task, Integer decision) {
        if (AuditTask.BIZ_TOPIC_SALE_REVIEW.equals(task.getBizType())) {
            if (AuditTask.STATUS_APPROVED.equals(decision)) {
                topicSaleFinalizationService.finalizeForSale(task.getBizId());
                coopInviteServiceProvider.getObject().markTopicDepositsDue(task.getBizId());
            }
            return;
        }
        if (!AuditTask.BIZ_TOPIC_PROJECT_REVIEW.equals(task.getBizType())) return;
        if (AuditTask.STATUS_REJECTED.equals(decision)) {
            if (!topicLifecycleService.transition(task.getBizId(),
                    com.chengyinhub.business.domain.enums.TopicLifecycleState.PENDING_REVIEW,
                    com.chengyinhub.business.domain.enums.TopicLifecycleState.DRAFT)) {
                throw new ServiceException("立项审核驳回后的生命周期 CAS 失败");
            }
            // 编辑窗口以 is_draft 判定完整编辑；退回立项后必须允许作者补正已过期的档期。
            if (cmsTopicMapper.updateTopicDraftState(task.getBizId(), 1) != 1) {
                throw new ServiceException("立项审核驳回后的草稿状态保存失败");
            }
            return;
        }
        if (!AuditTask.STATUS_APPROVED.equals(decision)) return;
        com.chengyinhub.business.domain.enums.TopicLifecycleState target = projectTarget(task.getSnapshotJson());
        if (!topicLifecycleService.transition(task.getBizId(),
                com.chengyinhub.business.domain.enums.TopicLifecycleState.PENDING_REVIEW, target)) {
            throw new ServiceException("立项审核通过后的生命周期 CAS 失败");
        }
        if (cmsTopicMapper.updateTopicDraftState(task.getBizId(), 0) != 1) {
            throw new ServiceException("立项审核通过后的草稿状态保存失败");
        }
        if (target == com.chengyinhub.business.domain.enums.TopicLifecycleState.PRICING) {
            CmsTopic topic = cmsTopicMapper.selectCmsTopicByIdForUpdate(task.getBizId());
            if (topic == null) throw new ServiceException("立项主题已不存在");
            commercialReviewServiceProvider.getObject().submitSaleReviewIfReady(task.getBizId(), topic.getMemberId());
        }
    }

    private static com.chengyinhub.business.domain.enums.TopicLifecycleState projectTarget(String snapshotJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(snapshotJson);
            String value = root == null || root.get("requestedTarget") == null
                    ? null : root.get("requestedTarget").asText();
            com.chengyinhub.business.domain.enums.TopicLifecycleState target =
                    com.chengyinhub.business.domain.enums.TopicLifecycleState.valueOf(value);
            if (target != com.chengyinhub.business.domain.enums.TopicLifecycleState.RECRUITING
                    && target != com.chengyinhub.business.domain.enums.TopicLifecycleState.PRICING) {
                throw new IllegalArgumentException("非法目标");
            }
            return target;
        } catch (Exception e) {
            throw new ServiceException("立项审核快照缺少合法 requestedTarget");
        }
    }

    /**
     * 审核决定 → 内容软封/恢复分发。普通举报内容保持 best-effort；主题与模板是审核发布真源，
     * 状态或发布快照任一步失败都必须抛出，由 {@link #review} 的事务回滚审核决定。
     */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<AppMediaAssetService> appMediaAssetServiceProvider;

    @Autowired
    private com.chengyinhub.business.service.support.AppMediaPublicationGuard mediaPublicationGuard;

    /** O5b-2:申诉附件解析与归属/审核状态核对(与发布期媒体同一套守卫)。 */
    @Autowired(required = false)
    private com.chengyinhub.business.service.support.AppMediaReferenceGuard appealMediaReferenceGuard;
    @Autowired(required = false)
    private com.chengyinhub.business.mapper.AppMediaAssetMapper appMediaAssetMapper;

    /**
     * N-04:IM 消息软封/恢复同步修正未读。只有 正常→屏蔽 / 屏蔽→正常 的真实迁移才调未读
     * (条件更新命中 1 行),重复结论不会减两次;其余起点(如已撤回)保持原来的无条件写状态。
     */
    private void applyImModeration(Long messageId, boolean down) {
        int from = down ? ImMessage.STATUS_NORMAL : ImMessage.STATUS_BLOCKED;
        int to = down ? ImMessage.STATUS_BLOCKED : ImMessage.STATUS_NORMAL;
        if (imMessageMapper.transitionStatus(messageId, from, to) == 1) {
            imConversationMemberMapper.adjustUnreadForModeratedMessage(messageId, down ? -1 : 1);
            return;
        }
        imMessageMapper.updateStatus(messageId, to);
    }

    private void applyModeration(AuditTask task, Integer decision, String reasonText) {
        String bizType = task.getBizType();
        Long bizId = task.getBizId();
        if (bizType == null || bizId == null) return;
        boolean down = AuditTask.STATUS_REJECTED.equals(decision); // 确认违规 → 下架
        boolean up = AuditTask.STATUS_APPROVED.equals(decision);   // 举报无效 → 恢复
        if (!down && !up) return;
        if (AuditTask.BIZ_MEDIA_ASSET.equals(bizType)) {
            if (!com.chengyinhub.common.utils.SecurityUtils.hasPermi(up ? "business:mediaReview:approve" : "business:mediaReview:reject"))
                throw new ServiceException("无媒体审核权限");
            appMediaAssetServiceProvider.getObject().applyAudit(task,up,reasonText);
            return;
        }
        try {
            if (AuditTask.BIZ_COMMUNITY_POST.equals(bizType)) {
                communityPostServiceProvider.getObject().applyAuditDecision(bizId, up);
            } else if (AuditTask.BIZ_COMMUNITY_POST_COMMENT.equals(bizType)) {
                communityPostServiceProvider.getObject().applyCommentAuditDecision(bizId, up);
            } else if (AuditTask.BIZ_SQUARE.equals(bizType)) {
                UmsCreativeSquare s = new UmsCreativeSquare();
                s.setId(bizId);
                // [RV-p6line F3] 举报不成立不动 status=3(随主题联动隐藏):主题恢复时 restoreTopicSyncPosts 放回。
                // ponytail: 先读后写,与主题下架并发时仍可能写回 1;要闭合再改成 where status<>3 的 CAS。
                UmsCreativeSquare cur = up ? umsCreativeSquareService.selectUmsCreativeSquareById(bizId) : null;
                boolean topicHidden = cur != null && Integer.valueOf(3).equals(cur.getStatus());
                // AUDIT-G3-4:「举报不成立」只说明这条举报无效,不能顺手把内容安全下架(文本 BLOCK / 图片回调
                // status=2,或审核驳回 check_status=2)的帖子放回公开。只有门禁送审的安全复核单(auto_gate)
                // 才是针对内容本身的人工复核,通过时可以恢复。
                boolean safetyTakenDown = cur != null && (Integer.valueOf(2).equals(cur.getStatus())
                        || Integer.valueOf(2).equals(cur.getCheckStatus()));
                if (up && safetyTakenDown && !SOURCE_AUTO_GATE.equals(task.getSourceType())) {
                    logger.info("举报不成立但帖子已被内容安全下架,保持下架 bizId={} status={} checkStatus={}",
                            bizId, cur.getStatus(), cur.getCheckStatus());
                    return;
                }
                s.setStatus(down ? Integer.valueOf(2) : (topicHidden ? null : Integer.valueOf(1)));
                s.setCheckStatus(down ? 2 : 1);
                umsCreativeSquareService.updateUmsCreativeSquare(s);
            } else if (AuditTask.BIZ_COMMENT.equals(bizType)) {
                UmsComment c = new UmsComment();
                c.setId(bizId);
                c.setStatus(down ? 2 : 1);
                umsCommentService.updateUmsComment(c);
            } else if (AuditTask.BIZ_CLUB_POST.equals(bizType)) {
                if (down) {
                    clubPostService.deleteClubPostById(bizId); // del_flag=2 软下架
                } else {
                    ClubPost p = new ClubPost();
                    p.setId(bizId);
                    p.setDelFlag(0);
                    clubPostService.updateClubPost(p);
                }
            } else if (AuditTask.BIZ_CLUB_POST_COMMENT.equals(bizType)) {
                if (down) {
                    clubPostCommentService.deleteClubPostCommentById(bizId); // del_flag=2 软下架
                } else {
                    ClubPostComment cc = new ClubPostComment();
                    cc.setId(bizId);
                    cc.setDelFlag(0);
                    clubPostCommentService.updateClubPostComment(cc); // 恢复
                }
            } else if (AuditTask.BIZ_HANGOUT.equals(bizType)) {
                // 每张举报单仍如实记录自己的「无效」结论，但不能借此撤回另一张已成立举报的处罚。
                // 局的恢复只允许走明确的申诉补偿链路，不能由新的举报单隐式完成。
                if (up) return;
                roamHangoutServiceProvider.getObject().moderate(bizId, down);
            } else if (AuditTask.BIZ_TEAM.equals(bizType)) {
                // 举报不成立:不动队伍(审核单结束后 nearby 自然恢复显示);成立:退出公开地图,不解散已组好的队。
                if (up) return;
                playTeamMapper.hideFromPublic(bizId);
                playTeamMapper.expirePendingApplications(bizId);
            } else if (AuditTask.BIZ_IM.equals(bizType)) {
                applyImModeration(bizId, down);
            } else if (AuditTask.BIZ_TOPIC.equals(bizType)) {
                // AUDIT-G2-2:举报不成立 = 内容维持原状(不重发、不进 Beta)。
                // AUDIT-INT-5:举报单已在独立槽,不再与复核上架单合并 ⇒ 举报不成立也不能解除内容安全留置
                // (留置只由内容审核槽的复核单解除,审核员在那张单上看的才是被机审扣下的图)。
                if (up && SOURCE_REPORT.equals(task.getSourceType())) {
                    return;
                }
                if (up) mediaPublicationGuard.requireTopicPublication(bizId);
                if (isTopicBundle(task)) {
                    topicBundleReviewService.applyDecision(bizId, task.getSubmitterId(), task.getSnapshotJson(), up);
                }
                // 主题先发后审(2026-10-20):这里是发布之后的人工结论。驳回(REJECT)→status=2 并下架;
                // 过审(APPROVE)→check_status=1,首发进 Beta,在架态按下方规则保持/恢复。
                if (down) {
                    CmsTopic t = new CmsTopic();
                    t.setId(bizId);
                    t.setStatus(2);
                    t.setCheckStatus(2);
                    t.setReason(reasonText);
                    t.setUserStatus(0);
                    if (cmsTopicMapper.updateCmsTopic(t) != 1) {
                        throw new ServiceException("主题审核状态保存失败");
                    }
                    // [6-09] 驳回 = 不公开:自动同步的广场帖一起收起,否则违规主题名/封面仍在广场可见。
                    cmsTopicServiceProvider.getObject().syncCreativeSquarePostVisibility(bizId, false);
                } else {
                    // 过审自动上架必须过配额闸,否则并发/连续批准可绕过 maxThemes 超发。
                    // 复用 quotaService.lockAndCheckThemeOnline(锁+复查单一实现,返回 err 不抛)——
                    // 不用 runWithThemeOnlineGuard 是因其抛 ServiceException 会把 review() 外层事务毒成 rollback-only。
                    // 行锁持至 review() 事务提交,消除 TOCTOU。
                    CmsTopic exist = cmsTopicMapper.selectCmsTopicById(bizId);
                    Long ownerId = exist != null ? exist.getMemberId() : null;
                    // 无归属会员(官方/存量脏数据)不占任何人的个人配额:保持旧行为直接上架,不许被闸卡死
                    String quotaErr = (ownerId != null && ownerId > 0)
                            ? quotaService.lockAndCheckThemeOnline(ownerId, bizId)
                            : null;
                    String supplyErr = quotaErr == null
                            ? cmsTopicServiceProvider.getObject().topicSupplyAutoOnlineBlockReason(bizId) : null;
                    String onlineErr = quotaErr != null ? quotaErr : supplyErr;
                    boolean alreadyPublished = exist != null && Integer.valueOf(1).equals(exist.getStatus())
                            && exist.getPublishedConfigVersion() != null && exist.getPublishedConfigVersion() > 0L;
                    // AUDIT-G2-1:内容安全下架留置中的主题,人工过审即解除留置并恢复在架(作者此前被拒的自助上架)。
                    boolean releasingHold = TopicContentSafetyHold.isHeld(exist);
                    boolean online = onlineErr == null
                            && (!alreadyPublished || Integer.valueOf(1).equals(exist.getUserStatus()) || releasingHold);
                    if (onlineErr != null) {
                        // 配额满:审核结论照旧生效,但不自动上架;主人稍后走自助上架(同一把锁)
                        logger.warn("审核过审但未自动上架 topicId={} memberId={}: {}", bizId, ownerId, onlineErr);
                    }
                    CmsTopic t = new CmsTopic();
                    t.setId(bizId);
                    t.setStatus(1);
                    t.setCheckStatus(1);
                    // 动态 SQL 仅在 reason != null 时更新；空串用于清掉历史驳回原因。
                    t.setReason("");
                    t.setUserStatus(online ? 1 : 0);
                    // Beta 试玩期(学 Questo):pro 主题过审先进 Beta,对玩家可见可玩但带 Beta 标识,
                    // 作者经 /api/topic/beta/graduate 自决转正式。simple 免审直发不进审核,天然不进 Beta。
                    if (!alreadyPublished) t.setBetaFlag(1);
                    if (cmsTopicMapper.updateCmsTopic(t) != 1) {
                        throw new ServiceException("主题审核状态保存失败");
                    }
                    // AUDIT-INT-8:人工过审 = 抽检通过,清掉先发后审打标的抽检池标记(#377 review_flag)。
                    cmsTopicMapper.updateReviewFlag(bizId, 0);
                    // AUDIT-INT-7:人工审过作者重新提交的版本,解除图片内容安全留置(申诉/撤销到不了这里)。
                    cmsTopicMapper.clearContentSafetyHold(bizId);
                    // LINEAR 同样依赖 published_config_version 作为“已发布”事实；图虽为 null，
                    // 仍必须经统一发布口递增版本，否则审核通过后新局会被判“路线尚未发布”。
                    if (!alreadyPublished) cmsTopicServiceProvider.getObject().publishApprovedRouteSnapshot(bizId);
                    // [6-09] 过审(且真的上架时)= 恢复公开:此前被联动收起的同步帖按原规则放回。
                    cmsTopicServiceProvider.getObject().syncCreativeSquarePostVisibility(bizId, online);
                    // R19-PENDINGSHELL:内容审核通过才是自办活动壳的建壳时点(壳公开可下单,待审期不得存在)。
                    // 幂等:已有壳(含商家壳)不重复建;进商家池主题不建(由中标流程生成)。
                    cmsTopicServiceProvider.getObject().ensureSelfHostActivityShell(bizId);
                }
            } else if (AuditTask.BIZ_ACTIVITY.equals(bizType)) {
                // M0-6 先审后发:过审→上架(status=1/publishStatus=1);驳回→status=2
                CmsActivity a = new CmsActivity();
                a.setId(bizId);
                a.setStatus(down ? 2L : 1L);
                a.setCheckStatus(down ? 2 : 1);
                // 动态 SQL 仅在 reason != null 时更新；过审要显式清掉历史驳回原因。
                a.setReason(down ? reasonText : "");
                a.setPublishStatus(down ? 0L : 1L);
                cmsActivityMapper.updateCmsActivity(a);
                // AUDIT-INT-7:人工审过作者重新提交的版本,解除图片内容安全留置(申诉/撤销到不了这里)。
                if (up) cmsActivityMapper.clearContentSafetyHold(bizId);
            } else if (AuditTask.BIZ_OFFICIAL_EVENT.equals(bizType)) {
                // 官方活动先审后发:过审→上架/排期;驳回→保持不公开
                officialEventServiceProvider.getObject().applyAuditDecision(bizId, up, reasonText, task.getSubmitterId());
            } else if (AuditTask.BIZ_COUPON.equals(bizType)) {
                // 优惠券先发后审抽检:确认违规→平台手动失效(status=3,停止新领取);无问题→不动
                if (up) return;
                com.chengyinhub.business.domain.SmsCoupon c = new com.chengyinhub.business.domain.SmsCoupon();
                c.setId(bizId);
                c.setStatus(3);
                smsCouponServiceProvider.getObject().updateSmsCoupon(c);
            } else if (AuditTask.BIZ_TEMPLATE.equals(bizType) && SOURCE_REPORT.equals(task.getSourceType())) {
                // AUDIT-G2-2:个人玩法举报。不成立不动;成立 = 下架个人玩法及其广场投影(可由作者修改重提)。
                if (up) return;
                recordPreTakedownState(task);
                if (templateMapper.takeDownReported(bizId, reasonText) != 1) {
                    logger.warn("举报成立但个人玩法已不在公开态,跳过下架 templateId={}", bizId);
                }
                libraryMapper.takeDownReportedByMemberTemplate(bizId, reasonText);
            } else if (AuditTask.BIZ_TEMPLATE_LIBRARY.equals(bizType) && SOURCE_REPORT.equals(task.getSourceType())) {
                // AUDIT-G2-2:模板广场举报。不成立不动;成立 = 只下架这条广场记录,不动作者的个人玩法。
                if (up) return;
                recordPreTakedownState(task);
                if (libraryMapper.takeDownReported(bizId, reasonText) != 1) {
                    logger.warn("举报成立但模板广场记录已不在公开态,跳过下架 libraryId={}", bizId);
                }
            } else if (AuditTask.BIZ_TEMPLATE.equals(bizType)) {
                if (up) mediaPublicationGuard.requireTemplate(task.getSubmitterId(), templateMapper.selectCmsMemberTemplateById(bizId), true);
                // 个人玩法审核只更新个人可用状态；模板广场由独立 BIZ_TEMPLATE_LIBRARY 任务裁决。
                com.chengyinhub.business.domain.CmsMemberTemplate template =
                        new com.chengyinhub.business.domain.CmsMemberTemplate();
                template.setId(bizId);
                template.setStatus(down ? 2L : 1L);
                template.setCheckStatus(down ? 2 : 1);
                template.setPublishStatus(down ? 0L : 1L);
                template.setReason(down ? reasonText : "");
                if (templateMapper.applyAuditDecision(template, task.getSubmitterId()) != 1) {
                    throw new ServiceException("模板审核真源更新失败");
                }
            } else if (AuditTask.BIZ_TEMPLATE_LIBRARY.equals(bizType)) {
                if (up) mediaPublicationGuard.requireTemplate(task.getSubmitterId(), libraryMapper.selectCmsTemplateLibraryById(bizId), true);
                com.chengyinhub.business.domain.CmsTemplateLibrary projection =
                        new com.chengyinhub.business.domain.CmsTemplateLibrary();
                projection.setId(bizId);
                projection.setStatus(down ? 2L : 1L);
                projection.setPublishStatus(down ? 0L : 1L);
                projection.setReason(down ? reasonText : "");
                CmsTemplateLibrary current = libraryMapper.selectCmsTemplateLibraryById(bizId);
                projection.setMemberTemplateId(current.getMemberTemplateId());
                if (libraryMapper.applyAuditDecision(projection, task.getSubmitterId()) != 1) {
                    throw new ServiceException("模板库审核投影更新失败");
                }
            } else if (AuditTask.BIZ_MERCHANT_REVIEW.equals(bizType)) {
                String expectedStatus = SOURCE_REPORT.equals(task.getSourceType())
                        ? com.chengyinhub.business.domain.MerchantReview.STATUS_VISIBLE
                        : com.chengyinhub.business.domain.MerchantReview.STATUS_PENDING_REVIEW;
                merchantReviewServiceProvider.getObject().moderateFromAudit(
                        bizId,
                        expectedStatus,
                        down ? com.chengyinhub.business.domain.MerchantReview.STATUS_HIDDEN
                                : com.chengyinhub.business.domain.MerchantReview.STATUS_VISIBLE,
                        reasonText);
            }
        } catch (Exception e) {
            if (AuditTask.BIZ_TOPIC.equals(bizType) && isTopicBundle(task)
                    && e instanceof IllegalStateException && e.getMessage() != null
                    && e.getMessage().startsWith("提交内容已变化")) {
                throw new ServiceException(e.getMessage(), HttpStatus.CONFLICT);
            }
            if (AuditTask.BIZ_COMMUNITY_POST.equals(bizType)
                    || AuditTask.BIZ_COMMUNITY_POST_COMMENT.equals(bizType)
                    || AuditTask.BIZ_TEMPLATE.equals(bizType)
                    || AuditTask.BIZ_TEMPLATE_LIBRARY.equals(bizType)
                    || AuditTask.BIZ_TOPIC.equals(bizType)
                    || AuditTask.BIZ_OFFICIAL_EVENT.equals(bizType)
                    || AuditTask.BIZ_MERCHANT_REVIEW.equals(bizType)
                    || AuditTask.BIZ_HANGOUT.equals(bizType)
                    || AuditTask.BIZ_TEAM.equals(bizType)) {
                throw e instanceof ServiceException
                        ? (ServiceException) e
                        : new ServiceException(AuditTask.BIZ_COMMUNITY_POST.equals(bizType)
                                ? "社区帖文审核状态同步失败"
                                : AuditTask.BIZ_COMMUNITY_POST_COMMENT.equals(bizType)
                                ? "社区评论审核状态同步失败"
                                : AuditTask.BIZ_TOPIC.equals(bizType) ? "主题审核状态同步失败"
                                : AuditTask.BIZ_OFFICIAL_EVENT.equals(bizType) ? "官方活动审核状态同步失败"
                                : AuditTask.BIZ_MERCHANT_REVIEW.equals(bizType)
                                        ? "评价审核状态同步失败" : AuditTask.BIZ_HANGOUT.equals(bizType)
                                                ? "组局处置失败，审核未提交，请重试" : AuditTask.BIZ_TEAM.equals(bizType)
                                                        ? "队伍处置失败，审核未提交，请重试" : "模板审核状态同步失败")
                                .setDetailMessage(e.getMessage());
            }
            // 其余举报内容保持 best-effort:失败不影响已提交的审核记录,但必须留痕——静默吞会造成
            // 「审核显示已通过、内容状态却没变」且无任何线索(配额闸引入锁/查后风险面更大)
            logger.warn("applyModeration best-effort 失败 bizType={} bizId={} decision={}", bizType, bizId, decision, e);
        }
    }

    private boolean isTopicBundle(AuditTask task) {
        if (task == null || !AuditTask.BIZ_TOPIC.equals(task.getBizType())) return false;
        if ("topic_bundle_v2".equals(task.getSourceType())) return true;
        // 申诉单沿用被驳回任务的快照：原单是主题包则按主题包复核(同一份快照、同一套内容 CAS)。
        return SOURCE_APPEAL.equals(task.getSourceType()) && topicBundleSnapshot(task.getSnapshotJson()) != null;
    }

    /** 主题包 v2 快照(schemaVersion=2 且 bizType=TOPIC)，其他快照返回 null。 */
    private static com.fasterxml.jackson.databind.JsonNode topicBundleSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isEmpty()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(snapshotJson);
            return root != null && root.path("schemaVersion").asInt() == 2
                    && AuditTask.BIZ_TOPIC.equals(root.path("bizType").asText())
                    && root.path("topic").isObject() ? root : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public AuditTask selectAuditTaskById(Long id) {
        return auditTaskMapper.selectAuditTaskById(id);
    }

    /** 工作台列表:按处理时限排队(最紧急在前,无 deadline 的历史单垫底)。 */
    @Override
    public List<AuditTask> selectAuditTaskList(AuditTask auditTask) {
        return auditTaskMapper.selectAuditTaskQueue(auditTask);
    }

    @Override
    public List<AuditTask> selectWorkbenchQueueWithGuardScope(AuditTask filter, List<Long> guardTopicIds) {
        // Caller resolves authenticated scope before starting PageHelper; never read filter.params as scope.
        return auditTaskMapper.selectWorkbenchQueueWithGuardScope(filter,
                guardTopicIds == null ? java.util.List.of() : guardTopicIds);
    }

    /**
     * 认领待审单。认领只是协作提示,不是锁:review() 不校验 current_assignee,
     * 其他有 business:audit:review 权限的审核员仍可直接处理已认领单(保持既有行为,
     * 避免认领人离岗时单子无法流转);处理后 current_assignee 改写为实际处理人。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void claim(Long taskId, Long operatorId) {
        if (taskId == null || operatorId == null) {
            throw new IllegalArgumentException("认领参数不完整");
        }
        AuditTask candidate = auditTaskMapper.selectAuditTaskById(taskId);
        if (candidate != null && isGuardType(candidate.getBizType())) {
            requireGuardReviewScope(candidate, operatorId);
            throw governanceError(ERROR_APPEAL_NOT_ELIGIBLE, "守卫申诉认领队列未启用，请使用已授权的审核处理入口", HttpStatus.CONFLICT);
        }
        if (auditTaskMapper.claimAuditTask(taskId, operatorId, DateUtils.getNowDate()) != 1) {
            throw new ServiceException("该任务已被认领或已处理");
        }
        // 申诉审核单被认领 = 申诉进入复核中(SUBMITTED → REVIEWING)，作者时间线可见。
        AuditTask claimed = auditTaskMapper.selectAuditTaskById(taskId);
        if (claimed != null && SOURCE_APPEAL.equals(claimed.getSourceType())) {
            auditAppealMapper.markReviewing(taskId);
        }
    }

    /** 新单处理时限:submittedAt + slaHours(bizType, riskLevel)。 */
    Date deadlineAt(String bizType, String riskLevel, Date submittedAt) {
        return submittedAt == null ? null
                : new Date(submittedAt.getTime() + slaHours(bizType, riskLevel) * 3600_000L);
    }

    int slaHours(String bizType, String riskLevel) {
        if (AuditTask.BIZ_TOPIC_PROJECT_REVIEW.equals(bizType) || AuditTask.BIZ_TOPIC_SALE_REVIEW.equals(bizType)) {
            return activityTopicDeadlineHours;
        }
        if ("HIGH".equals(riskLevel)) {
            return highRiskDeadlineHours;
        }
        if (AuditTask.BIZ_ACTIVITY.equals(bizType) || AuditTask.BIZ_TOPIC.equals(bizType)) {
            return activityTopicDeadlineHours;
        }
        if (AuditTask.BIZ_COMMUNITY_POST.equals(bizType) || AuditTask.BIZ_SQUARE.equals(bizType)) {
            return communityDeadlineHours;
        }
        return defaultDeadlineHours;
    }

    private void appendDecisionAudit(AuditTask task, Long operatorId, AuditReviewDTO request,
                                     AuditRecord record, Date now) {
        BusinessAuditEventMapper auditMapper = requireBusinessAuditMapper();
        String requestId = request.getRequestId();
        BusinessAuditEvent event = decisionAuditEvent(task, operatorId, request.getActorRoleCode(),
                "AUDIT_DECISION_" + statusToAction(request.getStatus()), requestId,
                record.getId(), request.getReasonText(),
                stateJson(task.getStatus(), task.getVersion()),
                decisionStateJson(task, request), now);
        if (auditMapper.insertBusinessAuditEvent(event) != 1) {
            throw new ServiceException("审批审计写入失败", HttpStatus.ERROR);
        }
    }

    private String decisionStateJson(AuditTask task, AuditReviewDTO request) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode state = (com.fasterxml.jackson.databind.node.ObjectNode)
                    mapper.readTree(stateJson(request.getStatus(), task.getVersion() + 1));
            boolean applied = itemProblemsEnabled && auditProblemService != null;
            state.put("itemProblemsApplied", applied);
            if (applied) state.set("problems", mapper.valueToTree(AuditProblemService.intent(request.getProblems())));
            return mapper.writeValueAsString(state);
        } catch (java.io.IOException e) {
            throw new ServiceException("审核意图保存失败", HttpStatus.ERROR);
        }
    }

    @Override
    public java.util.Map<String, Object> itemProblemReceipt(Long taskId, Long viewerId, String requestId) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("enabled", itemProblemsEnabled && auditProblemService != null);
        result.put("state", "NOT_FOUND");
        if (requestId == null || requestId.trim().isEmpty()) return result;
        if (requestId.length() > 64) throw new IllegalArgumentException("requestId 长度不能超过 64");
        // Existing detail permission is enforced by the controller; receipt belongs to the original reviewer.
        List<BusinessAuditEvent> events = requireBusinessAuditMapper().selectByTarget("AUDIT_TASK", taskId);
        if (events == null) return result;
        for (BusinessAuditEvent event : events) {
            if (!"PLATFORM".equals(event.getDomainType()) || !java.util.Objects.equals(taskId, event.getDomainId())
                    || !"AUDIT_DECISION_REJECT".equals(event.getAction()) || !requestId.equals(event.getRequestId())
                    || !java.util.Objects.equals(viewerId, event.getActorMemberId())) continue;
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode after = mapper.readTree(event.getAfterJson());
                if (!after.has("itemProblemsApplied")) { result.put("state", "LEGACY"); return result; }
                com.fasterxml.jackson.databind.JsonNode before = mapper.readTree(event.getBeforeJson());
                if (!after.path("itemProblemsApplied").isBoolean() || !before.path("version").isIntegralNumber()
                        || !after.path("status").isIntegralNumber() || after.path("status").asInt() != AuditTask.STATUS_REJECTED
                        || (after.path("itemProblemsApplied").asBoolean() && !after.path("problems").isArray())) {
                    result.put("state", "UNKNOWN"); return result;
                }
                result.put("taskId", taskId.toString());
                result.put("requestId", requestId);
                result.put("expectedVersion", before.path("version").asInt());
                result.put("status", after.path("status").asInt());
                result.put("reasonText", event.getReason());
                result.put("applied", after.path("itemProblemsApplied").asBoolean());
                result.put("problems", after.path("problems"));
                if (after.path("itemProblemsApplied").asBoolean()) {
                    AuditTask task = auditTaskMapper.selectAuditTaskById(taskId);
                    ContentState owner = task == null ? null : contentState(task.getBizType(), task.getBizId());
                    if (owner == null || auditProblemService == null) { result.put("state", "UNKNOWN"); return result; }
                    List<AuditReviewDTO.ProblemItem> persisted = new ArrayList<>();
                    for (com.chengyinhub.business.domain.AuditProblem row : auditProblemService.listForOwner(taskId, owner.ownerId)) {
                        if (!java.util.Objects.equals(event.getRelationId(), row.getRecordId())) continue;
                        AuditReviewDTO.ProblemItem item = new AuditReviewDTO.ProblemItem();
                        item.setItemType(row.getItemType()); item.setItemId(row.getItemId());
                        item.setReasonCode(row.getReasonCode()); item.setReasonText(row.getReasonText());
                        persisted.add(item);
                    }
                    if (!mapper.valueToTree(AuditProblemService.intent(persisted)).equals(after.path("problems"))) {
                        result.put("state", "UNKNOWN"); return result;
                    }
                }
                result.put("state", "FOUND");
                return result;
            } catch (java.io.IOException e) {
                throw new ServiceException("审核幂等回执损坏", HttpStatus.CONFLICT);
            }
        }
        return result;
    }

    private BusinessAuditEvent decisionAuditEvent(AuditTask task, Long operatorId, String actorRoleCode, String action,
                                                   String requestId, Long relationId, String reason,
                                                   String beforeJson, String afterJson, Date now) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("PLATFORM");
        event.setDomainId(task.getId());
        event.setOwnerMemberId(task.getSubmitterId() == null ? 0L : task.getSubmitterId());
        event.setActorType(BusinessAuditEvent.ACTOR_TYPE_SYS_USER);
        event.setActorMemberId(operatorId);
        event.setActorRoleCode(actorRoleCode == null || actorRoleCode.trim().isEmpty()
                ? "SYS_USER" : actorRoleCode.trim());
        event.setAction(action);
        event.setTargetType("AUDIT_TASK");
        event.setTargetId(task.getId());
        event.setRelationId(relationId);
        event.setBeforeJson(beforeJson);
        event.setAfterJson(afterJson);
        event.setReason(reason);
        event.setRequestId(requestId);
        event.setCreateTime(now);
        return event;
    }

    private AuditRevertResultVO replayResult(Long taskId, String requestId, BusinessAuditEvent replay) {
        try {
            com.fasterxml.jackson.databind.JsonNode after =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(replay.getAfterJson());
            AuditRevertResultVO result = new AuditRevertResultVO();
            result.setTaskId(taskId);
            result.setStatus(after.get("status").asInt());
            result.setVersion(after.get("version").asInt());
            result.setRevertedRecordId(replay.getRelationId());
            result.setRequestId(requestId);
            return result;
        } catch (Exception e) {
            throw new ServiceException("撤销幂等回执损坏", HttpStatus.CONFLICT);
        }
    }

    private String reviewRequestId(String supplied, Long taskId, Integer version, Integer status) {
        if (supplied != null && !supplied.trim().isEmpty()) {
            String normalized = supplied.trim();
            if (normalized.length() > 64) throw new IllegalArgumentException("requestId 长度不能超过 64");
            return normalized;
        }
        return "audit-review:" + taskId + ":" + version + ":" + status;
    }

    private void requireExactReviewReplay(BusinessAuditEvent replay, Long operatorId,
                                          Integer expectedVersion, AuditReviewDTO request) {
        if (!operatorId.equals(replay.getActorMemberId())) {
            throw governanceError(ERROR_REVIEW_FORBIDDEN, "只能由原审批人重放审核请求", HttpStatus.FORBIDDEN);
        }
        try {
            com.fasterxml.jackson.databind.JsonNode before =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(replay.getBeforeJson());
            com.fasterxml.jackson.databind.JsonNode after =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(replay.getAfterJson());
            if (after.path("itemProblemsApplied").asBoolean()) {
                com.fasterxml.jackson.databind.JsonNode intent = new com.fasterxml.jackson.databind.ObjectMapper()
                        .valueToTree(AuditProblemService.intent(request.getProblems()));
                if (!intent.equals(after.get("problems"))) throw governanceError(ERROR_REVIEW_KEY_REUSED,
                        "requestId 已用于其他逐项审核请求", HttpStatus.CONFLICT);
            } else if (!after.has("itemProblemsApplied") && itemProblemsEnabled
                    && request.getProblems() != null && !request.getProblems().isEmpty()) {
                throw governanceError(ERROR_REVIEW_KEY_REUSED, "旧审核回执无法证明逐项意图，请核对原审核记录", HttpStatus.CONFLICT);
            }
            boolean sameReason = replay.getReason() == null
                    ? request.getReasonText() == null : replay.getReason().equals(request.getReasonText());
            if (!sameReason || !before.hasNonNull("version")
                    || !expectedVersion.equals(before.get("version").asInt())
                    || !after.hasNonNull("status")
                    || !request.getStatus().equals(after.get("status").asInt())) {
                throw governanceError(ERROR_REVIEW_KEY_REUSED,
                        "requestId 已用于其他审核请求", HttpStatus.CONFLICT);
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw governanceError(ERROR_REVIEW_KEY_REUSED,
                    "审核幂等回执损坏", HttpStatus.CONFLICT);
        }
    }

    private boolean replayMatches(BusinessAuditEvent replay, Integer expectedVersion, String reason) {
        if (replay.getReason() == null ? reason != null : !replay.getReason().equals(reason)) return false;
        try {
            com.fasterxml.jackson.databind.JsonNode before =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(replay.getBeforeJson());
            return before.hasNonNull("version") && expectedVersion.equals(before.get("version").asInt());
        } catch (Exception e) {
            return false;
        }
    }

    private AuditTask applicantTask(AuditTask source) {
        AuditTask safe = new AuditTask();
        safe.setId(source.getId());
        safe.setBizType(source.getBizType());
        safe.setBizId(source.getBizId());
        safe.setSubmitterId(source.getSubmitterId());
        safe.setStatus(source.getStatus());
        safe.setSubmittedAt(source.getSubmittedAt());
        safe.setVersion(source.getVersion());
        return safe;
    }

    private List<AuditRecord> applicantTimeline(List<AuditRecord> source) {
        List<AuditRecord> safe = new ArrayList<>();
        if (source == null) return safe;
        for (AuditRecord record : source) {
            if (AuditRecord.ACTION_REPORT.equals(record.getAction())) continue;
            if (!AuditRecord.ACTION_SUBMIT.equals(record.getAction())
                    && !AuditRecord.ACTION_APPROVE.equals(record.getAction())
                    && !AuditRecord.ACTION_REJECT.equals(record.getAction())
                    && !AuditRecord.ACTION_REVERT.equals(record.getAction())) continue;
            AuditRecord item = new AuditRecord();
            item.setId(record.getId());
            item.setTaskId(record.getTaskId());
            item.setAction(record.getAction());
            item.setOperatorId(record.getOperatorId());
            item.setReasonText(record.getReasonText());
            item.setReasonCodesJson(record.getReasonCodesJson());
            item.setRevertedRecordId(record.getRevertedRecordId());
            item.setCreateTime(record.getCreateTime());
            safe.add(item);
        }
        Collections.reverse(safe);
        return safe;
    }

    private ServiceException governanceError(String errorCode, String message, int httpStatus) {
        return new ServiceException(message, httpStatus).setDetailMessage(errorCode);
    }

    private AuditRecord latestDecision(Long taskId, Integer status) {
        AuditRecord query = new AuditRecord();
        query.setTaskId(taskId);
        return latestDecision(auditRecordMapper.selectAuditRecordList(query), status);
    }

    private AuditRecord latestDecision(List<AuditRecord> records, Integer status) {
        String expectedAction = AuditTask.STATUS_APPROVED.equals(status)
                ? AuditRecord.ACTION_APPROVE
                : AuditTask.STATUS_REJECTED.equals(status) ? AuditRecord.ACTION_REJECT : null;
        if (expectedAction == null || records == null) return null;
        for (AuditRecord record : records) {
            if (expectedAction.equals(record.getAction())) return record;
        }
        return null;
    }

    private void reverseDecisionEffects(AuditTask task, AuditRecord decision) {
        if (isContentDecisionType(task.getBizType())) {
            reverseContentDecision(task, decision);
            return;
        }
        if (AuditTask.STATUS_REJECTED.equals(task.getStatus())
                && AuditTask.BIZ_MERCHANT_REVIEW.equals(task.getBizType())) {
            String targetStatus = SOURCE_REPORT.equals(task.getSourceType())
                    ? com.chengyinhub.business.domain.MerchantReview.STATUS_VISIBLE
                    : com.chengyinhub.business.domain.MerchantReview.STATUS_PENDING_REVIEW;
            merchantReviewServiceProvider.getObject().moderateFromAudit(
                    task.getBizId(),
                    com.chengyinhub.business.domain.MerchantReview.STATUS_HIDDEN,
                    targetStatus,
                    null);
        }
        // APPROVE 的既有内容审核路径只恢复/保持可见；撤回到待审不应反向下架。
    }

    /**
     * AUDIT-INT-4:举报成立下架模板/广场条目前,把下架前的公开态并入举报单快照(preTakedown)。
     * 只记会被 takeDownReported 动到的行(status=1);申诉单复制这份快照,申诉成立据此原样恢复。
     */
    private void recordPreTakedownState(AuditTask task) {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode pre = json.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode libraries = pre.putArray("libraries");
        if (AuditTask.BIZ_TEMPLATE.equals(task.getBizType())) {
            CmsMemberTemplate template = templateMapper.selectCmsMemberTemplateById(task.getBizId());
            if (template == null || !Long.valueOf(1L).equals(template.getStatus())) return;
            com.fasterxml.jackson.databind.node.ObjectNode row = pre.putObject("template");
            row.put("status", template.getStatus());
            row.put("checkStatus", template.getCheckStatus());
            row.put("publishStatus", template.getPublishStatus());
            row.put("reason", template.getReason() == null ? "" : template.getReason());
            List<CmsTemplateLibrary> projections = libraryMapper.selectByMemberTemplateForUpdate(task.getBizId());
            if (projections != null) for (CmsTemplateLibrary projection : projections) {
                if (Integer.valueOf(0).equals(projection.getDelFlag()) && Long.valueOf(1L).equals(projection.getStatus())) {
                    libraries.add(libraryState(json, projection));
                }
            }
        } else {
            CmsTemplateLibrary library = libraryMapper.selectCmsTemplateLibraryById(task.getBizId());
            if (library == null || !Long.valueOf(1L).equals(library.getStatus())) return;
            libraries.add(libraryState(json, library));
        }
        com.fasterxml.jackson.databind.node.ObjectNode snapshot;
        try {
            com.fasterxml.jackson.databind.JsonNode parsed = task.getSnapshotJson() == null ? null
                    : json.readTree(task.getSnapshotJson());
            if (parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
                snapshot = (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
            } else {
                snapshot = json.createObjectNode();
                if (task.getSnapshotJson() != null) snapshot.put("_raw", task.getSnapshotJson());
            }
            snapshot.set("preTakedown", pre);
            String merged = json.writeValueAsString(snapshot);
            if (auditTaskMapper.updateTaskSnapshot(task.getId(), merged) != 1) {
                throw new ServiceException("举报下架前状态保存失败");
            }
            task.setSnapshotJson(merged);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException("举报下架前状态序列化失败");
        }
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode libraryState(
            com.fasterxml.jackson.databind.ObjectMapper json, CmsTemplateLibrary library) {
        com.fasterxml.jackson.databind.node.ObjectNode row = json.createObjectNode();
        row.put("id", library.getId());
        row.put("status", library.getStatus());
        row.put("publishStatus", library.getPublishStatus());
        row.put("reason", library.getReason() == null ? "" : library.getReason());
        return row;
    }

    /** 快照里的举报下架前状态(AUDIT-INT-4);普通驳回的申诉没有它。 */
    private static com.fasterxml.jackson.databind.JsonNode preTakedownState(String snapshotJson) {
        if (snapshotJson == null) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode pre = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(snapshotJson).path("preTakedown");
            return pre.isObject() ? pre : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 举报下架申诉成立:从举报下架态原样恢复到 preTakedown(原来上架的回到上架,未上架的保持未上架)。
     * 主体行必须仍停在举报下架态,否则视为内容已变化返回 false;随个人玩法一起下架的广场投影尽量恢复。
     */
    private boolean restoreReportTakedown(AuditTask task, TemplateReviewLock lock,
                                          com.fasterxml.jackson.databind.JsonNode pre) {
        if (AuditTask.BIZ_TEMPLATE.equals(task.getBizType())) {
            com.fasterxml.jackson.databind.JsonNode row = pre.path("template");
            if (!row.isObject() || lock.template == null || lock.template.getMemberId() == null) return false;
            if (templateMapper.restoreReportTakedown(task.getBizId(), lock.template.getMemberId(),
                    row.path("status").asLong(), row.path("checkStatus").asInt(),
                    row.path("publishStatus").asLong(), row.path("reason").asText("")) != 1) {
                return false;
            }
            for (com.fasterxml.jackson.databind.JsonNode library : pre.path("libraries")) {
                libraryMapper.restoreReportTakedown(library.path("id").asLong(), library.path("status").asLong(),
                        library.path("publishStatus").asLong(), library.path("reason").asText(""));
            }
            return true;
        }
        for (com.fasterxml.jackson.databind.JsonNode library : pre.path("libraries")) {
            if (library.path("id").asLong() == task.getBizId()) {
                return libraryMapper.restoreReportTakedown(task.getBizId(), library.path("status").asLong(),
                        library.path("publishStatus").asLong(), library.path("reason").asText("")) == 1;
            }
        }
        return false;
    }

    /** AUDIT-INT-7:图片内容安全留置中的活动/主题,申诉与撤销都不能把它恢复,只能换图后重新人审。 */
    private void requireNoContentSafetyHold(String bizType, Long bizId, String message) {
        Integer hold = AuditTask.BIZ_ACTIVITY.equals(bizType) ? cmsActivityMapper.selectContentSafetyHold(bizId)
                : AuditTask.BIZ_TOPIC.equals(bizType) ? cmsTopicMapper.selectContentSafetyHold(bizId) : null;
        if (Integer.valueOf(1).equals(hold)) {
            throw governanceError(ERROR_CONTENT_SAFETY_HOLD, message, HttpStatus.CONFLICT);
        }
    }

    private boolean isTerminalDecision(Integer status) {
        return AuditTask.STATUS_APPROVED.equals(status) || AuditTask.STATUS_REJECTED.equals(status);
    }

    private boolean isRevertSupported(AuditTask task) {
        // 仅开放具备领域行锁 + 目标状态 CAS 的补偿路径；其余类型在补齐目标状态 CAS 前 fail closed。
        if (task == null) return false;
        if (AuditTask.BIZ_MERCHANT_REVIEW.equals(task.getBizType())) {
            return AuditTask.STATUS_REJECTED.equals(task.getStatus());
        }
        // 申诉裁决的真源是申诉单(每内容每人一次)，撤销会让申诉单与内容状态分叉，不开放。
        // 举报单/内容安全复核上架单裁决的是已公开(或被机审扣下)的内容，不是作者提交的待审版本：
        // reopenContent 写回的「待审」不是它们的决定前状态，撤销后再判也回不到原状(举报不成立早退不动内容)，不开放。
        // 先发后审抽检单(post_review_flag)决定前内容已在架：撤销「通过」会把在架内容下成待审，只开放撤销「驳回」。
        String source = task.getSourceType();
        if (SOURCE_APPEAL.equals(source) || SOURCE_REPORT.equals(source)
                || TopicContentSafetyHold.SOURCE_RELIST.equals(source)) {
            return false;
        }
        if (SOURCE_POST_REVIEW_FLAG.equals(source) && AuditTask.STATUS_APPROVED.equals(task.getStatus())) {
            return false;
        }
        return isContentDecisionType(task.getBizType()) && isTerminalDecision(task.getStatus());
    }

    /** 审核决定直接落在内容状态列上、可做目标状态 CAS 的内容类型(撤销与申诉共用)。 */
    private static boolean isContentDecisionType(String bizType) {
        return AuditTask.BIZ_ACTIVITY.equals(bizType) || AuditTask.BIZ_TOPIC.equals(bizType)
                || AuditTask.BIZ_TEMPLATE.equals(bizType) || AuditTask.BIZ_TEMPLATE_LIBRARY.equals(bizType);
    }

    /**
     * 撤销内容类审核决定：内容必须仍停在该决定写下的状态、且之后没有新一轮审核，才 CAS 回待审；
     * 否则抛 409 让 revert() 事务连同任务 CAS 一起回滚。
     */
    private void reverseContentDecision(AuditTask task, AuditRecord decision) {
        AuditTask latest = latestTask(task.getBizType(), task.getBizId());
        if (latest == null || !task.getId().equals(latest.getId())) {
            throw governanceError(ERROR_REVERT_STALE, "内容已进入新一轮审核，不能撤销", HttpStatus.CONFLICT);
        }
        requireNoContentSafetyHold(task.getBizType(), task.getBizId(),
                "内容图片未通过内容安全检测，不能撤销恢复，请作者更换图片后重新提交审核");
        if (AuditTask.STATUS_APPROVED.equals(task.getStatus())) requireApprovalReversible(task);
        if (!reopenContent(task, task.getStatus(), decision)) {
            throw governanceError(ERROR_REVERT_STALE, "内容状态已变化，不能撤销", HttpStatus.CONFLICT);
        }
    }

    /** 通过决定已产生无法 CAS 回收的下游事实时拒绝撤销，交给下架流程处理。 */
    private void requireApprovalReversible(AuditTask task) {
        Long bizId = task.getBizId();
        if (AuditTask.BIZ_TOPIC.equals(task.getBizType())) {
            List<Long> shells = cmsActivityMapper.selectActiveActivityIdsByTopicId(bizId);
            if (shells != null && !shells.isEmpty()) {
                throw governanceError(ERROR_REVERT_STATE, "主题过审后已生成可售活动，不能撤销通过，请走下架流程",
                        HttpStatus.CONFLICT);
            }
            com.fasterxml.jackson.databind.JsonNode bundle = isTopicBundle(task)
                    ? topicBundleSnapshot(task.getSnapshotJson()) : null;
            if (bundle != null) {
                for (com.fasterxml.jackson.databind.JsonNode template : bundle.path("bundledTemplates")) {
                    if (template.path("bundledDraft").asBoolean(false)) {
                        throw governanceError(ERROR_REVERT_STATE, "过审已同时发布随主题玩法，不能撤销通过，请走下架流程",
                                HttpStatus.CONFLICT);
                    }
                }
            }
        } else if (AuditTask.BIZ_TEMPLATE.equals(task.getBizType())) {
            List<CmsTemplateLibrary> projections = libraryMapper.selectByMemberTemplateForUpdate(bizId);
            if (projections != null) {
                for (CmsTemplateLibrary row : projections) {
                    if (Integer.valueOf(0).equals(row.getDelFlag()) && Long.valueOf(1L).equals(row.getStatus())) {
                        throw governanceError(ERROR_REVERT_STATE, "该玩法已在模板广场上架，不能撤销通过，请先处理模板广场条目",
                                HttpStatus.CONFLICT);
                    }
                }
            }
        }
    }

    /**
     * 把内容从审核决定写下的状态 CAS 回待审(撤销与申诉成立共用)。返回 false = 内容已变化。
     * decision 非空(撤销)时，活动额外要求决定后未被编辑；主题包按快照 configVersion、玩法/模板广场按审核快照比对。
     */
    private boolean reopenContent(AuditTask task, Integer decidedStatus, AuditRecord decision) {
        boolean approved = AuditTask.STATUS_APPROVED.equals(decidedStatus);
        String bizType = task.getBizType();
        Long bizId = task.getBizId();
        if (AuditTask.BIZ_ACTIVITY.equals(bizType)) {
            return cmsActivityMapper.reopenAuditDecision(bizId, approved ? 1L : 2L, approved ? 1 : 2,
                    approved ? 1L : 0L, decision == null ? null : decision.getCreateTime()) == 1;
        }
        if (AuditTask.BIZ_TOPIC.equals(bizType)) {
            com.fasterxml.jackson.databind.JsonNode bundle = isTopicBundle(task)
                    ? topicBundleSnapshot(task.getSnapshotJson()) : null;
            com.fasterxml.jackson.databind.JsonNode version = bundle == null ? null
                    : bundle.path("topic").path("configVersion");
            Long configVersion = version != null && version.isIntegralNumber() ? version.longValue() : null;
            if (cmsTopicMapper.reopenAuditDecision(bizId, approved ? 1 : 2, approved ? 1 : 2, configVersion) != 1) {
                return false;
            }
            // 撤销通过 = 回到待审不公开：同步帖一起收起(驳回态本就已收起)。
            if (approved) cmsTopicServiceProvider.getObject().syncCreativeSquarePostVisibility(bizId, false);
            return true;
        }
        if (AuditTask.BIZ_TEMPLATE.equals(bizType)) {
            List<CmsMemberTemplate> rows = templateMapper.selectCmsMemberTemplateByIdsForUpdate(
                    Collections.singletonList(bizId));
            CmsMemberTemplate template = rows == null || rows.isEmpty() ? null : rows.get(0);
            if (template == null || template.getMemberId() == null || !TemplateAuditSnapshot.matches(
                    task.getSnapshotJson(), TemplateAuditSnapshot.personal(template))) {
                return false;
            }
            return templateMapper.reopenAuditDecision(bizId, template.getMemberId(), approved ? 1L : 2L,
                    approved ? 1 : 2, approved ? 1L : 0L) == 1;
        }
        if (AuditTask.BIZ_TEMPLATE_LIBRARY.equals(bizType)) {
            CmsTemplateLibrary hint = libraryMapper.selectCmsTemplateLibraryById(bizId);
            if (hint == null || hint.getMemberTemplateId() == null) return false;
            templateMapper.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(hint.getMemberTemplateId()));
            CmsTemplateLibrary row = null;
            List<CmsTemplateLibrary> projections = libraryMapper.selectByMemberTemplateForUpdate(hint.getMemberTemplateId());
            if (projections != null) {
                for (CmsTemplateLibrary projection : projections) {
                    if (bizId.equals(projection.getId())) row = projection;
                }
            }
            if (row == null || row.getMemberId() == null
                    || !TemplateAuditSnapshot.matches(task.getSnapshotJson(), TemplateAuditSnapshot.market(row))) {
                return false;
            }
            return libraryMapper.reopenAuditDecision(bizId, row.getMemberId(), row.getMemberTemplateId(),
                    approved ? 1L : 2L, approved ? 1L : 0L) == 1;
        }
        return false;
    }

    /** 同内容最新一张审核单,跳过独立槽里未结的举报单(AUDIT-INT-5:新举报不改变「最近一次审核结论」)。 */
    private AuditTask latestContentTask(String bizType, Long bizId) {
        AuditTask query = new AuditTask();
        query.setBizType(bizType);
        query.setBizId(bizId);
        List<AuditTask> tasks = auditTaskMapper.selectAuditTaskList(query);
        if (tasks != null) for (AuditTask task : tasks) {
            if (!task.isOpenReport()) return task;
        }
        return null;
    }

    /** 同内容最新一张审核单(selectAuditTaskList 的 get(0) = 最新)。 */
    private AuditTask latestTask(String bizType, Long bizId) {
        AuditTask query = new AuditTask();
        query.setBizType(bizType);
        query.setBizId(bizId);
        List<AuditTask> tasks = auditTaskMapper.selectAuditTaskList(query);
        return tasks == null || tasks.isEmpty() ? null : tasks.get(0);
    }

    private com.chengyinhub.business.domain.AuditAppeal lockAppeal(AuditTask task) {
        com.chengyinhub.business.domain.AuditAppeal appeal = auditAppealMapper.selectByTaskIdForUpdate(task.getId());
        if (appeal == null || !task.getBizType().equals(appeal.getBizType()) || !task.getBizId().equals(appeal.getBizId())) {
            throw governanceError(ERROR_APPEAL_STALE, "申诉单不存在或与审核任务不一致", HttpStatus.CONFLICT);
        }
        return appeal;
    }

    private String requireStableKey(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw governanceError(ERROR_REVERT_BAD_REQUEST, "缺少 idempotencyKey", HttpStatus.BAD_REQUEST);
        }
        String normalized = value.trim();
        if (normalized.length() > 64) {
            throw governanceError(ERROR_REVERT_BAD_REQUEST, "idempotencyKey 长度不能超过 64", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private String requireReason(String value, String message) {
        String normalized = trimReason(value);
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String trimReason(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("理由长度不能超过 200 字");
        }
        return normalized;
    }

    private BusinessAuditEventMapper requireBusinessAuditMapper() {
        if (businessAuditEventMapper == null) {
            throw new ServiceException("操作审计组件不可用", HttpStatus.ERROR);
        }
        return businessAuditEventMapper;
    }

    private String stateJson(Integer status, Integer version) {
        return "{\"status\":" + status + ",\"version\":" + version + "}";
    }

    private boolean canTransition(Integer from, Integer to) {
        if (AuditTask.STATUS_PENDING.equals(from)) {
            return AuditTask.STATUS_APPROVED.equals(to)
                    || AuditTask.STATUS_REJECTED.equals(to)
                    || AuditTask.STATUS_ESCALATED.equals(to);
        }
        if (AuditTask.STATUS_ESCALATED.equals(from)) {
            return AuditTask.STATUS_APPROVED.equals(to)
                    || AuditTask.STATUS_REJECTED.equals(to);
        }
        return false;
    }

    private String statusToAction(Integer status) {
        if (AuditTask.STATUS_APPROVED.equals(status)) return "APPROVE";
        if (AuditTask.STATUS_REJECTED.equals(status)) return "REJECT";
        if (AuditTask.STATUS_ESCALATED.equals(status)) return "ESCALATE";
        if (AuditTask.STATUS_CANCELLED.equals(status)) return "CANCEL";
        return "REVIEW";
    }
}
