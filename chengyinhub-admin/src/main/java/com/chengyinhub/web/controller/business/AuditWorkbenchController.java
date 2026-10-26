package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.domain.dto.AuditRevertDTO;
import com.chengyinhub.business.domain.vo.AuditRevertResultVO;
import com.chengyinhub.business.domain.vo.AuditTaskDetailVO;
import com.chengyinhub.business.domain.dto.TopicCommercialReviewSubmitDTO;
import com.chengyinhub.business.domain.dto.TopicSaleCanonicalDraftDTO;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ITopicCommercialReviewService;
import com.chengyinhub.business.service.ITopicSaleCanonicalDraftService;
import com.chengyinhub.business.service.ITopicSaleFinalizationService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IClubPostCommentService;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.business.service.support.JourneyReviewTextService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.entity.SysRole;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * P3 统一审核工作台。
 */
@RestController
@RequestMapping("/business/audit")
public class AuditWorkbenchController extends BaseController {

    @Autowired
    private IAuditTaskService auditTaskService;

    @Autowired
    private com.chengyinhub.business.service.support.GuardAppealReviewerScope guardAppealReviewerScope;

    @Autowired
    private com.chengyinhub.business.service.ICommunityPostService communityPostService;

    @Autowired
    private ITopicCommercialReviewService topicCommercialReviewService;

    @Autowired
    private ITopicSaleCanonicalDraftService topicSaleCanonicalDraftService;

    @Autowired
    private ITopicSaleFinalizationService topicSaleFinalizationService;

    @Autowired
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    @Autowired
    private IClubPostService clubPostService;

    @Autowired
    private IClubPostCommentService clubPostCommentService;

    /** R12:审核详情里补 journey_rules_json / journey_story_json 与节点块叙事段可读文本(只读)。 */
    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;

    @Autowired
    private JourneyReviewTextService journeyReviewTextService;

    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/list")
    public TableDataInfo list(AuditTask auditTask) {
        List<Long> guardTopics = guardAppealReviewerScope == null ? java.util.List.of() : guardAppealReviewerScope.readableTopicIds();
        startPage();
        List<AuditTask> list = auditTaskService.selectWorkbenchQueueWithGuardScope(auditTask, guardTopics);
        TableDataInfo data = getDataTable(list);
        data.setRows(list.stream().map(this::auditTaskForRead).collect(java.util.stream.Collectors.toList()));
        return data;
    }

    private boolean canReadTask(AuditTask task) {
        return task == null || !com.chengyinhub.business.service.support.ArriveLocationGuard.APPEAL_BIZ_TYPE.equals(task.getBizType())
                || auditTaskService.canReadGuardTask(task);
    }

