package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.ContentSafetyRecheck;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.ContentSafetyRecheckMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IContentSafetyRecheckService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Round5C 自动复检(见 {@link IContentSafetyRecheckService})。
 *
 * <p>重试节奏:进队 5 分钟后首检;之后间隔 10、20、40、80 分钟,再每 2 小时一次,默认共 12 次(约 16.5 小时)。
 * 微信连续几小时不可用:内容一直只有作者可见,恢复后下一轮自动发布;超过约 16.5 小时仍审不了 →
 * 自动驳回(内容保持不公开),作者收到「审核未通过」系统通知,原因写明是自动检测未完成,可重新提交。
 *
 * <p>裁决全部经 {@link IAuditTaskService#review} 落地,复用各业务既有的发布/下架/通知口径,操作人记系统 0。
 */
@Service
public class ContentSafetyRecheckServiceImpl implements IContentSafetyRecheckService {

    private static final Logger log = LoggerFactory.getLogger(ContentSafetyRecheckServiceImpl.class);

    static final Long SYSTEM_OPERATOR = 0L;
    static final int DEFAULT_MAX_ATTEMPTS = 12;
    static final long FIRST_BACKOFF_MS = 5L * 60_000L;
    static final long MAX_BACKOFF_MS = 120L * 60_000L;
    /** 没有待审单(如草稿)时不消耗次数,每小时看一次;超过 30 天视为作者放弃,结束本行。 */
    static final long NO_TASK_RETRY_MS = 60L * 60_000L;
    static final long MAX_AGE_MS = 30L * 24 * 3600_000L;
    /** 单次送检文本上限,与主题叙事分批同量级,超长按段送。 */
    static final int TEXT_CHUNK = 2000;

    static final String REASON_RISKY = "内容安全：自动检测判定内容违规，未公开";
    static final String REASON_EXHAUSTED = "内容安全：自动检测多次未能完成，内容未公开，请稍后重新提交";

    @Autowired
    private ContentSafetyRecheckMapper mapper;
    @Autowired
    private AuditTaskMapper auditTaskMapper;
    @Autowired
    private UmsMemberMapper memberMapper;
    @Autowired
    private IWxContentSecurityService wx;
    @Autowired
    private ObjectProvider<IAuditTaskService> auditTaskServiceProvider;
    @Autowired(required = false)
    private com.chengyinhub.business.service.ISensitiveContentLibrary sensitiveLibrary;

    @Value("${content.safety.recheck.batch-limit:50}")
    private int batchLimit = 50;
    @Value("${content.safety.recheck.max-attempts:12}")
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

    private Clock clock = Clock.systemDefaultZone();

    @Override
    public void enqueue(PublishSafetyRequest request, Long contentId) {
        if (request == null || contentId == null) return;
        boolean pre = !PublishSafetyRequest.ReviewMode.POST_REVIEW.name().equals(request.getReviewMode())
                && !PublishSafetyRequest.ReviewMode.EXEMPT.name().equals(request.getReviewMode());
        boolean business = pre && (request.isHasGeo() || request.isHasPrice() || request.isHasSignup());
        enqueue(request.getContentType(), contentId, request.getMemberId(), request.getText(),
                request.getMediaUrls(), business);
    }

    @Override
    public void enqueue(String bizType, Long bizId, Long memberId, String text, List<String> mediaUrls,
                        boolean businessReview) {
        if (bizType == null || bizId == null) return;
        Date now = new Date(clock.millis());
        String media = mediaUrls == null || mediaUrls.isEmpty() ? null : JSON.toJSONString(mediaUrls);
        Integer business = businessReview ? 1 : 0;
        ContentSafetyRecheck open = mapper.selectPendingByBiz(bizType, bizId);
        if (open != null && mapper.refreshPending(open.getId(), memberId, text, media, business, now) == 1) {
            log.info("[内容复检] 刷新待复检 bizType={} bizId={}", bizType, bizId);
            return;
        }
        ContentSafetyRecheck row = new ContentSafetyRecheck();
        row.setBizType(bizType);
        row.setBizId(bizId);
        row.setMemberId(memberId);
        row.setTextBody(text);
        row.setMediaJson(media);
        row.setBusinessReview(business);
        row.setMaxAttempts(Math.max(1, Math.min(20, maxAttempts)));
        row.setNextAttemptAt(new Date(now.getTime() + FIRST_BACKOFF_MS));
        row.setCreateTime(now);
        mapper.insertRecheck(row);
        log.info("[内容复检] 进队 bizType={} bizId={}", bizType, bizId);
    }

    @Override
    public int runDue() {
        Date now = new Date(clock.millis());
        List<ContentSafetyRecheck> due = mapper.selectDue(now, Math.max(1, batchLimit));
        int done = 0;
        for (ContentSafetyRecheck row : due) {
            try {
                processOne(row, now);
                done++;
            } catch (RuntimeException e) {
                // 单行失败不拖垮整批;行仍是 pending,下一轮再来(review 冲突、库瞬断等)。
                log.error("[内容复检] 处理失败,下一轮重试 id={} bizType={} bizId={} exceptionType={}",
                        row.getId(), row.getBizType(), row.getBizId(), e.getClass().getSimpleName());
            }
        }
        return done;
    }

    enum Outcome { PASS, RISKY, UNAVAILABLE }

    void processOne(ContentSafetyRecheck row, Date now) {
        if (row.getCreateTime() != null && now.getTime() - row.getCreateTime().getTime() > MAX_AGE_MS) {
            mapper.finish(row.getId(), attempts(row), ContentSafetyRecheck.STATE_EXPIRED, "EXPIRED", now);
            return;
        }
        String openid = openidOf(row.getMemberId());
        String[] code = new String[1];
        Outcome outcome = check(row, openid, code);
        if (outcome == Outcome.UNAVAILABLE) {
            retry(row, now, code[0]);
            return;
        }
        if (outcome == Outcome.RISKY) {
            decide(row, now, false, REASON_RISKY, ContentSafetyRecheck.STATE_REJECTED, "RISKY");
            return;
        }
        if (Integer.valueOf(1).equals(row.getBusinessReview())) {
            // 内容安全已过;价格/地点/报名、官方活动等业务审批维持人工,单子留给审核台。
            mapper.finish(row.getId(), attempts(row), ContentSafetyRecheck.STATE_PASSED, "BUSINESS_REVIEW", now);
            log.info("[内容复检] 内容安全通过,业务审批仍待人工 bizType={} bizId={}", row.getBizType(), row.getBizId());
            return;
        }
        submitMedia(row, openid);
        decide(row, now, true, null, ContentSafetyRecheck.STATE_PASSED, "PASS");
    }

    Outcome check(ContentSafetyRecheck row, String openid, String[] code) {
        boolean hasText = row.getTextBody() != null && !row.getTextBody().trim().isEmpty();
        List<String> media = media(row);
        if (hasText && sensitiveLibrary != null
                && sensitiveLibrary.blockingCategory(row.getTextBody(), row.getBizType(), row.getBizId()) != null) {
            return Outcome.RISKY; // Round5D:自建词库命中不依赖微信
        }
        if (hasText) {
            if (!wx.isEnabled()) { code[0] = "DETECT_DISABLED"; return Outcome.UNAVAILABLE; }
            if (openid == null || openid.trim().isEmpty()) { code[0] = "NO_OPENID"; return Outcome.UNAVAILABLE; }
            for (String chunk : chunks(row.getTextBody())) {
                String risk;
                try {
                    risk = wx.checkText(chunk, openid);
                } catch (RuntimeException e) {
                    code[0] = "DETECT_ERROR";
                    return Outcome.UNAVAILABLE;
                }
                if (risk == null) continue;
                if (IWxContentSecurityService.isDetectionUnavailable(risk)) {
                    code[0] = "TEXT_UNAVAILABLE";
                    return Outcome.UNAVAILABLE;
                }
                // 违规与 suggest=review 同等处理(Round5C:review 当 risky),不留给人。
                return Outcome.RISKY;
            }
        }
        if (!media.isEmpty() && !wx.canSubmitMedia(openid)) {
            code[0] = "MEDIA_UNCHECKABLE";
            return Outcome.UNAVAILABLE;
        }
        return Outcome.PASS;
    }

    private void retry(ContentSafetyRecheck row, Date now, String code) {
        int next = attempts(row) + 1;
        int max = row.getMaxAttempts() == null ? maxAttempts : row.getMaxAttempts();
        if (next >= max) {
            log.error("[内容复检] 重试耗尽,内容保持不公开并通知作者 bizType={} bizId={} lastError={}",
                    row.getBizType(), row.getBizId(), code);
            decide(row, now, false, REASON_EXHAUSTED, ContentSafetyRecheck.STATE_EXHAUSTED, code);
            return;
        }
        long backoff = Math.min(MAX_BACKOFF_MS, FIRST_BACKOFF_MS << Math.min(10, next));
        mapper.reschedule(row.getId(), attempts(row), next, new Date(now.getTime() + backoff), code, now);
    }

    /** 找内容审核槽的未结单,以系统身份走既有 review():通过=各业务既有发布口,驳回=既有下架 + 作者通知。 */
    private void decide(ContentSafetyRecheck row, Date now, boolean approve, String reason, int state, String code) {
        IAuditTaskService audits = auditTaskServiceProvider.getObject();
        Long taskId = audits.findOpenContentTaskId(row.getBizType(), row.getBizId());
        if (taskId == null) {
            if (!approve) {
                // 没有待审单(草稿等):内容本来就不公开,结束本行;作者再提交时会重新过门禁。
                mapper.finish(row.getId(), attempts(row), state, code, now);
            } else {
                mapper.reschedule(row.getId(), attempts(row), attempts(row),
                        new Date(now.getTime() + NO_TASK_RETRY_MS), "NO_OPEN_TASK", now);
            }
            return;
        }
        AuditTask task = auditTaskMapper.selectAuditTaskById(taskId);
        AuditReviewDTO request = new AuditReviewDTO();
        request.setTaskId(taskId);
        request.setStatus(approve ? AuditTask.STATUS_APPROVED : AuditTask.STATUS_REJECTED);
        request.setReasonText(reason);
        request.setExpectedVersion(task == null ? null : task.getVersion());
        request.setRequestId("content-recheck-" + row.getId() + "-" + attempts(row));
        audits.review(taskId, SYSTEM_OPERATOR, request);
        mapper.finish(row.getId(), attempts(row), state, code, now);
        log.info("[内容复检] 自动{} bizType={} bizId={} taskId={}", approve ? "发布" : "驳回",
                row.getBizType(), row.getBizId(), taskId);
    }

    /** 通过后补送图片异步检测;违规由回调走既有自动下架。上传时已同步过 img_sec_check,这里是第二道。 */
    private void submitMedia(ContentSafetyRecheck row, String openid) {
        List<String> media = media(row);
        Integer wxBiz = wxBizType(row.getBizType());
        if (media.isEmpty() || wxBiz == null) return;
        try {
            wx.submitMediaCheck(wxBiz, row.getBizId(), String.join(",", media), openid);
        } catch (RuntimeException e) {
            log.error("[内容复检] 图片补送检失败 bizType={} bizId={} exceptionType={}",
                    row.getBizType(), row.getBizId(), e.getClass().getSimpleName());
        }
    }

    static Integer wxBizType(String bizType) {
        if (AuditTask.BIZ_TOPIC.equals(bizType)) return IWxContentSecurityService.BIZ_TYPE_TOPIC;
        if (AuditTask.BIZ_ACTIVITY.equals(bizType)) return IWxContentSecurityService.BIZ_TYPE_ACTIVITY;
        if (AuditTask.BIZ_OFFICIAL_EVENT.equals(bizType)) return IWxContentSecurityService.BIZ_TYPE_OFFICIAL_EVENT;
        // 社区帖文的图片按对象 key 送检,由帖文服务在审核通过时自行补送(applyAuditDecision)。
        return null;
    }

    static List<String> chunks(String text) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < text.length(); i += TEXT_CHUNK) {
            out.add(text.substring(i, Math.min(text.length(), i + TEXT_CHUNK)));
        }
        return out;
    }

    private static List<String> media(ContentSafetyRecheck row) {
        if (row.getMediaJson() == null || row.getMediaJson().trim().isEmpty()) return Collections.emptyList();
        try {
            JSONArray arr = JSON.parseArray(row.getMediaJson());
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < arr.size(); i++) {
                String v = arr.getString(i);
                if (v != null && !v.trim().isEmpty()) out.add(v);
            }
            return out;
        } catch (RuntimeException e) {
            return Collections.singletonList(row.getMediaJson());
        }
    }

    private String openidOf(Long memberId) {
        if (memberId == null) return null;
        try {
            UmsMember member = memberMapper.selectUmsMemberById(memberId);
            return member == null ? null : member.getWxOpenId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int attempts(ContentSafetyRecheck row) {
        return row.getAttemptCount() == null ? 0 : row.getAttemptCount();
    }
}
