package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ContentLandingMetadata;
import com.chengyinhub.business.domain.ContentLandingTranslation;
import com.chengyinhub.business.domain.dto.ContentLandingMetadataSaveRequest;
import com.chengyinhub.business.domain.dto.ContentLandingTranslationSaveRequest;
import com.chengyinhub.business.domain.vo.ContentLandingMetadataVO;
import com.chengyinhub.business.domain.vo.ContentLandingTranslationVO;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ContentLandingMetadataMapper;
import com.chengyinhub.business.service.IContentLandingMetadataService;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.dto.ContentLandingTranslationReviewRequest;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import org.springframework.transaction.annotation.Transactional;
import com.chengyinhub.business.service.support.PublicContentCatalogLocale;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * M7 落地元信息写面实现。
 *
 * <p><b>鉴权与归属</b>：读/写都先按 topic 取主题行，校验未删除且 {@code memberId} 等于当前用户；
 * 不是本人一律 403，且不区分「不存在」与「不是你的」以外的内部状态(不存在返 404)。
 * 这里只读既有 {@link CmsTopicMapper}，不改主题任何字段。
 *
 * <p><b>幂等</b>：新建用 expectedVersion=0，更新必须命中当前 row_version。
 * 并发下只有一个写成功，另一个拿到「影响 0 行」→ 409 并带当前版本，客户端重读后可重试。
 * 唯一键(主题 / 主题+语言)兜住并发插入。
 */
@Service
public class ContentLandingMetadataServiceImpl implements IContentLandingMetadataService {

    static final int SUMMARY_MAX = 500;
    static final int NOTE_MAX = 255;
    static final int TITLE_MAX = 200;
    static final int SUBTITLE_MAX = 255;

    @Autowired
    private ContentLandingMetadataMapper metadataMapper;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    /** 审校留痕。缺失即拒绝审校(503),不留"改过但无记录"。 */
    @Autowired(required = false)
    private BusinessAuditEventMapper businessAuditEventMapper;

    @Override
    public ContentLandingMetadataVO readOwn(Long topicId, Long memberId) {
        requireOwnedTopic(topicId, memberId);
        ContentLandingMetadataVO vo = toVO(metadataMapper.selectByTopicId(topicId),
                metadataMapper.selectTranslationsByTopicId(topicId));
        // 无行时也回显主题：作者界面要能知道自己在编辑哪一条，而不是拿到一个空对象。
        vo.setTopicId(topicId);
        return vo;
    }

    @Override
    public ContentLandingMetadataVO saveMetadata(ContentLandingMetadataSaveRequest request, Long memberId) {
        if (request == null) {
            throw new ServiceException("参数不能为空", 400);
        }
        CmsTopic topic = requireOwnedTopic(request.getTopicId(), memberId);
        String summary = trimToNull(request.getLandingSummary());
        requireLength(summary, SUMMARY_MAX, "landing_summary");
        String note = trimToNull(request.getAccessibilityNote());
        requireLength(note, NOTE_MAX, "accessibility_note");
        Integer minPeople = nonNegative(request.getGroupMinPeople(), "group_min_people");
        Integer maxPeople = nonNegative(request.getGroupMaxPeople(), "group_max_people");
        if (minPeople != null && maxPeople != null && minPeople > maxPeople) {
            throw new ServiceException("group_min_people 不能大于 group_max_people", 400);
        }
        Integer ageMin = nonNegative(request.getAgeMin(), "age_min");
        Integer ageMax = nonNegative(request.getAgeMax(), "age_max");
        if (ageMin != null && ageMax != null && ageMin > ageMax) {
            throw new ServiceException("age_min 不能大于 age_max", 400);
        }
        Integer status = request.getStatus();
        if (status != null && status != 0 && status != 1) {
            throw new ServiceException("status 只接受 0 或 1", 400);
        }

        ContentLandingMetadata existing = metadataMapper.selectByTopicId(topic.getId());
        Date now = new Date();
        ContentLandingMetadata row = new ContentLandingMetadata();
        row.setTopicId(topic.getId());
        row.setSourceLocale(existing != null && PublicContentCatalogLocale.hasText(existing.getSourceLocale())
                ? existing.getSourceLocale() : PublicContentCatalogLocale.SOURCE_LOCALE_DISPLAY);
        row.setLandingSummary(summary);
        row.setGroupEligible(booleanToInt(request.getGroupEligible()));
        row.setSchoolEligible(booleanToInt(request.getSchoolEligible()));
        row.setGroupMinPeople(minPeople);
        row.setGroupMaxPeople(maxPeople);
        row.setAgeMin(ageMin);
        row.setAgeMax(ageMax);
        row.setAccessibilityNote(note);
        row.setStatus(status == null ? Integer.valueOf(1) : status);
        row.setUpdateBy(memberId);
        row.setUpdateTime(now);

        long expectedVersion = request.getExpectedVersion() == null ? 0L : request.getExpectedVersion();
        if (expectedVersion < 0) {
            throw new ServiceException("expected_version 不能为负", 400);
        }
        if (existing == null) {
            if (expectedVersion != 0L) {
                throw new ServiceException("落地元信息版本冲突，请重新读取后重试", 409);
            }
            row.setCreateTime(now);
            try {
                metadataMapper.insertMetadata(row);
            } catch (DuplicateKeyException concurrentInsert) {
                // 并发新建：唯一键命中 ⇒ 别人先建好了，让调用方重读而不是覆盖。
                throw new ServiceException("落地元信息版本冲突，请重新读取后重试", 409);
            }
        } else {
            int updated = metadataMapper.updateMetadataVersioned(row, expectedVersion, now);
            if (updated == 0) {
                throw new ServiceException("落地元信息版本冲突，请重新读取后重试", 409);
            }
        }
        return toVO(metadataMapper.selectByTopicId(topic.getId()), metadataMapper.selectTranslationsByTopicId(topic.getId()));
    }

