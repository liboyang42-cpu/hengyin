package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.DispatchResult;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IDispatchService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.util.Collections;
import java.util.List;

/**
 * 报名调配深模块。
 *
 * <p>竞争位口径只有这一份：有效 {@code (topic_id,node_id)} 至多一个中标；node_id 为
 * null/0 的自由探索报名不构成竞争位。数据库最终兜底沿用
 * {@code migration_a13_dual_award_unique.sql} 的 win_key 唯一键，不再新增第二张占槽表。</p>
 */
@Service
public class DispatchServiceImpl implements IDispatchService {

    private final ICmsRegistrationMerchantService registrationService;
    private final ICmsTopicService topicService;
    private final CmsTopicMapper topicMapper;
    private final ClubMapper clubMapper;
    private final MmsMerchantMapper mmsMerchantMapper;

    public DispatchServiceImpl(ICmsRegistrationMerchantService registrationService,
                               ICmsTopicService topicService,
                               CmsTopicMapper topicMapper,
                               ClubMapper clubMapper,
                               MmsMerchantMapper mmsMerchantMapper) {
        this.registrationService = registrationService;
        this.topicService = topicService;
        this.topicMapper = topicMapper;
        this.clubMapper = clubMapper;
        this.mmsMerchantMapper = mmsMerchantMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DispatchResult confirmRegistration(Long registrationId) {
        CmsRegistrationMerchant candidate = registrationService.selectCmsRegistrationMerchantById(registrationId);
        if (candidate == null) {
            return DispatchResult.rejected(DispatchResult.Code.REGISTRATION_NOT_FOUND, "报名记录不存在");
        }
        DispatchResult merchantGate = lockActiveMerchantForDispatch(candidate.getMemberId());
        if (merchantGate != null) return merchantGate;
        DispatchResult topicGate = lockActiveTopicForDispatch(candidate.getTopicId());
        if (topicGate != null) return topicGate;
        if (slotHasOtherWinner(candidate)) {
            return DispatchResult.rejected(DispatchResult.Code.SLOT_OCCUPIED, "该竞争位已有中标商家");
        }
        try {
            if (registrationService.confirmWinnerIfPending(registrationId) == 0) {
                return DispatchResult.rejected(DispatchResult.Code.ALREADY_PROCESSED, "该报名已处理，请勿重复选定");
            }
        } catch (DuplicateKeyException e) {
            // 两个不同报名并发通过读守卫时，win_key 是最终裁决；转成稳定业务结果，不把 1062 暴露成 500。
            return DispatchResult.rejected(DispatchResult.Code.SLOT_OCCUPIED, "该竞争位已有中标商家");
        }
        sweepLosers(candidate);
        return DispatchResult.selected(registrationId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DispatchResult confirmNodeMerchant(Long topicId, Long nodeId, Long merchantMemberId) {
        if (topicId == null || merchantMemberId == null) {
            return DispatchResult.rejected(DispatchResult.Code.NO_PENDING_CANDIDATE, "缺少主题或商家");
        }
        DispatchResult merchantGate = lockActiveMerchantForDispatch(merchantMemberId);
        if (merchantGate != null) return merchantGate;
        DispatchResult topicGate = lockActiveTopicForDispatch(topicId);
        if (topicGate != null) return topicGate;
        CmsRegistrationMerchant probe = new CmsRegistrationMerchant();
        probe.setTopicId(topicId);
        probe.setMemberId(merchantMemberId);
        if (nodeId != null) probe.setNodeId(nodeId);
        probe.setAuditStatus(0L);
        probe.setDelFlag(0);
        List<CmsRegistrationMerchant> candidates = safe(registrationService.selectCmsRegistrationMerchantList(probe));
        if (candidates.isEmpty()) {
            // 2026-08-07:被邀商家此刻**本来就没有报名行** —— 他是被主办方邀来的,没走过报名。
            // 原来这里直接返 NO_PENDING_CANDIDATE,调用方只打一条 error 日志(零用户可见告警),
            // 商家以为「已合作」实际没占到节点,要人工去后台点一次 select 才闭环。
            //
            // 邀约本身就是主办方的决定 = 预先批准,所以按邀约补一条**已中标**的报名行,
            // 与自由探索「邀请 = 预先批准的申请」同一范式。
            // ⚠️ 仍然要过竞争位守卫:补出来的行也不能顶掉别人已占的位子。
            return confirmInvitedMerchant(topicId, nodeId, merchantMemberId);
        }
        return confirmRegistration(candidates.get(0).getId());
    }

    /** 选中与停用串行：只有审核通过、启用、未删除的商家能进入履约阵容。 */
    private DispatchResult lockActiveMerchantForDispatch(Long merchantMemberId) {
        MmsMerchant merchant = merchantMemberId == null ? null
                : mmsMerchantMapper.selectMmsMerchantByMemberIdForUpdate(merchantMemberId);
        if (merchant == null || !merchantMemberId.equals(merchant.getMemberId())
                || !Long.valueOf(1L).equals(merchant.getStatus())
                || !Long.valueOf(1L).equals(merchant.getAccountStatus())
                || !Integer.valueOf(0).equals(merchant.getDelFlag())) {
            return DispatchResult.rejected(DispatchResult.Code.MERCHANT_INACTIVE,
                    "商家未审核通过、已停用或已删除，不能选为承接方");
        }
        return null;
    }

    /**
     * 被邀商家没有报名行时,按邀约补一条已中标的报名。
     *
     * <p>竞争位仍由 {@code slotHasOtherWinner} 读守卫 + DB 的 {@code win_key} 唯一键把关 ——
     * 补录不等于插队。
     */
    private DispatchResult confirmInvitedMerchant(Long topicId, Long nodeId, Long merchantMemberId) {
        CmsRegistrationMerchant seed = new CmsRegistrationMerchant();
        seed.setTopicId(topicId);
        seed.setNodeId(nodeId);
        seed.setMemberId(merchantMemberId);
        seed.setMode(1);                       // 邀商家承接节点只存在于经典定向
        seed.setStatus(0L);
        seed.setAuditStatus(0L);               // 先落待调配,再走与报名同一条 CAS 中标路径
        seed.setDelFlag(0);
        if (slotHasOtherWinner(seed)) {
            return DispatchResult.rejected(DispatchResult.Code.SLOT_OCCUPIED, "该竞争位已有中标商家");
        }
        try {
            registrationService.insertCmsRegistrationMerchant(seed);
        } catch (DuplicateKeyException e) {
            return DispatchResult.rejected(DispatchResult.Code.SLOT_OCCUPIED, "该竞争位已有中标商家");
        }
        if (seed.getId() == null) {
            return DispatchResult.rejected(DispatchResult.Code.NO_PENDING_CANDIDATE, "补录承接记录失败");
        }
        return confirmRegistration(seed.getId());
    }

    /** 与俱乐部解散共用 club 行锁，避免候选确认在解散计数后继续落中标状态。 */
    private DispatchResult lockActiveTopicForDispatch(Long topicId) {
        if (topicId == null) {
            return DispatchResult.rejected(DispatchResult.Code.TOPIC_NOT_FOUND, "主题不存在");
        }
        CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || !Integer.valueOf(0).equals(topic.getDelFlag())) {
            return DispatchResult.rejected(DispatchResult.Code.TOPIC_NOT_FOUND, "主题不存在");
        }
        if (topic.getClubId() != null && topic.getClubId() > 0
                && clubMapper.selectClubByIdForUpdate(topic.getClubId()) == null) {
            return DispatchResult.rejected(DispatchResult.Code.CLUB_DISSOLVED,
                    "主题所属俱乐部已解散，不能确认候选");
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DispatchResult rejectRegistration(Long registrationId, String reason) {
        CmsRegistrationMerchant candidate = registrationService.selectCmsRegistrationMerchantById(registrationId);
        if (candidate == null) {
            return DispatchResult.rejected(DispatchResult.Code.REGISTRATION_NOT_FOUND, "报名记录不存在");
        }
        if (candidate.getTopicId() == null || topicService.selectCmsTopicById(candidate.getTopicId()) == null) {
            return DispatchResult.rejected(DispatchResult.Code.TOPIC_NOT_FOUND, "主题不存在");
        }
        String rejectionReason = (reason == null || reason.trim().isEmpty()) ? "后台驳回" : reason.trim();
        if (registrationService.rejectIfPending(registrationId, rejectionReason) == 0) {
            return DispatchResult.rejected(DispatchResult.Code.ALREADY_PROCESSED,
                    "该报名已选定或已处理，不能重复驳回");
        }
        return DispatchResult.registrationRejected(registrationId);
    }

    private boolean slotHasOtherWinner(CmsRegistrationMerchant candidate) {
        if (!hasNodeSlot(candidate.getNodeId())) return false;
        CmsRegistrationMerchant probe = slotProbe(candidate, 1L);
        for (CmsRegistrationMerchant winner : safe(registrationService.selectCmsRegistrationMerchantList(probe))) {
            if (winner.getId() != null && !winner.getId().equals(candidate.getId())) return true;
        }
        return false;
    }

    private void sweepLosers(CmsRegistrationMerchant winner) {
        if (!hasNodeSlot(winner.getNodeId())) return;
        CmsRegistrationMerchant probe = slotProbe(winner, 0L);
        for (CmsRegistrationMerchant sibling : safe(registrationService.selectCmsRegistrationMerchantList(probe))) {
            if (sibling.getId() == null || sibling.getId().equals(winner.getId())) continue;
            registrationService.rejectIfPending(sibling.getId(), "未选中：该地点已选定其他商家承接");
        }
    }

    private static CmsRegistrationMerchant slotProbe(CmsRegistrationMerchant source, Long auditStatus) {
        CmsRegistrationMerchant probe = new CmsRegistrationMerchant();
        probe.setTopicId(source.getTopicId());
        probe.setNodeId(source.getNodeId());
        probe.setAuditStatus(auditStatus);
        probe.setDelFlag(0);
        return probe;
    }

    private static boolean hasNodeSlot(Long nodeId) {
        return nodeId != null && nodeId != 0L;
    }

    private static <T> List<T> safe(List<T> value) {
        return value == null ? Collections.<T>emptyList() : value;
    }
}