    /** 认领待审单(协作提示,不阻止其他审核员处理);已被认领或已处理返回错误。 */
    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @Log(title = "P3审核认领", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/claim")
    public AjaxResult claim(@PathVariable("id") Long id) {
        auditTaskService.claim(id, getUserId());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        AuditTask task = auditTaskService.selectAuditTaskById(id);
        if (!canReadTask(task)) return new AjaxResult(HttpStatus.FORBIDDEN, "无该守卫审核范围");
        return success(auditTaskForRead(task));
    }

    /**
     * 审核详情:内容类型/风险原因/快照(task) + 处置记录与举报留痕(records) + 举报数(reportCount)。
     * 工作台人工通过/拒绝/下架前据此看清风险来源与举报热度(《社区内容规范》门禁验收项)。
     */
    public AjaxResult detail(Long id) { return detail(id, null); }

    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/{id}/detail")
    public AjaxResult detail(@PathVariable("id") Long id, @RequestParam(value = "requestId", required = false) String requestId) {
        AuditTask task = auditTaskService.selectAuditTaskById(id);
        if (task == null) {
            return error("审核任务不存在");
        }
        if (!canReadTask(task)) return new AjaxResult(HttpStatus.FORBIDDEN, "无该守卫审核范围");
        List<AuditRecord> records = auditTaskService.listAuditRecords(id);
        // 举报热度按「不同举报人数」计,而非 REPORT 记录原始条数——否则单个用户连点举报即可把 reportCount
        // 灌成大数,诱导审核员误判下架(武器化举报)。每条 REPORT 仍在 records 里留痕可查。
        java.util.Set<Long> reporters = new java.util.HashSet<>();
        for (AuditRecord r : records) {
            if ("REPORT".equals(r.getAction()) && r.getOperatorId() != null) {
                reporters.add(r.getOperatorId());
            }
        }
        AjaxResult ajax = success();
        ajax.put("task", auditTaskForRead(task));
        ajax.put("records", auditRecordsForRead(task, records));
        if (AuditTask.BIZ_COMMUNITY_POST.equals(task.getBizType())) {
            ajax.put("communityMediaPreview", communityPostService.auditMediaPreview(task));
        }
        ajax.put("reportCount", reporters.size());
        if (com.chengyinhub.business.service.support.ArriveLocationGuard.APPEAL_BIZ_TYPE.equals(task.getBizType())) {
            ajax.put("guardReviewReceipt", requestId == null || requestId.trim().isEmpty()
                    ? java.util.Map.of("state", "NOT_FOUND") : auditTaskService.guardReviewReceipt(id, getUserId(), requestId));
        } else {
            ajax.put("itemProblemReceipt", auditTaskService.itemProblemReceipt(id, getUserId(), requestId));
        }
        AuditTaskDetailVO governance = auditTaskService.detail(id, getUserId(), SysUser.isAdmin(getUserId()));
        if (governance != null) {
            ajax.put("canRevert", governance.isCanRevert());
            ajax.put("revertDeadline", governance.getRevertDeadline());
            ajax.put("serverTime", governance.getServerTime());
        }
        if (AuditTask.BIZ_MEDIA_ASSET.equals(task.getBizType())) {
            if (!com.chengyinhub.common.utils.SecurityUtils.hasPermi("business:mediaReview:query"))return error("无媒体审核读取权限");
            ajax.put("mediaPreviewUrl",appMediaAssetService.reviewerPreview(task.getBizId()));
            ajax.put("mediaType",com.alibaba.fastjson2.JSON.parseObject(task.getSnapshotJson()).getString("mediaType"));
        }
        appendAppealAttachments(ajax, task, records);
        appendJourneyReviewTexts(ajax, task);
        return ajax;
    }

    /**
     * S531-3:申诉单的附件(media-asset:{id})解析成 5 分钟预览地址,审核员不必拿编号去媒体审核页手查。
     * 只在申诉单且确有附件时追加;预览地址另需媒体审核读取权限,没有权限只给编号。单个附件失败不影响详情。
     */
    private void appendAppealAttachments(AjaxResult ajax, AuditTask task, List<AuditRecord> records) {
        if (!com.chengyinhub.business.service.impl.AuditTaskServiceImpl.SOURCE_APPEAL.equals(task.getSourceType())
                || records == null) {
            return;
        }
        java.util.Set<Long> assetIds = new java.util.LinkedHashSet<>();
        for (AuditRecord record : records) {
            if (record != null && AuditRecord.ACTION_SUBMIT.equals(record.getAction())) {
                assetIds.addAll(com.chengyinhub.business.service.support.AppealAttachmentRefs
                        .fromSubmitSnapshot(record.getSnapshotJson()));
            }
        }
        if (assetIds.isEmpty()) return;
        boolean canPreview;
        try {
            canPreview = com.chengyinhub.common.utils.SecurityUtils.hasPermi("business:mediaReview:query");
        } catch (Exception noLogin) {
            canPreview = false;
        }
        List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
        for (Long assetId : assetIds) {
            java.util.Map<String, Object> item = null;
            if (canPreview) {
                try {
                    item = appMediaAssetService.reviewerAttachmentPreview(assetId);
                } catch (Exception previewFailure) {
                    logger.warn("申诉附件预览签名失败 taskId={} assetId={}", task.getId(), assetId, previewFailure);
                }
            }
            if (item == null) {
                item = new java.util.LinkedHashMap<>();
                item.put("assetId", assetId);
                item.put("mediaType", null);
                item.put("previewUrl", null);
            }
            items.add(item);
        }
        ajax.put("appealAttachments", items);
        ajax.put("appealAttachmentsPreviewDenied", !canPreview);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.impl.AppMediaAssetService appMediaAssetService;

    private AuditTask auditTaskForRead(AuditTask task) {
        if (task == null || !AuditTask.BIZ_COMMUNITY_POST.equals(task.getBizType())) return task;
        AuditTask copy = new AuditTask();
        org.springframework.beans.BeanUtils.copyProperties(task, copy);
        copy.setSnapshotJson(communityPostService.auditSnapshotForRead(task.getSnapshotJson()));
        return copy;
    }

    private List<AuditRecord> auditRecordsForRead(AuditTask task, List<AuditRecord> records) {
        if (!AuditTask.BIZ_COMMUNITY_POST.equals(task.getBizType())) return records;
        List<AuditRecord> copies = new java.util.ArrayList<>();
        for (AuditRecord record : records) {
            AuditRecord copy = new AuditRecord();
            org.springframework.beans.BeanUtils.copyProperties(record, copy);
            copy.setSnapshotJson(communityPostService.auditSnapshotForRead(record.getSnapshotJson()));
            copies.add(copy);
        }
        return copies;
    }

    /**
     * R12:把旅程新叙事的可读文本追加进审核详情(只追加,不改原有字段)。
     *
     * <p>主题:journeyRulesJson / journeyStoryJson 原样 + 剧情与各节点块叙事段的逐条文本;
     * 模板与商家节点模板:块配置叙事段的逐条文本(快照里的 advancedConfigJson 是原始 JSON,
     * 这里给审核员人手可读的一份)。对象读不到时不追加,不影响原审核流程。
     */
    private void appendJourneyReviewTexts(AjaxResult ajax, AuditTask task) {
        if (AuditTask.BIZ_TOPIC.equals(task.getBizType())) {
            if ("topic_bundle_v2".equals(task.getSourceType())) {
                return;
            }
            CmsTopic topic = cmsTopicService.selectCmsTopicById(task.getBizId());
            if (topic != null) {
                ajax.put("journeyRulesJson", topic.getJourneyRulesJson());
                ajax.put("journeyStoryJson", topic.getJourneyStoryJson());
                ajax.put("narrativeTexts", journeyReviewTextService.topicTexts(topic));
            }
        } else if (AuditTask.BIZ_TEMPLATE.equals(task.getBizType())
                || AuditTask.BIZ_MERCHANT_NODE_TEMPLATE.equals(task.getBizType())) {
            CmsMemberTemplate template = cmsMemberTemplateService.selectCmsMemberTemplateById(task.getBizId());
            if (template != null) {
                ajax.put("narrativeTexts", journeyReviewTextService.templateTexts(template));
            }
        }
    }

    /** 商业立项审提交；菜单权限外再做对象级 topic scope 校验。 */
    @PreAuthorize("@ss.hasPermi('business:audit:submit')")
    @Log(title = "商业主题立项提审", businessType = BusinessType.INSERT)
    @PostMapping("/topic/{topicId}/project-submit")
    public AjaxResult submitProjectReview(@PathVariable Long topicId,
                                           @RequestBody TopicCommercialReviewSubmitDTO request) {
        if (!topicOperationsScopeAuthorizer.canEditTopic(topicId)) {
            return error("主题不存在或当前账号无操作范围");
        }
        try {
            if (request == null || request.getRequestedTarget() == null) {
                return error("立项审核目标不能为空");
            }
            TopicLifecycleState target = TopicLifecycleState.valueOf(request.getRequestedTarget());
            Long taskId = topicCommercialReviewService.submitProjectReview(topicId, getUserId(), target);
            AjaxResult result = success(taskId == null ? "纯 UGC 免审，已直接流转" : "已提交立项审核");
            result.put("taskId", taskId);
            result.put("reviewRequired", taskId != null);
            return result;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    /** 商业开售审提交；开售仍由 finalizeForSale 回读通过态后才可进入 SELLING。 */
    @PreAuthorize("@ss.hasPermi('business:audit:submit')")
    @Log(title = "商业主题开售提审", businessType = BusinessType.INSERT)
    @PostMapping("/topic/{topicId}/sale-submit")
    public AjaxResult submitSaleReview(@PathVariable Long topicId) {
        if (!topicOperationsScopeAuthorizer.canEditTopic(topicId)) {
            return error("主题不存在或当前账号无操作范围");
        }
        try {
            Long taskId = topicCommercialReviewService.submitSaleReview(topicId, getUserId());
            if (taskId == null) {
                topicSaleFinalizationService.finalizeForSale(topicId);
            }
            AjaxResult result = success(taskId == null ? "纯 UGC 已直接开售" : "已提交开售审核");
            result.put("taskId", taskId);
            result.put("reviewRequired", taskId != null);
            result.put("lifecycle", taskId == null ? TopicLifecycleState.SELLING.name()
                    : TopicLifecycleState.PRICING.name());
            return result;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:audit:submit')")
    @Log(title = "商业主题开售事实草稿", businessType = BusinessType.UPDATE)
    @PutMapping("/topic/{topicId}/sale-canonical-draft")
    public AjaxResult saveSaleCanonicalDraft(@PathVariable Long topicId,
                                             @RequestBody TopicSaleCanonicalDraftDTO request) {
        if (!topicOperationsScopeAuthorizer.canEditTopic(topicId)) {
            return error("主题不存在或当前账号无操作范围");
        }
        try {
            return success(topicSaleCanonicalDraftService.save(topicId, getUserId(), request));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/topic/{topicId}/sale-canonical-draft")
    public AjaxResult getSaleCanonicalDraft(@PathVariable Long topicId) {
        if (!topicOperationsScopeAuthorizer.canViewTopic(topicId)) {
            return error("主题不存在或当前账号无操作范围");
        }
        return success(topicSaleCanonicalDraftService.get(topicId));
    }

    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @Log(title = "P3统一审核", businessType = BusinessType.UPDATE)
    @PostMapping("/review")
    public AjaxResult review(@RequestBody AuditReviewDTO request) {
        if (request == null || request.getTaskId() == null || request.getStatus() == null) {
            return error("审核参数不完整");
        }
        try {
            request.setActorRoleCode(actorRoleCode());
            int rows = auditTaskService.review(request.getTaskId(), getUserId(), request);
            if (rows > 0) {
                // 驳回成立 → 自动下架违规内容(club 动态/评论),best-effort
                if (request.getStatus() != null && request.getStatus() == 2) {
                    try {
                        AuditTask t = auditTaskService.selectAuditTaskById(request.getTaskId());
                        if (t != null && t.getBizId() != null) {
                            if ("CLUB_POST".equals(t.getBizType())) {
                                clubPostService.deleteClubPostById(t.getBizId());
                            } else if ("CLUB_POST_COMMENT".equals(t.getBizType())) {
                                clubPostCommentService.deleteClubPostCommentById(t.getBizId());
                            }
                        }
                    } catch (Exception ignore) {}
                }
                return success("审核处理成功");
            }
            AjaxResult conflict = new AjaxResult(HttpStatus.CONFLICT, "审核状态已变化，请刷新后重试");
            conflict.put("errorCode", "AUDIT_STATE_CONFLICT");
            return conflict;
        } catch (IllegalArgumentException e) {
            AjaxResult badRequest = new AjaxResult(HttpStatus.BAD_REQUEST, e.getMessage());
            badRequest.put("errorCode", AuditTaskServiceImpl.REJECT_REASON_REQUIRED.equals(e.getMessage())
                    ? "REJECT_REASON_REQUIRED" : "AUDIT_REVIEW_INVALID");
            return badRequest;
        } catch (ServiceException e) {
            AjaxResult error = new AjaxResult(e.getCode() == null ? HttpStatus.ERROR : e.getCode(), e.getMessage());
            error.put("errorCode", AuditTaskServiceImpl.ERROR_REVIEW_STALE_LEVEL.equals(e.getDetailMessage())
                    ? AuditTaskServiceImpl.ERROR_REVIEW_STALE_LEVEL
                    : e.getCode() != null && e.getCode() == HttpStatus.CONFLICT
                    ? "AUDIT_SNAPSHOT_STALE" : "AUDIT_REVIEW_FAILED");
            return error;
        }
    }

    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @Log(title = "审批决定撤销", businessType = BusinessType.UPDATE)
    @PostMapping("/revert")
    public AjaxResult revert(@RequestBody AuditRevertDTO request) {
        if (request == null || request.getTaskId() == null) {
            return new AjaxResult(HttpStatus.BAD_REQUEST, "撤销参数不完整");
        }
        try {
            request.setActorRoleCode(actorRoleCode());
            AuditRevertResultVO result = auditTaskService.revert(request.getTaskId(), getUserId(),
                    SysUser.isAdmin(getUserId()), request);
            return success(result);
        } catch (ServiceException e) {
            int code = e.getCode() == null ? HttpStatus.ERROR : e.getCode();
            AjaxResult error = new AjaxResult(code, e.getMessage());
            error.put("errorCode", e.getDetailMessage() == null ? "REVERT_FAILED" : e.getDetailMessage());
            if (code == HttpStatus.CONFLICT) {
                error.put("requestId", request.getIdempotencyKey());
                error.put("serverTime", new java.util.Date());
                AuditTask current = auditTaskService.selectAuditTaskById(request.getTaskId());
                if (current != null) {
                    error.put("currentStatus", current.getStatus());
                    error.put("currentVersion", current.getVersion());
                }
            }
            return error;
        } catch (IllegalArgumentException e) {
            return new AjaxResult(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private String actorRoleCode() {
        Long userId = getUserId();
        if (SysUser.isAdmin(userId)) return "PLATFORM_ADMIN";
        try {
            List<SysRole> roles = getLoginUser().getUser().getRoles();
            if (roles != null && !roles.isEmpty() && roles.get(0).getRoleKey() != null) {
                return roles.get(0).getRoleKey();
            }
        } catch (Exception ignored) {
            // 仅手工构造 controller 的单测无 SecurityContext；生产请求由 @PreAuthorize 保证登录态。
        }
        return "SYS_USER";
    }
}