    @Override
    public ContentLandingMetadataVO saveTranslation(ContentLandingTranslationSaveRequest request, Long memberId) {
        if (request == null) {
            throw new ServiceException("参数不能为空", 400);
        }
        CmsTopic topic = requireOwnedTopic(request.getTopicId(), memberId);
        String locale = PublicContentCatalogLocale.normalize(request.getLocale());
        if (PublicContentCatalogLocale.isSource(locale)) {
            throw new ServiceException("原文语言不需要翻译条目，请直接编辑主题文案", 400);
        }
        // 作者不能自称已审校：公开目录只信 APPROVED，若作者能写它，就等于无人复核的
        // 多语种文案直接对外展示。作者面一律只写 PENDING；已批准的内容被作者改动后
        // 也必须回到 PENDING（下方 state 恒为 PENDING，天然满足）。
        String requestedState = trimToNull(request.getTranslationState());
        if (requestedState != null && !"PENDING".equalsIgnoreCase(requestedState)
                && !"APPROVED".equalsIgnoreCase(requestedState)) {
            throw new ServiceException("译文状态只能是 PENDING", 400);
        }
        if (requestedState != null && "APPROVED".equalsIgnoreCase(requestedState)) {
            throw new ServiceException("译文需后台审校后方可发布，作者提交只能是 PENDING", 403);
        }
        String state = "PENDING";
        String title = trimToNull(request.getTitle());
        String subtitle = trimToNull(request.getSubtitle());
        String summary = trimToNull(request.getSummary());
        requireLength(title, TITLE_MAX, "title");
        requireLength(subtitle, SUBTITLE_MAX, "subtitle");
        requireLength(summary, SUMMARY_MAX, "summary");
        if (!PublicContentCatalogLocale.hasText(title) && !PublicContentCatalogLocale.hasText(subtitle)
                && !PublicContentCatalogLocale.hasText(summary)) {
            throw new ServiceException("title/subtitle/summary 至少提供一项", 400);
        }

        ContentLandingMetadata metadata = metadataMapper.selectByTopicId(topic.getId());
        String sourceLocale = trimToNull(request.getSourceLocale());
        if (sourceLocale == null) {
            sourceLocale = metadata != null && PublicContentCatalogLocale.hasText(metadata.getSourceLocale())
                    ? metadata.getSourceLocale() : PublicContentCatalogLocale.SOURCE_LOCALE_DISPLAY;
        }
        if (PublicContentCatalogLocale.normalize(sourceLocale).equals(locale)) {
            throw new ServiceException("locale 不能等于原文语言", 400);
        }

        ContentLandingTranslation existing = metadataMapper.selectTranslation(topic.getId(), locale);
        Date now = new Date();
        ContentLandingTranslation row = new ContentLandingTranslation();
        row.setTopicId(topic.getId());
        row.setLocale(locale);
        row.setSourceLocale(PublicContentCatalogLocale.display(PublicContentCatalogLocale.normalize(sourceLocale)));
        row.setTitle(title);
        row.setSubtitle(subtitle);
        row.setSummary(summary);
        row.setTranslationState(state);
        row.setUpdateBy(memberId);
        row.setUpdateTime(now);

        long expectedVersion = request.getExpectedVersion() == null ? 0L : request.getExpectedVersion();
        if (expectedVersion < 0) {
            throw new ServiceException("expected_version 不能为负", 400);
        }
        if (existing == null) {
            if (expectedVersion != 0L) {
                throw new ServiceException("译文版本冲突，请重新读取后重试", 409);
            }
            row.setCreateTime(now);
            try {
                metadataMapper.insertTranslation(row);
            } catch (DuplicateKeyException concurrentInsert) {
                throw new ServiceException("译文版本冲突，请重新读取后重试", 409);
            }
        } else {
            int updated = metadataMapper.updateTranslationVersioned(row, expectedVersion, now);
            if (updated == 0) {
                throw new ServiceException("译文版本冲突，请重新读取后重试", 409);
            }
        }
        return toVO(metadataMapper.selectByTopicId(topic.getId()), metadataMapper.selectTranslationsByTopicId(topic.getId()));
    }

