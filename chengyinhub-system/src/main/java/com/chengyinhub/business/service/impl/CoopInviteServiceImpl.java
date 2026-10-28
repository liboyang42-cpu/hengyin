package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.CoopInviteRequestReceipt;
import com.chengyinhub.business.domain.dto.CoopInviteRequestResult;
import com.chengyinhub.business.mapper.CoopInviteRequestReceiptMapper;
import org.springframework.dao.DuplicateKeyException;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.constant.CoopTermsAxes;
import com.chengyinhub.common.constant.RecruitmentWindowPolicy;
import com.chengyinhub.business.service.support.ClubCoopWindowPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopCreditLogMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.CoopPerkTemplateMapper;
import com.chengyinhub.business.mapper.CoopReviewMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.IPricingService;
import com.chengyinhub.business.service.ICoopClubApplyService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.CoopPerkTemplate;
import com.chengyinhub.business.domain.CoopReview;
import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopCreditLog;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.CoopInviteExpiredException;
import com.chengyinhub.business.service.ITopicSaleCanonicalMutationService;

/**
 * 协作邀请 Service 实现(P1.5)
 */
@Service
public class CoopInviteServiceImpl implements ICoopInviteService {
    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.chengyinhub.business.service.ITopicCommercialReviewService commercialReviewService;


    private static final Logger logger = LoggerFactory.getLogger(CoopInviteServiceImpl.class);
    private static final long PENDING_INVITE_TTL_MILLIS = 48L * 60L * 60L * 1000L;

    @Autowired
    private CoopInviteMapper coopInviteMapper;

    @Autowired private CoopInviteRequestReceiptMapper requestReceiptMapper;
    @Autowired private IMerchantAccessService merchantAccessService;
    @Autowired private PlatformTransactionManager transactionManager;

    @Autowired private CoopDepositRefundService depositRefundService;
    @Autowired private com.chengyinhub.business.service.IWechatPayService wechatPayService;

    @Autowired
    private CoopOrderMapper coopOrderMapper;

    @Autowired
    private CoopCreditLogMapper coopCreditLogMapper;

    @Autowired
    private CoopSettlementMapper coopSettlementMapper;

    @Autowired
    private CoopPerkTemplateMapper coopPerkTemplateMapper;

    @Autowired
    private CoopReviewMapper coopReviewMapper;

    @Autowired
    private CoopPerkMapper coopPerkMapper;

    @Autowired
    private ClubMapper clubMapper;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    @Autowired
    private ClubCoopWindowPolicy clubCoopWindowPolicy;

    @Autowired
    private CmsRegistrationMapper cmsRegistrationMapper;

    @Autowired
    private CmsTopicNodeMapper cmsTopicNodeMapper;

    @Autowired
    private CmsRegistrationMerchantMapper cmsRegistrationMerchantMapper;

    @Autowired
    private MmsMerchantMapper mmsMerchantMapper;

    @Autowired
    private IPricingService pricingService;

    @Autowired
    private ICoopClubApplyService coopClubApplyService;

    @Autowired
    private INotifyService notifyService;

    @Autowired(required = false)
    private ITopicSaleCanonicalMutationService canonicalMutationService;

    /**
     * 受邀方 owner 的 member id。商家承接:to_id 即商家 member;俱乐部:to_id 是 club_id,需解析为主理人 member。
     * (走查发现:attachPerks/评价原直接比 to_id,导致俱乐部方申报供给/回评必失败。)
     */
    /** 两 member 是否为该主题已接受合作双方；所有 club 主键先解析为 owner memberId 再比较。 */
    private boolean isCoopParties(Long topicId, Long from, Long to) {
        if (from == null || to == null || topicId == null) return false;
        CoopInvite query = new CoopInvite();
        query.setTopicId(topicId);
        query.setStatus(1);
        List<CoopInvite> accepted = coopInviteMapper.selectCoopInviteList(query);
        if (accepted == null) return false;
        for (CoopInvite invite : accepted) {
            if (isLegacyMerchantNodeInvite(invite)) continue;
            Long inviter = resolveInviterOwner(invite);
            Long invitee = resolveInviteeOwner(invite);
            if ((from.equals(inviter) && to.equals(invitee))
                    || (from.equals(invitee) && to.equals(inviter))) {
                return true;
            }
        }
        return false;
    }

    private Long resolveInviteeOwner(CoopInvite inv) {
        if (inv == null) return null;
        if ("club".equals(inv.getToType()) && inv.getToId() != null) {
            Club c = clubMapper.selectClubById(inv.getToId());
            return c == null ? null : c.getMemberId();
        }
        return inv.getToId();
    }

    private Long resolveInviterOwner(CoopInvite inv) {
        if (inv == null) return null;
        if ("club".equals(inv.getFromType()) && inv.getFromId() != null) {
            Club c = clubMapper.selectClubById(inv.getFromId());
            return c == null ? null : c.getMemberId();
        }
        return inv.getFromId();
    }

    private boolean isLegacyMerchantNodeInvite(CoopInvite invite) {
        return invite != null
                && Integer.valueOf(CoopInvite.TYPE_LEGACY_MERCHANT_NODE).equals(invite.getInviteType());
    }

    private void assertLegacyInviteReadOnly(CoopInvite invite) {
        if (isLegacyMerchantNodeInvite(invite)) {
            throw new IllegalStateException("历史商家节点邀约仅供查看，不能再处理");
        }
    }

    private List<CoopInvite> mutableInvites(List<CoopInvite> invites) {
        if (invites == null || invites.isEmpty()) return Collections.emptyList();
        List<CoopInvite> active = new ArrayList<CoopInvite>();
        for (CoopInvite invite : invites) {
            if (!isLegacyMerchantNodeInvite(invite)) active.add(invite);
        }
        return active;
    }

    /**
     * B4-3 信用→槽位配额动态化:自由定向主题的合作方容量按【主题发布者近90天履约率】浮动。
     * 好(履约率≥90 且 0 违约)→8;差(履约率<60 或 违约≥3)→2;一般/样本不足→5(报告 P3 口径)。
     */
    public int dynamicTopicSlotCap(Long publisherId) {
        if (publisherId == null) return 5;
        Map<String, Object> c = creditSummary(publisherId);
        int rate = ((Number) c.getOrDefault("fulfillmentRate", 100)).intValue();
        int violations = ((Number) c.getOrDefault("violationCount", 0)).intValue();
        int fulfil = ((Number) c.getOrDefault("fulfil", 0)).intValue();
        int total = fulfil + violations;
        if (total < 3) return 5;                       // 样本不足,给默认
        if (rate >= 90 && violations == 0) return 8;   // 履约优
        if (rate < 60 || violations >= 3) return 2;    // 履约差
        return 5;                                      // 一般
    }

