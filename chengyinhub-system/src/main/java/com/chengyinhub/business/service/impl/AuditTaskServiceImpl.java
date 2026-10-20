package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.service.support.TemplateAuditSnapshot;

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
    private static final int MAX_REASON_LENGTH = 200;

    private static final Logger logger = LoggerFactory.getLogger(AuditTaskServiceImpl.class);

    /** 举报来源类型(仅此来源受 submitterId 限频;auto_gate/user_publish 不限)。 */
    private static final String SOURCE_REPORT = "report";
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

    private Long submitOrRefresh(String bizType, Long bizId, Long submitterId,
                                 String sourceType, String snapshotJson) {
        if (bizId == null || bizId <= 0L || submitterId == null || submitterId <= 0L
                || snapshotJson == null || snapshotJson.trim().isEmpty()) {
            throw new IllegalArgumentException("审核参数不完整");
        }
        AuditTask open = auditTaskMapper.selectOpenAuditTaskForUpdate(bizType, bizId);
        if (open != null) {
            requireRefreshableTask(open, sourceType);
            if (auditTaskMapper.refreshOpenAuditSnapshot(open.getId(), bizType, sourceType,
                    snapshotJson, submitterId, DateUtils.getNowDate()) != 1) {
                throw new IllegalStateException("待审任务快照刷新失败");
            }
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
            if (auditTaskMapper.refreshOpenAuditSnapshot(concurrent.getId(), bizType, sourceType,
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
        if (open != null) requireRefreshableTask(open, "topic_bundle_v2");
        return open != null;
    }

    private void requireRefreshableTask(AuditTask task, String sourceType) {
        boolean legacyTopicTask = "topic_bundle_v2".equals(sourceType)
                && ("user_publish".equals(task.getSourceType()) || "user_republish".equals(task.getSourceType()));
        if (!sourceType.equals(task.getSourceType()) && !legacyTopicTask) {
            throw new IllegalStateException("存在其他开放审核任务，请先处理后再提交");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitSafetyHold(String bizType, Long bizId, Long submitterId, String riskLevel, String snapshotJson) {
        Long taskId = findOrCreateOpenTask(bizType, bizId, submitterId, "auto_gate", riskLevel, snapshotJson);
        if (AuditTask.BIZ_COMMUNITY_POST.equals(bizType) && "EDIT_REVIEW".equals(riskLevel)) {
            AuditTask task = auditTaskMapper.selectAuditTaskByIdForUpdate(taskId);
            if (task == null || !bizType.equals(task.getBizType()) || !bizId.equals(task.getBizId())) {
                throw new ServiceException("推文审核任务已变化，请重试", HttpStatus.CONFLICT);
            }
            if (!java.util.Objects.equals(snapshotJson, task.getSnapshotJson())
                    && auditTaskMapper.refreshOpenAuditSnapshot(taskId, bizType, task.getSourceType(),
                            snapshotJson, task.getSubmitterId() == null ? submitterId : task.getSubmitterId(),
                            DateUtils.getNowDate()) != 1) {
                throw new ServiceException("推文审核快照刷新失败，请重试", HttpStatus.CONFLICT);
            }
        }
        return taskId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long reportContent(String bizType, Long bizId, Long reporterId, String reasonText) {
        String snapshot = buildReportSnapshot(bizType, bizId, reasonText);
        // 幂等:同一内容的多次举报汇聚到同一张未结审核单,避免刷单顶爆工作台;
        // 但每次举报仍 append 一条 AuditRecord(action=REPORT)留痕 → 举报数=REPORT 记录数、处置记录=记录历史,免迁移。
        Long taskId = findOrCreateOpenTask(bizType, bizId, reporterId, SOURCE_REPORT, "NORMAL", snapshot);
        if (AuditTask.BIZ_HANGOUT.equals(bizType) || AuditTask.BIZ_TEAM.equals(bizType)) {
            AuditTask locked = auditTaskMapper.selectAuditTaskByIdForUpdate(taskId);
            if (locked == null || (!AuditTask.STATUS_PENDING.equals(locked.getStatus())
                    && !AuditTask.STATUS_ESCALATED.equals(locked.getStatus()))) {
                throw new ServiceException("该轮举报已处理，请重新提交举报");
            }
        }
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
        return taskId;
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
        Long open = findOpenTaskId(bizType, bizId);
        if (open != null) {
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
            Long again = findOpenTaskId(bizType, bizId);
            if (again != null) {
                return again;
            }
            throw dup;
        }
        return task.getId();
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

    /** 返回同 bizType+bizId 的未结(PENDING/ESCALATED)审核单 id,无则 null。 */
    private Long findOpenTaskId(String bizType, Long bizId) {
        AuditTask q = new AuditTask();
        q.setBizType(bizType);
        q.setBizId(bizId);
        for (AuditTask existing : auditTaskMapper.selectAuditTaskList(q)) {
            if (AuditTask.STATUS_PENDING.equals(existing.getStatus())
                    || AuditTask.STATUS_ESCALATED.equals(existing.getStatus())) {
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
    public int review(Long taskId, Long operatorId, AuditReviewDTO request) {
        if (taskId == null || request == null || request.getStatus() == null) {
            throw new IllegalArgumentException("审核参数不完整");
        }
        if (AuditTask.STATUS_REJECTED.equals(request.getStatus())) {
            request.setReasonText(requireReason(request.getReasonText(), REJECT_REASON_REQUIRED));
        } else if (request.getReasonText() != null) {
            request.setReasonText(trimReason(request.getReasonText()));
        }
        AuditTask task = auditTaskMapper.selectAuditTaskById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在");
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
        boolean versionedReview = templateLock != null || isTopicBundle(task) || communityReview || AuditTask.BIZ_MEDIA_ASSET.equals(task.getBizType());
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
        if (templateLock != null) assertTemplateReviewCurrent(templateLock, request);
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
        record.setSnapshotJson(task.getSnapshotJson());
        record.setCreateTime(now);
        if (auditRecordMapper.insertAuditRecord(record) != 1) {
            throw new ServiceException("审批时间线写入失败", HttpStatus.ERROR);
        }

        applyCommercialReviewLifecycle(task, request.getStatus());

        // 审核决定 → 内容软封/恢复(REJECT=确认违规下架,APPROVE=举报无效恢复)
        notifyReviewResult(task, request);
        applyModeration(task, request.getStatus(), request.getReasonText());
        appendDecisionAudit(task, operatorId, request, record, now);
        return rows;
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
        java.util.Map<Long, String> recipients = new java.util.LinkedHashMap<>();
        for (Long reporter : reporters) {
            recipients.put(reporter, report ? (upheld ? "举报已处理" : "举报处理完成")
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
                : (upheld ? "你提交的内容未通过审核" : "你提交的内容已通过审核");
        String reason = request.getReasonText() == null || request.getReasonText().isEmpty()
                ? outcome : request.getReasonText();
        for (java.util.Map.Entry<Long, String> recipient : recipients.entrySet()) {
            if (recipient.getKey() == null || recipient.getKey() <= 0) continue;
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("taskId", task.getId());
            result.put("bizType", task.getBizType());
            result.put("bizId", task.getBizId());
            result.put("outcome", outcome);
            result.put("reason", reason);
            result.put("followUp", "本通知保留处理结果；如有异议，请通过客服提供审核任务编号补充材料。");
            java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
            card.put("title", recipient.getValue());
            card.put("sub", outcome + "。原因：" + reason);
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
        if (!isRevertSupported(task.getBizType(), task.getStatus())) {
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

        reverseDecisionEffects(task);
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
                && isRevertSupported(task.getBizType(), task.getStatus()));
        return detail;
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
                imMessageMapper.updateStatus(bizId, down ? ImMessage.STATUS_BLOCKED : ImMessage.STATUS_NORMAL);
            } else if (AuditTask.BIZ_TOPIC.equals(bizType)) {
                if (up) mediaPublicationGuard.requireTopicPublication(bizId);
                if (isTopicBundle(task)) {
                    topicBundleReviewService.applyDecision(bizId, task.getSubmitterId(), task.getSnapshotJson(), up);
                }
                // M0-6 先审后发:过审(APPROVE)→上架(status=1/userStatus=1);驳回(REJECT)→status=2
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
                    boolean online = onlineErr == null
                            && (!alreadyPublished || Integer.valueOf(1).equals(exist.getUserStatus()));
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
        return task != null && AuditTask.BIZ_TOPIC.equals(task.getBizType())
                && "topic_bundle_v2".equals(task.getSourceType());
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

    /**
     * 认领待审单。认领只是协作提示,不是锁:review() 不校验 current_assignee,
     * 其他有 business:audit:review 权限的审核员仍可直接处理已认领单(保持既有行为,
     * 避免认领人离岗时单子无法流转);处理后 current_assignee 改写为实际处理人。
     */
    @Override
    public void claim(Long taskId, Long operatorId) {
        if (taskId == null || operatorId == null) {
            throw new IllegalArgumentException("认领参数不完整");
        }
        if (auditTaskMapper.claimAuditTask(taskId, operatorId, DateUtils.getNowDate()) != 1) {
            throw new ServiceException("该任务已被认领或已处理");
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
                stateJson(request.getStatus(), task.getVersion() + 1), now);
        if (auditMapper.insertBusinessAuditEvent(event) != 1) {
            throw new ServiceException("审批审计写入失败", HttpStatus.ERROR);
        }
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

    private void reverseDecisionEffects(AuditTask task) {
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

    private boolean isTerminalDecision(Integer status) {
        return AuditTask.STATUS_APPROVED.equals(status) || AuditTask.STATUS_REJECTED.equals(status);
    }

    private boolean isRevertSupported(String bizType, Integer status) {
        // 仅开放具备领域行锁 + version CAS 的补偿路径；其余类型在补齐目标状态 CAS 前 fail closed。
        return AuditTask.BIZ_MERCHANT_REVIEW.equals(bizType)
                && AuditTask.STATUS_REJECTED.equals(status);
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