    @Override
    public List<ContentLandingMetadata> reviewList(String keyword) {
        List<ContentLandingMetadata> rows = metadataMapper.selectReviewable(trimToNull(keyword));
        return rows == null ? new ArrayList<ContentLandingMetadata>() : rows;
    }

    @Override
    public ContentLandingMetadataVO reviewDetail(Long topicId) {
        CmsTopic topic = requirePublicTopic(topicId);
        return toVO(metadataMapper.selectByTopicId(topic.getId()),
                metadataMapper.selectTranslationsByTopicId(topic.getId()));
    }

    @Override
    public String reviewDigest(Long topicId, String locale) {
        CmsTopic topic = requirePublicTopic(topicId);
        String normalized = PublicContentCatalogLocale.normalize(locale);
        ContentLandingTranslation row = metadataMapper.selectTranslation(topicId, normalized);
        if (row == null) {
            throw new ServiceException("译文不存在", 404);
        }
        return digestOf(row, topic);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ContentLandingMetadataVO reviewTranslation(ContentLandingTranslationReviewRequest request,
                                                      Long operatorUserId) {
        if (request == null) {
            throw new ServiceException("参数不能为空", 400);
        }
        if (operatorUserId == null || operatorUserId <= 0) {
            throw new ServiceException("缺少审校操作人", 401);
        }
        // 下架/打回/模板主题一律不能把译文推成公开可见：公开目录本就按这些闸过滤，
        // 这里提前拒，避免"已批准"的假象。
        CmsTopic topic = requirePublicTopic(request.getTopicId());
        String locale = PublicContentCatalogLocale.normalize(request.getLocale());
        if (PublicContentCatalogLocale.isSource(locale)) {
            throw new ServiceException("原文语言没有译文条目", 400);
        }
        String reason = trimToNull(request.getReason());
        if (reason == null) {
            throw new ServiceException("审校必须填写理由", 400);
        }
        requireLength(reason, 200, "reason");
        if (request.getExpectedVersion() == null || request.getExpectedVersion() < 0) {
            throw new ServiceException("缺少 expectedVersion", 400);
        }
        if (!PublicContentCatalogLocale.hasText(request.getObservedContentHash())) {
            throw new ServiceException("缺少审校内容摘要", 400);
        }

        ContentLandingTranslation current = metadataMapper.selectTranslation(topic.getId(), locale);
        if (current == null) {
            throw new ServiceException("译文不存在", 404);
        }
        if (current.getRowVersion() == null || !current.getRowVersion().equals(request.getExpectedVersion())) {
            throw new ServiceException("译文版本冲突，请重新读取后重试", 409);
        }
        // 防「看 A 审 B」：审校人提交的摘要必须等于此刻库内的正文+源内容修订身份。
        String currentDigest = digestOf(current, topic);
        if (!currentDigest.equalsIgnoreCase(request.getObservedContentHash().trim())) {
            throw new ServiceException("译文或原文在审校后已变更，请重新读取后重试", 409);
        }
        boolean approve = Boolean.TRUE.equals(request.getApprove());
        String targetState = approve ? "APPROVED" : "PENDING";
        if (approve && !PublicContentCatalogLocale.hasText(current.getTitle())) {
            // 已批准却连标题都没有：读侧会把标题回退成原文，用户会看到半中半英的「已翻译」。
            throw new ServiceException("APPROVED 译文必须包含 title", 400);
        }
        String beforeState = current.getTranslationState();
        if (targetState.equals(beforeState)) {
            // 幂等：已经是目标态就返回当前投影，不写状态、不留假审计。
            return toVO(metadataMapper.selectByTopicId(topic.getId()),
                    metadataMapper.selectTranslationsByTopicId(topic.getId()));
        }

        // 只翻转状态，不覆盖作者文字：正文三列原样回写。
        ContentLandingTranslation row = new ContentLandingTranslation();
        row.setTopicId(topic.getId());
        row.setLocale(current.getLocale());
        row.setSourceLocale(current.getSourceLocale());
        row.setTitle(current.getTitle());
        row.setSubtitle(current.getSubtitle());
        row.setSummary(current.getSummary());
        row.setTranslationState(targetState);
        row.setUpdateBy(operatorUserId);
        row.setUpdateTime(new Date());
        int updated = metadataMapper.updateTranslationVersioned(row, request.getExpectedVersion(), new Date());
        if (updated == 0) {
            throw new ServiceException("译文版本冲突，请重新读取后重试", 409);
        }
        writeReviewAudit(topic, current, beforeState, targetState, reason, operatorUserId);
        return toVO(metadataMapper.selectByTopicId(topic.getId()),
                metadataMapper.selectTranslationsByTopicId(topic.getId()));
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 公开目录闸：主题必须真的可对外展示（审核通过、已上架、未删除）。
     * 任一条不满足都不能把译文推成 APPROVED —— 否则会留下"已批准但永不可见"的假状态，
     * 或更糟：主题后来重新上架时，那段从未复核的内容自动对外。
     *
     * <p>模板主题({@code is_template=0}) 的排除由公开目录 SQL 自己把关
     * （{@code selectReviewable} 与 {@code PublicContentCatalogMapper} 都带
     * {@code cms_topic.is_template = 0}），{@code CmsTopic} 域对象未映射该列，
     * 这里不越层去猜；模板主题即使被批准也不会出现在公开目录。
     */
    private CmsTopic requirePublicTopic(Long topicId) {
        if (topicId == null || topicId <= 0) {
            throw new ServiceException("topicId 参数不合规", 400);
        }
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
            throw new ServiceException("主题不存在", 404);
        }
        boolean publiclyVisible = Integer.valueOf(1).equals(topic.getStatus())
                && Integer.valueOf(1).equals(topic.getUserStatus());
        if (!publiclyVisible) {
            throw new ServiceException("主题未上架或已下架，不能审校公开译文", 409);
        }
        return topic;
    }

    /**
     * 审校内容摘要：译文正文 + 源语言 + 主题原文修订时刻。
     *
     * <p>含 {@code topic.updateTime} 是刻意的：作者改了主题原文后，即使译文一字未动，
     * 旧摘要也必须失效——审校人当时看到的是「旧中文对应的英文」。
     */
    private static String digestOf(ContentLandingTranslation row, CmsTopic topic) {
        StringBuilder canonical = new StringBuilder("content-landing-translation-v1");
        appendPart(canonical, row.getLocale());
        appendPart(canonical, row.getSourceLocale());
        appendPart(canonical, row.getTitle());
        appendPart(canonical, row.getSubtitle());
        appendPart(canonical, row.getSummary());
        appendPart(canonical, topic == null || topic.getUpdateTime() == null
                ? null : Long.valueOf(topic.getUpdateTime().getTime()));
        return sha256Hex(canonical.toString());
    }

    private static void appendPart(StringBuilder canonical, String value) {
        if (value == null) {
            canonical.append("-1:");
            return;
        }
        canonical.append(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .append(':').append(value);
    }

    private static void appendPart(StringBuilder canonical, Long value) {
        appendPart(canonical, value == null ? null : String.valueOf(value));
    }

    private static String sha256Hex(String text) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** 审校留痕：操作者、理由、前/后状态。写不进去就整笔失败，不留"改过但无记录"。 */
    private void writeReviewAudit(CmsTopic topic, ContentLandingTranslation row,
                                  String beforeState, String afterState, String reason, Long operatorUserId) {
        if (businessAuditEventMapper == null) {
            throw new ServiceException("审校审计不可用，已拒绝本次操作", 503);
        }
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType("CONTENT_LANDING");
        audit.setDomainId(topic.getId());
        audit.setOwnerMemberId(topic.getMemberId());
        audit.setActorType(BusinessAuditEvent.ACTOR_TYPE_SYS_USER);
        audit.setActorMemberId(operatorUserId);
        audit.setActorRoleCode("SYS_USER");
        audit.setAction("CONTENT_LANDING_TRANSLATION_REVIEW");
        audit.setTargetType("CONTENT_LANDING_TRANSLATION");
        audit.setTargetId(row.getId());
        audit.setBeforeJson("{\"translationState\":\"" + beforeState + "\"}");
        audit.setAfterJson("{\"translationState\":\"" + afterState + "\"}");
        audit.setReason(reason);
        audit.setRequestId("content-landing-review:" + row.getId() + ":" + row.getRowVersion());
        audit.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(audit) != 1) {
            throw new ServiceException("审校审计写入失败，已回滚", 500);
        }
    }
    /** 归属校验：主题必须存在、未删、且发布者是当前用户。 */
    private CmsTopic requireOwnedTopic(Long topicId, Long memberId) {
        if (topicId == null || topicId <= 0) {
            throw new ServiceException("topicId 参数不合规", 400);
        }
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("请先登录", 401);
        }
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
            throw new ServiceException("主题不存在", 404);
        }
        if (!memberId.equals(topic.getMemberId())) {
            throw new ServiceException("无权修改该主题的落地信息", 403);
        }
        return topic;
    }

    private ContentLandingMetadataVO toVO(ContentLandingMetadata row, List<ContentLandingTranslation> translations) {
        ContentLandingMetadataVO vo = new ContentLandingMetadataVO();
        if (row == null) {
            // 未填写：整块为 null，让作者界面显示「未提供」，不伪造默认值。
            vo.setTranslations(new ArrayList<ContentLandingTranslationVO>());
            return vo;
        }
        vo.setTopicId(row.getTopicId());
        vo.setSourceLocale(row.getSourceLocale());
        vo.setLandingSummary(row.getLandingSummary());
        vo.setGroupEligible(intToBoolean(row.getGroupEligible()));
        vo.setSchoolEligible(intToBoolean(row.getSchoolEligible()));
        vo.setGroupMinPeople(row.getGroupMinPeople());
        vo.setGroupMaxPeople(row.getGroupMaxPeople());
        vo.setAgeMin(row.getAgeMin());
        vo.setAgeMax(row.getAgeMax());
        vo.setAccessibilityNote(row.getAccessibilityNote());
        vo.setStatus(row.getStatus());
        vo.setRowVersion(row.getRowVersion());
        vo.setUpdateTime(row.getUpdateTime());
        List<ContentLandingTranslationVO> list = new ArrayList<ContentLandingTranslationVO>();
        if (translations != null) {
            for (ContentLandingTranslation translation : translations) {
                ContentLandingTranslationVO item = new ContentLandingTranslationVO();
                item.setLocale(PublicContentCatalogLocale.display(translation.getLocale()));
                item.setSourceLocale(translation.getSourceLocale());
                item.setTitle(translation.getTitle());
                item.setSubtitle(translation.getSubtitle());
                item.setSummary(translation.getSummary());
                item.setTranslationState(translation.getTranslationState());
                item.setRowVersion(translation.getRowVersion());
                item.setUpdateTime(translation.getUpdateTime());
                list.add(item);
            }
        }
        vo.setTranslations(list);
        return vo;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void requireLength(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new ServiceException(field + " 最长 " + max + " 个字符", 400);
        }
    }

    private static Integer nonNegative(Integer value, String field) {
        if (value == null) return null;
        if (value < 0) {
            throw new ServiceException(field + " 不能为负数", 400);
        }
        return value;
    }

    private static Integer booleanToInt(Boolean value) {
        return value == null ? null : Integer.valueOf(value ? 1 : 0);
    }

    private static Boolean intToBoolean(Integer value) {
        return value == null ? null : Boolean.valueOf(value == 1);
    }
}