    @Override
    public CoopInvite selectCoopInviteById(Long id) {
        return coopInviteMapper.selectCoopInviteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<CoopInvite> selectCoopInviteList(CoopInvite coopInvite) {
        // 查询前先把已超时的待确认行幂等写入终态，列表不依赖 Quartz 是否正常运行。
        coopInviteMapper.expirePendingInvites(DateUtils.getNowDate());
        return coopInviteMapper.selectCoopInviteList(coopInvite);
    }

    @Override
    public List<CoopInvite> selectCoopInviteListReadOnly(CoopInvite coopInvite) {
        return coopInviteMapper.selectCoopInviteListReadOnly(coopInvite, DateUtils.getNowDate());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertCoopInvite(CoopInvite coopInvite) {
        return insertStandardInvite(coopInvite, null);
    }

    private int insertStandardInvite(CoopInvite coopInvite, CoopInviteRequestReceipt requestReceipt) {
        boolean originalRequest = requestReceipt != null;
        assertLegacyInviteReadOnly(coopInvite);
        if (CoopInvite.FROM_OFFICIAL.equals(coopInvite.getFromType())) {
            throw new IllegalStateException("官方邀约必须通过官方入口创建");
        }
        assertStandardInviteTypeMatrix(coopInvite);
        if (Integer.valueOf(1).equals(coopInvite.getShareMode())) {
            throw new IllegalStateException("普通合作邀约不支持销售分成，必须走官方邀约入口");
        }
        assertOriginApplyMatches(coopInvite);
        CmsTopic topic = lockInviteTopic(coopInvite);
        if (topic != null) assertInviteWindowOpen(topic, coopInvite, DateUtils.getNowDate());
        lockActiveClubsForInviteWrite(coopInvite, topic, "发起合作邀请");
        if (originalRequest && !requestSubjectMatchesCurrent(requestReceipt.getPrincipalMemberId(), requestReceipt, topic)) {
            throw new ServiceException("COOP_REQUEST_UNAVAILABLE", HttpStatus.FORBIDDEN);
        }
        // CU-M-48:「一主题一俱乐部」原来只在接受事务里判(acceptInviteWithLock 的同名闸),发起侧不判就能
        // 造出一条永远接不上的待确认邀约:对方点接受必被拒,列表长期挂待确认、双方都不知道在等什么。
        // 位置在 lockInviteTopic 的主题行锁之后、与接受路径同序取锁,避免和并发接受互相漏掉。
        if (originalRequest) assertRequestBusinessDuplicatesCurrent(coopInvite);
        else assertNoClubCoopOnTopic(coopInvite);
        // CU-M-96:重复待确认邀约闸。位置必须在 lockInviteTopic 之后 —— 主题行 FOR UPDATE
        // 把同主题的发起串起来,两道并发「发同一对象」才不会都读到「还没有」而双双放过。
        if (!originalRequest) assertNoDuplicatePendingInvite(coopInvite);
        assertActiveStandardInviteMerchantForUpdate(coopInvite);
        Date now = DateUtils.getNowDate();
        coopInvite.setCreateTime(now);
        if (originalRequest && topic != null) coopInvite.setExpireTime(topic.getRecruitDeadline());
        coopInvite.setExpireTime(pendingInviteExpiry(now, coopInvite.getExpireTime()));
        int n = coopInviteMapper.insertCoopInvite(coopInvite);
        // 申请转成邀约后回写申请状态(0→3)。与建邀约**同一事务**:
        // 分开做会出现「邀约建了但申请还显示待处理」—— 俱乐部侧以为主办方没理他,
        // 而主办方那边已经发出去了,两边各看各的,谁也不知道对方在等。
        if (n > 0 && coopInvite.getOriginApplyId() != null) {
            int converted = coopClubApplyService.markConverted(coopInvite.getOriginApplyId());
            if (originalRequest && converted != 1) throw new IllegalStateException("申请转换失败");
        }
        return n;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CoopInviteRequestResult createOrReplayInvite(Long principalMemberId, CoopInvite invite, Runnable beforeCreate) {
        requireRequestPrincipal(principalMemberId);
        String key = requireRequestId(invite == null ? null : invite.getRequestId());
        CoopInviteRequestReceipt receipt = requestReceiptMapper.selectByRequest(principalMemberId, key);
        if (receipt != null) {
            receipt = requestReceiptMapper.selectByRequestForUpdate(principalMemberId, key);
            return replayInviteRequest(principalMemberId, invite, receipt);
        }
        CoopInviteRequestReceipt claim = requestFacts(principalMemberId, invite);
        claim.setRequestId(key);
        claim.setFingerprintVersion(1);
        claim.setFingerprint(requestFingerprint(invite, claim));
        try {
            if (requestReceiptMapper.insertReceipt(claim) != 1 || claim.getId() == null) {
                throw new IllegalStateException("原请求登记失败");
            }
        } catch (DuplicateKeyException duplicate) {
            // Unique-key wait may outlive this transaction's RR snapshot: only a current read sees the winner.
            receipt = requestReceiptMapper.selectByRequestForUpdate(principalMemberId, key);
            if (receipt == null) throw duplicate;
            return replayInviteRequest(principalMemberId, invite, receipt);
        }
        beforeCreate.run();
        invite.setId(null);
        invite.setStatus(0);
        invite.setDelFlag(0);
        invite.setHandleTime(null);
        invite.setHandleReason(null);
        invite.setConfigDeadline(null);
        invite.setCreateBy(principalMemberId.toString());
        if (insertStandardInvite(invite, claim) != 1 || invite.getId() == null) {
            throw new IllegalStateException("邀约创建失败");
        }
        if (requestReceiptMapper.bindInvite(claim.getId(), invite.getId()) != 1) {
            throw new IllegalStateException("原请求绑定失败");
        }
        claim.setInviteId(invite.getId());
        return CoopInviteRequestResult.created(claim, false);
    }

    private CoopInviteRequestResult replayInviteRequest(Long principalMemberId, CoopInvite invite,
            CoopInviteRequestReceipt receipt) {
        if (!requestAvailable(principalMemberId, receipt, true)) {
            throw new ServiceException("COOP_REQUEST_UNAVAILABLE", HttpStatus.FORBIDDEN);
        }
        if (!Integer.valueOf(1).equals(receipt.getFingerprintVersion())
                || !receipt.getFingerprint().equals(requestFingerprint(invite, receipt))) {
            throw new ServiceException("COOP_REQUEST_CONFLICT", HttpStatus.CONFLICT);
        }
        invite.setId(receipt.getInviteId());
        return CoopInviteRequestResult.created(receipt, true);
    }

    @Override
    @Transactional(readOnly = true)
    public CoopInviteRequestResult findInviteRequest(Long principalMemberId, String requestId) {
        requireRequestPrincipal(principalMemberId);
        String key = requireRequestId(requestId);
        CoopInviteRequestReceipt receipt = requestReceiptMapper.selectByRequest(principalMemberId, key);
        if (receipt == null) return CoopInviteRequestResult.state("NOT_FOUND", key);
        return requestAvailable(principalMemberId, receipt) ? CoopInviteRequestResult.created(receipt, null)
                : CoopInviteRequestResult.state("UNAVAILABLE", key);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listInviteRequests(Long principalMemberId, Long before, int limit) {
        requireRequestPrincipal(principalMemberId);
        if ((before != null && before <= 0) || limit < 1 || limit > 50) {
            throw new ServiceException("原请求分页参数不合法", HttpStatus.BAD_REQUEST);
        }
        List<CoopInviteRequestReceipt> rows = requestReceiptMapper.selectPage(principalMemberId, before, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<CoopInviteRequestResult> items = new ArrayList<>();
        Long lastScanned = null;
        for (CoopInviteRequestReceipt receipt : rows.subList(0, Math.min(limit, rows.size()))) {
            lastScanned = receipt.getId();
            items.add(requestAvailable(principalMemberId, receipt) ? CoopInviteRequestResult.created(receipt, null)
                    : CoopInviteRequestResult.state("UNAVAILABLE", receipt.getRequestId()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", items);
        result.put("nextCursor", hasMore && lastScanned != null ? lastScanned.toString() : null);
        result.put("hasMore", hasMore);
        return result;
    }

    private void requireRequestPrincipal(Long principalMemberId) {
        if (principalMemberId == null || principalMemberId <= 0) {
            throw new ServiceException("请先登录", HttpStatus.UNAUTHORIZED);
        }
    }

    private String requireRequestId(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ServiceException("requestId格式不合法", HttpStatus.BAD_REQUEST);
        }
        return key;
    }

    /** Resolve current subject; caller-provided from/status/time/deposit fields are not request facts. */
    private CoopInviteRequestReceipt requestFacts(Long principalMemberId, CoopInvite invite) {
        if (invite == null || invite.getTopicId() == null || invite.getToId() == null || invite.getToId() <= 0
                || invite.getInviteType() == null || invite.getInviteType() < 0 || invite.getInviteType() > 1) {
            throw new ServiceException("邀请参数不合法", HttpStatus.BAD_REQUEST);
        }
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(invite.getTopicId());
        if (topic == null || !Integer.valueOf(0).equals(topic.getDelFlag())) {
            throw new ServiceException("COOP_REQUEST_UNAVAILABLE", HttpStatus.FORBIDDEN);
        }
        Long owner = principalMemberId;
        String fromType;
        if (invite.getInviteType() == 1 && topic.getClubId() != null && topic.getClubId() > 0) {
            Club club = clubMapper.selectClubById(topic.getClubId());
            if (club == null || !Integer.valueOf(0).equals(club.getDelFlag())
                    || !principalMemberId.equals(club.getMemberId())) {
                throw new ServiceException("COOP_REQUEST_UNAVAILABLE", HttpStatus.FORBIDDEN);
            }
            fromType = "club";
        } else if (invite.getInviteType() == 1 || MerchantOperationScope.isMerchant(invite.getScope())) {
            owner = requestMerchantOwner(principalMemberId, invite.getInviteType() == 1
                    ? MerchantPermission.COOP_MANAGE : MerchantPermission.PROJECT_MANAGE);
            fromType = "merchant";
        } else fromType = "talent";
        if (owner == null || !owner.equals(topic.getMemberId())) {
            throw new ServiceException("COOP_REQUEST_UNAVAILABLE", HttpStatus.FORBIDDEN);
        }
        invite.setFromType(fromType);
        invite.setFromId("club".equals(fromType) ? topic.getClubId() : owner);
        invite.setNodeId(null);
        invite.setTargetLevel(invite.getGameId() == null ? 0 : 1);
        assertStandardInviteTypeMatrix(invite);
        Long toOwner = requestRecipientOwner(invite.getToType(), invite.getToId());
        if (toOwner == null || owner.equals(toOwner)) {
            throw new ServiceException("COOP_REQUEST_UNAVAILABLE", HttpStatus.FORBIDDEN);
        }
        CoopInviteRequestReceipt facts = new CoopInviteRequestReceipt();
        facts.setPrincipalMemberId(principalMemberId);
        facts.setFromType(fromType);
        facts.setFromId(invite.getFromId());
        facts.setFromOwnerMemberId(owner);
        facts.setToOwnerMemberId(toOwner);
        facts.setScope(invite.getInviteType() == 0 && "merchant".equals(fromType) ? "MERCHANT" : "");
        facts.setInviteType(invite.getInviteType());
        facts.setTopicId(invite.getTopicId());
        facts.setToType(invite.getToType());
        facts.setToId(invite.getToId());
        return facts;
    }

    private Long requestMerchantOwner(Long principalMemberId, String permission) {
        // Receipt/topic waits must not reuse an outer RR snapshot that still grants a revoked employee access.
        TransactionTemplate authorization = new TransactionTemplate(transactionManager);
        authorization.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        authorization.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        authorization.setReadOnly(true);
        return authorization.execute(status -> merchantAccessService.require(principalMemberId, permission).getOwnerMemberId());
    }

    private Long requestRecipientOwner(String toType, Long toId) {
        if ("club".equals(toType)) {
            Club club = clubMapper.selectClubById(toId);
            return club != null && Integer.valueOf(0).equals(club.getDelFlag()) ? club.getMemberId() : null;
        }
        if ("merchant".equals(toType)) {
            MmsMerchant merchant = mmsMerchantMapper.selectMmsMerchantByMemberId(toId);
            return merchant != null && Integer.valueOf(0).equals(merchant.getDelFlag()) ? merchant.getMemberId() : null;
        }
        return null;
    }

    private boolean requestAvailable(Long principalMemberId, CoopInviteRequestReceipt receipt) {
        return requestAvailable(principalMemberId, receipt, false);
    }

    private boolean requestAvailable(Long principalMemberId, CoopInviteRequestReceipt receipt, boolean currentRead) {
        if (receipt == null || !principalMemberId.equals(receipt.getPrincipalMemberId()) || receipt.getInviteId() == null) return false;
        CoopInvite scope = new CoopInvite();
        scope.setInviteType(receipt.getInviteType());
        scope.setTopicId(receipt.getTopicId());
        scope.setFromType(receipt.getFromType());
        scope.setFromId(receipt.getFromId());
        scope.setToType(receipt.getToType());
        scope.setToId(receipt.getToId());
        scope.setScope(receipt.getScope());
        try {
            if (currentRead) {
                CmsTopic topic = cmsTopicMapper.selectCmsTopicByIdForUpdate(receipt.getTopicId());
                if (topic == null || !Integer.valueOf(0).equals(topic.getDelFlag())) return false;
                lockActiveClubsForInviteWrite(scope, topic, "回读原请求");
                if (!requestSubjectMatchesCurrent(principalMemberId, receipt, topic)) return false;
            } else {
                CoopInviteRequestReceipt current = requestFacts(principalMemberId, scope);
                if (!Objects.equals(current.getFromType(), receipt.getFromType())
                        || !Objects.equals(current.getFromId(), receipt.getFromId())
                        || !Objects.equals(current.getFromOwnerMemberId(), receipt.getFromOwnerMemberId())
                        || !Objects.equals(current.getToOwnerMemberId(), receipt.getToOwnerMemberId())) return false;
            }
            CoopInvite original = currentRead ? coopInviteMapper.selectCoopInviteByIdForUpdate(receipt.getInviteId())
                    : coopInviteMapper.selectCoopInviteById(receipt.getInviteId());
            return original != null && Integer.valueOf(0).equals(original.getDelFlag())
                    && Objects.equals(original.getInviteType(), receipt.getInviteType())
                    && Objects.equals(original.getTopicId(), receipt.getTopicId())
                    && Objects.equals(original.getFromType(), receipt.getFromType())
                    && Objects.equals(original.getFromId(), receipt.getFromId())
                    && Objects.equals(original.getToType(), receipt.getToType())
                    && Objects.equals(original.getToId(), receipt.getToId());
        } catch (ServiceException | IllegalStateException unavailable) { return false; }
    }

    /** Topic and sorted club locks are already held, before any invitation lock. No creation eligibility gates. */
    private boolean requestSubjectMatchesCurrent(Long principalMemberId, CoopInviteRequestReceipt receipt, CmsTopic topic) {
        if (!Objects.equals(topic.getMemberId(), receipt.getFromOwnerMemberId())) return false;
        boolean clubPublisher = receipt.getInviteType() == 1 && topic.getClubId() != null && topic.getClubId() > 0;
        if (clubPublisher) {
            Club publisher = clubMapper.selectClubByIdForUpdate(topic.getClubId());
            if (!"club".equals(receipt.getFromType()) || !Objects.equals(topic.getClubId(), receipt.getFromId())
                    || publisher == null || !Integer.valueOf(0).equals(publisher.getDelFlag())
                    || !Objects.equals(publisher.getMemberId(), receipt.getFromOwnerMemberId())
                    || !Objects.equals(principalMemberId, receipt.getFromOwnerMemberId())) return false;
        } else if ("merchant".equals(receipt.getFromType())) {
            Long owner = requestMerchantOwner(principalMemberId, receipt.getInviteType() == 1
                    ? MerchantPermission.COOP_MANAGE : MerchantPermission.PROJECT_MANAGE);
            if (!Objects.equals(owner, receipt.getFromOwnerMemberId()) || !Objects.equals(owner, receipt.getFromId())) return false;
        } else if (receipt.getInviteType() != 0 || !"talent".equals(receipt.getFromType())
                || !Objects.equals(principalMemberId, receipt.getFromOwnerMemberId())
                || !Objects.equals(principalMemberId, receipt.getFromId())) return false;
        if ("club".equals(receipt.getToType())) {
            Club recipient = clubMapper.selectClubByIdForUpdate(receipt.getToId());
            return recipient != null && Integer.valueOf(0).equals(recipient.getDelFlag())
                    && Objects.equals(recipient.getMemberId(), receipt.getToOwnerMemberId());
        }
        MmsMerchant recipient = mmsMerchantMapper.selectMmsMerchantByMemberIdForUpdate(receipt.getToId());
        return recipient != null && Integer.valueOf(0).equals(recipient.getDelFlag())
                && Objects.equals(recipient.getMemberId(), receipt.getToOwnerMemberId());
    }

    private String requestFingerprint(CoopInvite invite, CoopInviteRequestReceipt facts) {
        // Length-prefix every field so embedded separators cannot alias a different contract.
        String scope = Integer.valueOf(0).equals(invite.getInviteType()) && MerchantOperationScope.isMerchant(invite.getScope()) ? "MERCHANT" : "";
        Object[] fields = {1, invite.getInviteType(), invite.getTopicId(), invite.getToType(), invite.getToId(),
                invite.getGameId(), invite.getOriginApplyId(), invite.getMessage() == null ? "" : invite.getMessage(),
                scope, facts.getFromType(), facts.getFromId(), facts.getFromOwnerMemberId(),
                facts.getToOwnerMemberId(), invite.getShareMode(), requestDecimal(invite.getShareRate()), requestDecimal(invite.getFixedFee())};
        StringBuilder canonical = new StringBuilder();
        for (Object field : fields) {
            if (field == null) canonical.append("-1:");
            else { String value = field.toString(); canonical.append(value.length()).append(':').append(value); }
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : hash) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private String requestDecimal(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void assertRequestBusinessDuplicatesCurrent(CoopInvite invite) {
        // Topic lock precedes this current read, including when receipt/auth reads established an older RR snapshot.
        for (CoopInvite existing : requestReceiptMapper.selectTopicInvitesForUpdate(invite.getTopicId(), DateUtils.getNowDate())) {
            if (isLegacyMerchantNodeInvite(existing)) continue;
            if ("club".equals(invite.getToType()) && Integer.valueOf(1).equals(existing.getStatus())
                    && "club".equals(existing.getToType())) {
                throw new IllegalStateException("该主题已有合作俱乐部,一个主题只能有一个组织方;请先取消原俱乐部再邀");
            }
            if (Integer.valueOf(0).equals(existing.getStatus()) && Objects.equals(invite.getToType(), existing.getToType())
                    && Objects.equals(invite.getToId(), existing.getToId()) && Objects.equals(invite.getNodeId(), existing.getNodeId())) {
                throw new ServiceException("你已向对方发过这个主题的邀约,等对方处理后再发", HttpStatus.BAD_REQUEST);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Long> createOfficialMerchantInvites(Long officialMemberId,
            com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest request) {
        if (officialMemberId == null || request == null || request.getTopicId() == null
                || request.getMerchantIds() == null || request.getMerchantIds().isEmpty()) {
            throw new IllegalArgumentException("官方邀约参数不完整");
        }
        Long topicId = request.getTopicId();
        CoopInvite scope = new CoopInvite();
        scope.setTopicId(topicId);
        CmsTopic topic = lockInviteTopic(scope);
        lockActiveClubsForInviteWrite(scope, topic, "发起官方合作邀请");
        if (topic != null) RecruitmentWindowPolicy.assertOpen(topic.getLifecycle(), topic.getStatus(), topic.getUserStatus(), topic.getRecruitDeadline(), DateUtils.getNowDate());
        if (request.getNodeId() != null) {
            CmsTopicNode node = cmsTopicNodeMapper.selectCmsTopicNodeById(request.getNodeId());
            if (node == null || !topicId.equals(node.getTopicId())) {
                throw new IllegalArgumentException("节点不属于该主题");
            }
        }
        java.util.LinkedHashSet<Long> recipients = new java.util.LinkedHashSet<Long>();
        for (Long merchantId : request.getMerchantIds()) {
            if (merchantId != null && merchantId > 0) recipients.add(merchantId);
        }
        if (recipients.isEmpty()) throw new IllegalArgumentException("缺少有效商家");
        validateOfficialTerms(request.getShareMode(), request.getShareRate(), request.getFixedFee());
        // CU-M-96:官方入口绕过 insertCoopInvite 直接写库,同一道重复闸要在这里补上,
        // 否则「同主题同商家两条待确认」只剩一半被拦住。整批 fail-closed:任一个对方已有
        // 待确认邀约就不写任何一条(方法在事务里,RuntimeException 回滚),避免半批发出。
        for (Long merchantId : recipients) {
            CoopInvite probe = new CoopInvite();
            probe.setTopicId(topicId);
            probe.setNodeId(request.getNodeId());
            probe.setToType(CoopInvite.TO_MERCHANT);
            probe.setToId(merchantId);
            assertNoDuplicatePendingInvite(probe);
        }

        List<Long> ids = new ArrayList<Long>();
        Date now = DateUtils.getNowDate();
        for (Long merchantId : recipients) {
            assertActiveMerchant(merchantId);
            CoopInvite invite = new CoopInvite();
            invite.setInviteType(CoopInvite.TYPE_OFFICIAL_MERCHANT);
            invite.setFromType(CoopInvite.FROM_OFFICIAL);
            invite.setFromId(officialMemberId);
            invite.setToType(CoopInvite.TO_MERCHANT);
            invite.setToId(merchantId);
            invite.setTopicId(topicId);
            invite.setNodeId(request.getNodeId());
            invite.setStatus(0);
            invite.setMessage(request.getMessage());
            Date requestedDeadline = request.getExpireTime() != null
                    ? request.getExpireTime() : topic.getRecruitDeadline();
            invite.setExpireTime(pendingInviteExpiry(now, requestedDeadline));
            invite.setShareMode(request.getShareMode());
            invite.setShareRate(request.getShareRate());
            invite.setFixedFee(request.getFixedFee());
            invite.setDelFlag(0);
            invite.setCreateTime(now);
            coopInviteMapper.insertCoopInvite(invite);
            ids.add(invite.getId());
        }
        return ids;
    }

    private void validateOfficialTerms(Integer shareMode, BigDecimal shareRate, BigDecimal fixedFee) {
        if (shareMode == null) return;
        if (shareMode < 0 || shareMode > 2) throw new IllegalArgumentException("不支持的报酬模式");
        if (shareMode == 1 && (shareRate == null || shareRate.compareTo(BigDecimal.ZERO) <= 0
                || shareRate.compareTo(new BigDecimal("100")) > 0)) {
            throw new IllegalArgumentException("分成比例须在0到100之间");
        }
        if (shareMode == 2 && (fixedFee == null || fixedFee.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new IllegalArgumentException("固定报酬须大于0");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = CoopInviteExpiredException.class)
    public Long acceptOfficialInviteWithLock(Long inviteId, Long merchantMemberId) {
        CoopInvite invite = lockPendingOfficialInvite(inviteId, merchantMemberId);
        if (invite.getTopicId() == null) throw new IllegalStateException("官方邀约缺少关联主题");
        // 不同邀约行也可能指向同一承接档案；锁稳定的 topic 行，把同主题所有官方接受串行化，
        // 再在锁内按 merchant/topic/node/template 查询复用，堵住两张邀约并发 select→insert。
        CmsTopic topic = lockInviteTopic(invite);
        lockActiveClubsForInviteWrite(invite, topic, "接受官方合作邀请");
        if (topic != null) {
            RecruitmentWindowPolicy.assertOpen(topic.getLifecycle(), topic.getStatus(), topic.getUserStatus(), topic.getRecruitDeadline(), DateUtils.getNowDate());
        }

        CmsTopicNode node = null;
        if (invite.getNodeId() != null) {
            node = cmsTopicNodeMapper.selectCmsTopicNodeById(invite.getNodeId());
            if (node == null || !invite.getTopicId().equals(node.getTopicId())) {
                throw new IllegalStateException("节点不属于该主题");
            }
        }

        CmsRegistrationMerchant probe = new CmsRegistrationMerchant();
        probe.setMemberId(merchantMemberId);
        probe.setTopicId(invite.getTopicId());
        probe.setDelFlag(0);
        List<CmsRegistrationMerchant> existing = cmsRegistrationMerchantMapper.selectCmsRegistrationMerchantList(probe);
        Long registrationId = null;
        if (existing != null) {
            Long targetNodeId = invite.getNodeId();
            Long targetTemplateId = node == null ? null : node.getTemplateId();
            for (CmsRegistrationMerchant row : existing) {
                if (java.util.Objects.equals(targetNodeId, row.getNodeId())
                        && java.util.Objects.equals(targetTemplateId, row.getTemplateId())) {
                    registrationId = row.getId();
                    break;
                }
            }
        }

        if (registrationId == null) {
            CmsRegistrationMerchant registration = new CmsRegistrationMerchant();
            registration.setMemberId(merchantMemberId);
            registration.setTopicId(invite.getTopicId());
            registration.setMode(topic.getProductType());
            registration.setNodeId(invite.getNodeId());
            if (node != null) {
                registration.setTemplateId(node.getTemplateId());
                registration.setAddressName(node.getName());
                registration.setAddress(node.getAddress());
                registration.setLongitude(node.getLongitude());
                registration.setLatitude(node.getLatitude());
            }
            registration.setActivityDesc(invite.getMessage());
            registration.setStatus(0L);
            registration.setAuditStatus(0L);
            registration.setDelFlag(0);
            registration.setCreateTime(DateUtils.getNowDate());
            cmsRegistrationMerchantMapper.insertCmsRegistrationMerchant(registration);
            registrationId = registration.getId();
        }

        CoopInvite update = new CoopInvite();
        update.setId(invite.getId());
        update.setStatus(1);
        update.setHandleTime(DateUtils.getNowDate());
        if (coopInviteMapper.updateCoopInvite(update) != 1) {
            throw new IllegalStateException("邀约状态更新失败");
        }
        return registrationId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = CoopInviteExpiredException.class)
    public void rejectOfficialInviteWithLock(Long inviteId, Long merchantMemberId, String reason) {
        CoopInvite invite = lockPendingOfficialInvite(inviteId, merchantMemberId);
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isEmpty()) throw new IllegalStateException("请填写拒绝原因");
        if (normalizedReason.length() > 255) throw new IllegalStateException("拒绝原因不能超过255字");
        CoopInvite update = new CoopInvite();
        update.setId(invite.getId());
        update.setStatus(2);
        update.setHandleTime(DateUtils.getNowDate());
        update.setHandleReason(normalizedReason);
        if (coopInviteMapper.updateCoopInvite(update) != 1) {
            throw new IllegalStateException("邀约状态更新失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = CoopInviteExpiredException.class)
    public void rejectPendingInviteWithLock(Long inviteId, Long operatorMemberId, String reason) {
        CoopInvite invite = coopInviteMapper.selectCoopInviteByIdForUpdate(inviteId);
        if (invite == null) throw new IllegalStateException("邀请不存在");
        assertLegacyInviteReadOnly(invite);
        terminalizeIfExpired(invite, DateUtils.getNowDate());
        if (invite.getStatus() == null || invite.getStatus() != 0) {
            throw new IllegalStateException("邀约状态已变化，请刷新");
        }
        if (operatorMemberId == null || !operatorMemberId.equals(resolveInviteeOwner(invite))) {
            throw new IllegalStateException("仅受邀方可拒绝");
        }
        CoopInvite update = new CoopInvite();
        update.setId(invite.getId());
        update.setStatus(2);
        update.setHandleTime(DateUtils.getNowDate());
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty()) throw new IllegalStateException("请填写拒绝原因");
        if (normalized.length() > 255) throw new IllegalStateException("拒绝原因不能超过255字");
        update.setHandleReason(normalized);
        if (coopInviteMapper.updateCoopInvite(update) != 1) {
            throw new IllegalStateException("邀约状态已变化，请刷新");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = CoopInviteExpiredException.class)
    public void cancelPendingInviteWithLock(Long inviteId, Long operatorMemberId, String reason) {
        CoopInvite invite = coopInviteMapper.selectCoopInviteByIdForUpdate(inviteId);
        if (invite == null) throw new IllegalStateException("邀请不存在");
        assertLegacyInviteReadOnly(invite);
        terminalizeIfExpired(invite, DateUtils.getNowDate());
        if (invite.getStatus() == null || invite.getStatus() != 0) {
            throw new IllegalStateException("邀约状态已变化，请刷新");
        }
        if (operatorMemberId == null || !operatorMemberId.equals(resolveInviterOwner(invite))) {
            throw new IllegalStateException("仅发起方可取消待确认邀约");
        }
        CoopInvite update = new CoopInvite();
        update.setId(invite.getId());
        update.setStatus(3);
        update.setHandleTime(DateUtils.getNowDate());
        // CU-C-86:撤回页写明「理由会发给对方」并拦空理由,原来这里不收理由 ⇒ handle_reason 恒 NULL,双方都读不回。
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty()) throw new IllegalStateException("请填写撤回理由");
        if (normalized.length() > 255) throw new IllegalStateException("撤回理由不能超过255字");
        update.setHandleReason(normalized);
        if (coopInviteMapper.updateCoopInvite(update) != 1) {
            throw new IllegalStateException("邀约状态已变化，请刷新");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> listOfficialMerchantInvites(Long merchantMemberId) {
        assertActiveMerchant(merchantMemberId);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (CoopInvite invite : officialInvitesFor(merchantMemberId, null)) {
            rows.add(officialInviteView(invite, merchantMemberId));
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> listAwardedOfficialActivities(Long merchantMemberId) {
        if (merchantMemberId == null || merchantMemberId <= 0) return Collections.emptyList();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (CoopInvite invite : officialInvitesFor(merchantMemberId, 1)) {
            Map<String, Object> row = officialInviteView(invite, merchantMemberId);
            if (Long.valueOf(1L).equals(row.get("auditStatus"))) rows.add(row);
        }
        return rows;
    }

    private List<CoopInvite> officialInvitesFor(Long merchantMemberId, Integer status) {
        CoopInvite query = new CoopInvite();
        query.setInviteType(CoopInvite.TYPE_OFFICIAL_MERCHANT);
        query.setFromType(CoopInvite.FROM_OFFICIAL);
        query.setToType(CoopInvite.TO_MERCHANT);
        query.setToId(merchantMemberId);
        query.setStatus(status);
        List<CoopInvite> invites = selectCoopInviteList(query);
        if (invites == null || invites.isEmpty()) return Collections.emptyList();
        List<CoopInvite> visible = new ArrayList<CoopInvite>();
        for (CoopInvite invite : invites) {
            if (hasActiveOfficialTopic(invite)) visible.add(invite);
        }
        return visible;
    }

    /** 官方邀约历史留库，但玩家/商家消费面不展示已删除主题或已解散父俱乐部。 */
    private boolean hasActiveOfficialTopic(CoopInvite invite) {
        if (invite == null || invite.getTopicId() == null) return false;
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(invite.getTopicId());
        if (topic == null || !Integer.valueOf(0).equals(topic.getDelFlag())) return false;
        return topic.getClubId() == null || topic.getClubId() <= 0
                || clubMapper.selectClubById(topic.getClubId()) != null;
    }

    private Map<String, Object> officialInviteView(CoopInvite invite, Long merchantMemberId) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("inviteId", invite.getId());
        row.put("topicId", invite.getTopicId());
        row.put("nodeId", invite.getNodeId());
        row.put("status", invite.getStatus());
        row.put("message", invite.getMessage());
        row.put("handleReason", invite.getHandleReason());
        row.put("expireTime", invite.getExpireTime());
        row.put("shareMode", invite.getShareMode());
        row.put("shareRate", invite.getShareRate());
        row.put("fixedFee", invite.getFixedFee());
        CmsTopic topic = invite.getTopicId() == null ? null : cmsTopicMapper.selectCmsTopicById(invite.getTopicId());
        row.put("title", topic == null ? null : topic.getName());
        row.put("cover", topic == null ? null : topic.getImgUrl());
        if (topic != null && row.get("expireTime") == null) row.put("expireTime", topic.getRecruitDeadline());

        CmsRegistrationMerchant probe = new CmsRegistrationMerchant();
        probe.setMemberId(merchantMemberId);
        probe.setTopicId(invite.getTopicId());
        probe.setNodeId(invite.getNodeId());
        probe.setDelFlag(0);
        List<CmsRegistrationMerchant> registrations = cmsRegistrationMerchantMapper.selectCmsRegistrationMerchantList(probe);
        if (registrations != null) {
            for (CmsRegistrationMerchant registration : registrations) {
                if (!java.util.Objects.equals(invite.getNodeId(), registration.getNodeId())) continue;
                row.put("registrationId", registration.getId());
                row.put("registrationStatus", registration.getStatus());
                row.put("auditStatus", registration.getAuditStatus());
                break;
            }
        }
        return row;
    }

    private CoopInvite lockPendingOfficialInvite(Long inviteId, Long merchantMemberId) {
        CoopInvite invite = coopInviteMapper.selectCoopInviteByIdForUpdate(inviteId);
        if (invite == null) throw new IllegalStateException("邀请不存在");
        if (invite.getInviteType() == null || invite.getInviteType() != CoopInvite.TYPE_OFFICIAL_MERCHANT
                || !CoopInvite.FROM_OFFICIAL.equals(invite.getFromType())
                || !CoopInvite.TO_MERCHANT.equals(invite.getToType())) {
            throw new IllegalStateException("不是官方商家邀约");
        }
        if (merchantMemberId == null || !merchantMemberId.equals(invite.getToId())) {
            throw new IllegalStateException("仅受邀商家可处理");
        }
        terminalizeIfExpired(invite, DateUtils.getNowDate());
        if (invite.getStatus() == null || invite.getStatus() != 0) {
            throw new IllegalStateException("该邀请已处理");
        }
        assertActiveMerchantForUpdate(merchantMemberId);
        return invite;
    }

    private void assertActiveMerchant(Long memberId) {
        assertActiveMerchantRow(mmsMerchantMapper.selectMmsMerchantByMemberId(memberId));
    }

    private void assertActiveMerchantForUpdate(Long memberId) {
        assertActiveMerchantRow(mmsMerchantMapper.selectMmsMerchantByMemberIdForUpdate(memberId));
    }

    private void assertActiveMerchantRow(MmsMerchant merchant) {
        if (merchant == null || !Long.valueOf(1L).equals(merchant.getStatus())
                || !Long.valueOf(1L).equals(merchant.getAccountStatus())
                || !Integer.valueOf(0).equals(merchant.getDelFlag())) {
            throw new com.chengyinhub.business.service.NotActiveMerchantException("仅审核通过且启用的商家可参与合作邀约");
        }
    }

    private void assertStandardInviteTypeMatrix(CoopInvite invite) {
        if (invite == null || invite.getInviteType() == null) {
            throw new IllegalStateException("邀请类型不合法");
        }
        if (invite.getInviteType() == 0) {
            if (!CoopInvite.TO_MERCHANT.equals(invite.getToType())
                    || !("talent".equals(invite.getFromType()) || "merchant".equals(invite.getFromType()))) {
                throw new IllegalStateException("邀请类型与合作方类型不匹配");
            }
            return;
        }
        if (invite.getInviteType() == 1) {
            if (!"club".equals(invite.getToType())
                    || !("merchant".equals(invite.getFromType()) || "club".equals(invite.getFromType()))) {
                throw new IllegalStateException("邀请类型与合作方类型不匹配");
            }
            return;
        }
        throw new IllegalStateException("邀请类型不合法");
    }

    private void assertActiveStandardInviteMerchantForUpdate(CoopInvite invite) {
        if (invite.getInviteType() == 0) {
            assertActiveMerchantForUpdate(invite.getToId());
        }
        if ("merchant".equals(invite.getFromType())) {
            assertActiveMerchantForUpdate(invite.getFromId());
        }
    }

    /** 开放报名溯源决定 type1 的应缴方,所以校验和落邀约必须同一事务,不能留在 Controller。 */
    private void assertOriginApplyMatches(CoopInvite invite) {
        if (invite.getOriginApplyId() == null) return;
        if (invite.getInviteType() == null || invite.getInviteType() != 1 || !"club".equals(invite.getToType())) {
            throw new IllegalStateException("开放报名溯源仅适用于邀约俱乐部");
        }
        com.chengyinhub.business.domain.CoopClubApply apply = coopClubApplyService.selectById(invite.getOriginApplyId());
        if (apply == null || apply.getStatus() == null || apply.getStatus() != 0
                || invite.getTopicId() == null || invite.getToId() == null
                || !invite.getTopicId().equals(apply.getTopicId()) || !invite.getToId().equals(apply.getClubId())) {
            throw new IllegalStateException("开放报名溯源不匹配");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markTopicDepositsDue(Long topicId) {
        CmsTopic topic = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic != null && Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
            return;
        }
        List<CoopInvite> invites = coopInviteMapper.selectAcceptedByTopicForUpdate(topicId);
        if (invites == null) return;
        for (CoopInvite invite : invites) {
            if (isLegacyMerchantNodeInvite(invite)) continue;
            Integer status = invite.getDepositStatus();
            if (status != null && status != 0) continue;
            if (coopInviteMapper.updateDepositIfCurrent(invite.getId(), status, invite.getDepositAmount(), 5, DEPOSIT_AMOUNT) != 1)
                throw new IllegalStateException("保证金应缴登记失败 inviteId=" + invite.getId());
            try {
                Long payer = depositOwedParty(invite);
                if (payer != null) {
                    notifyDepositDueAfterCommit(invite.getId(), payer, topic == null ? "" : topic.getName());
                }
            } catch (Exception e) {
                logger.warn("锁价保证金提醒准备失败 inviteId={}", invite.getId(), e);
            }
        }
    }

    /** 外部消息只能在锁价事务提交后发送,避免后续出票失败回滚而留下错误待缴提醒。 */
    private void notifyDepositDueAfterCommit(final Long inviteId, final Long payer, final String topicName) {
        Runnable notify = new Runnable() {
            @Override
            public void run() {
                try {
                    notifyService.notifyCoopStatus(payer, topicName, "系统", "待缴保证金",
                            "主题已锁价开卖，请缴纳¥50保证金");
                } catch (Exception e) {
                    logger.warn("锁价保证金提醒发送失败 inviteId={}", inviteId, e);
                }
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notify.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notify.run();
            }
        });
    }

    @Override
    public int updateCoopInvite(CoopInvite coopInvite) {
        if (coopInvite != null) {
            if (isLegacyMerchantNodeInvite(coopInvite)) assertLegacyInviteReadOnly(coopInvite);
            if (coopInvite.getId() != null) {
                assertLegacyInviteReadOnly(coopInviteMapper.selectCoopInviteById(coopInvite.getId()));
            }
        }
        coopInvite.setUpdateTime(DateUtils.getNowDate());
        return coopInviteMapper.updateCoopInvite(coopInvite);
    }

    @Override
    public int expireStaleInvites() {
        // 阻断#8 / 台账 1-11:只过期挂起邀约;已接受邀约不再按 config_deadline 过期(主题级没有配置步骤)
        return coopInviteMapper.expirePendingInvites(DateUtils.getNowDate());
    }

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = CoopInviteExpiredException.class)
    public CoopInvite acceptInviteWithLock(Long inviteId, Long nodeId) {
        CoopInvite location = coopInviteMapper.selectCoopInviteById(inviteId);
        if (location == null) throw new IllegalStateException("邀请不存在");
        CmsTopic pricedTopic = location.getTopicId() == null ? null
                : cmsTopicMapper.selectCmsTopicByIdForUpdate(location.getTopicId());
        CoopInvite inv = coopInviteMapper.selectCoopInviteByIdForUpdate(inviteId);
        if (inv == null || !java.util.Objects.equals(location.getTopicId(), inv.getTopicId()))
            throw new IllegalStateException("邀约归属已变化");
        assertLegacyInviteReadOnly(inv);
        assertStandardInviteTypeMatrix(inv);
        Date now = DateUtils.getNowDate();
        terminalizeIfExpired(inv, now);
        if (inv.getStatus() == null || inv.getStatus() != 0) throw new IllegalStateException("该邀请已处理");
        // 接受是条款快照真正冻结的最后一道入口，不能只依赖发起 Controller 的预检。
        // 旧邀约允许三项条款全空(兼容历史数据)，但只要声明了模式，就必须与配套字段一致。
        assertTermsConsistent(inv, "本次");

        // [资损·冻结阵容] 主题一旦锁价/售票(lifecycle≥3)禁止再接受合作邀请入伙:
        // 否则新增分成商家会使结算时 Σ分成/保底档超过定价假设 → margin 翻负 → 守恒断言拒结 → 款项悬空转人工
        //(与地板漏保底率同源)。定价按当时阵容取最坏值,只有冻结阵容后地板才真保证结算守恒。
        if (inv.getTopicId() != null) {
            // 行锁读:与 confirmPrice 的 selectCmsTopicByIdForUpdate 串行,堵住"锁价快照 vs 结算阵容"TOCTOU 悬空窗。
            if (pricedTopic != null) {
                assertInviteWindowOpen(pricedTopic, inv, now);
            }
            if (pricedTopic != null && TopicLifecycleState.commercialTermsFrozen(pricedTopic.getLifecycle())) {
                throw new IllegalStateException("主题已锁价,不能再接受合作邀请");
            }
        }
        lockActiveClubsForInviteWrite(inv, pricedTopic, "接受合作邀请");

        boolean gameLevel = inv.getGameId() != null;
        Long capacityPublisherId = pricedTopic != null && pricedTopic.getMemberId() != null
                ? pricedTopic.getMemberId() : inv.getFromId();

        // 容量复核(锁内):经典定向每游戏已接受=1;自由定向每主题已接受≤5
        if (gameLevel) {
            CoopInvite q = new CoopInvite();
            q.setGameId(inv.getGameId());
            q.setStatus(1);
            if (!mutableInvites(coopInviteMapper.selectCoopInviteList(q)).isEmpty())
                throw new IllegalStateException("该游戏已有合作方,请先取消再邀");
        } else if (inv.getTopicId() != null) {
            CoopInvite q = new CoopInvite();
            q.setTopicId(inv.getTopicId());
            q.setStatus(1);
            java.util.List<CoopInvite> accepted = mutableInvites(coopInviteMapper.selectCoopInviteList(q));
            int cap = dynamicTopicSlotCap(capacityPublisherId); // B4-3:按发布者履约率浮动(8/5/2)
            if (accepted.size() >= cap)
                throw new IllegalStateException("该主题合作方已满(" + cap + "),不能再接受");
            // ★一主题只能有一个俱乐部(2026-08-05 用户拍板)。
            //
            // 不是配额问题,是分账基数不可分割:结算里俱乐部走 LEAD_FEE,基数写死成
            // 「全主题核销数 / 全主题销售额」(CmsTopicServiceImpl:isClub 分支,注释写明
            // 「带的是整趟,换成扫码数会恒得 0」)。所以两个俱乐部各 20% 不是分掉 20%,
            // 而是各自按整份销售额拿 20% —— 同一批玩家被算了两次带队费。
            //
            // 既有的 90% 总和封顶挡不住这个:两家各 20% 合计 40%,完全合法地通过。
            // 那道闸防的是「总额超编」,防不了「重复归因」。
            //
            // 与经典定向 game 级「已有合作方就拒」同构 —— 那边一个游戏只能有一个合作方,
            // 这边一个主题只能有一个组织方。
            if ("club".equals(inv.getToType())) {
                for (CoopInvite a : accepted) {
                    if ("club".equals(a.getToType())) {
                        throw new IllegalStateException("该主题已有合作俱乐部,一个主题只能有一个组织方;请先取消原俱乐部再邀");
                    }
                }
            }
            // D-配额:被邀商家(非俱乐部)合计封顶 5(与发起人保底 +2n% 封顶5家对齐;不分商家/俱乐部邀)
            if (!"club".equals(inv.getToType())) {
                long merchants = 0;
                for (CoopInvite a : accepted) if (!"club".equals(a.getToType())) merchants++;
                if (merchants >= 5)
                    throw new IllegalStateException("该主题被邀商家已达上限(5),不能再接受");
            }
            // P1-COOP-01:分成型(share_mode=1)已接受邀约的 share_rate 总和,在本次接受事务内不得超过 90%。
            // Controller 建约时只按「当时已接受」预检,拦不住多个挂起邀约被并发接受时各自都在 90% 以内
            // 却合计超编;真正的封顶必须落在这一步,不能只信 Controller 的预检。
            //
            // ★并发合同(别打乱顺序):这一步能封顶,前提是上面「主题已锁价」那段对 topic 的
            // selectCmsTopicByIdForUpdate(FOR UPDATE 行锁)已经先于这里第一次 selectCoopInviteList
            // 执行完毕——行锁把并发的 accept 串行化,才能保证下面读到的 accepted 是"当前真实已接受",
            // 而不是别的事务尚未提交前的旧快照。谁在这两步之间插入一次不经过行锁的普通读,就等于让
            // MySQL RR 快照语义悄悄失效。见测试 accept_topicLevel_locksTopicBeforeReadingAcceptedList
            // (记录真实调用序列,断言行锁必须是第一次库读)——改动这段代码前先确认那条测试还在且仍然通过。
            //
            // fail-closed:分成型条款的 share_rate 若缺失/负数/超过 90,单条本身就是脏数据,必须直接拒绝——
            // 不能沿用旧逻辑「share_rate==null 时跳过整条校验」,那会把当前邀约或历史 accepted 里的脏数据
            // 当 0 计入,低估真实总和,让 90% 封顶形同虚设。
            //
            // ★历史 accepted 行只查「影响这次求和」的两条,不做完整的模式—字段形状校验:
            //   ① mode==1 必须有合法 rate(要计入求和,脏值会低估总和);
            //   ② rate 存在但 mode!=1 也拒(否则这个 rate 被静默排除在求和之外,同样低估)。
            // 反过来,mode/fixed_fee 的形状问题(如 mode==2 缺 fixed_fee)不影响分成总和,结算侧也只在
            // mode==1 读 shareRate、mode==2 读 fixedFee(PricingServiceImpl / CmsTopicServiceImpl),
            // 惰性字段是无害数据。若在这里连形状一起拦,一条历史脏行就会把该主题**所有**后续 accept
            // 永久锁死且零告警——那不是资损防线,是可用性事故。完整形状校验只对「本次要冻结的这条」做。
            BigDecimal sum = BigDecimal.ZERO;
            for (CoopInvite a : accepted) {
                if (a.getShareMode() != null && a.getShareMode() == 1) {
                    sum = sum.add(assertValidShareRate(a.getShareRate(), "历史已接受"));
                } else if (a.getShareRate() != null) {
                    throw new IllegalStateException("历史已接受分成比例与报酬模式不一致,请先修正该合作条款");
                }
            }
            if (inv.getShareMode() != null && inv.getShareMode() == 1) {
                sum = sum.add(assertValidShareRate(inv.getShareRate(), "本次"));
                if (sum.compareTo(new BigDecimal("90")) > 0)
                    throw new IllegalStateException("分成比例之和超过 90%,请调整");
            }
        }

        // Controller 门禁只是快速拒绝；生成合作单前必须在同一事务里重读商家状态。
        // 否则「预检后被停用」或绕过 Controller 的调用仍会进入结算阵容。
        assertActiveStandardInviteMerchantForUpdate(inv);

        // 接受
        CoopInvite upd = new CoopInvite();
        upd.setId(inv.getId());
        upd.setStatus(1);
        upd.setHandleTime(now);
        // 阻断#8 / 台账 1-11:主题级邀约报名时已选章节,没有「配置游戏」步骤,接受时不写 config_deadline
        coopInviteMapper.updateCoopInvite(upd);

        // 生成合作单:条款快照冻结,后续结算只认合作单
        CoopOrder order = new CoopOrder();
        order.setInviteId(inv.getId());
        order.setTopicId(inv.getTopicId());
        order.setGameId(inv.getGameId());
        order.setFromId(inv.getFromId());
        order.setToId(inv.getToId());
        order.setToType(inv.getToType());
        order.setShareMode(inv.getShareMode());
        order.setShareRate(inv.getShareRate());
        order.setFixedFee(inv.getFixedFee());
        freezeOrderContractSnapshot(order, inv);
        order.setTermsVersion("v1");
        order.setStatus(0);
        order.setDelFlag(0);
        order.setCreateTime(now);
        // [P0-26] 必须走 createCoopOrder 而不是裸 insert —— 权益快照在那里冻结。
        // 这里原本是 coopOrderMapper.insertCoopOrder(order),而**这条才是主路径**
        // (商家在小程序点「接受邀约」);裸 insert 绕过冻结 ⇒ perks_snapshot 恒 NULL。
        createCoopOrder(order);

        // 顶替同槽其他挂起候选 → status=4
        if (gameLevel) {
            CoopInvite pendQ = new CoopInvite();
            pendQ.setGameId(inv.getGameId());
            pendQ.setStatus(0);
            supersede(coopInviteMapper.selectCoopInviteList(pendQ), inv.getId(), now);
        } else if (inv.getTopicId() != null) {
            // 自由定向:最后一个坑占满的瞬间才清所有剩余挂起(cap 同 B4-3 动态)
            CoopInvite accQ = new CoopInvite();
            accQ.setTopicId(inv.getTopicId());
            accQ.setStatus(1);
            if (mutableInvites(coopInviteMapper.selectCoopInviteList(accQ)).size() >= dynamicTopicSlotCap(capacityPublisherId)) {
                CoopInvite pendQ = new CoopInvite();
                pendQ.setTopicId(inv.getTopicId());
                pendQ.setStatus(0);
                supersede(coopInviteMapper.selectCoopInviteList(pendQ), inv.getId(), now);
            }
        }

        inv.setStatus(1);
        if (pricedTopic != null) {
            inv.setSaleReviewProgress(commercialReviewService.submitSaleReviewIfReady(pricedTopic.getId(), pricedTopic.getMemberId()));
        }
        return inv;
    }

    /** F04 商家→俱乐部邀约与合作池/申请同一招商期口径;其余邀约类型仍只认招商写闸。 */
    private void assertInviteWindowOpen(CmsTopic topic, CoopInvite invite, Date now) {
        if (TopicLifecycleState.commercialTermsFrozen(topic.getLifecycle())) {
            throw new IllegalStateException("主题已锁价,不能再发起或接受合作邀请");
        }
        if ("club".equals(invite.getToType())) {
            clubCoopWindowPolicy.assertOpen(topic, invite, now);
        } else {
            RecruitmentWindowPolicy.assertOpen(topic.getLifecycle(), topic.getStatus(), topic.getUserStatus(), topic.getRecruitDeadline(), now);
        }
    }

    /**
     * 与解散事务共用 club 行锁：承接俱乐部及本团主题发出的合作都不能在解散计数后再落合作单。
     */
    private CmsTopic lockInviteTopic(CoopInvite invite) {
        if (invite == null || invite.getTopicId() == null) return null;
        CmsTopic topic = cmsTopicMapper.selectCmsTopicByIdForUpdate(invite.getTopicId());
        if (topic == null || !Integer.valueOf(0).equals(topic.getDelFlag())) {
            throw new IllegalStateException("关联主题不存在");
        }
        return topic;
    }

    private void lockActiveClubsForInviteWrite(CoopInvite invite, CmsTopic topic, String action) {
        List<Long> clubIds = new ArrayList<Long>();
        if (invite != null && "club".equals(invite.getFromType()) && invite.getFromId() != null) {
            clubIds.add(invite.getFromId());
        }
        if (invite != null && "club".equals(invite.getToType()) && invite.getToId() != null) {
            if (!clubIds.contains(invite.getToId())) clubIds.add(invite.getToId());
        }
        if (topic != null && topic.getClubId() != null && topic.getClubId() > 0
                && !clubIds.contains(topic.getClubId())) {
            clubIds.add(topic.getClubId());
        }
        Collections.sort(clubIds);
        for (Long clubId : clubIds) {
            if (clubMapper.selectClubByIdForUpdate(clubId) == null) {
                throw new IllegalStateException("关联俱乐部已解散，不能" + action);
            }
        }
    }

    /**
     * CU-M-48 ★一主题只能有一个组织方(俱乐部)。与 acceptInviteWithLock 里那道闸同源同语义:
     * 同一 mapper 查询口径(coopInviteMapper.selectCoopInviteList,同主题 + status=1)、同样的历史
     * 节点邀约排除(mutableInvites)、同一句文案。理由也是同一条 —— 结算里俱乐部按整趟基数拿 LEAD_FEE
     * (见 CmsTopicServiceImpl 的 isClub 分支),两个俱乐部同时成立等于同一批玩家被算两次带队费。
     * ⚠️ 只拦「本次目标就是 club」:商家侧挂起与槽位/90% 封顶仍归接受侧判,不在这里误伤。
     */
    private void assertNoClubCoopOnTopic(CoopInvite invite) {
        if (invite == null || invite.getTopicId() == null || !"club".equals(invite.getToType())) return;
        CoopInvite query = new CoopInvite();
        query.setTopicId(invite.getTopicId());
        query.setStatus(1);
        for (CoopInvite accepted : mutableInvites(coopInviteMapper.selectCoopInviteList(query))) {
            if ("club".equals(accepted.getToType())) {
                throw new IllegalStateException("该主题已有合作俱乐部,一个主题只能有一个组织方;请先取消原俱乐部再邀");
            }
        }
    }

    /**
     * CU-M-96 ★同一主题对同一对方不允许并存多条待确认邀约(总控 2026-09-25 裁决「禁止重复」)。
     * 判据是 topicId + toType + toId + nodeId 四项相同且 status=0;对方接受/拒绝/撤回后(status≠0)可以再发。
     *
     * ⚠️ 读的是本服务的 selectCoopInviteList 而不是 mapper:后者查询前会 expirePendingInvites
     * 把超时行幂等写成终态 5。直连 mapper 的话,一条 TTL 已过但还没被兜底任务终态化的 status=0
     * 行会永久挡住重发,而页面上那条邀约早已不可接受。
     * mutableInvites 沿用同文件的既有口径:历史节点邀约(invite_type=2)是只读遗留,不参与判重。
     *
     * <p>nodeId 在 Java 侧比而不是塞进查询条件:{@code CoopInviteMapper.xml} 的动态 {@code <where>}
     * 只在非空时追加 {@code and node_id = #{nodeId}},传 null 等于「不限节点」—— 那样「同主题同商家、
     * 但接的是另外一站」也会被当成重复挡掉(官方邀约正是按节点发的,见 createOfficialMerchantInvites
     * 的 request.getNodeId())。所以这里查回该对象该主题的全部待确认行,再按 Objects.equals 配节点:
     * 两边都为空(主题级邀约)算同一条,一空一非空不算重复。
     */
    private void assertNoDuplicatePendingInvite(CoopInvite invite) {
        if (invite == null || invite.getTopicId() == null
                || invite.getToType() == null || invite.getToId() == null) return;
        CoopInvite pending = new CoopInvite();
        pending.setTopicId(invite.getTopicId());
        pending.setToType(invite.getToType());
        pending.setToId(invite.getToId());
        pending.setStatus(0);
        for (CoopInvite existing : mutableInvites(selectCoopInviteList(pending))) {
            if (Integer.valueOf(0).equals(existing.getStatus())
                    && java.util.Objects.equals(existing.getNodeId(), invite.getNodeId())) {
                throw new ServiceException("你已向对方发过这个主题的邀约,等对方处理后再发", HttpStatus.BAD_REQUEST);
            }
        }
    }

    private Date pendingInviteExpiry(Date createTime, Date earlierDeadline) {
        Date ttlDeadline = new Date(createTime.getTime() + PENDING_INVITE_TTL_MILLIS);
        return earlierDeadline != null && earlierDeadline.before(ttlDeadline) ? earlierDeadline : ttlDeadline;
    }

    private boolean isPendingInviteExpired(CoopInvite invite, Date now) {
        Date deadline = invite.getExpireTime();
        if (invite.getCreateTime() != null) {
            Date ttlDeadline = new Date(invite.getCreateTime().getTime() + PENDING_INVITE_TTL_MILLIS);
            if (deadline == null || ttlDeadline.before(deadline)) deadline = ttlDeadline;
        }
        return deadline != null && !deadline.after(now);
    }

    private void terminalizeIfExpired(CoopInvite invite, Date now) {
        if (invite.getStatus() == null || invite.getStatus() != 0 || !isPendingInviteExpired(invite, now)) return;
        CoopInvite expired = new CoopInvite();
        expired.setId(invite.getId());
        expired.setStatus(5);
        expired.setUpdateTime(now);
        if (coopInviteMapper.updateCoopInvite(expired) != 1) {
            throw new IllegalStateException("邀约过期状态更新失败");
        }
        throw new CoopInviteExpiredException("该邀请已过期");
    }

    /**
     * P1-COOP-R2 fail-closed:分成型(share_mode=1)条款的 share_rate 必须落在 [0,90] 区间内,
     * 缺失/负数/超过 90 一律视为脏数据直接拒绝——不当 0 计入求和,否则会低估分成总和,让 90% 封顶
     * 形同虚设(who 仅用于错误文案区分是"本次"邀约还是"历史已接受"里的脏数据)。
     */
    private BigDecimal assertValidShareRate(BigDecimal rate, String who) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(new BigDecimal("90")) > 0) {
            throw new IllegalStateException(who + "分成比例缺失或不合法(须在 0~90 之间),请调整");
        }
        return rate;
    }

    /**
     * 接受前校验合作模式与条款字段的一致性。share_mode 为空且两个金额也为空时保留历史邀约兼容；
     * 一旦出现任一条款字段，就不能让结算按另一种模式静默解释。
     * ★只对「本次要接受、条款就此冻结」的那条邀约做。历史 accepted 行不走这里(见上面求和处的说明):
     * 对已经落地的旧行做形状校验挡不住任何资损,却会让一条脏行锁死该主题所有后续 accept。
     */
    private void assertTermsConsistent(CoopInvite invite, String who) {
        Integer mode = invite.getShareMode();
        BigDecimal rate = invite.getShareRate();
        BigDecimal fixedFee = invite.getFixedFee();
        if (mode == null) {
            if (rate != null || fixedFee != null)
                throw new IllegalStateException(who + "报酬模式缺失但携带了金额条款,请调整");
            return;
        }
        if (mode < 0 || mode > 2)
            throw new IllegalStateException(who + "报酬模式不合法");
        if (mode == 0) {
            if (rate != null || fixedFee != null)
                throw new IllegalStateException(who + "报酬模式为引流时不能携带分成或固定费用");
        } else if (mode == 1) {
            assertValidShareRate(rate, who);
            if (fixedFee != null)
                throw new IllegalStateException(who + "报酬模式为分成时不能携带固定费用");
        } else {
            if (fixedFee == null || fixedFee.compareTo(BigDecimal.ZERO) <= 0)
                throw new IllegalStateException(who + "报酬模式为固定时需填写正数固定费用");
            if (rate != null)
                throw new IllegalStateException(who + "报酬模式为固定时不能携带分成比例");
        }
    }

    /** 把挂起候选批量转「已顶替(4)」,排除刚同意的那条 */
    private void supersede(List<CoopInvite> pendings, Long acceptedId, Date now) {
        if (pendings == null) return;
        for (CoopInvite p : pendings) {
            if (p.getId().equals(acceptedId)) continue;
            CoopInvite s = new CoopInvite();
            s.setId(p.getId());
            s.setStatus(4);
            s.setHandleTime(now);
            coopInviteMapper.updateCoopInvite(s);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelAcceptedInvite(Long inviteId, Long operatorMemberId, String reason, int creditEvent) {
        CoopInvite location = coopInviteMapper.selectCoopInviteById(inviteId);
        CmsTopic lockedTopic = location == null || location.getTopicId() == null ? null
                : cmsTopicMapper.selectCmsTopicByIdForUpdate(location.getTopicId());
        CoopInvite inv = coopInviteMapper.selectCoopInviteByIdForUpdate(inviteId);
        if (location == null || inv == null || !java.util.Objects.equals(location.getTopicId(), inv.getTopicId()))
            throw new IllegalStateException("合作归属已变化");
        assertLegacyInviteReadOnly(inv);
        if (inv == null || inv.getStatus() == null || inv.getStatus() != 1)
            throw new IllegalStateException("合作状态已变化,请刷新");
        Long fromOwner = resolveInviterOwner(inv);
        Long toOwner = resolveInviteeOwner(inv);
        if (operatorMemberId == null
                || (!operatorMemberId.equals(fromOwner) && !operatorMemberId.equals(toOwner))) {
            throw new IllegalStateException("仅合作双方可取消");
        }
        // 旧 JWT 可能在商家停用后继续存活。取消已接受合作会作废合作单并触发退款，
        // 因此必须在同一事务内锁行复核“本次操作方”对应的商家主体；另一方是俱乐部时不受影响。
        if (CoopInvite.TO_MERCHANT.equals(inv.getToType()) && operatorMemberId.equals(toOwner)) {
            assertActiveMerchantForUpdate(inv.getToId());
        }
        if ("merchant".equals(inv.getFromType()) && operatorMemberId.equals(fromOwner)) {
            assertActiveMerchantForUpdate(inv.getFromId());
        }
        if (inv.getTopicId() != null) {
            CmsTopic topic = lockedTopic;
            if (topic != null && TopicLifecycleState.commercialTermsFrozen(topic.getLifecycle())) {
                throw new IllegalStateException("阵容已锁定,联系客服协商");
            }
        }
        Date now = DateUtils.getNowDate();

        CoopInvite upd = new CoopInvite();
        upd.setId(inv.getId());
        upd.setStatus(3);
        upd.setHandleTime(now);
        coopInviteMapper.updateCoopInvite(upd);

        CoopCreditLog log = new CoopCreditLog();
        log.setMemberId(operatorMemberId);
        log.setEventType(creditEvent);
        log.setInviteId(inv.getId());
        log.setReason(reason);
        log.setCreateTime(now);
        coopCreditLogMapper.insertCoopCreditLog(log);

        // ④无损退:作废该邀约对应生效 coop_order,结算不再计(售票后掉链子的票款走活动退款链无损退玩家)
        coopOrderMapper.voidOrderByInviteId(inv.getId());
        invalidateApprovedCanonical(inv.getTopicId(), "coop-cancel-accepted");
        if (Integer.valueOf(2).equals(inv.getDepositStatus()) || Integer.valueOf(4).equals(inv.getDepositStatus()))
            depositRefundService.requestLocked(inv);

    }

    @Override
    public Map<String, Object> creditSummary(Long memberId) {
        Date since = new Date(DateUtils.getNowDate().getTime() - 90L * 24 * 3600 * 1000);
        List<CoopCreditLog> logs = coopCreditLogMapper.selectRecentByMember(memberId, since);
        int fulfil = 0, timeout = 0, cancelOutside = 0, violateInside = 0;
        for (CoopCreditLog l : logs) {
            if (l.getEventType() == null) continue;
            switch (l.getEventType()) {
                case 0: fulfil++; break;
                case 1: timeout++; break;
                case 2: cancelOutside++; break;
                case 3: violateInside++; break;
                default: break;
            }
        }
        int violationCount = timeout + cancelOutside + violateInside;
        int total = fulfil + violationCount;
        int rate = total == 0 ? 100 : (int) Math.round(fulfil * 100.0 / total);
        Map<String, Object> m = new HashMap<>();
        m.put("days", 90);
        m.put("fulfil", fulfil);
        m.put("timeout", timeout);
        m.put("cancelOutside", cancelOutside);
        m.put("violateInside", violateInside);
        m.put("violationCount", violationCount);
        m.put("fulfillmentRate", rate);
        return m;
    }

    @Override
    public List<CoopSettlement> mySettlements(Long merchantId) {
        // mybiz=合作分润工作台:只展示承接方/商家应收(LEAD_FEE/MERCHANT_VERIFY);发起人保底/毛利(INITIATOR)
        // 属发起人自身主题收入(走 clubFinance/finance),不进商家工作台。旧数据 payee_type=null(=商家)照常展示;平台行 merchant_id=null 本就不会命中。
        List<CoopSettlement> filtered = new ArrayList<>();
        for (CoopSettlement s : coopSettlementMapper.selectByMerchant(merchantId)) {
            String pt = s.getPayeeType();
            if (pt == null || "merchant".equals(pt) || "club".equals(pt)) filtered.add(s);
        }
        return filtered;
    }

    /**
     * T7:俱乐部/发布者财务视图——按主题聚合「我的分润 / 商家应收 / 打款状态 / 到账时间」。
     * 发布者分润 = 总销售额 − 平台佣金 − 商家应收(结算后即时入账);商家应收挂 T+7。
     */
    @Override
    public List<Map<String, Object>> clubFinance(Long memberId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (memberId == null) return rows;
        for (Long topicId : coopOrderMapper.selectTopicIdsByFromId(memberId)) {
            if (topicId == null) continue;
            CmsTopic t = cmsTopicMapper.selectCmsTopicById(topicId);
            if (t == null) continue;
            // 归属校验:只列本人发布的主题
            if (t.getMemberId() == null || !t.getMemberId().equals(memberId)) continue;

            List<com.chengyinhub.business.domain.vo.ClubSettlementSummaryRow> settles = coopSettlementMapper.selectSummaryByTopicId(topicId);
            // 分项感知:平台行不计入任何一方;INITIATOR(保底+毛利)=我(发起人)收入;LEAD_FEE/MERCHANT_VERIFY=承接方/商家应收。
            BigDecimal merchantTotal = BigDecimal.ZERO, merchantPaid = BigDecimal.ZERO;
            BigDecimal myAccrued = BigDecimal.ZERO;
            boolean sawInitiatorLine = false;
            boolean myAllPaid = true;
            Date merchantPayoutTime = null;   // 承接方/商家最近到账时间
            Date merchantPayableTime = null;  // 承接方/商家 T+7 预告到账时间
            for (com.chengyinhub.business.domain.vo.ClubSettlementSummaryRow s : settles) {
                // 1-03 F17:与俱乐部汇总(ApiClubSettlementController.summary)同口径 ——
                // 作废行不计;金额 = 原额 + 已执行调整;已打款金额优先取实付流水。
                String pt = s.getPayeeType();
                if ("platform".equals(pt)) continue; // 平台佣金不进任何一方视图
                boolean initiator = "initiator".equals(pt);
                if (initiator) sawInitiatorLine = true;   // 作废的发起人行也说明是新模型,别回落旧公式
                if (s.getStatus() != null && s.getStatus() == 2) continue;
                BigDecimal amt = s.getAmount() == null ? BigDecimal.ZERO : s.getAmount();
                if (s.getExecutedAdjustmentAmount() == null) {
                    // 调整行金额缺失 = 脏数据,净额算不出;按原额展示并留痕,别静默当 0 调整
                    logger.error("[F17] 结算行存在金额缺失的已执行调整,净额未核验 settlementId={}", s.getId());
                } else {
                    amt = amt.add(s.getExecutedAdjustmentAmount());
                }
                // status=1 只在 addBalance 成功后置,是到账凭据;存量行可能没有可匹配的新格式流水,此时按净额计
                boolean paid = s.getStatus() != null && s.getStatus() == 1;
                BigDecimal paidAmt = !paid ? null : s.getActualPaidAmount() != null ? s.getActualPaidAmount() : amt;
                if (initiator) {
                    myAccrued = myAccrued.add(amt);
                    if (!paid) myAllPaid = false;
                    continue;
                }
                merchantTotal = merchantTotal.add(amt);
                if (merchantPayableTime == null && s.getPayableTime() != null) merchantPayableTime = s.getPayableTime();
                if (paidAmt != null) {
                    merchantPaid = merchantPaid.add(paidAmt);
                    if (s.getPayoutTime() != null && (merchantPayoutTime == null || s.getPayoutTime().after(merchantPayoutTime)))
                        merchantPayoutTime = s.getPayoutTime();
                }
            }

            Integer lc = t.getLifecycle();
            boolean settled = lc != null && lc >= 5; // 5结算 6关闭
            boolean exploreDay = Integer.valueOf(ProductType.FREE_EXPLORE).equals(t.getProductType());
            BigDecimal totalSales = nz(exploreDay
                    ? cmsRegistrationMapper.selectTotalSalesEvidenceByTopicIdIncludingActivityShell(topicId)
                    : cmsRegistrationMapper.selectTotalSalesByTopicId(topicId));
            BigDecimal verifiedSales = nz(exploreDay
                    ? cmsRegistrationMapper.selectVerifiedSalesEvidenceByTopicIdIncludingActivityShell(topicId)
                    : cmsRegistrationMapper.selectVerifiedSalesByTopicId(topicId));
            // [P0-28 批1] 用该主题锁价时冻的快照,不是 live 值 —— 这是主理人看到的财务数字,
            // 用 live 值会让页面显示的钱与结算实际算的钱对不上(结算读的是快照)。
            // 原来这行取的是循环外的一次 platformRate(),对所有主题一视同仁,那本身也是错的。
            BigDecimal platformAmount = totalSales.multiply(pricingService.platformRateOf(t))
                    .setScale(2, RoundingMode.HALF_UP);
            // 我(发起人)分润:新分项模型=INITIATOR 行之和;旧模型(无 INITIATOR 行、即时到账)=总销售额−平台−承接商家应收。
            BigDecimal myIncome = null;
            if (settled) {
                myIncome = sawInitiatorLine ? myAccrued : totalSales.subtract(platformAmount).subtract(merchantTotal);
                if (myIncome.compareTo(BigDecimal.ZERO) < 0) myIncome = BigDecimal.ZERO;
            }
            // 我的分润是否已到账:新模型看 INITIATOR 行是否打款;旧模型结算即即时到账。
            // 到账按行状态判(全部非作废发起人行都已打款),不拿实付额比净额 —— 打款后才执行的调整会让两者永远对不上
            boolean myIncomeArrived = sawInitiatorLine
                    ? (myAccrued.compareTo(BigDecimal.ZERO) > 0 && myAllPaid)
                    : settled;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("topicId", topicId);
            row.put("topicName", t.getName());
            row.put("lifecycle", lc);
            row.put("settled", settled);
            row.put("totalSales", totalSales);
            row.put("verifiedSales", verifiedSales);
            row.put("platformAmount", platformAmount);
            row.put("merchantTotal", merchantTotal);
            row.put("merchantPaid", merchantPaid);
            row.put("myIncome", myIncome);                 // 结算前为 null(待结算)
            row.put("myIncomeArrived", myIncomeArrived);   // 新模型:INITIATOR 行 T+7 到账;旧模型:结算即到账
            row.put("merchantPayableTime", merchantPayableTime == null ? null : DateUtils.parseDateToStr("yyyy-MM-dd", merchantPayableTime));
            row.put("merchantPayoutTime", merchantPayoutTime == null ? null : DateUtils.parseDateToStr("yyyy-MM-dd", merchantPayoutTime));
            rows.add(row);
        }
        return rows;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    @Override
    public List<CoopPerkTemplate> listPerkTemplates(Long merchantId) {
        return coopPerkTemplateMapper.selectByMerchant(merchantId);
    }

    @Override
    public Long savePerkTemplate(CoopPerkTemplate t) {
        t.setDelFlag(0);
        t.setCreateTime(DateUtils.getNowDate());
        coopPerkTemplateMapper.insertCoopPerkTemplate(t);
        return t.getId();
    }

    @Override
    public int deletePerkTemplate(Long id, Long merchantId) {
        return coopPerkTemplateMapper.softDelete(id, merchantId);
    }

    /**
     * 开放招商不是邀约：这里把商家常备模板复制为独立权益快照，由 merchant_chapter_offer 引用。
     * 不能让前端直接拿 templateId 填 perkId——两张表的主键空间独立，既会校验失败，也会误指向别人的权益。
     */
    @Override
    public Long materializeStandalonePerkFromTemplate(Long templateId, Long merchantId, Integer quota) {
        if (templateId == null || merchantId == null) {
            throw new IllegalStateException("请选择要供给的权益");
        }
        CoopPerkTemplate t = coopPerkTemplateMapper.selectById(templateId);
        if (t == null) {
            throw new IllegalStateException("权益模板不存在或已删除");
        }
        if (!merchantId.equals(t.getMerchantId())) {
            throw new IllegalStateException("只能使用自己的权益模板");
        }
        ensureUsablePerkTemplate(t);
        CoopPerk p = new CoopPerk();
        p.setMerchantId(merchantId);
        p.setPerkType(t.getPerkType());
        p.setName(t.getName());
        p.setUnitCost(t.getUnitCost());
        p.setRetailValue(t.getRetailValue());
        p.setQuota(quota);
        p.setRedeemRule(t.getRedeemRule());
        p.setValidEnd(t.getValidEnd());
        p.setDelFlag(0);
        p.setCreateTime(DateUtils.getNowDate());
        coopPerkMapper.insertCoopPerk(p);
        return p.getId();
    }

    @Override
    public void saveReview(CoopReview r) {
        // 资格校验:评价人与被评人必须是该主题上已接受(status=1)的合作双方,防刷分/虚假评价。
        // 俱乐部主理人以 club_id 参与匹配(俱乐部邀约 to_id=club_id,非主理人 member,否则俱乐部方回评必被拦)。
        if (!isCoopParties(r.getTopicId(), r.getFromId(), r.getToId())) {
            throw new IllegalStateException("仅合作双方可互评");
        }
        if (coopReviewMapper.countExisting(r.getTopicId(), r.getFromId(), r.getToId()) > 0) {
            throw new IllegalStateException("已评价过该合作");
        }
        r.setDelFlag(0);
        r.setCreateTime(DateUtils.getNowDate());
        coopReviewMapper.insertCoopReview(r);
    }

    @Override
    public Map<String, Object> reviewSummary(Long toId) {
        return coopReviewMapper.summaryByToId(toId);
    }

    @Override
    public List<CoopReview> listReviews(Long toId) {
        return coopReviewMapper.selectByToId(toId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int attachPerks(Long inviteId, Long callerMemberId, List<Long> templateIds) {
        CoopInvite inv = coopInviteMapper.selectCoopInviteById(inviteId);
        if (inv == null) throw new IllegalStateException("邀约不存在");
        assertLegacyInviteReadOnly(inv);
        // 受邀方才可申报供给(商家承接 to_id=member;俱乐部 to_id=club_id 需解析为主理人)
        if (!callerMemberId.equals(resolveInviteeOwner(inv))) throw new IllegalStateException("仅受邀方可申报供给");
        List<CoopPerkTemplate> templates = new ArrayList<>();
        if (templateIds != null) {
            for (Long tid : templateIds) {
                CoopPerkTemplate t = coopPerkTemplateMapper.selectById(tid);
                if (t == null || !callerMemberId.equals(t.getMerchantId())) continue; // 只用自己的模板
                ensureUsablePerkTemplate(t);
                templates.add(t);
            }
        }
        coopPerkMapper.deleteByInviteId(inviteId); // 全部模板通过校验后再清旧，避免失败时丢失原申报
        int n = 0;
        for (CoopPerkTemplate t : templates) {
            CoopPerk p = new CoopPerk();
            p.setInviteId(inviteId);
            p.setPerkType(t.getPerkType());
            p.setName(t.getName());
            p.setUnitCost(t.getUnitCost());
            p.setRetailValue(t.getRetailValue());
            p.setQuota(t.getQuota());
            p.setRedeemRule(t.getRedeemRule());
            p.setValidEnd(t.getValidEnd());
            p.setDelFlag(0);
            p.setCreateTime(DateUtils.getNowDate());
            coopPerkMapper.insertCoopPerk(p);
            n++;
        }
        invalidateApprovedCanonical(inv.getTopicId(), "coop-perks-attach");
        return n;
    }

    private void ensureUsablePerkTemplate(CoopPerkTemplate template) {
        if (template.getRetailValue() == null
                || template.getRetailValue().compareTo(BigDecimal.ZERO) <= 0
                || template.getQuota() == null
                || template.getQuota() <= 0) {
            throw new IllegalStateException("权益模板缺少零售价或可接待份数，请删除后重新创建");
        }
        // CU-M-79:有效期闸就放在这个共享入口 — 之前只有开放招商那一条路判日期,批量申报
        // (attachPerks)漏掉 ⇒ 过期模板会被直接物化成可承接核销的 coop_perk。
        // 按瞬时比(validEnd 存当日 00:00):与下游 offer 的 validEnd.after(now) 同口径 ——
        // 否则今天到期的模板能物化出来,却在所有在售/承接判定里已算过期。
        if (template.getValidEnd() != null && !template.getValidEnd().after(DateUtils.getNowDate())) {
            throw new IllegalStateException("权益模板已过期，请选择仍在有效期内的权益");
        }
    }

    @Override
    public List<CoopPerk> listPerks(Long inviteId) {
        return coopPerkMapper.selectByInviteId(inviteId);
    }

    @Override
    public Long resolvePerkOwner(Long perkId) {
        if (perkId == null) return null;
        CoopPerk p = coopPerkMapper.selectById(perkId);
        if (p == null) return null;
        if (p.getMerchantId() != null) return p.getMerchantId();
        if (p.getInviteId() == null) return null;
        // 复用 attachPerks 用的同一个受邀方解析(club → 主理人),口径单一真源
        return resolveInviteeOwner(coopInviteMapper.selectCoopInviteById(p.getInviteId()));
    }

    // —— 保证金 ——

    @Override
    public Long depositOwedParty(CoopInvite inv) {
        if (inv == null || inv.getInviteType() == null) return null;
        if (inv.getInviteType() == 1 && inv.getOriginApplyId() != null) return resolveInviterOwner(inv);
        if (inv.getInviteType() == 0 || inv.getInviteType() == 1) {
            return resolveInviteeOwner(inv);
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public java.util.Map<String, Object> prepareDeposit(Long inviteId, Long callerMemberId) {
        CoopInvite location = coopInviteMapper.selectCoopInviteById(inviteId);
        if (location == null) throw new IllegalStateException("邀约不存在");
        CmsTopic topic = location.getTopicId() == null ? null
                : cmsTopicMapper.selectCmsTopicByIdForUpdate(location.getTopicId());
        CoopInvite inv = coopInviteMapper.selectCoopInviteByIdForUpdate(inviteId);
        if (inv == null || !java.util.Objects.equals(location.getTopicId(), inv.getTopicId()))
            throw new IllegalStateException("邀约归属已变化");
        assertLegacyInviteReadOnly(inv);
        // accepted 存量也可能带有旧脏类型组合；先验证标准矩阵，避免绕过下面的商家行锁闸。
        assertStandardInviteTypeMatrix(inv);
        if (inv.getStatus() == null || inv.getStatus() != 1) throw new IllegalStateException("仅已接受的合作可缴纳保证金");
        Long payerMemberId = depositOwedParty(inv);
        if (payerMemberId == null || !callerMemberId.equals(payerMemberId)) throw new IllegalStateException("仅应缴方可缴纳保证金");
        // 停用商家的旧 JWT 仍可能存活；凡保证金应缴方是商家，必须在改变支付状态前锁行复核。
        if (inv.getInviteType() == 0 && CoopInvite.TO_MERCHANT.equals(inv.getToType())) {
            assertActiveMerchantForUpdate(inv.getToId());
        } else if (inv.getInviteType() == 1 && inv.getOriginApplyId() != null
                && "merchant".equals(inv.getFromType())) {
            assertActiveMerchantForUpdate(inv.getFromId());
        }
        TopicLifecycleState lifecycle = null;
        if (topic != null) {
            try {
                lifecycle = TopicLifecycleState.fromCode(topic.getLifecycle());
            } catch (IllegalArgumentException ignored) {
                // 未知持久化值必须与未开售同样 fail closed。
            }
        }
        if (lifecycle == null || !lifecycle.isSaleStarted()) {
            throw new IllegalStateException("锁价开卖后才需缴纳保证金");
        }
        if (topic.getIsSettlement() != null && topic.getIsSettlement() == 1) {
            throw new IllegalStateException("主题已结算,保证金将按应收抵扣处理");
        }
        if (inv.getDepositStatus() != null && inv.getDepositStatus() == 1
                && inv.getDepositOutTradeNo() != null) {
            java.util.Map<String, Object> pending = new HashMap<>();
            pending.put("outTradeNo", inv.getDepositOutTradeNo());
            pending.put("amount", inv.getDepositAmount() == null ? DEPOSIT_AMOUNT : inv.getDepositAmount());
            pending.put("payerMemberId", payerMemberId);
            return pending;
        }
        if (inv.getDepositStatus() == null || inv.getDepositStatus() != 5) {
            throw new IllegalStateException("当前没有待缴保证金");
        }
        BigDecimal amount = inv.getDepositAmount() == null ? DEPOSIT_AMOUNT : inv.getDepositAmount();
        String outTradeNo = "DEP" + inviteId + "T" + System.currentTimeMillis();
        if (coopInviteMapper.updateDeposit(inviteId, 1, outTradeNo, null, amount) != 1)
            throw new IllegalStateException("保证金支付状态写入失败");
        java.util.Map<String, Object> m = new HashMap<>();
        m.put("outTradeNo", outTradeNo);
        m.put("amount", amount);
        m.put("payerMemberId", payerMemberId);
        return m;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CoopInvite authorizeDepositRefundRetry(Long inviteId, Long operatorMemberId) {
        CoopInvite location = coopInviteMapper.selectCoopInviteById(inviteId);
        if (location == null) throw new IllegalStateException("邀约不存在");
        CmsTopic topic = location.getTopicId() == null ? null
                : cmsTopicMapper.selectCmsTopicByIdForUpdate(location.getTopicId());
        CoopInvite invite = coopInviteMapper.selectCoopInviteByIdForUpdate(inviteId);
        if (invite == null || !java.util.Objects.equals(location.getTopicId(), invite.getTopicId()))
            throw new IllegalStateException("邀约归属已变化");
        if (invite == null) throw new IllegalStateException("邀约不存在");
        assertLegacyInviteReadOnly(invite);
        assertStandardInviteTypeMatrix(invite);
        Long inviterOwner = resolveInviterOwner(invite);
        Long inviteeOwner = resolveInviteeOwner(invite);
        boolean historical = Integer.valueOf(4).equals(invite.getDepositStatus());
        boolean isCurrentClubOwner = false;
        if (historical) {
            java.util.SortedSet<Long> clubIds = new java.util.TreeSet<>();
            if (topic != null && topic.getClubId() != null) clubIds.add(topic.getClubId());
            if ("club".equals(invite.getToType()) && invite.getToId() != null) clubIds.add(invite.getToId());
            for (Long clubId : clubIds) {
                Long currentOwner;
                try { currentOwner = coopInviteMapper.selectClubOwnerForDepositQueryNowait(clubId); }
                catch (org.springframework.dao.DataAccessException failure) {
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        if (cause instanceof java.sql.SQLException && ((java.sql.SQLException) cause).getErrorCode() == 3572)
                            throw new IllegalStateException("身份正在更新，请稍后核对", failure);
                    }
                    throw failure;
                }
                if (currentOwner == null) throw new IllegalStateException("俱乐部归属无法确认，请联系平台核验");
                if (currentOwner.equals(operatorMemberId)) isCurrentClubOwner = true;
                if (topic != null && clubId.equals(topic.getClubId()) && "talent".equals(invite.getFromType()))
                    inviterOwner = currentOwner;
                if ("club".equals(invite.getToType()) && clubId.equals(invite.getToId())) inviteeOwner = currentOwner;
            }
        }
        boolean isInviter = operatorMemberId != null && operatorMemberId.equals(inviterOwner);
        boolean isInvitee = operatorMemberId != null && operatorMemberId.equals(inviteeOwner);
        if (!isInviter && !isInvitee && !isCurrentClubOwner) {
            throw new IllegalStateException("仅合作双方可重试保证金退款");
        }

        boolean retryable = historical || Integer.valueOf(3).equals(invite.getStatus())
                || (topic != null && Integer.valueOf(1).equals(topic.getIsSettlement()));
        if (!retryable) {
            throw new IllegalStateException("仅取消合作或履约完成后可重试退款");
        }

        // 只复核本次实际操作方所属的商家。俱乐部侧仍可在对面商家停用后取回本方保证金。
        if (isInviter && "merchant".equals(invite.getFromType())) {
            assertActiveMerchantForUpdate(invite.getFromId());
        }
        if (isInvitee && CoopInvite.TO_MERCHANT.equals(invite.getToType())) {
            assertActiveMerchantForUpdate(invite.getToId());
        }
        return invite;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markDepositPaid(String outTradeNo) {
        Map<String,String> facts = wechatPayService.queryOrderStatus(outTradeNo);
        if (facts == null || outTradeNo == null || !outTradeNo.equals(facts.get("out_trade_no"))
                || !"CNY".equals(facts.get("currency")) || !"SUCCESS".equals(facts.get("trade_state"))) return false;
        try { return markDepositPaid(outTradeNo, Long.valueOf(facts.get("total")), facts.get("transaction_id")); }
        catch (NumberFormatException e) { return false; }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markDepositPaid(String outTradeNo, Long paidCents, String transactionId) {
        CoopInvite inv = coopInviteMapper.selectByDepositOutTradeNoForUpdate(outTradeNo);
        if (inv == null || isLegacyMerchantNodeInvite(inv) || inv.getDepositAmount() == null
                || paidCents == null || transactionId == null || transactionId.trim().isEmpty()) return false;
        try { if (inv.getDepositAmount().movePointRight(2).longValueExact() != paidCents) return false; }
        catch (ArithmeticException e) { return false; }
        if (transactionId.equals(inv.getDepositPaidTransactionId())
                && (Integer.valueOf(2).equals(inv.getDepositStatus()) || Integer.valueOf(4).equals(inv.getDepositStatus())
                    || Integer.valueOf(3).equals(inv.getDepositStatus()))) return true;
        if (!Integer.valueOf(1).equals(inv.getDepositStatus())) return false;
        return coopInviteMapper.markDepositPaidConfirmed(inv.getId(), outTradeNo, inv.getDepositAmount(), transactionId) == 1;
    }

    @Override
    public int deductDeposit(Long inviteId) {
        return coopInviteMapper.deductHeldDeposit(inviteId);
    }

    /** 兼容旧内部接口：这里只查渠道，不因“受理”写已退。 */
    @Override
    public int settleDepositRefunded(Long inviteId, String refundNo) {
        CoopInvite row = coopInviteMapper.selectCoopInviteById(inviteId);
        if (row == null || refundNo == null || !refundNo.equals(row.getDepositRefundNo())) return 0;
        Map<String,String> facts = wechatPayService.queryRefundFacts(refundNo);
        return facts != null && refundNo.equals(facts.get("outRefundNo"))
                && java.util.Objects.equals(row.getDepositOutTradeNo(), facts.get("outTradeNo"))
                && "SUCCESS".equals(facts.get("status")) && depositRefundService.handle(facts) ? 1 : 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requestDepositRefund(Long inviteId, Long operatorMemberId) {
        CoopInvite invite = authorizeDepositRefundRetry(inviteId, operatorMemberId);
        depositRefundService.requestLocked(invite);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requestTopicDepositRefunds(Long topicId) {
        CmsTopic topic = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || !Integer.valueOf(1).equals(topic.getIsSettlement()))
            throw new IllegalStateException("主题尚未完成结算，不能退保证金");
        for (CoopInvite row : coopInviteMapper.selectAcceptedByTopicForUpdate(topicId)) {
            if (row == null || isLegacyMerchantNodeInvite(row) || !topicId.equals(row.getTopicId())) continue;
            if (Integer.valueOf(2).equals(row.getDepositStatus()) || Integer.valueOf(4).equals(row.getDepositStatus()))
                depositRefundService.requestLocked(row);
        }
    }

    @Override public boolean handleDepositRefundResult(Map<String,String> facts) { return depositRefundService.handle(facts); }
    @Override public void reconcileDepositRefunds() { depositRefundService.reconcile(); }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCoopOrder(CoopOrder order) {
        CoopInvite invite = order.getInviteId() == null || coopInviteMapper == null
                ? null : coopInviteMapper.selectCoopInviteById(order.getInviteId());
        assertLegacyInviteReadOnly(invite);
        // A-13 防双开单:同一报名已建过生效合作单则复用旧单,不重复建(库 reg_id 无 UNIQUE 前的代码兜底)。
        if (order.getRegId() != null) {
            List<CoopOrder> existing = coopOrderMapper.selectCoopOrderByRegId(order.getRegId());
            if (existing != null && !existing.isEmpty()) {
                return existing.get(0).getId();
            }
        }
        if (order.getCreateTime() == null) order.setCreateTime(DateUtils.getNowDate());
        if (order.getStatus() == null) order.setStatus(0);
        if (order.getDelFlag() == null) order.setDelFlag(0);
        freezeOrderContractSnapshot(order, invite);
        coopOrderMapper.insertCoopOrder(order);
        invalidateApprovedCanonical(order.getTopicId() == null && invite != null ? invite.getTopicId() : order.getTopicId(),
                "coop-order-create");
        return order.getId();
    }

    private void invalidateApprovedCanonical(Long topicId, String mutation) {
        if (canonicalMutationService != null && topicId != null) {
            canonicalMutationService.invalidateApprovedCanonical(topicId, mutation);
        }
    }

    /**
     * P0-26 权益快照兑现:成单瞬间把该邀约已申报的权益冻进合作单。
     *
     * <p>建表注释写的是「同意瞬间冻结,后续结算只认合作单」,但在此之前 setPerksSnapshot **零调用方**
     * —— 管道(实体/resultMap/insert 的 &lt;if&gt;)全铺好了,没人往里灌 ⇒ 因为 insert 用的是
     * `&lt;if test="perksSnapshot != null"&gt;`,该列恒为 NULL,「结算只认合作单」从未发生。
     *
     * <p>为什么非冻不可:改权益只能 delete-then-insert(CoopPerkMapper 无 update),商家事后重新申报
     * 会把旧记录整批软删 ⇒ 历史合作单一旦要对账,**没有任何证据**能证明当初谈的是什么。
     *
     * <p>放在 createCoopOrder 这个漏斗里而不是两个建单点各写一遍。
     *
     * <p>⚠️ **这个漏斗当初是假的**:本方法刚加上时,{@code acceptInviteWithLock}(商家点「接受邀约」,
     * 也就是**主路径**)是自建 CoopOrder 后直接 {@code coopOrderMapper.insertCoopOrder},根本不经过
     * createCoopOrder ⇒ 主路径的 perks_snapshot 照样恒 NULL,而这里的 javadoc 还信誓旦旦写着
     * 「两条路径都汇入这里、不会有第三条漏掉」。**javadoc 里的这类断言不是证据,写之前要 grep 全部调用方。**
     * 现已把 acceptInviteWithLock 改为经本漏斗。**新增建单路径时必须走 createCoopOrder,不许裸 insert。**
     *
     * <p>失败不阻断成单:快照是审计证据,不是成单的前置条件 —— 为了记账把合作关系挡在门外不划算。
     * 但要留日志,否则又是一次静默。
     */
    private void freezePerksSnapshot(CoopOrder order) {
        if (order.getInviteId() == null || order.getPerksSnapshot() != null) {
            return;
        }
        try {
            List<CoopPerk> perks = coopPerkMapper.selectByInviteId(order.getInviteId());
            if (perks != null && !perks.isEmpty()) {
                order.setPerksSnapshot(com.alibaba.fastjson2.JSON.toJSONString(perks));
            }
        } catch (Exception e) {
            logger.error("权益快照冻结失败 inviteId={} —— 合作单照建,但该单事后无权益证据可对",
                    order.getInviteId(), e);
        }
    }

    /** 接受时由服务端从邀约重建不可变合同快照，客户端不得传 role/scope/选集/证据规则。 */
    private void freezeOrderContractSnapshot(CoopOrder order, CoopInvite invite) {
        if (invite != null) {
            // 这些值是服务端合同快照，必须无条件以锁定邀约重建，不能信任调用方携带的同名字段。
            order.setShareMode(invite.getShareMode());
            order.setShareRate(invite.getShareRate());
            order.setFixedFee(invite.getFixedFee());
            order.setRoleSnapshot("club".equals(invite.getToType()) ? "ORGANIZER" : "SUPPLY");
            order.setScopeTypeSnapshot(invite.getNodeId() == null ? "TOPIC" : "NODE");
            order.setScopeIdSnapshot(invite.getNodeId() == null ? invite.getTopicId() : invite.getNodeId());
            order.setEligiblePurchaseKindsSnapshot("GUIDED_TICKET");
            order.setFulfillmentEvidenceRuleSnapshot("club".equals(invite.getToType())
                    ? "COOP_GROUP_VERIFY" : "REGISTRATION_SCAN");
            order.setDepositAmount(invite.getDepositAmount());
        }
        freezePerksSnapshot(order);
        CoopTermsAxes.Value axes = CoopTermsAxes.fromOrder(order.getShareMode(), order.getPerksSnapshot());
        order.setCompensationMode(axes.getCompensationMode());
        order.setBenefitMode(axes.getBenefitMode());
        if (invite != null || order.getTermsSnapshot() == null) {
            Map<String, Object> terms = new LinkedHashMap<String, Object>();
            terms.put("shareMode", order.getShareMode());
            terms.put("shareRate", order.getShareRate());
            terms.put("fixedFee", order.getFixedFee());
            terms.put("depositAmount", order.getDepositAmount());
            terms.put("perksSnapshot", order.getPerksSnapshot());
            terms.put("compensationMode", order.getCompensationMode());
            terms.put("benefitMode", order.getBenefitMode());
            order.setTermsSnapshot(JSON.toJSONString(terms));
        }
    }
}
