package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.MerchantSearchQuery;
import com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.service.MerchantChapterApplicationExistsException;
import com.chengyinhub.business.service.MerchantChapterApplicationWithdrawException;
import com.chengyinhub.business.support.ChapterRecruitmentRules;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.RecruitmentWindowPolicy;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** V1 章节招商申请服务。 */
@Service
public class MerchantChapterApplicationServiceImpl implements IMerchantChapterApplicationService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.support.TopicSupplyReviewService supplyReviewService;

    @Autowired
    private MerchantChapterApplicationMapper applicationMapper;

    @Autowired
    private com.chengyinhub.business.mapper.AuditTaskMapper auditTaskMapper;

    @Autowired
    private com.chengyinhub.business.service.support.ChapterApplicationInviteNotice inviteNotice;

    @Autowired
    private CmsTopicChapterMapper chapterMapper;

    @Autowired
    private CmsTopicMapper topicMapper;

    @Autowired
    private com.chengyinhub.business.service.IProductRoleService productRoleService;

    @Autowired
    private MmsMerchantMapper merchantMapper;

    @Autowired
    private MerchantChapterOfferMapper offerMapper;

    @Autowired
    private ISysCategoryService sysCategoryService;

    @org.springframework.beans.factory.annotation.Autowired
    @Lazy
    private com.chengyinhub.business.service.IChapterMerchantNodeService chapterMerchantNodeService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantChapterApplication apply(Long chapterId, Long merchantId, String message) {
        if (chapterId == null || chapterId <= 0) {
            throw new IllegalStateException("缺少章节");
        }
        if (merchantId == null || merchantId <= 0) {
            throw new IllegalStateException("请先登录");
        }
        // 与章节删除、审核和入驻共用锁：删除完成后不能再插入悬空申请。
        supplyReviewService.lockBeforeChapter(chapterId);
        if (chapterMapper.lockChaptersForCapacity(Collections.singletonList(chapterId)).isEmpty()) {
            throw new IllegalStateException("章节不存在");
        }
        CmsTopicChapter chapter = requireChapter(chapterId);
        CmsTopic topic = requireTopic(chapter);
        assertRecruitmentOpen(chapter, topic);
        if (merchantId.equals(topic.getMemberId())) {
            throw new IllegalStateException("不能申请承接自己发布的主题章节");
        }
        assertMatchingActiveMerchant(chapter, merchantId);

        String normalizedMessage = normalizeText(message, "申请说明");
        // 唯一键包含已撤回记录；必须查全量并恢复原行，否则 insert 会误撞重复键，挡住“撤回重申”。
        MerchantChapterApplication existing = applicationMapper.selectAnyByChapterAndMerchant(chapterId, merchantId);
        if (existing != null) {
            if (Integer.valueOf(2).equals(existing.getDelFlag())) {
                if (!Integer.valueOf(MerchantChapterApplication.STATUS_PENDING).equals(existing.getStatus())
                        || applicationMapper.restoreWithdrawn(existing.getId(), normalizedMessage,
                                String.valueOf(merchantId)) != 1) {
                    throw new MerchantChapterApplicationExistsException("历史申请状态刚发生变化，请刷新后重试");
                }
                existing.setApplicationRound(requireStartedRound(existing.getId()));
                existing.setDelFlag(0);
                existing.setMessage(normalizedMessage);
                existing.setAuditRemark(null);
                existing.setAuditMemberId(null);
                existing.setHandleTime(null);
                return existing;
            }
            if (Integer.valueOf(MerchantChapterApplication.STATUS_PENDING).equals(existing.getStatus())) {
                throw new MerchantChapterApplicationExistsException("该章节已申请，等待发布者审核");
            }
            if (Integer.valueOf(MerchantChapterApplication.STATUS_APPROVED).equals(existing.getStatus())) {
                throw new MerchantChapterApplicationExistsException("该章节申请已通过，请继续填写实际供给");
            }
            if (applicationMapper.reapply(existing.getId(), normalizedMessage, String.valueOf(merchantId)) != 1) {
                throw new MerchantChapterApplicationExistsException("历史申请状态刚发生变化，请刷新后重试");
            }
            existing.setApplicationRound(requireStartedRound(existing.getId()));
            existing.setStatus(MerchantChapterApplication.STATUS_PENDING);
            existing.setMessage(normalizedMessage);
            existing.setAuditRemark(null);
            existing.setAuditMemberId(null);
            existing.setHandleTime(null);
            return existing;
        }

        MerchantChapterApplication application = new MerchantChapterApplication();
        application.setApplicationRound(1L);
        application.setTopicId(topic.getId());
        application.setChapterId(chapterId);
        application.setMerchantId(merchantId);
        application.setStatus(MerchantChapterApplication.STATUS_PENDING);
        application.setMessage(normalizedMessage);
        application.setDelFlag(0);
        application.setCreateBy(String.valueOf(merchantId));
        application.setCreateTime(DateUtils.getNowDate());
        try {
            applicationMapper.insertMerchantChapterApplication(application);
        } catch (DuplicateKeyException e) {
            throw new MerchantChapterApplicationExistsException("该章节已申请，请刷新后查看状态");
        }
        return application;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantChapterApplication withdraw(Long applicationId, Long merchantId) {
        if (applicationId == null || applicationId <= 0) {
            throw withdrawError(MerchantChapterApplicationWithdrawException.ERROR_CODE_NOT_FOUND, "申请不存在");
        }
        if (merchantId == null || merchantId <= 0) {
            throw new IllegalStateException("请先登录");
        }
        MerchantChapterApplication application = lockForWithdrawal(applicationId);
        assertWithdrawableBy(application, merchantId);
        if (Integer.valueOf(2).equals(application.getDelFlag())) {
            return application;
        }
        if (!Integer.valueOf(0).equals(application.getDelFlag())) {
            throw withdrawError(MerchantChapterApplicationWithdrawException.ERROR_CODE_STATE_CHANGED,
                    "申请状态刚发生变化，请刷新后重试");
        }
        if (applicationMapper.withdrawPending(applicationId, merchantId) == 1) {
            application.setDelFlag(2);
            // 点位内容保留待重申，但先退出待审/公开链路，避免独立点位审核入口误放行。
            chapterMerchantNodeService.auditAlongApplication(application.getChapterId(), merchantId, merchantId,
                    false, "商家已撤回承接申请");
            // 只有本次真正撤回成功才通知主办；幂等重放与并发落败分支都不进这里，不会重复发。
            CmsTopic topic = topicMapper.selectCmsTopicById(application.getTopicId());
            inviteNotice.submitOutcome(application.getTopicId(), application.getId(), application.getApplicationRound(),
                    com.chengyinhub.business.service.support.ChapterApplicationInviteNotice.Outcome.WITHDRAW,
                    topic == null ? null : topic.getMemberId(), "章节申请已撤回", "商家已撤回章节承接申请");
            return application;
        }

        // 与审核并发时由 SQL 的 status + del_flag 条件决定唯一赢家，再回读真实状态给稳定结果。
        MerchantChapterApplication latest = applicationMapper.selectByIdIncludingDeleted(applicationId);
        assertWithdrawableBy(latest, merchantId);
        if (Integer.valueOf(2).equals(latest.getDelFlag())) {
            return latest;
        }
        throw withdrawError(MerchantChapterApplicationWithdrawException.ERROR_CODE_STATE_CHANGED,
                "申请状态刚发生变化，请刷新后重试");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantChapterApplication audit(Long applicationId, Long publisherMemberId, boolean approved, String auditRemark) {
        return decide(applicationId, publisherMemberId, approved, auditRemark, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantChapterApplication respondInvite(Long applicationId, Long merchantMemberId, boolean accepted) {
        return decide(applicationId, merchantMemberId, accepted, accepted ? "商家已接受邀请" : "商家已拒绝邀请", true);
    }

    private MerchantChapterApplication decide(Long applicationId, Long publisherMemberId, boolean approved, String auditRemark, boolean invitedResponse) {
        if (applicationId == null || applicationId <= 0) {
            throw new IllegalStateException("缺少申请");
        }
        if (publisherMemberId == null || publisherMemberId <= 0) {
            throw new IllegalStateException("请先登录");
        }
        MerchantChapterApplication application = applicationMapper.selectMerchantChapterApplicationById(applicationId);
        if (application == null || application.getChapterId() == null) {
            throw new IllegalStateException("申请不存在");
        }

        // 与 enroll 同一锁序：章节锁 -> 主题锁。审批通过会预占名额，必须和实际入驻串行。
        supplyReviewService.lockBeforeChapter(application.getChapterId());
        if (chapterMapper.lockChaptersForCapacity(Collections.singletonList(application.getChapterId())).isEmpty()) {
            throw new IllegalStateException("章节不存在");
        }
        CmsTopicChapter chapter = requireChapter(application.getChapterId());
        CmsTopic topic = chapter.getTopicId() == null ? null
                : topicMapper.selectCmsTopicByIdForUpdate(chapter.getTopicId());
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag()) || topic.getMemberId() == null) {
            throw new IllegalStateException("主题归属不明，暂不能处理申请");
        }
        application = currentApplication(applicationId, chapter.getId());
        // 归属只从 chapter -> topic 反查；application.topicId 仅为查询索引。
        if (application.getTopicId() != null && !topic.getId().equals(application.getTopicId())) {
            throw new IllegalStateException("申请与章节归属不一致，暂不能处理");
        }
        if (invitedResponse) {
            if (!Integer.valueOf(MerchantChapterApplication.SOURCE_INVITE).equals(application.getSource())
                    || !publisherMemberId.equals(application.getMerchantId())) {
                throw new IllegalStateException("仅受邀商家可处理本邀请");
            }
        } else {
            if (Integer.valueOf(MerchantChapterApplication.SOURCE_INVITE).equals(application.getSource())) {
                throw new IllegalStateException("邀请须由商家接受，主办方可撤回邀请");
            }
            if (publisherMemberId.equals(application.getMerchantId())) {
                throw new IllegalStateException("申请商家本人不能审核自己的申请");
            }
            if (!publisherMemberId.equals(topic.getMemberId())) {
                throw new IllegalStateException("仅主题发布者可审核本章节申请");
            }
        }
        if (!Integer.valueOf(MerchantChapterApplication.STATUS_PENDING).equals(application.getStatus())) {
            if (invitedResponse && Integer.valueOf(approved ? MerchantChapterApplication.STATUS_APPROVED
                    : MerchantChapterApplication.STATUS_REJECTED).equals(application.getStatus())
                    && Integer.valueOf(0).equals(application.getDelFlag())) return application;
            throw new IllegalStateException("该申请已处理");
        }

        if (approved) {
            assertRecruitmentOpen(chapter, topic, invitedResponse && application.getReplacementOfOfferId() != null);
            if (application.getReplacementOfOfferId() != null
                    && (offerMapper.selectReplacementForUpdate(application.getReplacementOfOfferId()) != null
                        || hasApprovedReplacement(application.getChapterId(), application.getReplacementOfOfferId(),
                            application.getId()))) {
                throw new IllegalStateException("原供给已有确认替补，请刷新后查看");
            }
            assertMatchingActiveMerchant(chapter, application.getMerchantId());
            assertApprovalCapacity(chapter, application.getMerchantId());
        }

        Date handledAt = DateUtils.getNowDate();
        String normalizedRemark = normalizeText(auditRemark, "审核备注");
        if (!approved && !invitedResponse && normalizedRemark == null) {
            // F-R2F-05:主办驳回必须说明原因，商家据此修改后重申；通知正文也不会出现空原因
            throw new IllegalStateException("请填写拒绝原因");
        }
        int targetStatus = approved ? MerchantChapterApplication.STATUS_APPROVED
                : MerchantChapterApplication.STATUS_REJECTED;
        if (applicationMapper.updateAudit(application.getId(), targetStatus, normalizedRemark,
                publisherMemberId, handledAt) != 1) {
            throw new IllegalStateException("该申请状态刚发生变化，请刷新后重试");
        }
        application.setStatus(targetStatus);
        application.setAuditRemark(normalizedRemark);
        application.setAuditMemberId(publisherMemberId);
        application.setHandleTime(handledAt);
        // 点位跟着申请一起处置 —— 一次审完(2026-08-06 用户定)。
        // 商家报名时就把点位填好,主办方看着实际玩法决定要不要这家;分两次审等于让他
        // 在不知道玩法的情况下先决定要不要人,商家还多等一轮。
        chapterMerchantNodeService.auditAlongApplication(
                chapter.getId(), application.getMerchantId(), topic.getMemberId(), approved, normalizedRemark);
        inviteNotice.submitOutcome(topic.getId(), application.getId(), application.getApplicationRound(),
                invitedResponse ? com.chengyinhub.business.service.support.ChapterApplicationInviteNotice.Outcome.INVITE_DECISION
                        : com.chengyinhub.business.service.support.ChapterApplicationInviteNotice.Outcome.REVIEW,
                invitedResponse ? topic.getMemberId() : application.getMerchantId(),
                invitedResponse ? "章节邀请处理结果" : "章节申请审核结果",
                invitedResponse ? (approved ? "商家已接受章节邀请" : "商家已拒绝章节邀请")
                        : (approved ? "章节申请已通过" : "章节申请未通过：" + normalizedRemark));
        return application;
    }

    @Override
    public List<Map<String, Object>> invitableMerchants(Long topicId, Long publisherMemberId) {
        return invitableMerchants(topicId, publisherMemberId, null, null);
    }

    @Override
    public List<Map<String, Object>> invitableMerchants(Long topicId, Long publisherMemberId,
                                                        Double originLat, Double originLng) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (topicId == null || publisherMemberId == null) return out;
        boolean byDistance = originLat != null && originLng != null;
        CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
        // 归属:不是本主题发布者就不给看名单(能邀谁本身也是信息)
        if (topic == null || topic.getMemberId() == null || !topic.getMemberId().equals(publisherMemberId)) {
            return out;
        }
        boolean ordinaryRecruitmentOpen = true;
        try {
            RecruitmentWindowPolicy.assertOpen(topic.getLifecycle(), topic.getStatus(), topic.getUserStatus(),
                    topic.getRecruitDeadline(), DateUtils.getNowDate());
        } catch (IllegalStateException closed) {
            ordinaryRecruitmentOpen = false;
        }
        CmsTopicChapter probeChapter = new CmsTopicChapter();
        probeChapter.setTopicId(topicId);
        List<CmsTopicChapter> chapters = chapterMapper.selectCmsTopicChapterList(probeChapter);
        if (chapters == null) return out;
        List<MerchantChapterOffer> history = offerMapper.selectAdminQueue(topicId, null);
        Set<Long> replaced = new HashSet<>();
        if (history != null) {
            for (MerchantChapterOffer row : history) {
                if (row.getReplacementOfOfferId() != null) replaced.add(row.getReplacementOfOfferId());
            }
        }

        for (CmsTopicChapter chapter : chapters) {
            if (chapter.getCategoryId() == null) continue;      // 没配品类就无从匹配
            if (!Integer.valueOf(1).equals(chapter.getRecruitEnabled())) continue;   // 没开招商的章节不出现
            // ⚠️ 与 assertMatchingActiveMerchant 同一套判据:同品类 + status=1 + accountStatus=1。
            // 两处各写一套会出现「列表里选得到、点邀请被拒」。
            // 带定位时走既有的距离查询(与商家发现 E5 同一口径),不另写一套 Haversine。
            MerchantSearchQuery probe = new MerchantSearchQuery();
            probe.setCategoryId(chapter.getCategoryId());
            probe.setStatus(1L);
            probe.setAccountStatus(1L);
            List<? extends MmsMerchant> all;
            if (byDistance) {
                probe.setOriginLat(originLat);
                probe.setOriginLng(originLng);
                all = merchantMapper.selectMerchantListByDistance(probe);
            } else {
                all = merchantMapper.selectMmsMerchantList(probe);
            }
            if (all == null) continue;
            List<MerchantChapterOffer> withdrawals = new ArrayList<>();
            if (Integer.valueOf(0).equals(topic.getUserStatus())) {
                if (history != null) {
                    for (MerchantChapterOffer row : history) {
                        if (chapter.getId().equals(row.getChapterId()) && Integer.valueOf(0).equals(row.getStatus())
                                && !replaced.contains(row.getId()) && row.getWithdrawReason() != null
                                && !row.getWithdrawReason().trim().isEmpty()) withdrawals.add(row);
                    }
                }
            }
            for (MmsMerchant m : all) {
                if (m.getMemberId() == null) continue;
                if (Integer.valueOf(2).equals(m.getDelFlag())) continue;
                if (m.getMemberId().equals(publisherMemberId)) continue;   // 不能邀自己
                // 已有申请/邀请的组合排除 —— 点下去也只会撞「已有本章节的申请」
                if (applicationMapper.selectByChapterAndMerchant(chapter.getId(), m.getMemberId()) != null) continue;
                if (offerMapper.countAllByChapterAndMerchant(chapter.getId(), m.getMemberId()) > 0) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                // id = 商家档案主键,商家公开主页的路由参数;memberId 才是邀约合同里的对方
                row.put("id", m.getId());
                row.put("memberId", m.getMemberId());
                row.put("name", m.getName() == null ? "未命名商家" : m.getName());
                row.put("logo", m.getLogo());
                row.put("address", m.getAddress());
                row.put("chapterId", chapter.getId());
                row.put("chapterName", chapter.getName() == null ? "未命名章节" : chapter.getName());
                // 2026-10-31 业主:每一站一个档位,商家只能接受或拒绝 —— 邀约里要写清这一站的档与条件(只读)
                row.put("termsMode", chapter.getTermsMode() == null ? "PERK" : chapter.getTermsMode());
                row.put("perkMinValue", chapter.getPerkMinValue());
                row.put("maxPerHeadFee", chapter.getMaxPerHeadFee());
                row.put("distance", m instanceof MerchantSearchQuery ? ((MerchantSearchQuery) m).getDistance() : null);
                if (withdrawals.isEmpty()) {
                    if (com.chengyinhub.business.domain.enums.TopicLifecycleState.commercialTermsFrozen(topic.getLifecycle())
                            || !ordinaryRecruitmentOpen || !Integer.valueOf(1).equals(topic.getStatus())
                            || !Integer.valueOf(1).equals(topic.getUserStatus())) continue;
                    out.add(row);
                } else {
                    for (MerchantChapterOffer original : withdrawals) {
                        Map<String, Object> replacementRow = new LinkedHashMap<>(row);
                        replacementRow.put("replacementOfOfferId", original.getId());
                        out.add(replacementRow);
                    }
                }
            }
        }
        // 逐章节查出来的行要重排成一条「从近到远」的名单 —— 否则会按章节分段,
        // 同一商家的两行也可能被别的章节隔开。无坐标(distance=null)一律垫底。
        if (byDistance) {
            out.sort(Comparator.comparing(
                    r -> (Double) r.get("distance"),
                    Comparator.nullsLast(Comparator.naturalOrder())));
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantChapterApplication invite(Long chapterId, Long publisherMemberId, Long merchantMemberId) {
        return invite(chapterId, publisherMemberId, merchantMemberId, (String) null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantChapterApplication invite(Long chapterId, Long publisherMemberId, Long merchantMemberId, String message) {
        return invite(chapterId, publisherMemberId, merchantMemberId, message, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantChapterApplication invite(Long chapterId, Long publisherMemberId, Long merchantMemberId, Long replacementOfOfferId) {
        return invite(chapterId, publisherMemberId, merchantMemberId, null, replacementOfOfferId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantChapterApplication invite(Long chapterId, Long publisherMemberId, Long merchantMemberId, String message, Long replacementOfOfferId) {
        String normalizedMessage = normalizeText(message, "邀约语");
        if (chapterId == null || chapterId <= 0) throw new IllegalStateException("缺少章节");
        if (publisherMemberId == null || publisherMemberId <= 0) throw new IllegalStateException("请先登录");
        if (merchantMemberId == null || merchantMemberId <= 0) throw new IllegalStateException("请选择商家");
        if (publisherMemberId.equals(merchantMemberId)) {
            throw new IllegalStateException("不能邀请自己承接自己的主题");
        }
        // 与申请、审核共用同一把章节锁:删除完成后不能再插入悬空邀请。
        supplyReviewService.lockBeforeChapter(chapterId);
        if (chapterMapper.lockChaptersForCapacity(Collections.singletonList(chapterId)).isEmpty()) {
            throw new IllegalStateException("章节不存在");
        }
        CmsTopicChapter chapter = requireChapter(chapterId);
        CmsTopic topic = requireTopic(chapter);
        if (topic.getMemberId() == null || !topic.getMemberId().equals(publisherMemberId)) {
            throw new IllegalStateException("仅主题发布者可以邀请商家承接");
        }
        assertRecruitmentOpen(chapter, topic, replacementOfOfferId != null);
        if (replacementOfOfferId != null) {
            MerchantChapterOffer original = offerMapper.selectByIdForUpdate(replacementOfOfferId);
            if (original == null || !topic.getId().equals(original.getTopicId())
                    || !chapterId.equals(original.getChapterId())
                    || !Integer.valueOf(0).equals(original.getStatus())
                    || original.getWithdrawReason() == null || original.getWithdrawReason().trim().isEmpty()
                    || merchantMemberId.equals(original.getMerchantId())) {
                throw new IllegalStateException("请选择本章节已退出的原供给及其他替补商家");
            }
            if (offerMapper.selectReplacementForUpdate(replacementOfOfferId) != null) {
                throw new IllegalStateException("原供给已有确认的替补，请刷新查看当前退出供给");
            }
            // 邀请改为「待商家接受」后，待接受的补位邀请同样占住这条原供给的补位位，
            // 否则可以对同一退出供给并发发出多份补位邀请、被多家同时接受。
            if (hasReplacementApplication(chapterId, replacementOfOfferId, null)) {
                throw new IllegalStateException("该供给已有补位邀请，请等待确认或撤回后另邀");
            }
        }
        assertMatchingActiveMerchant(chapter, merchantMemberId);
        // 待接受邀请不占名额；接受时在章节锁内检查并预占。

        MerchantChapterApplication existing = applicationMapper.selectByChapterAndMerchant(chapterId, merchantMemberId);
        if (existing != null) {
            if (Integer.valueOf(MerchantChapterApplication.STATUS_APPROVED).equals(existing.getStatus())) {
                throw new IllegalStateException("该商家已可承接本章节,不必重复邀请");
            }
            throw new IllegalStateException("该商家已有本章节的申请,请到待审列表处理");
        }

        MerchantChapterApplication withdrawn = applicationMapper.selectAnyByChapterAndMerchant(
                chapterId, merchantMemberId);
        if (withdrawn != null && (!Integer.valueOf(2).equals(withdrawn.getDelFlag())
                || !Integer.valueOf(MerchantChapterApplication.STATUS_PENDING).equals(withdrawn.getStatus()))) {
            throw new IllegalStateException("历史申请状态刚发生变化，请刷新后查看");
        }

        Date now = DateUtils.getNowDate();
        MerchantChapterApplication invite = new MerchantChapterApplication();
        invite.setApplicationRound(1L);
        invite.setTopicId(topic.getId());
        invite.setChapterId(chapterId);
        invite.setReplacementOfOfferId(replacementOfOfferId);
        invite.setMerchantId(merchantMemberId);
        invite.setMessage(normalizedMessage);
        invite.setStatus(MerchantChapterApplication.STATUS_PENDING);
        invite.setSource(MerchantChapterApplication.SOURCE_INVITE);
        invite.setAuditMemberId(null);
        invite.setHandleTime(null);
        invite.setDelFlag(0);
        invite.setCreateBy(String.valueOf(publisherMemberId));
        invite.setCreateTime(now);
        if (withdrawn != null) {
            invite.setId(withdrawn.getId());
            if (applicationMapper.reinviteWithdrawn(invite) != 1) {
                throw new IllegalStateException("历史申请状态刚发生变化，请刷新后重试");
            }
            invite.setApplicationRound(requireStartedRound(invite.getId()));
            inviteNotice.submit(topic.getId(), invite.getId(), invite.getApplicationRound(), merchantMemberId);
            return invite;
        }
        try {
            applicationMapper.insertMerchantChapterApplication(invite);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("该商家已有本章节的申请,请刷新后查看");
        }
        inviteNotice.submit(topic.getId(), invite.getId(), invite.getApplicationRound(), merchantMemberId);
        return invite;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantChapterApplication revokeInvite(Long applicationId, Long publisherMemberId) {
        MerchantChapterApplication application = applicationMapper.selectByIdIncludingDeleted(applicationId);
        if (application == null || application.getChapterId() == null) {
            throw new IllegalStateException("邀请不存在");
        }
        supplyReviewService.lockBeforeChapter(application.getChapterId());
        if (chapterMapper.lockChaptersForCapacity(Collections.singletonList(application.getChapterId())).isEmpty()) {
            throw new IllegalStateException("章节不存在");
        }
        CmsTopicChapter chapter = requireChapter(application.getChapterId());
        CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(chapter.getTopicId());
        application = currentApplication(applicationId, chapter.getId());
        if (!Integer.valueOf(MerchantChapterApplication.SOURCE_INVITE).equals(application.getSource())) {
            throw new IllegalStateException("邀请不存在");
        }
        if (topic == null || publisherMemberId == null || !publisherMemberId.equals(topic.getMemberId())) {
            throw new IllegalStateException("仅主题发布者可撤回邀请");
        }
        if (!Integer.valueOf(MerchantChapterApplication.STATUS_PENDING).equals(application.getStatus())) {
            throw new IllegalStateException("已处理的邀请不可撤回");
        }
        if (Integer.valueOf(2).equals(application.getDelFlag())) return application;
        if (applicationMapper.withdrawPending(applicationId, application.getMerchantId()) != 1) {
            throw new IllegalStateException("邀请状态已变化，请刷新后重试");
        }
        application.setDelFlag(2);
        inviteNotice.submitOutcome(topic.getId(), application.getId(), application.getApplicationRound(),
                com.chengyinhub.business.service.support.ChapterApplicationInviteNotice.Outcome.INVITE_REVOKE,
                application.getMerchantId(), "章节邀请已撤回", "主办方已撤回待接受的章节邀请");
        return application;
    }

    /** The plain row only locates parents. Outcome/recipient/round decisions use this complete current row. */
    private MerchantChapterApplication currentApplication(Long id, Long lockedChapterId) {
        MerchantChapterApplication current = applicationMapper.selectByIdIncludingDeletedForUpdate(id);
        if (current == null || !java.util.Objects.equals(lockedChapterId, current.getChapterId()))
            throw new IllegalStateException("申请与章节状态已变化，请刷新后重试");
        return current;
    }

    /** Do not hold an application while waiting for the same parents used by audit/reinvite/node writes. */
    private MerchantChapterApplication lockForWithdrawal(Long id) {
        MerchantChapterApplication locator = applicationMapper.selectByIdIncludingDeleted(id);
        if (locator != null && locator.getChapterId() != null) {
            CmsTopicChapter chapter = chapterMapper.selectCmsTopicChapterById(locator.getChapterId());
            // A missing historical chapter may still have a pure withdrawn replay. The stored topic
            // index is used only to acquire a fence, never as an ownership/permission decision.
            Long topicId = chapter != null ? chapter.getTopicId() : locator.getTopicId();
            if (topicId != null) {
                auditTaskMapper.selectTopicPublicationTasksForUpdate(topicId);
                topicMapper.selectCmsTopicByIdForUpdate(topicId);
            }
            chapterMapper.lockChaptersForCapacity(Collections.singletonList(locator.getChapterId()));
            CmsTopicChapter currentChapter = chapterMapper.selectContentByIdForUpdate(locator.getChapterId());
            if (currentChapter != null && !java.util.Objects.equals(topicId, currentChapter.getTopicId()))
                throw withdrawError(MerchantChapterApplicationWithdrawException.ERROR_CODE_STATE_CHANGED,
                        "章节归属已变化，请刷新后重试");
            MerchantChapterApplication current = applicationMapper.selectByIdIncludingDeletedForUpdate(id);
            if (current != null && !java.util.Objects.equals(locator.getChapterId(), current.getChapterId()))
                throw withdrawError(MerchantChapterApplicationWithdrawException.ERROR_CODE_STATE_CHANGED,
                        "申请与章节状态已变化，请刷新后重试");
            return current; // The original assertWithdrawableBy retains its NOT_FOUND/ownership/finalized errors.
        }
        // Missing-parent legacy rows and pure withdrawn replay retain the original no-new-effects handling.
        return applicationMapper.selectByIdIncludingDeletedForUpdate(id);
    }

    private Long requireStartedRound(Long applicationId) {
        Long round = applicationMapper.selectApplicationRoundForUpdate(applicationId);
        if (round == null || round <= 0) throw new IllegalStateException("新申请轮次未持久保存，请刷新后重试");
        return round;
    }

    /** 本章节是否已有指向同一原供给的补位邀请(待接受或已接受)，可排除当前这一条。 */
    private boolean hasReplacementApplication(Long chapterId, Long replacementOfOfferId, Long excludeId) {
        return hasReplacementWithStatus(chapterId, replacementOfOfferId, excludeId, MerchantChapterApplication.STATUS_PENDING)
                || hasApprovedReplacement(chapterId, replacementOfOfferId, excludeId);
    }

    private boolean hasApprovedReplacement(Long chapterId, Long replacementOfOfferId, Long excludeId) {
        return hasReplacementWithStatus(chapterId, replacementOfOfferId, excludeId, MerchantChapterApplication.STATUS_APPROVED);
    }

    private boolean hasReplacementWithStatus(Long chapterId, Long replacementOfOfferId, Long excludeId, int status) {
        List<MerchantChapterApplication> rows = applicationMapper.selectByChapterAndStatus(chapterId, status);
        if (rows == null) return false;
        for (MerchantChapterApplication row : rows) {
            if (row != null && replacementOfOfferId.equals(row.getReplacementOfOfferId())
                    && (excludeId == null || !excludeId.equals(row.getId()))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Long approvedReplacementOfferId(Long chapterId, Long merchantId) {
        MerchantChapterApplication application = applicationMapper.selectByChapterAndMerchantForUpdate(
                chapterId, merchantId);
        return application != null && Integer.valueOf(MerchantChapterApplication.STATUS_APPROVED)
                .equals(application.getStatus()) ? application.getReplacementOfOfferId() : null;
    }

    @Override
    public boolean hasApprovedApplication(Long chapterId, Long merchantId) {
        if (chapterId == null || merchantId == null) {
            return false;
        }
        MerchantChapterApplication application = applicationMapper.selectByChapterAndMerchant(chapterId, merchantId);
        return application != null && Integer.valueOf(MerchantChapterApplication.STATUS_APPROVED)
                .equals(application.getStatus());
    }

    @Override
    public void assertCurrentMerchantEligibility(CmsTopicChapter chapter, Long merchantId) {
        if (chapter == null) {
            throw new IllegalStateException("章节不存在");
        }
        assertMatchingActiveMerchant(chapter, merchantId);
    }

    @Override
    public MerchantChapterRecruitmentStats getRecruitmentStats(Long chapterId, Integer maxMerchant) {
        ChapterRecruitmentRules.assertMaxMerchant(maxMerchant);
        MerchantChapterRecruitmentStats stats = new MerchantChapterRecruitmentStats();
        if (chapterId == null) {
            stats.setPendingApplicationCount(0);
            stats.setApprovedApplicationCount(0);
            stats.setActiveOfferCount(0);
            stats.setReservedMerchantCount(0);
            stats.setRemainingMerchantCount(maxMerchant == null || maxMerchant == 0 ? null : maxMerchant);
            return stats;
        }
        List<MerchantChapterApplication> pending = applicationMapper.selectByChapterAndStatus(
                chapterId, MerchantChapterApplication.STATUS_PENDING);
        List<MerchantChapterApplication> approved = applicationMapper.selectByChapterAndStatus(
                chapterId, MerchantChapterApplication.STATUS_APPROVED);
        List<MerchantChapterOffer> liveOffers = offerMapper.selectByChapter(chapterId);
        int pendingCount = pending == null ? 0 : pending.size();
        int approvedCount = approved == null ? 0 : approved.size();
        int activeOfferCount = liveOffers == null ? 0 : liveOffers.size();
        Set<Long> reserved = buildReservedMerchantIds(approved, liveOffers);
        stats.setPendingApplicationCount(pendingCount);
        stats.setApprovedApplicationCount(approvedCount);
        stats.setActiveOfferCount(activeOfferCount);
        stats.setReservedMerchantCount(reserved.size());
        stats.setRemainingMerchantCount(maxMerchant == null || maxMerchant == 0
                ? null : Math.max(0, maxMerchant - reserved.size()));
        return stats;
    }

    /**
     * 批量统计:固定两次查询(申请一次、生效供给一次),与章节数无关。
     *
     * <p>占用口径复用 {@link #buildReservedMerchantIds} —— 与单章版是同一份实现,
     * 不在这里另写一遍去重逻辑,否则列表页与详情页迟早对不上。
     */
    @Override
    public Map<Long, MerchantChapterRecruitmentStats> getRecruitmentStatsByChapters(
            Map<Long, Integer> maxMerchantByChapterId) {
        Map<Long, MerchantChapterRecruitmentStats> result =
                new java.util.LinkedHashMap<Long, MerchantChapterRecruitmentStats>();
        if (maxMerchantByChapterId == null || maxMerchantByChapterId.isEmpty()) {
            return result;
        }
        List<Long> chapterIds = new java.util.ArrayList<Long>();
        for (Long chapterId : maxMerchantByChapterId.keySet()) {
            if (chapterId != null) {
                chapterIds.add(chapterId);
            }
        }
        if (chapterIds.isEmpty()) {
            return result;
        }

        // 三次查询,就这三次 —— 与章节数、主题数无关。
        // pending 这次也查:不查就只能给它填 0,而 0 和「没统计」在调用方看来一模一样,
        // 是典型的静默假数据。多一次**批量**查询换 DTO 不撒谎,值得。
        List<MerchantChapterApplication> approvedRows = applicationMapper
                .selectByChapterIdsAndStatus(chapterIds, MerchantChapterApplication.STATUS_APPROVED);
        List<MerchantChapterApplication> pendingRows = applicationMapper
                .selectByChapterIdsAndStatus(chapterIds, MerchantChapterApplication.STATUS_PENDING);
        List<MerchantChapterOffer> liveOfferRows = offerMapper.selectLiveByChapterIds(chapterIds);

        Map<Long, Integer> pendingCountByChapter = new java.util.HashMap<Long, Integer>();
        if (pendingRows != null) {
            for (MerchantChapterApplication row : pendingRows) {
                if (row == null || row.getChapterId() == null) {
                    continue;
                }
                pendingCountByChapter.merge(row.getChapterId(), 1, Integer::sum);
            }
        }

        Map<Long, List<MerchantChapterApplication>> approvedByChapter =
                new java.util.HashMap<Long, List<MerchantChapterApplication>>();
        if (approvedRows != null) {
            for (MerchantChapterApplication row : approvedRows) {
                if (row == null || row.getChapterId() == null) {
                    continue;
                }
                approvedByChapter.computeIfAbsent(row.getChapterId(),
                        k -> new java.util.ArrayList<MerchantChapterApplication>()).add(row);
            }
        }
        Map<Long, List<MerchantChapterOffer>> liveOffersByChapter =
                new java.util.HashMap<Long, List<MerchantChapterOffer>>();
        if (liveOfferRows != null) {
            for (MerchantChapterOffer row : liveOfferRows) {
                if (row == null || row.getChapterId() == null) {
                    continue;
                }
                liveOffersByChapter.computeIfAbsent(row.getChapterId(),
                        k -> new java.util.ArrayList<MerchantChapterOffer>()).add(row);
            }
        }

        for (Map.Entry<Long, Integer> entry : maxMerchantByChapterId.entrySet()) {
            Long chapterId = entry.getKey();
            if (chapterId == null) {
                continue;
            }
            Integer maxMerchant = entry.getValue();
            ChapterRecruitmentRules.assertMaxMerchant(maxMerchant);
            List<MerchantChapterApplication> approved = approvedByChapter.get(chapterId);
            List<MerchantChapterOffer> liveOffers = liveOffersByChapter.get(chapterId);

            MerchantChapterRecruitmentStats stats = new MerchantChapterRecruitmentStats();
            Set<Long> reserved = buildReservedMerchantIds(approved, liveOffers);
            stats.setPendingApplicationCount(pendingCountByChapter.getOrDefault(chapterId, 0));
            stats.setApprovedApplicationCount(approved == null ? 0 : approved.size());
            stats.setActiveOfferCount(liveOffers == null ? 0 : liveOffers.size());
            stats.setReservedMerchantCount(reserved.size());
            stats.setRemainingMerchantCount(maxMerchant == null || maxMerchant == 0
                    ? null : Math.max(0, maxMerchant - reserved.size()));
            result.put(chapterId, stats);
        }
        return result;
    }

    @Override
    public List<MerchantChapterApplication> selectForTopic(Long topicId, Long publisherMemberId) {
        if (topicId == null || topicId <= 0) {
            throw new IllegalStateException("缺少主题");
        }
        CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
        if (topic == null || topic.getMemberId() == null || !topic.getMemberId().equals(publisherMemberId)) {
            throw new IllegalStateException("仅主题发布者可查看章节申请");
        }
        return applicationMapper.selectByTopicId(topicId);
    }

    @Override
    public List<MerchantChapterApplication> selectMine(Long merchantId) {
        if (merchantId == null || merchantId <= 0) {
            throw new IllegalStateException("请先登录");
        }
        return applicationMapper.selectByMerchantId(merchantId);
    }

    @Override
    public List<MerchantChapterApplication> selectForPublisher(Long publisherMemberId) {
        if (publisherMemberId == null || publisherMemberId <= 0) {
            throw new IllegalStateException("请先登录");
        }
        return applicationMapper.selectByPublisherId(publisherMemberId);
    }

    private CmsTopicChapter requireChapter(Long chapterId) {
        CmsTopicChapter chapter = chapterMapper.selectCmsTopicChapterById(chapterId);
        if (chapter == null || chapter.getTopicId() == null) {
            throw new IllegalStateException("章节不存在");
        }
        return chapter;
    }

    private CmsTopic requireTopic(CmsTopicChapter chapter) {
        // apply 已持有章节行锁；继续锁主题行，避免主题软删与申请插入交错而留下悬空申请。
        CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(chapter.getTopicId());
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag()) || topic.getMemberId() == null) {
            throw new IllegalStateException("主题不存在、已删除或归属不明");
        }
        return topic;
    }

    /** 申请/审批共用的上线、模式、锁价与显式招商开关闸。 */
    private void assertRecruitmentOpen(CmsTopicChapter chapter, CmsTopic topic) {
        assertRecruitmentOpen(chapter, topic, false);
    }

    private void assertRecruitmentOpen(CmsTopicChapter chapter, CmsTopic topic, boolean replacement) {
        if (replacement) {
            com.chengyinhub.business.support.OrdinarySupplyReplacementPolicy.assertOpen(topic);
        }
        ChapterRecruitmentRules.assertMaxMerchant(chapter.getMaxMerchant());
        ChapterRecruitmentRules.assertV1RecruitmentTermsMode(chapter.getTermsMode(),
                com.chengyinhub.common.constant.TermsMode.REVSHARE.equals(chapter.getTermsMode())
                && com.chengyinhub.business.support.ClubDirectMerchantPolicy.payerClubId(
                        topic, productRoleService.activeContentSlot(topic.getId())) != null);
        ChapterRecruitmentRules.assertPerkMinValueForTerms(chapter.getTermsMode(), chapter.getPerkMinValue());
        if (!Integer.valueOf(1).equals(chapter.getRecruitEnabled())) {
            throw new IllegalStateException("该章节未开放商家承接");
        }
        SysCategory category = chapter.getCategoryId() == null ? null
                : sysCategoryService.selectSysCategoryById(chapter.getCategoryId().longValue());
        ChapterRecruitmentRules.assertMerchantCategory(chapter.getCategoryId(), category);
        if (topic.getProductType() == null || topic.getProductType() != ProductType.FREE_EXPLORE) {
            throw new IllegalStateException("该章节所属路线不是自由定向，暂不开放商家承接");
        }
        if ((!Integer.valueOf(1).equals(topic.getStatus())
                && !(replacement && "pro".equals(topic.getPublishMode())
                    && Integer.valueOf(0).equals(topic.getStatus())
                    && Integer.valueOf(com.chengyinhub.business.domain.enums.TopicLifecycleState.PENDING_REVIEW.getCode()).equals(topic.getLifecycle())))
                || topic.getUserStatus() == null
                || (!Integer.valueOf(1).equals(topic.getUserStatus())
                    && !(replacement && Integer.valueOf(0).equals(topic.getUserStatus())))) {
            throw new IllegalStateException("主题未上架或未过审，暂不能申请承接");
        }
        if (replacement && topic.getCircleThemeCode() != null && !topic.getCircleThemeCode().trim().isEmpty()) {
            throw new IllegalStateException("圈层主题请沿现成供给复核路径补足");
        }
        if (!replacement && com.chengyinhub.business.domain.enums.TopicLifecycleState
                .commercialTermsFrozen(topic.getLifecycle())) {
            throw new IllegalStateException("主题已锁价，不能再申请或审核商家承接");
        }
        if (!replacement) {
            RecruitmentWindowPolicy.assertOpen(topic.getLifecycle(), topic.getStatus(), topic.getUserStatus(),
                    topic.getRecruitDeadline(), DateUtils.getNowDate());
        }
    }

    private void assertMatchingActiveMerchant(CmsTopicChapter chapter, Long merchantMemberId) {
        MmsMerchant merchant = merchantMapper.selectMmsMerchantByMemberId(merchantMemberId);
        if (merchant == null || !Long.valueOf(1L).equals(merchant.getStatus())
                || !Long.valueOf(1L).equals(merchant.getAccountStatus())
                || Integer.valueOf(2).equals(merchant.getDelFlag())) {
            throw new IllegalStateException("仅审核通过且启用的商家可申请承接");
        }
        if (merchant.getCategoryId() == null || !merchant.getCategoryId().equals(chapter.getCategoryId())) {
            throw new IllegalStateException("商家品类与本章节不匹配");
        }
    }

    /** 已通过申请与已生效 offer 取 merchantId 并集，避免审批超额或同一商家被重复计数。 */
    private void assertApprovalCapacity(CmsTopicChapter chapter, Long applicantMerchantId) {
        Integer cap = chapter.getMaxMerchant();
        ChapterRecruitmentRules.assertMaxMerchant(cap);
        if (cap == null || cap == 0) {
            return;
        }
        List<MerchantChapterApplication> approved = applicationMapper.selectByChapterAndStatus(
                chapter.getId(), MerchantChapterApplication.STATUS_APPROVED);
        List<MerchantChapterOffer> liveOffers = offerMapper.selectByChapter(chapter.getId());
        Set<Long> reservedMerchantIds = buildReservedMerchantIds(approved, liveOffers);
        if (!reservedMerchantIds.contains(applicantMerchantId) && reservedMerchantIds.size() >= cap) {
            throw new IllegalStateException("本章节承接商家已满(" + cap + "家)");
        }
    }

    /** 统计和审批统一按“已通过申请 ∪ 已生效供给”的商家去重口径计算占用。 */
    private Set<Long> buildReservedMerchantIds(List<MerchantChapterApplication> approved,
            List<MerchantChapterOffer> liveOffers) {
        Set<Long> reservedMerchantIds = new HashSet<>();
        if (approved != null) {
            for (MerchantChapterApplication row : approved) {
                if (row != null && row.getMerchantId() != null) {
                    reservedMerchantIds.add(row.getMerchantId());
                }
            }
        }
        if (liveOffers != null) {
            for (MerchantChapterOffer offer : liveOffers) {
                if (offer != null && offer.getMerchantId() != null) {
                    reservedMerchantIds.add(offer.getMerchantId());
                }
            }
        }
        return reservedMerchantIds;
    }

    private String normalizeText(String text, String label) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        if (normalized.length() > 500) {
            throw new IllegalStateException(label + "不能超过500字");
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private void assertWithdrawableBy(MerchantChapterApplication application, Long merchantId) {
        if (application == null) {
            throw withdrawError(MerchantChapterApplicationWithdrawException.ERROR_CODE_NOT_FOUND, "申请不存在");
        }
        if (!merchantId.equals(application.getMerchantId())) {
            throw withdrawError(MerchantChapterApplicationWithdrawException.ERROR_CODE_NOT_OWNER,
                    "只能撤回自己的申请");
        }
        if (!Integer.valueOf(MerchantChapterApplication.STATUS_PENDING).equals(application.getStatus())) {
            throw withdrawError(MerchantChapterApplicationWithdrawException.ERROR_CODE_FINALIZED,
                    "已通过或已拒绝的申请不可撤回");
        }
    }

    private MerchantChapterApplicationWithdrawException withdrawError(String errorCode, String message) {
        return new MerchantChapterApplicationWithdrawException(errorCode, message);
    }
}
