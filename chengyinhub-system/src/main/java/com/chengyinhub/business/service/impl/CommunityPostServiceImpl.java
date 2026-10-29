package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.community.CommunityPostFeedQuery;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.domain.dto.CommunityReportSubmitRequest;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.domain.vo.CommunityReportVO;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.CommunityTrustService;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICommunityPostService;
import com.chengyinhub.business.service.ICommunityMediaReceiptService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.business.service.support.CommunityReportPolicy;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.JsonUtils;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class CommunityPostServiceImpl implements ICommunityPostService {
    private static final Set<String> TYPES = set("MOMENT", "ACTIVITY_RECAP", "ROUTE_DISCOVERY", "CITY_TIP", "CLUB_ANNOUNCEMENT");
    private static final Set<String> AUDIENCES = set("PUBLIC", "FOLLOWERS", "COMMUNITY", "PRIVATE");
    private static final Set<String> COMMENT_POLICIES = set("EVERYONE", "FOLLOWERS", "MEMBERS", "MENTIONED", "OFF");
    private static final Set<String> LOCATION_PRECISIONS = set("NONE", "CITY", "APPROXIMATE", "EXACT");
    private static final Set<String> DISCLOSURES = set("NONE", "SPONSORED", "GIFTED", "MERCHANT_OWNER", "MERCHANT_EMPLOYEE");
    private static final Set<String> ACTIONS = set("LIKE", "BOOKMARK", "NOT_INTERESTED", "SEE_LESS_AUTHOR", "SEE_LESS_TOPIC");
    private static final Set<String> FEEDS = set("LATEST", "FOLLOWING", "NEARBY", "TOPIC", "COMMUNITY", "FEATURED", "FOR_YOU", "TRENDING");
    private static final Set<String> REFERENCE_TYPES = set("ACTIVITY", "TOPIC", "ROUTE", "CLUB", "POI");
    private static final Set<String> REFERENCE_PRIVACY = set("PUBLIC_SAFE");
    private static final Set<String> AUTHOR_SAFETY_LABELS = set("DANGEROUS_ACTIVITY", "SENSITIVE_CONTENT",
            "FLASHING_IMAGES", "SPOILER", "TEMPORARY_CLOSURE", "ACCESSIBILITY_LIMIT", "WEATHER_RISK");
    private static final Set<String> ENFORCEMENTS = set("LABEL", "DOWNRANK", "SEARCH_EXCLUDE", "INTERACTION_LIMIT", "HIDE_PENDING_REVIEW", "REMOVE", "WARNING", "POST_RATE_LIMIT", "COMMENT_TIMEOUT", "READ_ONLY", "TEMP_SUSPEND", "PERMANENT_BAN");
    private static final Set<String> MEMBER_ENFORCEMENTS = set("POST_RATE_LIMIT", "COMMENT_TIMEOUT", "READ_ONLY", "TEMP_SUSPEND", "PERMANENT_BAN");
    private static final Set<String> AGGREGATED_NOTIFICATION_ACTIONS = set("LIKE", "BOOKMARK", "COMMENT_LIKE");
    private static final long NOTIFICATION_AGGREGATION_WINDOW_MILLIS = 6L * 60L * 60L * 1000L;
    /** community_post.audit_level:1 机审通过 / 2 人审通过(0 未审为列默认值) */
    private static final int AUDIT_LEVEL_MACHINE = 1;
    private static final int AUDIT_LEVEL_HUMAN = 2;
    private static final Logger log = LoggerFactory.getLogger(CommunityPostServiceImpl.class);

    /** O-05 #1 降分发开关(默认关):分发类处置把公开帖落 LIMITED,受众内读者可直达查看。 */
    @Value("${chengyin.community.post-limit.enabled:false}")
    private boolean postLimitEnabled;

    private final CommunityPostMapper mapper;
    private final CommunityPostLifecyclePolicy policy;
    private final IContentSafetyGate safetyGate;
    private final IAuditTaskService auditTaskService;
    private final CommunityTrustService communityTrustService;
    private final ICommunityMediaReceiptService mediaReceiptService;
    private ObjectProvider<IWxContentSecurityService> wxContentSecurityProvider;
    private ObjectProvider<com.chengyinhub.business.service.IImService> imServiceProvider;
    /** 申诉处理时限：与举报首响 SLA 同口径，标准 24 小时；紧急原因(CommunityReportPolicy)15 分钟。 */
    private static final long APPEAL_STANDARD_DUE_MINUTES = 24 * 60L;

    public CommunityPostServiceImpl(CommunityPostMapper mapper, CommunityPostLifecyclePolicy policy,
                                    IContentSafetyGate safetyGate, IAuditTaskService auditTaskService,
                                    CommunityTrustService communityTrustService,
                                    ICommunityMediaReceiptService mediaReceiptService) {
        this.mapper = mapper;
        this.policy = policy;
        this.safetyGate = safetyGate;
        this.auditTaskService = auditTaskService;
        this.communityTrustService = communityTrustService;
        this.mediaReceiptService = mediaReceiptService;
    }

    /** 图片异步机审(WxContentSecurityServiceImpl 反向依赖本服务,setter 注入 ObjectProvider 懒取破环)。 */
    @Autowired(required = false)
    public void setWxContentSecurityProvider(ObjectProvider<IWxContentSecurityService> wxContentSecurityProvider) {
        this.wxContentSecurityProvider = wxContentSecurityProvider;
    }

    /** 系统消息(作者能在小程序系统会话里看到的通道)；ImServiceImpl 间接依赖本服务，ObjectProvider 懒取破环。 */
    @Autowired(required = false)
    public void setImServiceProvider(ObjectProvider<com.chengyinhub.business.service.IImService> imServiceProvider) {
        this.imServiceProvider = imServiceProvider;
    }

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> result = new HashMap<>();
        result.put("postTypes", sorted(TYPES));
        result.put("audiences", sorted(AUDIENCES));
        result.put("commentPolicies", sorted(COMMENT_POLICIES));
        result.put("feedModes", sorted(FEEDS));
        result.put("disclosureTypes", sorted(DISCLOSURES));
        result.put("authorSafetyLabels", sorted(AUTHOR_SAFETY_LABELS));
        result.put("memberActions", sorted(ACTIONS));
        result.put("reporting", communityTrustService.getReportTaxonomy());
        Map<String, Integer> limits = new HashMap<>();
        limits.put("bodyCharacters", 5000); limits.put("mediaPerPost", 9);
        limits.put("topicsPerPost", 5); limits.put("mentionsPerPost", 10);
        result.put("limits", limits);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> registerMedia(Long memberId, CommunityPostRequests.MediaRegister request) {
        requirePrincipal(memberId);
        if (request == null) throw error("缺少媒体参数", 400);
        requireRequestId(request.getUploadRequestId());
        String type = upper(request.getMediaType());
        if (!("IMAGE".equals(type) || "VIDEO".equals(type) || "MAP_SNAPSHOT".equals(type) || "FILE".equals(type))) {
            throw error("不支持的媒体类型", 400);
        }
        if (!safeMediaObjectKey(request.getObjectKey()) || request.getObjectKey().length() > 500 || blank(request.getMimeType())
                || request.getByteSize() == null || request.getByteSize() <= 0 || request.getByteSize() > 200L * 1024 * 1024) {
            throw error("媒体信息不完整或文件过大", 400);
        }
        Map<String, Object> existing = mapper.selectMediaByOwnerRequest(memberId, request.getUploadRequestId());
        if (existing != null) {
            validateMediaReplay(existing, type, request);
            return mediaForRead(existing);
        }
        boolean validReceipt = mediaReceiptService.verify(memberId, request.getObjectKey(), request.getByteSize(), request.getUploadReceipt());
        String canonicalKey = null;
        if (!validReceipt) {
            canonicalKey = canonicalMediaKey(request.getObjectKey());
            validReceipt = !canonicalKey.equals(request.getObjectKey())
                    && mediaReceiptService.verify(memberId, canonicalKey, request.getByteSize(), request.getUploadReceipt());
        }
        if (!validReceipt) throw error("媒体上传回执无效或已过期", 403);
        String receiptHash = sha256(request.getUploadReceipt());
        Map<String, Object> receiptOwner = mapper.selectMediaByReceiptHash(receiptHash);
        if (receiptOwner != null) {
            if (!memberId.equals(number(value(receiptOwner, "owner_member_id")))) {
                throw error("媒体上传回执已被使用", 409);
            }
            validateMediaReplay(receiptOwner, type, request);
            return mediaForRead(receiptOwner);
        }
        String objectKey = canonicalKey == null ? canonicalMediaKey(request.getObjectKey()) : canonicalKey;
        Date now = new Date();
        try {
            mapper.insertMedia(memberId, request.getUploadRequestId(), type, objectKey, request.getMimeType(),
                    request.getByteSize(), request.getWidth(), request.getHeight(), request.getDurationMs(),
                    trimToNull(request.getAltText()), sha256(objectKey), receiptHash, now);
        } catch (DuplicateKeyException e) {
            existing = mapper.selectMediaByOwnerRequest(memberId, request.getUploadRequestId());
            if (existing == null) existing = mapper.selectMediaByReceiptHash(receiptHash);
            if (existing == null || !memberId.equals(number(value(existing, "owner_member_id")))) {
                throw error("该媒体已经登记", 409);
            }
            validateMediaReplay(existing, type, request);
        }
        return mediaForRead(mapper.selectMediaByOwnerRequest(memberId, request.getUploadRequestId()));
    }

    @Override
    @Transactional
    public Map<String, Object> createDraft(Long memberId, CommunityPostRequests.Upsert request) {
        requirePrincipal(memberId);
        validateUpsert(memberId, request, false, false);
        CommunityPost post = mapPost(memberId, request);
        String fingerprint = upsertFingerprint(post, request);
        Map<String, Object> receipt = mapper.selectCommandReceipt(memberId, request.getClientRequestId());
        if (receipt != null) {
            validateCreateReplay(receipt, fingerprint);
            Long aggregateId = number(value(receipt, "aggregate_id"));
            CommunityPost replayed = mapper.selectByAuthorRequest(memberId, request.getClientRequestId());
            if (positive(aggregateId) && replayed != null && memberId.equals(replayed.getAuthorId())
                    && aggregateId.equals(replayed.getId())
                    && CommunityPost.LIFECYCLE_DELETED.equals(replayed.getLifecycle())
                    && mapper.countAuthorDeleteEvidence(memberId, aggregateId) == 1) {
                return Collections.singletonMap("intentDiscarded", true);
            }
            return detail(memberId, aggregateId);
        }
        CommunityPost existing = mapper.selectByAuthorRequest(memberId, request.getClientRequestId());
        if (existing != null) {
            throw error("旧草稿缺少完整幂等回执，请从草稿箱继续", 409);
        }
        requirePostingAllowed(memberId);
        post.setLifecycle(CommunityPost.LIFECYCLE_DRAFT);
        Date now = new Date();
        post.setCreateTime(now);
        post.setUpdateTime(now);
        if (mapper.insertPost(post) != 1) throw error("草稿保存失败", 500);
        replaceRelations(post, request, now);
        mapper.upsertSearchDocument(post.getId(), "HIDDEN", now);
        outbox(post.getId(), "POST_CREATED", payload("postId", post.getId()), now);
        Map<String, Object> result = new HashMap<>();
        result.put("aggregateId", post.getId());
        result.put("fingerprint", fingerprint);
        if (mapper.insertCommandReceipt(memberId, request.getClientRequestId(), "POST_CREATE",
                post.getId(), json(result), now) != 1) {
            throw error("建帖请求号已被使用", 409);
        }
        return detail(memberId, post.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> edit(Long memberId, Long postId, CommunityPostRequests.Upsert request) {
        requirePrincipal(memberId);
        CommunityPost current = locked(postId);
        boolean preserveLegacyLabel = memberId.equals(current.getAuthorId()) && hasLegacyMigrationLabel(current);
        validateUpsert(memberId, request, true, preserveLegacyLabel);
        if (!CommunityPost.LIFECYCLE_DRAFT.equals(current.getLifecycle())) {
            requireGuidelineAck(memberId, request.getGuidelineVersionId());
        }
        int expectedVersion = requiredVersion(request.getExpectedVersion());
        CommunityPostLifecyclePolicy.Decision decision = policy.decide(current,
                CommunityPostLifecyclePolicy.ACTION_EDIT, expectedVersion, memberId.equals(current.getAuthorId()));
        Date now = new Date();
        mapper.insertRevision(postId, memberId, expectedVersion, json(current), "AUTHOR_EDIT",
                CommunityPost.LIFECYCLE_DRAFT.equals(current.getLifecycle()) ? "NOT_REQUIRED" : "PENDING", now);
        CommunityPost replacement = mapPost(memberId, request);
        replacement.setId(postId);
        if (mapper.updateContentCas(replacement, expectedVersion, decision.getLifecycle(), now) != 1) stale();
        replaceRelations(replacement, request, now);
        mapper.upsertSearchDocument(postId, "HIDDEN", now);
        if (CommunityPost.LIFECYCLE_CHECKING.equals(decision.getLifecycle())) {
            replacement.setVersion(decision.getVersion());
            replacement.setLifecycle(decision.getLifecycle());
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("post", replacement);
            snapshot.put("media", mapper.selectMediaForSafety(postId));
            auditTaskService.submitSafetyHold(AuditTask.BIZ_COMMUNITY_POST, postId, memberId,
                    "EDIT_REVIEW", auditSnapshotForRead(json(snapshot)));
        }
        outbox(postId, "POST_EDITED", payload("version", expectedVersion + 1), now);
        return detail(memberId, postId);
    }

    @Override
    @Transactional
    public Map<String, Object> publish(Long memberId, String openid, Long postId, CommunityPostRequests.State request) {
        requirePrincipal(memberId);
        requireRequestId(request == null ? null : request.getRequestId());
        CommunityPost current = locked(postId);
        if (isCommandReplay(memberId, request.getRequestId(), "POST_PUBLISH", postId)) {
            return detail(memberId, postId);
        }
        requirePostingAllowed(memberId);
        int expectedVersion = requiredVersion(request.getExpectedVersion());
        policy.decide(current, CommunityPostLifecyclePolicy.ACTION_PUBLISH, expectedVersion,
                memberId.equals(current.getAuthorId()));
        if (positive(current.getCommunityId()) && mapper.countCommunityMember(memberId, current.getCommunityId()) == 0) {
            throw error("只能向自己加入的社群发布", 403);
        }
        if (blank(current.getBody())) throw error("正文应为 1-5000 字", 400);
        requireGuidelineAck(memberId, current.getGuidelineVersionId());

        PublishSafetyRequest safety = new PublishSafetyRequest();
        safety.setContentType(AuditTask.BIZ_COMMUNITY_POST);
        safety.setContentId(postId);
        safety.setMemberId(memberId);
        safety.setOpenid(blank(openid) ? mapper.selectMemberWxOpenId(memberId) : openid);
        safety.setText(((current.getTitle() == null ? "" : current.getTitle() + "\n") + current.getBody()).trim());
        List<Map<String, Object>> media = mediaForRead(mapper.selectMediaForSafety(postId));
        List<String> mediaUrls = new ArrayList<>();
        for (Map<String, Object> row : media) {
            Object url = value(row, "derived_object_key");
            if (url != null && !blank(url.toString())) mediaUrls.add(url.toString());
        }
        safety.setMediaUrls(mediaUrls);
        safety.setHasGeo(!"NONE".equals(current.getLocationPrecision()));
        Map<String, Object> safetySnapshot = new HashMap<>();
        safetySnapshot.put("post", current);
        safetySnapshot.put("media", media);
        safety.setSnapshot(json(safetySnapshot));
        safety.setReviewMode(PublishSafetyRequest.ReviewMode.PRE_REVIEW.name());
        SafetyDecision safetyDecision = safetyGate.decide(safety);
        if (safetyDecision == null || safetyDecision.isBlock()) {
            throw error("内容未通过安全检查，请修改后重试", 400);
        }
        String target = safetyDecision.isPass() ? CommunityPost.LIFECYCLE_PUBLISHED : CommunityPost.LIFECYCLE_CHECKING;
        Date now = new Date();
        if (mapper.transitionCas(postId, CommunityPost.LIFECYCLE_DRAFT, target, expectedVersion,
                safetyDecision.isPass() ? now : null, null, safetyDecision.isPass() ? 1 : 0,
                safetyDecision.isPass() ? 1 : 0, 1, now) != 1) stale();
        mapper.updateMediaReviewState(postId, safetyDecision.isPass() ? "PASS" : "HOLD", now);
        // 审核分级:机审直发 → audit_level=1;门禁「放行但打标」→ 进抽检池。HOLD 的等级在人审通过时落(applyAuditDecision)。
        if (safetyDecision.isPass() || safetyDecision.isFlagged()) {
            mapper.updateAuditMarks(postId, safetyDecision.isPass() ? AUDIT_LEVEL_MACHINE : null, null,
                    safetyDecision.isFlagged() ? 1 : null, now);
        }
        mapper.upsertSearchDocument(postId, safetyDecision.isPass() ? "VISIBLE" : "HIDDEN", now);
        outbox(postId, safetyDecision.isPass() ? "POST_PUBLISHED" : "POST_REVIEW_REQUESTED",
                payload("auditTaskId", safetyDecision.getAuditTaskId()), now);
        if (safetyDecision.isPass()) {
            submitMediaForAsyncCheck(postId, safety.getOpenid(), mediaUrls);
            for (Long mentionedMemberId : mapper.selectMentionedMemberIds(postId)) {
                notifyMember(mentionedMemberId, postId, memberId, "COMMENT_MENTION", "POST_MENTION", now);
            }
        }
        saveCommandReceipt(memberId, request.getRequestId(), "POST_PUBLISH", postId, now);
        return detail(memberId, postId);
    }

    @Override
    @Transactional
    public void delete(Long memberId, Long postId, CommunityPostRequests.State request) {
        requirePrincipal(memberId);
        requireRequestId(request == null ? null : request.getRequestId());
        CommunityPost current = locked(postId);
        if (isCommandReplay(memberId, request.getRequestId(), "POST_DELETE", postId)) return;
        int expected = requiredVersion(request.getExpectedVersion());
        policy.decide(current, CommunityPostLifecyclePolicy.ACTION_DELETE, expected, memberId.equals(current.getAuthorId()));
        Date now = new Date();
        if (mapper.transitionCas(postId, current.getLifecycle(), CommunityPost.LIFECYCLE_DELETED, expected,
                null, now, 0, 0, 0, now) != 1) stale();
        mapper.orphanMedia(postId, new Date(now.getTime() + 30L * 24 * 3600_000L), now);
        if (mapper.insertErasureJob(null, postId, "POST_DELETE", request.getRequestId(),
                "{\"search\":true,\"notifications\":true,\"mediaAfterDays\":30}", now) != 1) {
            throw error("删除编排保存失败", 500);
        }
        mapper.upsertSearchDocument(postId, "DELETED", now);
        outbox(postId, "POST_DELETED", payload("postId", postId), now);
        saveCommandReceipt(memberId, request.getRequestId(), "POST_DELETE", postId, now);
    }

    @Override
    @Transactional
    public void withdrawLocation(Long memberId, Long postId, CommunityPostRequests.State request) {
        requirePrincipal(memberId);
        requireRequestId(request == null ? null : request.getRequestId());
        if (isCommandReplay(memberId, request.getRequestId(), "LOCATION_WITHDRAW", postId)) return;
        int expected = requiredVersion(request.getExpectedVersion());
        Date now = new Date();
        if (mapper.withdrawPostLocation(postId, memberId, expected, now) != 1) {
            throw error("帖文不存在、已变化或无权撤回位置", 409);
        }
        if (mapper.insertErasureJob(memberId, postId, "LOCATION_WITHDRAW", request.getRequestId(),
                "{\"location\":true,\"search\":true}", now) != 1) {
            throw error("位置撤回编排保存失败", 500);
        }
        mapper.upsertSearchDocument(postId, "HIDDEN", now);
        outbox(postId, "POST_LOCATION_WITHDRAWN", payload("postId", postId), now);
        saveCommandReceipt(memberId, request.getRequestId(), "LOCATION_WITHDRAW", postId, now);
    }

    @Override
    @Transactional
    public void copyrightRemove(Long operatorUserId, Long postId, CommunityPostRequests.State request) {
        requireRequestId(request == null ? null : request.getRequestId());
        if (isCommandReplay(operatorUserId, request.getRequestId(), "COPYRIGHT_REMOVE", postId)) return;
        CommunityPost current = locked(postId);
        int expected = requiredVersion(request.getExpectedVersion());
        Date now = new Date();
        if (mapper.transitionCas(postId, current.getLifecycle(), CommunityPost.LIFECYCLE_DELETED, expected,
                null, now, 0, 0, 0, now) != 1) stale();
        mapper.orphanMedia(postId, new Date(now.getTime() + 30L * 24 * 3600_000L), now);
        if (mapper.insertErasureJob(current.getAuthorId(), postId, "COPYRIGHT_REMOVE", request.getRequestId(),
                "{\"search\":true,\"notifications\":true,\"mediaAfterDays\":30}", now) != 1) {
            throw error("版权移除编排保存失败", 500);
        }
        mapper.upsertSearchDocument(postId, "DELETED", now);
        outbox(postId, "POST_COPYRIGHT_REMOVED", payload("postId", postId), now);
        saveCommandReceipt(operatorUserId, request.getRequestId(), "COPYRIGHT_REMOVE", postId, now);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(Long viewerId, Long postId) {
        viewerId = viewerId == null || viewerId < 0 ? 0L : viewerId;
        CommunityPost post = readableById(postId, viewerId);
        if (post == null) throw error("帖文不存在或不可见", 404);
        return hydrate(post, viewerId);
    }

    /**
     * 读者口径的单帖读取:先走既有 selectById;读不到再按直达口读 LIMITED(受众内、未拉黑/屏蔽的登录非作者)。
     * 降分发(LIMITED)只减分发不下架,feed/搜索/推荐照旧不出。直达口不看开关(S532-3):LIMITED 行只可能在开关
     * 打开期间产生,开关关回后这些帖仍须照常可读,不能一律 404;从未产生过 LIMITED 的库,这里只多一次查不到的查询。
     */
    private CommunityPost readableById(Long postId, Long viewerId) {
        CommunityPost post = mapper.selectById(postId, viewerId);
        if (post == null && viewerId != null && viewerId > 0) post = mapper.selectLimitedById(postId, viewerId);
        return post;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> revisions(Long viewerId, Long postId) {
        requirePrincipal(viewerId);
        CommunityPost post = mapper.selectById(postId, viewerId);
        if (post == null) throw error("帖文不存在", 404);
        if (!viewerId.equals(post.getAuthorId())) throw error("仅作者可查看编辑历史", 403);
        return mapper.selectRevisions(postId, viewerId);
    }

    @Override
    public List<Map<String, Object>> filterPublicCompletedReferences(Long memberId,
            List<Map<String, Object>> completed) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (memberId == null || memberId <= 0 || completed == null) return result;
        for (Map<String, Object> row : completed) {
            if (row == null || !(row.get("dataType") instanceof Number)
                    || !(row.get("dataId") instanceof Number)) continue;
            Long type = number(row.get("dataType"));
            String referenceType = Long.valueOf(1L).equals(type) ? "ACTIVITY"
                    : Long.valueOf(2L).equals(type) ? "TOPIC" : null;
            if (referenceType == null) continue;
            Long referenceId = number(row.get("dataId"));
            if (referenceId == null || referenceId <= 0) continue;
            Map<String, Object> snapshot = mapper.selectReferenceSnapshot(referenceType, referenceId, memberId);
            if (snapshot != null && !snapshot.isEmpty()) result.add(row);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> referenceOptions(Long memberId, String referenceType, Integer limit) {
        requirePrincipal(memberId);
        String type = upper(referenceType);
        // P0-24:可选项白名单必须与发帖校验(REFERENCE_TYPES)同一份,否则选得出、发不出(400)。
        if (!REFERENCE_TYPES.contains(type)) {
            throw error("不支持的关联类型", 400);
        }
        return mapper.selectReferenceOptions(type, memberId, boundedLimit(limit));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> feed(Long viewerId, CommunityPostFeedQuery query) {
        viewerId = viewerId == null || viewerId < 0 ? 0L : viewerId;
        if (query == null) query = new CommunityPostFeedQuery();
        String mode = upper(defaultIfBlank(query.getMode(), "LATEST"));
        if (!FEEDS.contains(mode)) throw error("不支持的广场分区", 400);
        if ("FOLLOWING".equals(mode)) requirePrincipal(viewerId);
        int limit = boundedLimit(query.getLimit());
        if ("NEARBY".equals(mode) && blank(query.getCityCode())) throw error("附近分区需要城市", 400);
        if ("TOPIC".equals(mode) && blank(query.getTopicCode())) throw error("话题分区需要 topicCode", 400);
        if ("COMMUNITY".equals(mode) && !positive(query.getCommunityId())) throw error("社群分区需要 communityId", 400);
        if ("FEATURED".equals(mode) && blank(query.getCollectionCode())) query.setCollectionCode("CITY_PICK");
        query.setViewerId(viewerId);
        query.setMode(mode);
        query.setLimit(limit + 1);
        List<CommunityPost> rows = mapper.selectFeed(query);
        boolean hasMore = rows.size() > limit;
        if (hasMore) rows = new ArrayList<>(rows.subList(0, limit));
        List<Map<String, Object>> items = new ArrayList<>();
        for (CommunityPost post : rows) items.add(hydrate(post, viewerId));
        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("hasMore", hasMore);
        result.put("nextCursor", rows.isEmpty() ? null : rows.get(rows.size() - 1).getId());
        result.put("nextCursorScore", rows.isEmpty() ? null : rows.get(rows.size() - 1).getTrendingScore());
        result.put("mode", mode);
        return result;
    }

    @Override
    public Map<String, Object> bookmarks(Long memberId, Long cursor, Integer limit) {
        return personalPosts(memberId, cursor, limit, true);
    }

    @Override
    public Map<String, Object> ownPosts(Long memberId, Long cursor, Integer limit) {
        return personalPosts(memberId, cursor, limit, false);
    }

    private Map<String, Object> personalPosts(Long memberId, Long cursor, Integer requestedLimit, boolean bookmarks) {
        requirePrincipal(memberId);
        int limit = boundedLimit(requestedLimit);
        CommunityPostFeedQuery query = new CommunityPostFeedQuery();
        query.setViewerId(memberId);
        query.setCursor(cursor);
        query.setLimit(limit + 1);
        query.setMode(bookmarks ? "BOOKMARKS" : "OWN_POSTS");
        List<CommunityPost> rows = bookmarks ? mapper.selectBookmarkedFeed(query) : mapper.selectOwnPosts(query);
        boolean hasMore = rows.size() > limit;
        if (hasMore) rows = new ArrayList<>(rows.subList(0, limit));
        List<Map<String, Object>> items = new ArrayList<>();
        for (CommunityPost post : rows) items.add(hydrate(post, memberId));
        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("hasMore", hasMore);
        result.put("nextCursor", rows.isEmpty() ? null : rows.get(rows.size() - 1).getId());
        result.put("mode", query.getMode());
        return result;
    }

    @Override
    public Map<String, Object> drafts(Long memberId, Long cursor, Integer limit) {
        requirePrincipal(memberId);
        List<CommunityPost> rows = mapper.selectAuthorDrafts(memberId, cursor, boundedLimit(limit));
        List<Map<String, Object>> items = new ArrayList<>();
        for (CommunityPost post : rows) items.add(hydrate(post, memberId));
        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("nextCursor", rows.isEmpty() ? null : rows.get(rows.size() - 1).getId());
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> act(Long memberId, Long postId, CommunityPostRequests.Action request) {
        requirePrincipal(memberId);
        if (request == null) throw error("缺少互动参数", 400);
        String action = upper(request.getActionType());
        if (!ACTIONS.contains(action)) throw error("不支持的互动类型", 400);
        requireRequestId(request.getRequestId());
        CommunityPost post = mapper.selectById(postId, memberId);
        if (post == null) throw error("帖文不存在或不可见", 404);
        String commandType = "POST_ACTION_" + action;
        if (isCommandReplay(memberId, request.getRequestId(), commandType, postId)) {
            return feedbackResultOrDetail(memberId, postId, action);
        }
        if (!Integer.valueOf(1).equals(post.getInteractionEnabled()) && ("LIKE".equals(action) || "BOOKMARK".equals(action))) {
            throw error("该帖文已关闭互动", 409);
        }
        boolean inserted = false;
        if (mapper.countMemberAction(postId, memberId, action) == 0) {
            try {
                inserted = mapper.insertMemberAction(postId, memberId, action, request.getRequestId(),
                        trimToNull(request.getSource()), new Date()) == 1;
            } catch (DuplicateKeyException e) {
                if (mapper.countMemberAction(postId, memberId, action) == 0) throw error("请求号已被其他互动使用", 409);
            }
        }
        Date now = new Date();
        if (inserted) {
            mapper.recomputeCounts(postId);
            if (("LIKE".equals(action) || "BOOKMARK".equals(action)) && !memberId.equals(post.getAuthorId())) {
                notifyMember(post.getAuthorId(), postId, memberId, "INTERACTION", action, now);
            }
            outbox(postId, "POST_ACTION_CHANGED", payload("action", action), now);
        }
        saveCommandReceipt(memberId, request.getRequestId(), commandType, postId, now);
        return feedbackResultOrDetail(memberId, postId, action);
    }

    @Override
    @Transactional
    public Map<String, Object> unact(Long memberId, Long postId, String actionType, String requestId) {
        requirePrincipal(memberId);
        String action = upper(actionType);
        if (!ACTIONS.contains(action)) throw error("不支持的互动类型", 400);
        requireRequestId(requestId);
        String commandType = "POST_UNACTION_" + action;
        if (isCommandReplay(memberId, requestId, commandType, postId)) return detail(memberId, postId);
        mapper.deleteMemberAction(postId, memberId, action);
        mapper.recomputeCounts(postId);
        Date now = new Date();
        outbox(postId, "POST_ACTION_CHANGED", payload("action", "UN" + action), now);
        saveCommandReceipt(memberId, requestId, commandType, postId, now);
        return detail(memberId, postId);
    }

    @Override
    @Transactional
    public Map<String, Object> comment(Long memberId, Long postId, CommunityPostRequests.Comment request) {
        requirePrincipal(memberId);
        if (mapper.countActiveMemberEnforcement(memberId, Arrays.asList("COMMENT_TIMEOUT", "READ_ONLY", "TEMP_SUSPEND", "PERMANENT_BAN"), new Date()) > 0) {
            throw error("当前账号暂不可回复", 403);
        }
        if (request == null || blank(request.getBody()) || request.getBody().trim().length() > 2000) {
            throw error("评论正文应为 1-2000 字", 400);
        }
        requireRequestId(request.getClientRequestId());
        Map<String, Object> replay = mapper.selectCommentByAuthorRequest(memberId, request.getClientRequestId());
        if (replay != null) {
            if (!postId.equals(number(value(replay, "post_id")))
                    || !request.getBody().trim().equals(String.valueOf(value(replay, "body")))
                    || !sameCommentParent(number(value(replay, "parent_id")), request.getParentId())) {
                throw error("同一请求号不能用于不同回复", 409);
            }
            return commentResult(memberId, postId, String.valueOf(value(replay, "author_approval_state")),
                    String.valueOf(value(replay, "lifecycle")));
        }
        CommunityPost post = mapper.selectById(postId, memberId);
        if (post == null) throw error("帖文不存在或不可见", 404);
        boolean follows = mapper.countFollow(memberId, post.getAuthorId()) > 0;
        boolean member = positive(post.getCommunityId()) && mapper.countCommunityMember(memberId, post.getCommunityId()) > 0;
        boolean mentioned = mapper.countMention(postId, memberId) > 0;
        if (!policy.canComment(post, memberId, follows, member, mentioned)) throw error("作者限制了谁可以回复", 403);
        enforceSlowMode(post, memberId);
        Long rootId = null;
        Long parentId = request.getParentId();
        Long repliedToMemberId = null;
        if (positive(parentId)) {
            Map<String, Object> parent = mapper.selectCommentForReply(parentId, postId, memberId);
            if (parent == null) throw error("回复目标不存在或不可见", 404);
            Long parentRoot = number(value(parent, "root_id"));
            rootId = positive(parentRoot) ? parentRoot : number(value(parent, "id"));
            repliedToMemberId = number(value(parent, "author_id"));
        } else if (positive(request.getRootId()) || positive(request.getRepliedToMemberId())) {
            throw error("回复关系必须以可见父评论为准", 400);
        }
        String approval = Integer.valueOf(1).equals(post.getReplyApprovalEnabled()) && !memberId.equals(post.getAuthorId())
                ? "PENDING" : "VISIBLE";
        Date now = new Date();
        try {
            mapper.insertComment(postId, memberId, rootId, parentId, repliedToMemberId,
                    request.getClientRequestId(), request.getBody().trim(), "CHECKING", approval, now);
        } catch (DuplicateKeyException e) {
            replay = mapper.selectCommentByAuthorRequestForUpdate(memberId, request.getClientRequestId());
            if (replay == null || !postId.equals(number(value(replay, "post_id")))
                    || !request.getBody().trim().equals(String.valueOf(value(replay, "body")))
                    || !sameCommentParent(number(value(replay, "parent_id")), request.getParentId())) {
                throw error("回复请求号已被使用", 409);
            }
            return commentResult(memberId, postId, String.valueOf(value(replay, "author_approval_state")),
                    String.valueOf(value(replay, "lifecycle")));
        }
        Map<String, Object> inserted = mapper.selectCommentByAuthorRequest(memberId, request.getClientRequestId());
        if (inserted == null) throw error("回复保存失败", 500);
        Long commentId = number(value(inserted, "id"));
        PublishSafetyRequest safety = new PublishSafetyRequest();
        safety.setContentType(AuditTask.BIZ_COMMUNITY_POST_COMMENT);
        safety.setContentId(commentId);
        safety.setMemberId(memberId);
        safety.setOpenid(mapper.selectMemberWxOpenId(memberId));
        safety.setText(request.getBody().trim());
        safety.setMediaUrls(Collections.<String>emptyList());
        safety.setSnapshot(json(inserted));
        safety.setReviewMode(PublishSafetyRequest.ReviewMode.PRE_REVIEW.name());
        SafetyDecision decision = safetyGate.decide(safety);
        if (decision == null || decision.isBlock()) throw error("回复未通过安全检查，请修改后重试", 400);
        String lifecycle = decision.isPass() ? "PUBLISHED" : "CHECKING";
        if (decision.isPass() && mapper.updateCommentLifecycle(commentId, "CHECKING", "PUBLISHED", 0, now) != 1) stale();
        mapper.recomputeCounts(postId);
        if (decision.isPass()) {
            if (!memberId.equals(post.getAuthorId())) {
                notifyMember(post.getAuthorId(), postId, memberId, "INTERACTION", "COMMENT", commentId, now);
            }
            if (positive(repliedToMemberId) && !repliedToMemberId.equals(memberId)
                    && !repliedToMemberId.equals(post.getAuthorId())) {
                notifyMember(repliedToMemberId, postId, memberId, "INTERACTION", "REPLY", commentId, now);
            }
        }
        outbox(postId, decision.isPass() ? "POST_COMMENTED" : "COMMENT_REVIEW_REQUESTED",
                payload("commentId", commentId), now);
        return commentResult(memberId, postId, approval, lifecycle);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> comments(Long viewerId, Long postId, Long cursor, Integer limit) {
        viewerId = viewerId == null || viewerId < 0 ? 0L : viewerId;
        detail(viewerId, postId);
        int rootLimit = boundedLimit(limit);
        List<Map<String, Object>> rows = visibleCommentThreadRows(
                mapper.selectComments(postId, viewerId, cursor, rootLimit + 1));
        Long nextCursor = null;
        int roots = 0;
        boolean hasMore = false;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            if (value(row, "parent_id") == null) {
                if (++roots > rootLimit) {
                    rows = new ArrayList<>(rows.subList(0, i));
                    hasMore = true;
                    break;
                }
                nextCursor = number(value(row, "id"));
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("items", rows);
        result.put("hasMore", hasMore);
        result.put("nextCursor", nextCursor);
        return result;
    }

    private List<Map<String, Object>> visibleCommentThreadRows(List<Map<String, Object>> rows) {
        Map<Long, Map<String, Object>> byId = new HashMap<>();
        Set<Long> neededDeletedIds = new HashSet<>();
        for (Map<String, Object> row : rows) byId.put(number(value(row, "id")), row);
        for (Map<String, Object> row : rows) {
            if ("DELETED".equals(value(row, "lifecycle"))) continue;
            Long rootId = number(value(row, "root_id"));
            Map<String, Object> root = byId.get(rootId);
            if (root != null && "DELETED".equals(value(root, "lifecycle"))) neededDeletedIds.add(rootId);
            Long parentId = number(value(row, "parent_id"));
            Map<String, Object> parent = byId.get(parentId);
            while (parent != null && "DELETED".equals(value(parent, "lifecycle"))
                    && neededDeletedIds.add(parentId)) {
                parentId = number(value(parent, "parent_id"));
                parent = byId.get(parentId);
            }
        }
        List<Map<String, Object>> visibleRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!"DELETED".equals(value(row, "lifecycle"))) {
                visibleRows.add(row);
            } else if (neededDeletedIds.contains(number(value(row, "id")))) {
                Map<String, Object> placeholder = new HashMap<>(row);
                placeholder.keySet().removeIf(key -> "body".equalsIgnoreCase(key));
                placeholder.put("body", "");
                placeholder.put("deletedPlaceholder", true);
                visibleRows.add(placeholder);
            }
        }
        return visibleRows;
    }

    @Override
    @Transactional
    public void approveComment(Long memberId, Long postId, Long commentId, CommunityPostRequests.State request) {
        requirePrincipal(memberId);
        requireRequestId(request == null ? null : request.getRequestId());
        if (isCommandReplay(memberId, request.getRequestId(), "COMMENT_APPROVE", commentId)) return;
        int expectedVersion = requiredVersion(request.getExpectedVersion());
        Date now = new Date();
        if (mapper.approveComment(postId, commentId, memberId, expectedVersion, now) != 1) {
            throw error("待审核回复不存在、已变化或无权操作", 409);
        }
        mapper.recomputeCounts(postId);
        outbox(postId, "COMMENT_APPROVED", payload("commentId", commentId), now);
        saveCommandReceipt(memberId, request.getRequestId(), "COMMENT_APPROVE", commentId, now);
    }

    @Override
    @Transactional
    public void deleteComment(Long memberId, Long postId, Long commentId, CommunityPostRequests.State request) {
        requirePrincipal(memberId);
        requireRequestId(request == null ? null : request.getRequestId());
        if (isCommandReplay(memberId, request.getRequestId(), "COMMENT_DELETE", commentId)) return;
        int expectedVersion = requiredVersion(request.getExpectedVersion());
        Date now = new Date();
        if (mapper.deleteComment(postId, commentId, memberId, expectedVersion, now) != 1) {
            throw error("评论不存在、已变化或无权删除", 409);
        }
        mapper.recomputeCounts(postId);
        outbox(postId, "COMMENT_DELETED", payload("commentId", commentId), now);
        saveCommandReceipt(memberId, request.getRequestId(), "COMMENT_DELETE", commentId, now);
    }

    @Override
    @Transactional
    public void recordShare(Long memberId, Long postId, String requestId) {
        requirePrincipal(memberId);
        requireRequestId(requestId);
        if (isCommandReplay(memberId, requestId, "POST_SHARE", postId)) return;
        if (mapper.selectById(postId, memberId) == null) {
            throw error("帖文不存在或不可分享", 404);
        }
        Date now = new Date();
        if (!trySaveCommandReceipt(memberId, requestId, "POST_SHARE", postId, now)) return;
        if (mapper.incrementShareCount(postId, now) != 1) throw error("帖文不存在或不可分享", 404);
        outbox(postId, "POST_SHARED", payload("memberId", memberId), now);
    }

    @Override
    @Transactional
    public void likeComment(Long memberId, Long commentId, String requestId) {
        requirePrincipal(memberId);
        requireRequestId(requestId);
        // Keep the same action -> receipt lock order as UNLIKE, including legacy migration.
        Map<String, Object> action = mapper.selectCommentLikeActionForUpdate(commentId, memberId);
        Map<String, Object> receipt = mapper.selectCommandReceiptForUpdate(memberId, requestId);
        if (receipt != null) {
            requireCommentLikeReceipt(receipt, commentId, false);
            return;
        }
        Date now = new Date();
        if (action != null) {
            if (!requestId.equals(value(action, "request_id"))) throw error("评论已点赞或请求号冲突", 409);
            preserveCommentLikeReceipt(memberId, commentId, requestId, now, false);
            return;
        }
        try {
            if (mapper.insertCommentAction(commentId, memberId, requestId, now) != 1) throw error("评论不存在或不可互动", 404);
        } catch (DuplicateKeyException e) {
            action = mapper.selectCommentLikeActionForUpdate(commentId, memberId);
            if (action == null || !requestId.equals(value(action, "request_id"))) {
                throw error("评论已点赞或请求号冲突", 409);
            }
            preserveCommentLikeReceipt(memberId, commentId, requestId, now, false);
            return;
        }
        Map<String, Object> comment = mapper.selectCommentForAudit(commentId);
        Long postId = comment == null ? null : number(value(comment, "post_id"));
        if (!positive(postId)) throw error("评论所属帖文不存在", 404);
        mapper.recomputeCommentLikes(commentId);
        Long commentAuthorId = number(value(comment, "author_id"));
        if (positive(commentAuthorId) && !memberId.equals(commentAuthorId)) {
            notifyMember(commentAuthorId, postId, memberId, "INTERACTION", "COMMENT_LIKE", commentId, now);
        }
        outbox(postId, "COMMENT_LIKED", payload("commentId", commentId), now);
        preserveCommentLikeReceipt(memberId, commentId, requestId, now, false);
    }

    @Override
    @Transactional
    public void unlikeComment(Long memberId, Long commentId, String requestId) {
        requirePrincipal(memberId);
        requireRequestId(requestId);
        Map<String, Object> action = mapper.selectCommentLikeActionForUpdate(commentId, memberId);
        String originalRequestId = action == null ? null : (String) value(action, "request_id");
        // Two legacy actions may cross-use request IDs. Lock all receipt keys in a stable order.
        if (originalRequestId != null && originalRequestId.compareTo(requestId) < 0) {
            mapper.selectCommandReceiptForUpdate(memberId, originalRequestId);
        }
        Map<String, Object> receipt = mapper.selectCommandReceiptForUpdate(memberId, requestId);
        if (originalRequestId != null && originalRequestId.compareTo(requestId) > 0) {
            mapper.selectCommandReceiptForUpdate(memberId, originalRequestId);
        }
        if (receipt != null) {
            if (!"COMMENT_UNLIKE".equals(String.valueOf(value(receipt, "command_type")))
                    || !commentId.equals(number(value(receipt, "aggregate_id")))) {
                throw error("同一请求号不能用于不同命令", 409);
            }
            return;
        }
        Map<String, Object> comment = mapper.selectCommentForAudit(commentId);
        Long postId = comment == null ? null : number(value(comment, "post_id"));
        if (!positive(postId)) throw error("评论不存在", 404);
        Date now = new Date();
        if (action != null) {
            if (blank(originalRequestId)) throw error("原评论点赞身份缺失", 500);
            preserveCommentLikeReceipt(memberId, commentId, originalRequestId, now, true);
        }
        int deleted = mapper.deleteCommentAction(commentId, memberId);
        mapper.recomputeCommentLikes(commentId);
        if (deleted > 0) outbox(postId, "COMMENT_UNLIKED", payload("commentId", commentId), now);
        saveCommandReceipt(memberId, requestId, "COMMENT_UNLIKE", commentId, now);
    }

    @Override
    public Map<String, Object> activeGuideline() {
        Map<String, Object> result = mapper.selectActiveGuideline();
        if (result == null) throw error("社区规范尚未启用", 503);
        return result;
    }

    @Override
    @Transactional
    public void acknowledgeGuideline(Long memberId, CommunityPostRequests.GuidelineAck request) {
        requirePrincipal(memberId);
        if (request == null || !positive(request.getGuidelineVersionId())) throw error("缺少规范版本", 400);
        requireRequestId(request.getRequestId());
        Map<String, Object> active = activeGuideline();
        if (!request.getGuidelineVersionId().equals(number(value(active, "id")))) throw error("请确认当前有效的社区规范", 409);
        if (mapper.countGuidelineAck(memberId, request.getGuidelineVersionId()) == 0) {
            try {
                mapper.insertGuidelineAck(memberId, request.getGuidelineVersionId(),
                        defaultIfBlank(request.getScene(), "PUBLISH"), request.getRequestId(), new Date());
            } catch (DuplicateKeyException e) {
                if (mapper.countGuidelineAck(memberId, request.getGuidelineVersionId()) == 0) throw error("请求号已被使用", 409);
            }
        }
    }

    @Override
    @Transactional
    public void mute(Long memberId, Long mutedMemberId, String requestId) {
        requirePrincipal(memberId);
        if (!positive(mutedMemberId) || memberId.equals(mutedMemberId)) throw error("不能静音该用户", 400);
        requireRequestId(requestId);
        Map<String, Object> replay = mapper.selectMuteByRequest(memberId, requestId);
        if (replay != null) {
            if (!mutedMemberId.equals(number(value(replay, "muted_member_id")))) {
                throw error("请求号已被其他静音操作使用", 409);
            }
            return;
        }
        try { mapper.insertMute(memberId, mutedMemberId, requestId, new Date()); }
        catch (DuplicateKeyException e) {
            replay = mapper.selectMuteByRequest(memberId, requestId);
            if (replay != null && !mutedMemberId.equals(number(value(replay, "muted_member_id")))) {
                throw error("请求号已被其他静音操作使用", 409);
            }
        }
    }

    @Override
    @Transactional
    public void unmute(Long memberId, Long mutedMemberId, String requestId) {
        requirePrincipal(memberId);
        requireRequestId(requestId);
        if (isCommandReplay(memberId, requestId, "MEMBER_UNMUTE", mutedMemberId)) return;
        mapper.deleteMute(memberId, mutedMemberId);
        saveCommandReceipt(memberId, requestId, "MEMBER_UNMUTE", mutedMemberId, new Date());
    }

    @Override
    @Transactional
    public void notifyFollow(Long followedMemberId, Long actorMemberId) {
        requirePrincipal(followedMemberId);
        requirePrincipal(actorMemberId);
        if (!followedMemberId.equals(actorMemberId)) {
            notifyMember(followedMemberId, null, actorMemberId, "SOCIAL", "FOLLOW", new Date());
        }
    }

    @Override
    public Object report(Long memberId, CommunityReportSubmitRequest request) {
        requirePrincipal(memberId);
        if (request == null) throw error("缺少举报参数", 400);
        String targetType = upper(defaultIfBlank(request.getTargetType(), "CONTENT"));
        if (!positive(request.getTargetId())) throw error("举报对象无效", 400);
        if ("COMMENT".equals(targetType)) {
            Map<String, Object> comment = mapper.selectCommentForAudit(request.getTargetId());
            Long postId = comment == null ? null : number(value(comment, "post_id"));
            Map<String, Object> visibleComment = postId == null ? null
                    : mapper.selectCommentForReply(request.getTargetId(), postId, memberId);
            if (comment == null || visibleComment == null || mapper.selectById(postId, memberId) == null) {
                throw error("评论不存在或不可见", 404);
            }
            if (memberId.equals(number(value(comment, "author_id")))) throw error("不能举报自己的评论", 400);
            request.setTargetType("COMMENT");
        } else {
            // S532-1:看得到的 LIMITED 帖也必须能举报,口径与 detail 一致(互动/评论/分享仍按既有 selectById,待 owner 定)。
            if (!"CONTENT".equals(targetType) || readableById(request.getTargetId(), memberId) == null) {
                throw error("帖文不存在或不可见", 404);
            }
            request.setTargetType("CONTENT");
        }
        return CommunityReportVO.from(communityTrustService.submitReport(memberId, request));
    }

    @Override
    @Transactional
    public void appeal(Long memberId, CommunityPostRequests.Appeal request) {
        requirePrincipal(memberId);
        if (request == null || !positive(request.getEnforcementId()) || blank(request.getReason())) throw error("缺少申诉内容", 400);
        requireRequestId(request.getRequestId());
        Map<String, Object> enforcement = mapper.selectEnforcement(request.getEnforcementId());
        if (enforcement == null) throw error("处置记录不存在", 404);
        Long targetMember = number(value(enforcement, "target_member_id"));
        Long postId = number(value(enforcement, "post_id"));
        if (targetMember == null && postId != null) {
            CommunityPost post = mapper.selectByIdForUpdate(postId);
            targetMember = post == null ? null : post.getAuthorId();
        }
        if (!memberId.equals(targetMember)) throw error("只能申诉自己的处置", 403);
        List<Long> evidenceAssetIds = request.getEvidenceAssetIds();
        if (evidenceAssetIds != null && !evidenceAssetIds.isEmpty()) {
            throw error("证据附件归属与脱敏链路尚未接通，请先提交文字申诉", 409);
        }
        Date now = new Date();
        Date dueAt = appealDueAt(enforcement, now);
        try {
            mapper.insertAppeal(request.getEnforcementId(), memberId, request.getReason().trim(),
                    json(Collections.emptyList()),
                    request.getRequestId(), dueAt, now);
        } catch (DuplicateKeyException e) { throw error("该处置已有进行中的申诉", 409); }
        notifyGovernance(memberId, postId, 0L, "APPEAL_SUBMITTED", request.getEnforcementId(), now);
        notifyAppealReceived(memberId, request.getEnforcementId(), postId, dueAt);
        if (positive(postId)) outbox(postId, "APPEAL_SUBMITTED", payload("enforcementId", request.getEnforcementId()), now);
        else typedOutbox("COMMUNITY_MEMBER", memberId, "ACCOUNT_APPEAL_SUBMITTED",
                payload("enforcementId", request.getEnforcementId()), now);
    }

    @Override
    public Map<String, Object> memberEnforcements(Long memberId, Long cursor, Integer limit) {
        requirePrincipal(memberId);
        List<Map<String, Object>> rows = mapper.selectMemberEnforcements(memberId, cursor, boundedLimit(limit));
        Map<String, Object> result = new HashMap<>();
        result.put("items", rows);
        result.put("nextCursor", rows.isEmpty() ? null : value(rows.get(rows.size() - 1), "id"));
        return result;
    }

    @Override
    public Map<String, Object> notifications(Long memberId, Long cursor, Integer limit) {
        requirePrincipal(memberId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> source : mapper.selectNotifications(memberId, cursor, boundedLimit(limit))) {
            Map<String, Object> row = new LinkedHashMap<>(source);
            if (!"SYSTEM_GOVERNANCE".equals(String.valueOf(value(row, "notification_type")))) {
                Object rawPayload = value(row, "payload_json");
                Map<String, Object> payload = JsonUtils.parseToMap(rawPayload == null ? null : rawPayload.toString());
                if (payload == null) payload = new LinkedHashMap<>();
                payload.put("actorId", number(value(row, "actor_member_id")));
                row.put("payload_json", json(payload));
            }
            rows.add(row);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("items", rows);
        result.put("nextCursor", rows.isEmpty() ? null : rows.get(rows.size() - 1).get("id"));
        return result;
    }

    @Override
    public Map<String, Object> notificationPreferences(Long memberId) {
        requirePrincipal(memberId);
        Map<String, Object> stored = mapper.selectNotificationPreferences(memberId);
        Map<String, Object> result = new HashMap<>();
        result.put("interactionEnabled", stored == null || truthy(value(stored, "interaction_enabled")));
        result.put("mentionEnabled", stored == null || truthy(value(stored, "mention_enabled")));
        result.put("socialEnabled", stored == null || truthy(value(stored, "social_enabled")));
        result.put("governanceEnabled", true);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> updateNotificationPreferences(Long memberId,
                                                              CommunityPostRequests.NotificationPreferences request) {
        requirePrincipal(memberId);
        if (request == null) throw error("缺少通知偏好", 400);
        if (mapper.upsertNotificationPreferences(memberId, request.getInteractionEnabled(),
                request.getMentionEnabled(), request.getSocialEnabled(), new Date()) < 0) {
            throw error("通知偏好保存失败", 500);
        }
        return notificationPreferences(memberId);
    }

    @Override
    @Transactional
    public void readNotification(Long memberId, Long notificationId, String requestId) {
        requirePrincipal(memberId);
        requireRequestId(requestId);
        if (isCommandReplay(memberId, requestId, "NOTIFICATION_READ", notificationId)) return;
        if (mapper.markNotificationRead(notificationId, memberId, new Date()) != 1) throw error("通知不存在", 404);
        saveCommandReceipt(memberId, requestId, "NOTIFICATION_READ", notificationId, new Date());
    }

    @Override
    @Transactional
    public void feature(Long operatorUserId, Long postId, CommunityPostRequests.Featured request) {
        if (request == null || blank(request.getCollectionCode()) || blank(request.getReason())) throw error("缺少精选参数", 400);
        requireRequestId(request.getRequestId());
        String commandType = fingerprintedCommand("POST_FEATURE", request.getCollectionCode(), request.getCityCode(),
                request.getReason(), request.getStartsAt(), request.getEndsAt());
        CommunityPost post = mapper.selectByIdForUpdate(postId);
        if (isCommandReplay(operatorUserId, request.getRequestId(), commandType, postId)) return;
        if (post == null || !CommunityPost.LIFECYCLE_PUBLISHED.equals(post.getLifecycle())) throw error("只能精选已发布帖文", 409);
        Date now = new Date();
        mapper.insertFeatured(postId, upper(request.getCollectionCode()), trimToNull(request.getCityCode()),
                request.getReason().trim(), operatorUserId, request.getStartsAt() == null ? now : request.getStartsAt(), request.getEndsAt(), now);
        notifyMember(post.getAuthorId(), postId, operatorUserId, "SOCIAL", "FEATURED", now);
        outbox(postId, "POST_FEATURED", payload("collection", request.getCollectionCode()), now);
        saveCommandReceipt(operatorUserId, request.getRequestId(), commandType, postId, now);
    }

    @Override
    @Transactional
    public void enforce(Long operatorUserId, Long postId, CommunityPostRequests.Enforcement request) {
        applyPostEnforcement(operatorUserId, postId, request, null);
    }

    @Override
    @Transactional
    public Long enforceComment(Long operatorUserId, Long commentId, CommunityPostRequests.Enforcement request) {
        return applyCommentEnforcement(operatorUserId, commentId, request, null);
    }

    /**
     * 举报→处置桥接(AUDIT-G3-5):举报单裁定违规时由 CommunityTrustService 在同一事务里调用,
     * 处置行带 report_case_id 回指举报单。CONTENT 走帖文处置,COMMENT 走评论处置;返回处置 id。
     */
    @Override
    @Transactional
    public Long enforceReportedTarget(Long operatorUserId, Long reportCaseId, String targetType, Long targetId,
                                      CommunityPostRequests.Enforcement request) {
        if (!positive(reportCaseId) || !positive(targetId)) throw error("举报处置参数不完整", 400);
        String type = upper(targetType);
        if ("CONTENT".equals(type)) return applyPostEnforcement(operatorUserId, targetId, request, reportCaseId);
        if ("COMMENT".equals(type)) return applyCommentEnforcement(operatorUserId, targetId, request, reportCaseId);
        throw error("该举报对象不支持内容处置", 400);
    }

    private void validateEnforcementRequest(CommunityPostRequests.Enforcement request) {
        if (request == null || !ENFORCEMENTS.contains(upper(request.getEnforcementType())) || blank(request.getRuleCode())
                || !positive(request.getGuidelineVersionId()) || blank(request.getReasonCode())
                || !nonEmptyJsonObject(request.getEvidenceJson())) throw error("处置参数不完整", 400);
        requireRequestId(request.getRequestId());
        if (mapper.countGuidelineVersion(request.getGuidelineVersionId()) != 1) {
            throw error("社区规范版本不存在", 409);
        }
        if (mapper.countGuidelineRule(request.getGuidelineVersionId(), upper(request.getRuleCode())) != 1) {
            throw error("处置规则不属于该社区规范版本", 409);
        }
    }

    private Long applyPostEnforcement(Long operatorUserId, Long postId, CommunityPostRequests.Enforcement request,
                                      Long reportCaseId) {
        validateEnforcementRequest(request);
        String ruleCode = upper(request.getRuleCode());
        String commandType = fingerprintedCommand("POST_ENFORCE", request.getTargetMemberId(), request.getEnforcementType(),
                ruleCode, request.getGuidelineVersionId(), request.getReasonCode(), request.getEvidenceJson(),
                request.getStartsAt(), request.getEndsAt());
        CommunityPost post = locked(postId);
        if (isCommandReplay(operatorUserId, request.getRequestId(), commandType, postId)) {
            return number(value(mapper.selectEnforcementByRequest(operatorUserId, request.getRequestId()), "id"));
        }
        Date now = new Date();
        String type = upper(request.getEnforcementType());
        if (MEMBER_ENFORCEMENTS.contains(type)
                && !post.getAuthorId().equals(request.getTargetMemberId())) {
            throw error("账号级处置必须明确指向帖文作者", 400);
        }
        if (!(CommunityPost.LIFECYCLE_PUBLISHED.equals(post.getLifecycle())
                || CommunityPost.LIFECYCLE_LIMITED.equals(post.getLifecycle())
                || CommunityPost.LIFECYCLE_HIDDEN.equals(post.getLifecycle())
                || CommunityPost.LIFECYCLE_REMOVED.equals(post.getLifecycle()))) {
            throw error("只能处置已经发布或已受治理限制的帖文", 409);
        }
        Date startsAt = request.getStartsAt() == null ? now : request.getStartsAt();
        if (request.getEndsAt() != null && !request.getEndsAt().after(startsAt)) {
            throw error("处置结束时间必须晚于开始时间", 400);
        }
        String previousLifecycle = post.getLifecycle();
        if (CommunityPost.LIFECYCLE_HIDDEN.equals(previousLifecycle)
                || CommunityPost.LIFECYCLE_REMOVED.equals(previousLifecycle)) {
            previousLifecycle = defaultIfBlank(mapper.selectActiveEnforcementBaseline(postId, now), previousLifecycle);
        }
        try {
            mapper.insertEnforcement(postId, request.getTargetMemberId(), type, ruleCode,
                    request.getGuidelineVersionId(), request.getReasonCode(), request.getEvidenceJson().trim(),
                    previousLifecycle,
                    operatorUserId, startsAt, request.getEndsAt(), request.getRequestId(), now);
        } catch (DuplicateKeyException e) { throw error("处置请求号已使用", 409); }
        Map<String, Object> enforcement = mapper.selectEnforcementByRequest(operatorUserId, request.getRequestId());
        if (enforcement == null) throw error("处置记录保存失败", 500);
        Long enforcementId = number(value(enforcement, "id"));
        if (reportCaseId != null && mapper.linkEnforcement(enforcementId, reportCaseId, null) != 1) {
            throw error("处置与举报单关联失败", 500);
        }
        if (!startsAt.after(now)) reconcileEnforcements(postId, now);
        Long notificationTarget = MEMBER_ENFORCEMENTS.contains(type)
                ? request.getTargetMemberId() : post.getAuthorId();
        notifyGovernance(notificationTarget, postId, operatorUserId, type, enforcementId, now);
        outbox(postId, "POST_ENFORCED", payload("type", type), now);
        saveCommandReceipt(operatorUserId, request.getRequestId(), commandType, postId, now);
        return enforcementId;
    }

    /**
     * 评论处置(AUDIT-G3-6):只支持 REMOVE,把已公开的评论下架。处置行 post_id 留空、comment_id 指向评论、
     * target_member_id 指向评论作者 —— 帖文级的生效/恢复查询都按 post_id 走,评论处置不会被当成整帖下架;
     * 评论作者在「我的处置」里能看到并申诉,申诉撤销时恢复评论(见 reviewAppeal)。
     */
    private Long applyCommentEnforcement(Long operatorUserId, Long commentId, CommunityPostRequests.Enforcement request,
                                         Long reportCaseId) {
        validateEnforcementRequest(request);
        String type = upper(request.getEnforcementType());
        if (!"REMOVE".equals(type)) throw error("评论只支持下架处置", 400);
        if (request.getEndsAt() != null) throw error("评论下架不支持设置结束时间", 400);
        if (!positive(commentId)) throw error("评论 id 无效", 400);
        String ruleCode = upper(request.getRuleCode());
        String commandType = fingerprintedCommand("COMMENT_ENFORCE", type, ruleCode, request.getGuidelineVersionId(),
                request.getReasonCode(), request.getEvidenceJson());
        Map<String, Object> comment = mapper.selectCommentForAudit(commentId);
        if (comment == null) throw error("评论不存在", 404);
        if (isCommandReplay(operatorUserId, request.getRequestId(), commandType, commentId)) {
            return number(value(mapper.selectEnforcementByRequest(operatorUserId, request.getRequestId()), "id"));
        }
        if (!"PUBLISHED".equals(String.valueOf(value(comment, "lifecycle")))) {
            throw error("只能处置已公开的评论", 409);
        }
        Integer version = integer(value(comment, "version"));
        Long postId = number(value(comment, "post_id"));
        Long authorId = number(value(comment, "author_id"));
        Date now = new Date();
        if (version == null || mapper.updateCommentLifecycle(commentId, "PUBLISHED", "REMOVED", version, now) != 1) stale();
        try {
            mapper.insertEnforcement(null, authorId, type, ruleCode, request.getGuidelineVersionId(),
                    request.getReasonCode(), request.getEvidenceJson().trim(), "PUBLISHED",
                    operatorUserId, now, null, request.getRequestId(), now);
        } catch (DuplicateKeyException e) { throw error("处置请求号已使用", 409); }
        Map<String, Object> enforcement = mapper.selectEnforcementByRequest(operatorUserId, request.getRequestId());
        if (enforcement == null) throw error("处置记录保存失败", 500);
        Long enforcementId = number(value(enforcement, "id"));
        if (mapper.linkEnforcement(enforcementId, reportCaseId, commentId) != 1) throw error("评论处置关联失败", 500);
        mapper.recomputeCounts(postId);
        notifyGovernance(authorId, postId, operatorUserId, "COMMENT_" + type, enforcementId, now);
        outbox(postId, "COMMENT_ENFORCED", payload("commentId", commentId), now);
        saveCommandReceipt(operatorUserId, request.getRequestId(), commandType, commentId, now);
        return enforcementId;
    }

    /** 申诉时限：来源举报单的首响时限快照、处置原因/规则编码命中的举报原因取最短；都没有则标准 24 小时。 */
    private Date appealDueAt(Map<String, Object> enforcement, Date now) {
        long minutes = APPEAL_STANDARD_DUE_MINUTES;
        Long reportCaseId = number(value(enforcement, "report_case_id"));
        if (positive(reportCaseId)) {
            Integer caseMinutes = mapper.selectReportCaseFirstResponseMinutes(reportCaseId);
            if (caseMinutes != null && caseMinutes > 0) minutes = Math.min(minutes, caseMinutes);
        }
        for (String key : new String[]{"reason_code", "rule_code"}) {
            Object code = value(enforcement, key);
            CommunityReportPolicy.Reason reason = code == null ? null : CommunityReportPolicy.find(upper(code.toString()));
            if (reason != null) minutes = Math.min(minutes, reason.getFirstResponseMinutes());
        }
        return new Date(now.getTime() + minutes * 60_000L);
    }

    /** 申诉已受理：与审核通知同一系统消息通道与卡片结构，同事务写入；requestId 重放撞唯一键在此之前返回，不重复通知。 */
    private void notifyAppealReceived(Long memberId, Long enforcementId, Long postId, Date dueAt) {
        com.chengyinhub.business.service.IImService im = imServiceProvider == null ? null : imServiceProvider.getIfAvailable();
        if (im == null) {
            log.warn("[社区申诉] 系统消息服务不可用，申诉受理通知未发送: enforcementId={}", enforcementId);
            return;
        }
        String deadline = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(dueAt);
        String outcome = "你的申诉已受理，平台将在 " + deadline + " 前完成复核";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bizType", "COMMUNITY_POST_ENFORCEMENT");
        result.put("bizId", enforcementId);
        result.put("postId", postId);
        result.put("outcome", outcome);
        result.put("dueAt", dueAt);
        result.put("followUp", "复核期间原处理暂时维持，结果会再通知你。");
        im.sendSystem(memberId, com.chengyinhub.business.domain.ImMessage.MSG_CARD, "申诉已受理",
                com.chengyinhub.business.service.support.SystemNoticeCard.json("申诉已受理", outcome + "。", result));
    }

    @Override
    @Transactional
    public Map<String, Object> claimAppeal(Long operatorUserId, Long appealId, Integer expectedVersion) {
        if (!positive(operatorUserId)) throw error("请先登录", 401);
        if (!positive(appealId)) throw error("申诉 id 无效", 400);
        Map<String, Object> appeal = mapper.selectAppeal(appealId);
        if (appeal == null) throw error("申诉不存在", 404);
        String status = String.valueOf(value(appeal, "status"));
        Long reviewer = number(value(appeal, "reviewer_user_id"));
        // 同一审核员重复打开 = 幂等；他人已认领或已结案 = 409。
        if (!"REVIEWING".equals(status) || !operatorUserId.equals(reviewer)) {
            if (!"SUBMITTED".equals(status)) throw error("申诉已被认领或已处理", 409);
            int version = requiredVersion(expectedVersion);
            if (mapper.claimAppealCas(appealId, operatorUserId, version, new Date()) != 1) {
                throw error("申诉已被认领或已处理", 409);
            }
            appeal = mapper.selectAppeal(appealId);
        }
        return appeal;
    }

    @Override
    @Transactional
    public void reviewAppeal(Long operatorUserId, Long appealId, CommunityPostRequests.AppealReview request) {
        if (request == null || !("UPHELD".equals(upper(request.getDecision())) || "REVERSED".equals(upper(request.getDecision())))
                || blank(request.getReason())) throw error("申诉结论不完整", 400);
        Map<String, Object> appeal = mapper.selectAppeal(appealId);
        if (appeal == null) throw error("申诉不存在", 404);
        Long enforcementId = number(value(appeal, "enforcement_id"));
        Map<String, Object> enforcement = mapper.selectEnforcement(enforcementId);
        if (enforcement == null) throw error("原处置不存在", 409);
        Long postId = number(value(enforcement, "post_id"));
        int version = requiredVersion(request.getExpectedVersion());
        String decision = upper(request.getDecision());
        Date now = new Date();
        if (mapper.reviewAppealCas(appealId, decision, request.getReason().trim(), operatorUserId, version, now) != 1) stale();
        Long commentId = number(value(enforcement, "comment_id"));
        if ("REVERSED".equals(decision)) {
            Integer enforcementVersion = integer(value(enforcement, "version"));
            if (mapper.reverseEnforcement(enforcementId, enforcementVersion == null ? 0 : enforcementVersion, now) != 1) stale();
            if (commentId != null) restoreEnforcedComment(commentId, now);
            else if (postId != null) reconcileEnforcements(postId, now);
        }
        notifyGovernance(number(value(appeal, "appellant_member_id")), postId, operatorUserId,
                "APPEAL_" + decision, enforcementId, now);
        if (positive(postId)) outbox(postId, "APPEAL_REVIEWED", payload("decision", decision), now);
        else typedOutbox("COMMUNITY_MEMBER", number(value(appeal, "appellant_member_id")),
                "ACCOUNT_APPEAL_REVIEWED", payload("decision", decision), now);
    }

    /** 评论处置被申诉撤销:REMOVED 的评论放回 PUBLISHED;作者已自行删除(DELETED)的不复活。 */
    private void restoreEnforcedComment(Long commentId, Date now) {
        Map<String, Object> comment = mapper.selectCommentForAudit(commentId);
        if (comment == null || !"REMOVED".equals(String.valueOf(value(comment, "lifecycle")))) return;
        Integer version = integer(value(comment, "version"));
        if (version == null || mapper.updateCommentLifecycle(commentId, "REMOVED", "PUBLISHED", version, now) != 1) stale();
        mapper.recomputeCounts(number(value(comment, "post_id")));
    }

    @Override
    public List<Map<String, Object>> enforcements(Long postId) {
        if (!positive(postId)) throw error("帖文 id 无效", 400);
        return mapper.selectEnforcements(postId);
    }

    @Override
    public Map<String, Object> appeals(String status, Long cursor, Integer limit) {
        String normalized = blank(status) ? null : upper(status);
        if (normalized != null && !("SUBMITTED".equals(normalized) || "REVIEWING".equals(normalized)
                || "UPHELD".equals(normalized) || "REVERSED".equals(normalized))) throw error("申诉状态无效", 400);
        List<Map<String, Object>> rows = mapper.selectAppeals(normalized, cursor, boundedLimit(limit));
        Map<String, Object> result = new HashMap<>();
        result.put("items", rows);
        result.put("nextCursor", rows.isEmpty() ? null : value(rows.get(rows.size() - 1), "id"));
        return result;
    }

    @Override
    @Transactional
    public void applyAuditDecision(Long postId, boolean approved) {
        CommunityPost post = locked(postId);
        if (approved) requireGuidelineAck(post.getAuthorId(), post.getGuidelineVersionId());
        String action = approved ? CommunityPostLifecyclePolicy.ACTION_APPROVE : CommunityPostLifecyclePolicy.ACTION_REMOVE;
        CommunityPostLifecyclePolicy.Decision decision = policy.decide(post, action, post.getVersion(), false);
        Date now = new Date();
        if (mapper.transitionCas(postId, post.getLifecycle(), decision.getLifecycle(), post.getVersion(), approved ? now : null,
                null, approved ? 1 : 0, approved ? 1 : 0, approved ? 1 : 0, now) != 1) stale();
        mapper.updateMediaReviewState(postId, approved ? "PASS" : "REJECTED", now);
        if (!approved) recordReviewRejection(post, now);
        if (approved) {
            // 人审通过 → audit_level=2;从待审(CHECKING)放出来的 = 先审后发。从 HIDDEN 复核放回的是先发后审,不改该标记。
            mapper.updateAuditMarks(postId, AUDIT_LEVEL_HUMAN,
                    CommunityPost.LIFECYCLE_CHECKING.equals(post.getLifecycle()) ? 1 : null, null, now);
        }
        mapper.upsertSearchDocument(postId, approved ? "VISIBLE" : "HIDDEN", now);
        notifyMember(post.getAuthorId(), postId, 0L, "SYSTEM_GOVERNANCE", approved ? "APPROVED" : "REMOVED", now);
        outbox(postId, approved ? "POST_REVIEW_APPROVED" : "POST_REVIEW_REJECTED", payload("postId", postId), now);
    }

    /**
     * 人审驳回落一条 REMOVE 处置(AUDIT-G3-1):作者在「我的处置」里看得到,并能走既有申诉(按处置 id)。
     * 原实现只把帖子改成 REMOVED,没有处置行,作者无从申诉。previous_lifecycle 是申诉撤销后要恢复到的状态:
     * 待审(CHECKING)被驳回的,撤销即视为人审通过 → PUBLISHED;已公开/受限的恢复原状;HIDDEN 优先取既有处置基线。
     * 系统操作人记 0,request_id 由帖子 id + 驳回前版本号构成,同一次驳回重放不会重复建行。
     */
    private void recordReviewRejection(CommunityPost post, Date now) {
        String from = post.getLifecycle();
        String restoreTo = CommunityPost.LIFECYCLE_LIMITED.equals(from) ? CommunityPost.LIFECYCLE_LIMITED
                : CommunityPost.LIFECYCLE_HIDDEN.equals(from)
                ? defaultIfBlank(mapper.selectActiveEnforcementBaseline(post.getId(), now), CommunityPost.LIFECYCLE_PUBLISHED)
                : CommunityPost.LIFECYCLE_PUBLISHED;
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("source", "AUDIT_REVIEW_REJECTED");
        evidence.put("postId", post.getId());
        evidence.put("fromLifecycle", from);
        evidence.put("postVersion", post.getVersion());
        try {
            mapper.insertEnforcement(post.getId(), null, "REMOVE", "AUDIT_REVIEW_REJECTED", post.getGuidelineVersionId(),
                    "REVIEW_REJECTED", json(evidence), restoreTo, 0L, now, null,
                    "AUDIT_REJECT:" + post.getId() + ":" + post.getVersion(), now);
        } catch (DuplicateKeyException ignored) {
            // 同一驳回(同帖同版本)重放:处置行已在,保持幂等
        }
    }

    /**
     * 图片异步机审命中风险。复用生命周期 HIDE 而不是 LIMIT:
     * LIMIT 只是降分发(仅 PUBLISHED 可进),违规图仍挂在帖子上;HIDE 是「隐藏待复核」——与执法
     * HIDE_PENDING_REVIEW 落 HIDDEN 同义,且 APPROVE 可从 HIDDEN 放回,误判能由人审一键恢复。
     * 只处理公开态(PUBLISHED/LIMITED);CHECKING 本就待人审、HIDDEN/REMOVED/DELETED 已不公开,视为已落地。
     */
    @Override
    @Transactional
    public boolean applyRiskyMediaResult(Long postId) {
        CommunityPost post = mapper.selectByIdForUpdate(postId);
        if (post == null) return false;
        String lifecycle = post.getLifecycle();
        if (!CommunityPost.LIFECYCLE_PUBLISHED.equals(lifecycle) && !CommunityPost.LIFECYCLE_LIMITED.equals(lifecycle)) {
            return true;
        }
        int expectedVersion = post.getVersion() == null ? 0 : post.getVersion();
        CommunityPostLifecyclePolicy.Decision decision =
                policy.decide(post, CommunityPostLifecyclePolicy.ACTION_HIDE, expectedVersion, false);
        Date now = new Date();
        if (mapper.transitionCas(postId, lifecycle, decision.getLifecycle(), expectedVersion,
                null, null, 0, 0, 0, now) != 1) stale();
        mapper.upsertSearchDocument(postId, "HIDDEN", now);
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("post", post);
        snapshot.put("media", mapper.selectMediaForSafety(postId));
        snapshot.put("reason", "MEDIA_RISKY");
        Long auditTaskId = auditTaskService.submitSafetyHold(AuditTask.BIZ_COMMUNITY_POST, postId,
                post.getAuthorId(), "HIGH", json(snapshot));
        outbox(postId, "POST_MEDIA_RISKY_HIDDEN", payload("auditTaskId", auditTaskId), now);
        // 只在本次真正从公开态隐藏时通知；已隐藏/待审/终态的重复回调在上面早返回，不重复通知。
        notifyImageTakedown(post);
        return true;
    }

    private void notifyImageTakedown(CommunityPost post) {
        com.chengyinhub.business.service.IImService im = imServiceProvider == null ? null : imServiceProvider.getIfAvailable();
        if (im == null || !positive(post.getAuthorId())) return;
        try {
            im.sendSystemInNestedTransaction(post.getAuthorId(), com.chengyinhub.business.domain.ImMessage.MSG_CARD,
                    com.chengyinhub.business.service.support.SystemNoticeCard.IMAGE_TAKEDOWN_TITLE,
                    com.chengyinhub.business.service.support.SystemNoticeCard.imageTakedown(AuditTask.BIZ_COMMUNITY_POST,
                            post.getId(), com.chengyinhub.business.service.support.SystemNoticeCard.item(
                                    "帖文", blank(post.getTitle()) ? post.getBody() : post.getTitle())));
        } catch (RuntimeException e) {
            com.chengyinhub.business.service.support.SavepointLockFailures.rethrowIfLockFailure(e);
            // 通知失败不挡下架：违规图先收起，通知缺失只告警。
            log.error("[社区帖文] 图片违规下架通知发送失败: postId={}, exceptionType={}",
                    post.getId(), e.getClass().getSimpleName());
        }
    }

    @Override
    public List<String> activeMediaKeys(Long postId) {
        List<String> keys = new ArrayList<>();
        for (Map<String, Object> row : mapper.selectMediaForSafety(postId)) {
            Object stored = value(row, "derived_object_key");
            if (stored == null || blank(stored.toString())) continue;
            try {
                keys.add(canonicalMediaKey(stored.toString()));
            } catch (ServiceException notOurStorage) {
                // 不是当前对象存储的媒体不可能是本链路送检的那张;跳过,让回调按「媒体已变」处理。
            }
        }
        return keys;
    }

    /**
     * 直发且带图:图片送微信异步机审,命中由回调走 applyRiskyMediaResult。送检失败只告警,不回滚已发布内容(同附近的局)。
     * 送检地址是 5 分钟预签名 URL(私有桶),inbox 按对象 key 登记,回调用 activeMediaKeys 核对。
     */
    private void submitMediaForAsyncCheck(Long postId, String openid, List<String> mediaUrls) {
        if (mediaUrls.isEmpty() || wxContentSecurityProvider == null) return;
        IWxContentSecurityService wx = wxContentSecurityProvider.getIfAvailable();
        if (wx == null) return;
        for (String url : mediaUrls) {
            try {
                wx.submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_COMMUNITY_POST, postId, url,
                        canonicalMediaKey(url), openid);
            } catch (Exception e) {
                log.error("[社区帖文] 图片送检异常,需人工复审: postId={}, exceptionType={}",
                        postId, e.getClass().getSimpleName());
            }
        }
    }

    @Override
    @Transactional
    public void applyCommentAuditDecision(Long commentId, boolean approved) {
        Map<String, Object> comment = mapper.selectCommentForAudit(commentId);
        if (comment == null) throw error("回复不存在", 404);
        String current = String.valueOf(value(comment, "lifecycle"));
        String target = approved ? "PUBLISHED" : "REMOVED";
        if (target.equals(current)) return;
        if ("DELETED".equals(current)) return;
        if (!"CHECKING".equals(current)) throw error("回复状态已变化，请重新审核", 409);
        Integer version = value(comment, "version") instanceof Number
                ? ((Number) value(comment, "version")).intValue() : null;
        if (version == null || mapper.updateCommentLifecycle(commentId, "CHECKING", target, version, new Date()) != 1) stale();
        Long postId = number(value(comment, "post_id"));
        mapper.recomputeCounts(postId);
        if (approved) {
            CommunityPost post = mapper.selectByIdForUpdate(postId);
            Long authorId = number(value(comment, "author_id"));
            if (post != null && !post.getAuthorId().equals(authorId)) {
                notifyMember(post.getAuthorId(), postId, authorId, "INTERACTION", "COMMENT", commentId, new Date());
            }
            Long repliedToMemberId = number(value(comment, "replied_to_member_id"));
            if (post != null && positive(repliedToMemberId) && !repliedToMemberId.equals(authorId)
                    && !repliedToMemberId.equals(post.getAuthorId())) {
                notifyMember(repliedToMemberId, postId, authorId, "INTERACTION", "REPLY", commentId, new Date());
            }
        }
        outbox(postId, approved ? "COMMENT_REVIEW_APPROVED" : "COMMENT_REVIEW_REJECTED",
                payload("commentId", commentId), new Date());
    }

    @Override
    @Transactional
    public Map<String, Integer> runMaintenance(Integer requestedLimit) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        int limit = boundedLimit(requestedLimit);
        Date now = new Date();
        int enforcementCount = 0;
        for (Long postId : mapper.selectEnforcementBoundaryPostIds(now, limit)) {
            mapper.activateDueEnforcements(postId, now);
            mapper.expireDueEnforcements(postId, now);
            reconcileEnforcements(postId, now);
            enforcementCount++;
        }

        int outboxCount = 0;
        for (Map<String, Object> row : mapper.selectDueOutbox(now, limit)) {
            Long id = number(value(row, "id"));
            Long aggregateId = number(value(row, "aggregate_id"));
            String aggregateType = String.valueOf(value(row, "aggregate_type"));
            if (id == null || mapper.claimOutbox(id, now) != 1) continue;
            try {
                if ("COMMUNITY_NOTIFICATION".equals(aggregateType)) {
                    deliverNotificationOutbox(row, now);
                } else if ("COMMUNITY_POST".equals(aggregateType)) {
                    CommunityPost post = mapper.selectByIdForUpdate(aggregateId);
                    if (post != null) {
                        String visibility = CommunityPost.LIFECYCLE_DELETED.equals(post.getLifecycle()) ? "DELETED"
                                : CommunityPost.LIFECYCLE_PUBLISHED.equals(post.getLifecycle())
                                && Integer.valueOf(1).equals(post.getSearchEligible()) ? "VISIBLE" : "HIDDEN";
                        mapper.upsertSearchDocument(aggregateId, visibility, now);
                    }
                } else if ("COMMUNITY_MEMBER".equals(aggregateType)) {
                    Object eventType = value(row, "event_type");
                    Object payloadJson = value(row, "payload_json");
                    if (!positive(aggregateId) || eventType == null || payloadJson == null
                            || mapper.upsertMemberGovernanceProjection(aggregateId, eventType.toString(),
                            payloadJson.toString(), now) <= 0) {
                        throw error("账号治理事件投影失败", 500);
                    }
                } else {
                    throw error("未知的社区事件聚合类型", 500);
                }
                mapper.completeOutbox(id, now);
                outboxCount++;
            } catch (RuntimeException e) {
                mapper.retryOutbox(id, e.getClass().getSimpleName(),
                        new Date(now.getTime() + 5L * 60_000L), now);
            }
        }

        int erasureCount = 0;
        for (Map<String, Object> row : mapper.selectDueErasureJobs(now, limit)) {
            Long id = number(value(row, "id"));
            Long postId = number(value(row, "post_id"));
            String triggerType = String.valueOf(value(row, "trigger_type"));
            if (id == null || mapper.claimErasureJob(id, now) != 1) continue;
            if (Integer.valueOf(1).equals(integer(value(row, "legal_hold")))) {
                mapper.retryErasureJob(id, "BLOCKED_LEGAL_HOLD", "LEGAL_HOLD", now, now);
                continue;
            }
            try {
                mapper.deletePostSearchDocument(postId);
                if ("LOCATION_WITHDRAW".equals(triggerType)) {
                    CommunityPost post = mapper.selectByIdForUpdate(postId);
                    if (post != null && !CommunityPost.LIFECYCLE_DELETED.equals(post.getLifecycle())) {
                        mapper.upsertSearchDocument(postId,
                                CommunityPost.LIFECYCLE_PUBLISHED.equals(post.getLifecycle())
                                        && Integer.valueOf(1).equals(post.getSearchEligible()) ? "VISIBLE" : "HIDDEN", now);
                    }
                    mapper.completeErasureJob(id, now);
                    erasureCount++;
                    continue;
                }
                mapper.deletePostNotificationActors(postId);
                mapper.deletePostNotifications(postId);
                Date deleteAfter = mapper.selectNextMediaDeleteAfter(postId);
                if (deleteAfter != null && deleteAfter.after(now)) {
                    mapper.retryErasureJob(id, "RETRY", "MEDIA_RETENTION_WINDOW", deleteAfter, now);
                    continue;
                }
                for (Map<String, Object> media : mapper.selectDueMediaForErasure(postId, now)) {
                    Long mediaId = number(value(media, "id"));
                    String objectKey = String.valueOf(value(media, "object_key"));
                    if (mapper.countLiveMediaObjectRefs(objectKey, mediaId) == 0) {
                        FileOssUploadUtils.deleteOwnedObjectIfPresent(objectKey);
                    }
                }
                mapper.eraseDueMedia(postId, now);
                mapper.completeErasureJob(id, now);
                erasureCount++;
            } catch (RuntimeException e) {
                mapper.retryErasureJob(id, "RETRY", e.getClass().getSimpleName(),
                        new Date(now.getTime() + 30L * 60_000L), now);
            }
        }
        int guidelineNoticeCount = 0;
        Map<String, Object> guideline = mapper.selectActiveGuideline();
        Long guidelineId = guideline == null ? null : number(value(guideline, "id"));
        if (positive(guidelineId)) {
            String aggregateKey = "null:GUIDELINE_UPDATED:" + guidelineId;
            for (Long memberId : mapper.selectGuidelineNoticeRecipients(guidelineId, aggregateKey, limit)) {
                notifyGovernance(memberId, null, 0L, "GUIDELINE_UPDATED", guidelineId, now);
                guidelineNoticeCount++;
            }
        }
        Map<String, Integer> result = new HashMap<>();
        result.put("enforcements", enforcementCount);
        result.put("outbox", outboxCount);
        result.put("erasures", erasureCount);
        result.put("guidelineNotices", guidelineNoticeCount);
        return result;
    }

    private void reconcileEnforcements(Long postId, Date now) {
        CommunityPost post = locked(postId);
        List<String> active = mapper.selectActivePostEnforcementTypes(postId, now);
        String lifecycle = active.contains("REMOVE") ? CommunityPost.LIFECYCLE_REMOVED
                : active.contains("HIDE_PENDING_REVIEW") ? CommunityPost.LIFECYCLE_HIDDEN
                : (CommunityPost.LIFECYCLE_HIDDEN.equals(post.getLifecycle())
                    || CommunityPost.LIFECYCLE_REMOVED.equals(post.getLifecycle()))
                    ? defaultIfBlank(mapper.selectEnforcementRestoreLifecycle(postId), post.getLifecycle())
                    : CommunityPost.LIFECYCLE_LIMITED.equals(post.getLifecycle())
                    ? CommunityPost.LIFECYCLE_PUBLISHED : post.getLifecycle();
        lifecycle = limitedLifecycle(lifecycle, active, postLimitEnabled);
        int recommendation = active.contains("DOWNRANK") || active.contains("HIDE_PENDING_REVIEW") || active.contains("REMOVE") ? 0 : 1;
        int search = active.contains("SEARCH_EXCLUDE") || active.contains("HIDE_PENDING_REVIEW") || active.contains("REMOVE") ? 0 : 1;
        int interaction = active.contains("INTERACTION_LIMIT") || active.contains("REMOVE") ? 0 : 1;
        if (mapper.reconcilePostEnforcementCas(postId, post.getVersion(), lifecycle,
                recommendation, search, interaction, now) != 1) stale();
        mapper.upsertSearchDocument(postId, search == 1 && CommunityPost.LIFECYCLE_PUBLISHED.equals(lifecycle) ? "VISIBLE" : "HIDDEN", now);
        outbox(postId, "POST_ENFORCEMENT_RECONCILED", payload("postId", postId), now);
    }

    /**
     * O-05 #1:分发类处置(DOWNRANK / SEARCH_EXCLUDE)命中且帖子本该公开时落 LIMITED(降分发,作者仍可见);
     * 处置失效后由上面的既有分支把 LIMITED 放回 PUBLISHED。
     * S532-2:从 HIDDEN/REMOVED 恢复时取的是存下的 previous_lifecycle,可能是 LIMITED;此时分发处置若已不在生效
     * (或开关已关),必须放回 PUBLISHED,不能停在没有任何处置支撑的 LIMITED。开关关闭且输入不是 LIMITED 时原样返回。
     */
    static String limitedLifecycle(String lifecycle, List<String> activeEnforcements, boolean enabled) {
        boolean distribution = activeEnforcements != null
                && (activeEnforcements.contains("DOWNRANK") || activeEnforcements.contains("SEARCH_EXCLUDE"));
        if (CommunityPost.LIFECYCLE_LIMITED.equals(lifecycle)) {
            return enabled && distribution ? lifecycle : CommunityPost.LIFECYCLE_PUBLISHED;
        }
        if (!enabled || !CommunityPost.LIFECYCLE_PUBLISHED.equals(lifecycle)) return lifecycle;
        return distribution ? CommunityPost.LIFECYCLE_LIMITED : lifecycle;
    }

    private boolean sameCommentParent(Long storedParentId, Long requestedParentId) {
        if (positive(storedParentId) || positive(requestedParentId)) {
            return storedParentId != null && storedParentId.equals(requestedParentId);
        }
        return true;
    }

    private Map<String, Object> commentResult(Long memberId, Long postId, String approval, String lifecycle) {
        Map<String, Object> result = new HashMap<>();
        result.put("approvalState", approval);
        result.put("lifecycle", lifecycle);
        result.put("post", detail(memberId, postId));
        return result;
    }

    private Map<String, Object> feedbackResultOrDetail(Long memberId, Long postId, String action) {
        if ("NOT_INTERESTED".equals(action) || "SEE_LESS_AUTHOR".equals(action) || "SEE_LESS_TOPIC".equals(action)) {
            Map<String, Object> result = new HashMap<>();
            result.put("postId", postId);
            result.put("action", action);
            result.put("applied", true);
            return result;
        }
        return detail(memberId, postId);
    }

    private void replaceRelations(CommunityPost post, CommunityPostRequests.Upsert request, Date now) {
        List<Long> mediaIds = safe(request.getMediaIds());
        mapper.detachMedia(post.getId(), post.getAuthorId(), mediaIds, now);
        for (int i = 0; i < mediaIds.size(); i++) {
            if (mapper.attachMedia(post.getId(), post.getAuthorId(), mediaIds.get(i), i, now) != 1) {
                throw error("媒体不存在、尚未上传完成或不属于当前用户", 409);
            }
        }
        mapper.deleteTopics(post.getId());
        for (String topic : safe(request.getTopicCodes())) mapper.insertTopic(post.getId(), upper(topic), now);
        mapper.deleteMentions(post.getId());
        for (Long mentioned : safe(request.getMentionedMemberIds())) mapper.insertMention(post.getId(), mentioned, now);
        mapper.deleteReferences(post.getId());
        for (CommunityPostRequests.Reference ref : safe(request.getReferences())) {
            String type = upper(ref.getReferenceType());
            Map<String, Object> snapshot = mapper.selectReferenceSnapshot(type, ref.getReferenceId(), post.getAuthorId());
            if (snapshot == null || snapshot.isEmpty()) {
                throw error("关联对象不存在或不可公开", 409);
            }
            mapper.insertReference(post.getId(), type, ref.getReferenceId(), json(snapshot), "PUBLIC_SAFE", now);
        }
    }

    private void validateMediaReplay(Map<String, Object> existing, String mediaType,
                                     CommunityPostRequests.MediaRegister request) {
        String existingType = String.valueOf(value(existing, "media_type"));
        Object storedKey = value(existing, "object_key");
        String existingKey = canonicalMediaKey(String.valueOf(storedKey == null ? value(existing, "derived_object_key") : storedKey));
        String existingMime = String.valueOf(value(existing, "mime_type"));
        Long existingSize = number(value(existing, "byte_size"));
        if (!mediaType.equalsIgnoreCase(existingType)
                || !canonicalMediaKey(request.getObjectKey()).equals(existingKey)
                || !request.getMimeType().equalsIgnoreCase(existingMime)
                || !request.getByteSize().equals(existingSize)) {
            throw error("同一上传请求号不能用于不同媒体", 409);
        }
    }

    private void validateCreateReplay(Map<String, Object> receipt, String fingerprint) {
        if (!"POST_CREATE".equals(String.valueOf(value(receipt, "command_type")))) {
            throw error("同一请求号不能用于不同命令", 409);
        }
        Object raw = value(receipt, "result_json");
        Map<String, Object> result = raw instanceof Map
                ? (Map<String, Object>) raw
                : JsonUtils.parseToMap(raw == null ? null : raw.toString());
        if (result == null || !fingerprint.equals(String.valueOf(result.get("fingerprint")))) {
            throw error("同一请求号不能用于不同帖文内容", 409);
        }
    }

    private String upsertFingerprint(CommunityPost post, CommunityPostRequests.Upsert request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("communityId", post.getCommunityId());
        canonical.put("postType", post.getPostType());
        canonical.put("title", post.getTitle());
        canonical.put("body", post.getBody());
        canonical.put("audience", post.getAudience());
        canonical.put("commentPolicy", post.getCommentPolicy());
        canonical.put("replyApprovalEnabled", post.getReplyApprovalEnabled());
        canonical.put("slowModeSeconds", post.getSlowModeSeconds());
        canonical.put("locationPrecision", post.getLocationPrecision());
        canonical.put("cityCode", post.getCityCode());
        canonical.put("poiName", post.getPoiName());
        canonical.put("approximateGeohash", post.getApproximateGeohash());
        canonical.put("disclosureType", post.getDisclosureType());
        canonical.put("safetyLabels", safe(request.getSafetyLabels()));
        canonical.put("guidelineVersionId", post.getGuidelineVersionId());
        canonical.put("mediaIds", safe(request.getMediaIds()));
        List<String> topics = new ArrayList<>();
        for (String topic : safe(request.getTopicCodes())) topics.add(upper(topic));
        canonical.put("topicCodes", topics);
        canonical.put("mentionedMemberIds", safe(request.getMentionedMemberIds()));
        List<String> references = new ArrayList<>();
        for (CommunityPostRequests.Reference ref : safe(request.getReferences())) {
            references.add(upper(ref.getReferenceType()) + ":" + ref.getReferenceId());
        }
        canonical.put("references", references);
        return sha256(json(canonical));
    }

    private CommunityPost mapPost(Long memberId, CommunityPostRequests.Upsert request) {
        CommunityPost post = new CommunityPost();
        post.setAuthorId(memberId);
        post.setCommunityId(request.getCommunityId());
        post.setClientRequestId(request.getClientRequestId().trim());
        post.setPostType(upper(request.getPostType()));
        post.setTitle(trimToNull(request.getTitle()));
        post.setBody(request.getBody() == null ? "" : request.getBody().trim());
        post.setAudience(upper(request.getAudience()));
        post.setCommentPolicy(upper(request.getCommentPolicy()));
        post.setReplyApprovalEnabled(Integer.valueOf(1).equals(request.getReplyApprovalEnabled()) ? 1 : 0);
        post.setSlowModeSeconds(request.getSlowModeSeconds() == null ? 0 : request.getSlowModeSeconds());
        post.setLocationPrecision(upper(defaultIfBlank(request.getLocationPrecision(), "NONE")));
        post.setCityCode("NONE".equals(post.getLocationPrecision()) ? null : trimToNull(request.getCityCode()));
        post.setPoiName("NONE".equals(post.getLocationPrecision()) ? null : trimToNull(request.getPoiName()));
        post.setApproximateGeohash(
                "NONE".equals(post.getLocationPrecision()) || "CITY".equals(post.getLocationPrecision())
                        ? null : trimToNull(request.getApproximateGeohash()));
        post.setRecommendationEligible(1);
        post.setSearchEligible(1);
        post.setInteractionEnabled(1);
        post.setDisclosureType(upper(defaultIfBlank(request.getDisclosureType(), "NONE")));
        List<String> safetyLabels = new ArrayList<>();
        for (String label : safe(request.getSafetyLabels())) safetyLabels.add(upper(label));
        post.setSafetyLabelsJson(json(safetyLabels));
        post.setGuidelineVersionId(request.getGuidelineVersionId());
        return post;
    }

    private void validateUpsert(Long memberId, CommunityPostRequests.Upsert request, boolean edit, boolean preserveLegacyLabel) {
        if (request == null) throw error("缺少帖文参数", 400);
        if (!edit) requireRequestId(request.getClientRequestId());
        else if (blank(request.getClientRequestId())) request.setClientRequestId("edit-not-used");
        if (!TYPES.contains(upper(request.getPostType()))) throw error("不支持的帖文类型", 400);
        if (request.getBody() != null && request.getBody().trim().length() > 5000) throw error("正文最多 5000 字", 400);
        if (request.getTitle() != null && request.getTitle().trim().length() > 120) throw error("标题最多 120 字", 400);
        if (!AUDIENCES.contains(upper(request.getAudience()))) throw error("不支持的可见范围", 400);
        if ("COMMUNITY".equals(upper(request.getAudience())) && !positive(request.getCommunityId())) {
            throw error("社群可见帖文必须选择社群", 400);
        }
        if (positive(request.getCommunityId()) && mapper.countCommunityMember(memberId, request.getCommunityId()) == 0) {
            throw error("只能向自己加入的社群发布", 403);
        }
        if (!COMMENT_POLICIES.contains(upper(request.getCommentPolicy()))) throw error("不支持的评论权限", 400);
        if ("MEMBERS".equals(upper(request.getCommentPolicy())) && !positive(request.getCommunityId())) {
            throw error("成员回复权限必须选择社群", 400);
        }
        if ("MENTIONED".equals(upper(request.getCommentPolicy())) && safe(request.getMentionedMemberIds()).isEmpty()) {
            throw error("仅提及者回复时至少选择一位成员", 400);
        }
        String location = upper(defaultIfBlank(request.getLocationPrecision(), "NONE"));
        if (!LOCATION_PRECISIONS.contains(location)) throw error("不支持的位置精度", 400);
        boolean hasCity = !blank(request.getCityCode());
        boolean hasPoi = !blank(request.getPoiName());
        boolean hasGrid = !blank(request.getApproximateGeohash());
        if ("NONE".equals(location) && (hasCity || hasPoi || hasGrid)) {
            throw error("无位置帖文不能携带城市、地点或位置网格", 400);
        }
        if (("CITY".equals(location) || "APPROXIMATE".equals(location)) && !hasCity) {
            throw error("公开地点必须带城市", 400);
        }
        if ("CITY".equals(location) && hasGrid) throw error("城市级位置不能携带位置网格", 400);
        if ("APPROXIMATE".equals(location)
                && (!hasGrid || !request.getApproximateGeohash().trim().matches("[0123456789bcdefghjkmnpqrstuvwxyz]{4,8}"))) {
            throw error("模糊位置网格格式无效", 400);
        }
        if ("EXACT".equals(location)
                && (!hasGrid || !request.getApproximateGeohash().trim().matches("[0123456789bcdefghjkmnpqrstuvwxyz]{9,12}"))) {
            throw error("精确私密位置网格格式无效", 400);
        }
        if (hasCity && request.getCityCode().trim().length() > 32) throw error("城市标识最多 32 字符", 400);
        if (hasPoi && request.getPoiName().trim().length() > 120) throw error("地点名称最多 120 字", 400);
        if ("EXACT".equals(location) && !"PRIVATE".equals(upper(request.getAudience()))) throw error("精确位置只能保存为私密草稿", 400);
        if (!DISCLOSURES.contains(upper(defaultIfBlank(request.getDisclosureType(), "NONE")))) throw error("不支持的利益披露类型", 400);
        if (!positive(request.getGuidelineVersionId())) throw error("缺少社区规范版本", 400);
        if (mapper.countGuidelineVersion(request.getGuidelineVersionId()) != 1) {
            throw error("社区规范版本不存在", 409);
        }
        if (safe(request.getMediaIds()).size() > 9) throw error("单帖最多 9 个媒体", 400);
        if (safe(request.getTopicCodes()).size() > 5) throw error("单帖最多 5 个话题", 400);
        if (safe(request.getMentionedMemberIds()).size() > 10) throw error("单帖最多提及 10 人", 400);
        if (safe(request.getSafetyLabels()).size() > 5) throw error("单帖最多 5 个安全提示", 400);
        Set<String> uniqueSafetyLabels = new HashSet<>();
        for (String label : safe(request.getSafetyLabels())) {
            String normalized = upper(label);
            if ((!AUTHOR_SAFETY_LABELS.contains(normalized)
                    && !(preserveLegacyLabel && "LEGACY_MIGRATED".equals(normalized)))
                    || !uniqueSafetyLabels.add(normalized)) {
                throw error("帖文安全提示无效", 400);
            }
        }
        if (preserveLegacyLabel && !uniqueSafetyLabels.contains("LEGACY_MIGRATED")) {
            if (uniqueSafetyLabels.size() >= 5) throw error("单帖最多 5 个安全提示", 400);
            List<String> labels = new ArrayList<>(safe(request.getSafetyLabels()));
            labels.add("LEGACY_MIGRATED");
            request.setSafetyLabels(labels);
        }
        if (request.getSlowModeSeconds() != null && (request.getSlowModeSeconds() < 0 || request.getSlowModeSeconds() > 86400)) {
            throw error("慢速模式应为 0-86400 秒", 400);
        }
        for (CommunityPostRequests.Reference ref : safe(request.getReferences())) {
            if (ref == null || !REFERENCE_TYPES.contains(upper(ref.getReferenceType())) || !positive(ref.getReferenceId())) {
                throw error("帖文关联对象无效", 400);
            }
            String snapshot = defaultIfBlank(ref.getSnapshotJson(), "{}");
            if (snapshot.length() > 10000 || !jsonObject(snapshot)) {
                throw error("关联快照应为不超过 10000 字符的 JSON 对象", 400);
            }
            if (!REFERENCE_PRIVACY.contains(upper(defaultIfBlank(ref.getPrivacySnapshot(), "PUBLIC_SAFE")))) {
                throw error("关联快照不得包含非公开字段", 400);
            }
        }
    }


    /** A single repeatable-read snapshot binds preview media to the exact pending audit content. */
    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> auditMediaPreview(AuditTask task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task == null ? null : task.getId());
        result.put("taskVersion", task == null ? null : task.getVersion());
        result.put("postId", task == null ? null : task.getBizId());
        result.put("postVersion", null);
        result.put("expiresInSeconds", 300);
        result.put("status", "UNAVAILABLE");
        result.put("items", Collections.emptyList());
        if (task == null || !AuditTask.BIZ_COMMUNITY_POST.equals(task.getBizType())
                || task.getId() == null || task.getVersion() == null || task.getSubmitterId() == null) return result;
        try {
            AuditTask fresh = auditTaskService.selectAuditTaskById(task.getId());
            if (fresh == null || !java.util.Objects.equals(task.getVersion(), fresh.getVersion())
                    || !java.util.Objects.equals(task.getSnapshotJson(), fresh.getSnapshotJson())
                    || !java.util.Objects.equals(task.getBizId(), fresh.getBizId())
                    || !java.util.Objects.equals(task.getSubmitterId(), fresh.getSubmitterId())
                    || !AuditTask.BIZ_COMMUNITY_POST.equals(fresh.getBizType())) {
                result.put("status", "STALE");
                return result;
            }
            com.alibaba.fastjson2.JSONObject snapshot = com.alibaba.fastjson2.JSON.parseObject(task.getSnapshotJson());
            if (snapshot == null || !snapshot.containsKey("post") || !snapshot.containsKey("media")) return result;
            com.alibaba.fastjson2.JSONObject expected = snapshot.getJSONObject("post");
            com.alibaba.fastjson2.JSONArray expectedMedia = snapshot.getJSONArray("media");
            if (expected == null || expectedMedia == null || expected.getInteger("version") == null) return result;
            if (!java.util.Objects.equals(task.getBizId(), expected.getLong("id"))
                    || !java.util.Objects.equals(task.getSubmitterId(), expected.getLong("authorId"))) return result;
            Integer version = expected.getInteger("version");
            String lifecycle = expected.getString("lifecycle");
            if (CommunityPost.LIFECYCLE_DRAFT.equals(lifecycle)) version = Math.addExact(version, 1);
            else if (!CommunityPost.LIFECYCLE_CHECKING.equals(lifecycle)) return result;
            result.put("postVersion", version);
            CommunityPost current = mapper.selectById(task.getBizId(), task.getSubmitterId());
            if (current == null || !java.util.Objects.equals(version, current.getVersion())
                    || !java.util.Objects.equals(task.getSubmitterId(), current.getAuthorId())
                    || !CommunityPost.LIFECYCLE_CHECKING.equals(current.getLifecycle())
                    || !java.util.Objects.equals(expected.getString("body"), current.getBody())
                    || !java.util.Objects.equals(expected.getString("title"), current.getTitle())
                    || !(AuditTask.STATUS_PENDING.equals(fresh.getStatus())
                    || AuditTask.STATUS_ESCALATED.equals(fresh.getStatus()))) {
                result.put("status", "STALE");
                return result;
            }
            Map<Long, String> keys = new LinkedHashMap<>();
            for (Object item : expectedMedia) {
                if (!(item instanceof Map)) return result;
                Map<?, ?> row = (Map<?, ?>) item;
                Long id = number(row.get("id"));
                Object reference = row.get("object_key");
                if (reference == null) reference = row.get("objectKey");
                if (id == null || id <= 0 || reference == null || keys.containsKey(id)) return result;
                keys.put(id, canonicalMediaKey(reference.toString()));
            }
            List<Map<String, Object>> attached = mapper.selectMediaForSafety(task.getBizId());
            if (attached == null || attached.size() != keys.size()) {
                result.put("status", "STALE");
                return result;
            }
            Set<Long> seen = new HashSet<>();
            for (Map<String, Object> row : attached) {
                Long id = number(value(row, "id"));
                Object reference = value(row, "object_key");
                if (id == null || reference == null || !seen.add(id)
                        || !java.util.Objects.equals(keys.get(id), canonicalMediaKey(reference.toString()))) {
                    result.put("status", "STALE");
                    return result;
                }
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map.Entry<Long, String> entry : keys.entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", entry.getKey());
                item.put("url", FileOssUploadUtils.generateCommunityMediaPresignedUrl(entry.getValue()));
                items.add(item);
            }
            result.put("items", items);
            result.put("status", "READY");
        } catch (RuntimeException unavailable) {
            // No storage URL or partial signature is returned when evidence or signing cannot be verified.
            result.put("status", "UNAVAILABLE");
            result.put("items", Collections.emptyList());
        }
        return result;
    }

    /** Only known community snapshot media fields are projected; user-authored text remains untouched. */
    @Override
    public String auditSnapshotForRead(String snapshotJson) {
        if (blank(snapshotJson)) return snapshotJson;
        try {
            com.alibaba.fastjson2.JSONObject snapshot = com.alibaba.fastjson2.JSON.parseObject(snapshotJson);
            if (snapshot == null || !snapshot.containsKey("media")) return snapshotJson;
            com.alibaba.fastjson2.JSONArray rows = snapshot.getJSONArray("media");
            if (rows == null) return snapshotJson;
            for (Object item : rows) {
                if (!(item instanceof Map)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) item;
                for (String name : new ArrayList<>(row.keySet())) {
                    if ("object_key".equalsIgnoreCase(name) || "objectKey".equalsIgnoreCase(name)
                            || "derived_object_key".equalsIgnoreCase(name)
                            || "derivedObjectKey".equalsIgnoreCase(name) || "url".equalsIgnoreCase(name)) {
                        Object reference = row.get(name);
                        try {
                            row.put(name, reference == null ? null : canonicalMediaKey(reference.toString()));
                        } catch (RuntimeException invalidReference) {
                            row.put(name, null);
                        }
                    }
                }
            }
            return snapshot.toJSONString();
        } catch (RuntimeException invalidSnapshot) {
            // Invalid JSON cannot provide a safely identifiable media projection.
            return null;
        }
    }

    private String canonicalMediaKey(String reference) {
        try {
            return FileOssUploadUtils.communityMediaObjectKey(reference);
        } catch (IllegalArgumentException invalid) {
            throw error("媒体必须来自当前配置的对象存储", 400);
        }
    }

    private Map<String, Object> mediaForRead(Map<String, Object> stored) {
        if (stored == null) return null;
        Map<String, Object> result = new LinkedHashMap<>(stored);
        Object original = value(stored, "object_key");
        if (original == null) original = value(stored, "objectKey");
        Object derived = value(stored, "derived_object_key");
        if (derived == null) derived = value(stored, "derivedObjectKey");
        result.keySet().removeIf(name -> "object_key".equalsIgnoreCase(name) || "objectKey".equalsIgnoreCase(name)
                || "derived_object_key".equalsIgnoreCase(name) || "derivedObjectKey".equalsIgnoreCase(name));
        if (original != null) result.put("object_key", canonicalMediaKey(original.toString()));
        if (derived != null && !blank(derived.toString())) {
            String key = canonicalMediaKey(derived.toString());
            result.put("derived_object_key", FileOssUploadUtils.generateCommunityMediaPresignedUrl(key));
        }
        return result;
    }

    private List<Map<String, Object>> mediaForRead(List<Map<String, Object>> stored) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (stored != null) for (Map<String, Object> row : stored) result.add(mediaForRead(row));
        return result;
    }

    /**
     * P-52/G-3:帖文读路径。认不出的历史媒体引用(不在当前对象存储)只跳过这一张,
     * 不能让整条帖文、整页列表报「媒体必须来自当前配置的对象存储」。
     */
    private List<Map<String, Object>> readableMedia(Long postId, List<Map<String, Object>> stored) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (stored == null) return result;
        for (Map<String, Object> row : stored) {
            try {
                result.add(mediaForRead(row));
            } catch (ServiceException unrecognised) {
                log.warn("[社区帖文] 跳过无法识别的帖文媒体: postId={}, mediaId={}", postId, value(row, "id"));
            }
        }
        return result;
    }

    private Map<String, Object> hydrate(CommunityPost post, Long viewerId) {
        Map<String, Object> result = new HashMap<>();
        result.put("post", post);
        result.put("media", readableMedia(post.getId(), mapper.selectMedia(post.getId())));
        result.put("references", mapper.selectReferences(post.getId()));
        boolean viewerIsAuthor = viewerId.equals(post.getAuthorId());
        result.put("mentions", viewerIsAuthor
                ? mapper.selectMentionedMemberIds(post.getId()) : Collections.emptyList());
        result.put("viewerIsAuthor", viewerIsAuthor);
        result.put("topicCodes", viewerIsAuthor ? mapper.selectTopicCodes(post.getId()) : Collections.emptyList());
        result.put("viewerFollowing", positive(viewerId) && !viewerIsAuthor
                && mapper.countFollow(viewerId, post.getAuthorId()) > 0);
        boolean viewerCanComment = false;
        if (positive(viewerId)) {
            boolean follows = false;
            boolean communityMember = false;
            boolean mentioned = false;
            if (CommunityPost.COMMENT_FOLLOWERS.equals(post.getCommentPolicy())) {
                follows = mapper.countFollow(viewerId, post.getAuthorId()) > 0;
            } else if (CommunityPost.COMMENT_MEMBERS.equals(post.getCommentPolicy()) && positive(post.getCommunityId())) {
                communityMember = mapper.countCommunityMember(viewerId, post.getCommunityId()) > 0;
            } else if (CommunityPost.COMMENT_MENTIONED.equals(post.getCommentPolicy())) {
                mentioned = mapper.countMention(post.getId(), viewerId) > 0;
            }
            viewerCanComment = policy.canComment(post, viewerId, follows, communityMember, mentioned)
                    && mapper.countActiveMemberEnforcement(viewerId,
                    Arrays.asList("COMMENT_TIMEOUT", "READ_ONLY", "TEMP_SUSPEND", "PERMANENT_BAN"), new Date()) == 0;
        }
        result.put("viewerCanComment", viewerCanComment);
        return result;
    }

    private void enforceSlowMode(CommunityPost post, Long memberId) {
        int seconds = post.getSlowModeSeconds() == null ? 0 : post.getSlowModeSeconds();
        if (seconds <= 0 || memberId.equals(post.getAuthorId())) return;
        Date latest = mapper.selectLatestCommentTime(post.getId(), memberId);
        if (latest != null && System.currentTimeMillis() - latest.getTime() < seconds * 1000L) {
            throw error("该对话开启了慢速模式，请稍后再回复", 429);
        }
    }

    private void requireGuidelineAck(Long memberId, Long versionId) {
        Map<String, Object> active = activeGuideline();
        Long activeId = number(value(active, "id"));
        if (!versionId.equals(activeId) || mapper.countGuidelineAck(memberId, versionId) == 0) {
            throw error("发布前请确认当前社区规范", 428);
        }
    }

    private CommunityPost locked(Long postId) {
        if (!positive(postId)) throw error("帖文 id 无效", 400);
        CommunityPost post = mapper.selectByIdForUpdate(postId);
        if (post == null) throw error("帖文不存在", 404);
        return post;
    }

    private void requirePostingAllowed(Long memberId) {
        Date now = new Date();
        if (mapper.countActiveMemberEnforcement(memberId,
                Arrays.asList("POST_RATE_LIMIT", "READ_ONLY", "TEMP_SUSPEND", "PERMANENT_BAN"), now) > 0) {
            throw error("当前账号暂不可发布", 403);
        }
        if (mapper.countRecentPosts(memberId, new Date(now.getTime() - 3600_000L)) >= 20) {
            throw error("发布过于频繁，请稍后再试", 429);
        }
    }

    private void outbox(Long postId, String type, String payload, Date now) {
        if (mapper.insertOutbox(UUID.randomUUID().toString(), postId, type, payload, now) != 1) {
            throw error("社区事件保存失败", 500);
        }
    }

    private void typedOutbox(String aggregateType, Long aggregateId, String type, String payload, Date now) {
        if (!positive(aggregateId) || mapper.insertTypedOutbox(UUID.randomUUID().toString(), aggregateType,
                aggregateId, type, payload, now) != 1) {
            throw error("社区事件保存失败", 500);
        }
    }

    private void requireCommentLikeReceipt(Map<String, Object> receipt, Long commentId, boolean legacyUnlike) {
        if (!"COMMENT_LIKE".equals(String.valueOf(value(receipt, "command_type")))
                || !commentId.equals(number(value(receipt, "aggregate_id")))) {
            // A legacy action must not overwrite an existing immutable command or prevent its owner's UNLIKE.
            if (!legacyUnlike) throw error("同一请求号不能用于不同命令", 409);
        }
    }

    private void preserveCommentLikeReceipt(Long memberId, Long commentId, String requestId,
                                             Date now, boolean legacyUnlike) {
        Map<String, Object> receipt = mapper.selectCommandReceiptForUpdate(memberId, requestId);
        if (receipt != null) {
            requireCommentLikeReceipt(receipt, commentId, legacyUnlike);
            return;
        }
        try {
            if (mapper.insertCommandReceipt(memberId, requestId, "COMMENT_LIKE", commentId,
                    payload("aggregateId", commentId), now) == 1) return;
        } catch (DuplicateKeyException duplicate) {
            // Only a known duplicate is classified below. Deadlocks and unknown writes must roll back.
        }
        receipt = mapper.selectCommandReceiptForUpdate(memberId, requestId);
        if (receipt == null) throw error("评论点赞回执保存失败", 500);
        requireCommentLikeReceipt(receipt, commentId, legacyUnlike);
    }

    private boolean isCommandReplay(Long memberId, String requestId, String commandType, Long aggregateId) {
        Map<String, Object> receipt = mapper.selectCommandReceipt(memberId, requestId);
        if (receipt == null) return false;
        if (!commandType.equals(String.valueOf(value(receipt, "command_type")))
                || !aggregateId.equals(number(value(receipt, "aggregate_id")))) {
            throw error("同一请求号不能用于不同命令", 409);
        }
        return true;
    }

    private String fingerprintedCommand(String prefix, Object... values) {
        return prefix + ":" + sha256(json(Arrays.asList(values))).substring(0, 32);
    }

    private boolean trySaveCommandReceipt(Long memberId, String requestId, String commandType,
                                          Long aggregateId, Date now) {
        if (mapper.insertCommandReceipt(memberId, requestId, commandType, aggregateId,
                payload("aggregateId", aggregateId), now) == 1) return true;
        if (isCommandReplay(memberId, requestId, commandType, aggregateId)) return false;
        throw error("命令回执保存失败", 500);
    }

    private void saveCommandReceipt(Long memberId, String requestId, String commandType,
                                    Long aggregateId, Date now) {
        if (!trySaveCommandReceipt(memberId, requestId, commandType, aggregateId, now)) {
            throw error("命令已处理，请刷新状态", 409);
        }
    }

    private void notifyMember(Long recipientId, Long postId, Long actorId, String type, String action, Date now) {
        notifyMember(recipientId, postId, actorId, type, action, null, now);
    }

    private void notifyMember(Long recipientId, Long postId, Long actorId, String type, String action,
                              Long commentId, Date now) {
        if (!positive(recipientId) || recipientId.equals(actorId)) return;
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> notification = new HashMap<>();
        notification.put("recipientId", recipientId);
        notification.put("postId", postId);
        notification.put("actorId", actorId);
        notification.put("action", action);
        notification.put("commentId", commentId);
        notification.put("notificationType", type);
        long bucket = now.getTime() / NOTIFICATION_AGGREGATION_WINDOW_MILLIS;
        String aggregateKey = AGGREGATED_NOTIFICATION_ACTIONS.contains(action)
                ? postId + ":" + action + ":" + (commentId == null ? 0L : commentId) + ":" + bucket
                : postId + ":" + action + ":" + actorId + ":" + (commentId == null ? 0L : commentId);
        notification.put("aggregateKey", aggregateKey);
        if (mapper.insertTypedOutbox(eventId, "COMMUNITY_NOTIFICATION", recipientId,
                "NOTIFICATION_REQUESTED", json(notification), now) != 1) {
            throw error("通知事件保存失败", 500);
        }
    }

    private void notifyGovernance(Long recipientId, Long postId, Long actorId, String action,
                                  Long enforcementId, Date now) {
        if (!positive(recipientId)) return;
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> notification = new HashMap<>();
        notification.put("recipientId", recipientId);
        notification.put("postId", postId);
        notification.put("actorId", actorId);
        notification.put("action", action);
        notification.put("enforcementId", enforcementId);
        notification.put("notificationType", "SYSTEM_GOVERNANCE");
        notification.put("aggregateKey", postId + ":" + action + ":" + enforcementId);
        if (mapper.insertTypedOutbox(eventId, "COMMUNITY_NOTIFICATION", recipientId,
                "NOTIFICATION_REQUESTED", json(notification), now) != 1) {
            throw error("通知事件保存失败", 500);
        }
    }

    private void deliverNotificationOutbox(Map<String, Object> row, Date now) {
        Object raw = value(row, "payload_json");
        Map<String, Object> event = JsonUtils.parseToMap(raw == null ? null : raw.toString());
        if (event == null) throw error("通知事件负载无效", 500);
        Long recipientId = number(value(event, "recipientId"));
        Long postId = number(value(event, "postId"));
        Long actorId = number(value(event, "actorId"));
        Object rawType = value(event, "notificationType");
        Object rawAggregateKey = value(event, "aggregateKey");
        String type = rawType == null ? null : rawType.toString();
        String aggregateKey = rawAggregateKey == null ? null : rawAggregateKey.toString();
        Long commentId = number(value(event, "commentId"));
        Object eventId = value(row, "event_id");
        if (!positive(recipientId) || blank(type) || blank(aggregateKey) || eventId == null) {
            throw error("通知事件字段不完整", 500);
        }
        boolean deliverable = "SYSTEM_GOVERNANCE".equals(type)
                || (postId == null && "SOCIAL".equals(type)
                ? mapper.countMemberNotificationDeliverable(recipientId, actorId) == 1
                : mapper.countNotificationDeliverable(recipientId, postId, actorId, commentId) == 1);
        if (deliverable) {
            int written = mapper.insertNotification(recipientId, eventId.toString(), postId, actorId, commentId, type,
                    aggregateKey, json(event), now);
            if (written > 0 && positive(actorId) && !"SYSTEM_GOVERNANCE".equals(type)) {
                mapper.insertNotificationActor(recipientId, type, aggregateKey, actorId, eventId.toString(), now);
                mapper.recomputeNotificationActorCount(recipientId, type, aggregateKey, now);
            }
        }
    }

    private String payload(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return json(map);
    }

    private String json(Object value) {
        String json = JsonUtils.toJsonString(value);
        if (json == null) throw error("内容序列化失败", 500);
        return json;
    }

    private void requirePrincipal(Long id) { if (!positive(id)) throw error("请先登录", 401); }
    private void requireRequestId(String value) {
        if (blank(value) || value.trim().length() < 8 || value.trim().length() > 96) throw error("requestId 应为 8-96 字符", 400);
    }
    private int requiredVersion(Integer version) { if (version == null || version < 0) throw error("缺少 expectedVersion", 400); return version; }
    private int boundedLimit(Integer limit) { return limit == null ? 20 : Math.max(1, Math.min(limit, 50)); }
    private boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number && ((Number) value).intValue() == 1)
                || "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }
    private void stale() { throw error("数据已变化，请刷新后重试", 409); }
    private ServiceException error(String message, int code) { return new ServiceException(message, code); }
    private static boolean positive(Long value) { return value != null && value > 0; }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static boolean safeMediaObjectKey(String value) {
        if (blank(value)) return false;
        String normalized = value.trim().toLowerCase();
        return normalized.startsWith("https://") || normalized.startsWith("/")
                || value.matches("upload/[0-9a-f]{32}\\.[a-z0-9]{1,10}");
    }
    private boolean hasLegacyMigrationLabel(CommunityPost post) {
        if (blank(post.getSafetyLabelsJson())) return false;
        try {
            for (String label : JsonUtils.getObjectMapper().readValue(post.getSafetyLabelsJson(), String[].class)) {
                if ("LEGACY_MIGRATED".equals(label)) return true;
            }
            return false;
        } catch (Exception ignored) {
            throw error("原帖安全标签无法读取，请刷新后重试", 409);
        }
    }

    private static boolean jsonObject(String value) {
        try {
            return JsonUtils.getObjectMapper().readTree(value).isObject();
        } catch (Exception ignored) {
            return false;
        }
    }
    private static boolean nonEmptyJsonObject(String value) {
        if (blank(value)) return false;
        try {
            com.fasterxml.jackson.databind.JsonNode node = JsonUtils.getObjectMapper().readTree(value);
            return node.isObject() && node.size() > 0;
        } catch (Exception ignored) {
            return false;
        }
    }
    private static String upper(String value) { return value == null ? null : value.trim().toUpperCase(); }
    private static String defaultIfBlank(String value, String fallback) { return blank(value) ? fallback : value; }
    private static String trimToNull(String value) { return blank(value) ? null : value.trim(); }
    private static boolean same(Object a, Object b) { return a == null ? b == null : a.equals(b); }
    private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static <T> List<T> safe(List<T> values) { return values == null ? Collections.<T>emptyList() : values; }
    private static List<String> sorted(Set<String> values) {
        List<String> result = new ArrayList<>(values);
        Collections.sort(result);
        return result;
    }
    private static Long number(Object value) {
        if (value == null) return null;
        return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : Long.valueOf(value.toString());
    }
    private static Integer integer(Object value) {
        if (value == null) return null;
        return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : Integer.valueOf(value.toString());
    }
    private static Object value(Map<String, Object> map, String key) {
        if (map.containsKey(key)) return map.get(key);
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }
    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : bytes) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
