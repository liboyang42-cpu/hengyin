package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.config.RestoreDispatchGuard;

import com.chengyinhub.business.domain.dto.RegistrationCancellationPayments;
import com.chengyinhub.business.domain.dto.RegistrationCancellationPayments.Identity;
import com.chengyinhub.business.domain.dto.RegistrationCancellationPayments.Scope;
import com.chengyinhub.business.domain.dto.RegistrationCancellationPayments.Order;

import com.chengyinhub.common.constant.EntitlementInvalidReason;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.ActivityCancelManualRefundCase;
import com.chengyinhub.business.domain.dto.BeforeStartNotifyResult;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.ITeamGroupService;
import com.chengyinhub.business.service.WechatOrderNotExistException;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IActivityCancelManualRefundCaseService;
import com.chengyinhub.business.util.OrderRefundCash;
import com.chengyinhub.business.util.RefundPolicy;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 报名退款服务实现。
 *
 * 退款口径:现金走微信【原路退回】(WechatPaymentOperations.refund,SUCCESS/PROCESSING 视为受理成功),
 * 受理失败进 §6.1 人工兜底队列(不再自动转平台余额——那会造成二清);积分一并返还。
 * 现金部分复用 {@link IRefundService}(结算冲销 + 标记 registration.payment_status=4),
 * 再镜像积分返还 / 库存恢复 把钱与积分还给玩家。
 *
 * 注:成团(自动散团引擎)已于 2026-07-15 下线,类名 TeamGroup 为历史包袱,见接口注释。
 */
@Service
public class TeamGroupServiceImpl implements ITeamGroupService {

    private static final Logger logger = LoggerFactory.getLogger(TeamGroupServiceImpl.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 系统操作人(自动退款 reviewerId 占位) */
    private static final Long SYSTEM_OPERATOR = 0L;

    @Autowired
    private OmsTicketMapper omsTicketMapper;

    /** [P0-15] ③ 退款释放权益容量。 */
    @Autowired
    private com.chengyinhub.business.service.IPerkLedgerService perkLedgerService;

    @Autowired
    private CmsRegistrationMapper cmsRegistrationMapper;

    @Autowired
    private com.chengyinhub.business.mapper.SettlementDetailMapper settlementDetailMapper;

    @Autowired
    private com.chengyinhub.business.mapper.CmsActivityMapper cmsActivityMapper;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    /** [FIX-WEATHER] 主题取消要走 casUserStatus 的服务版:它同事务联动广场同步帖(6-09)。 */
    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.chengyinhub.business.service.ICmsTopicService cmsTopicService;

    /** [FIX-WEATHER] 主题取消逐场取消时,俱乐部场次要走 6-17 的俱乐部收尾。它构造注入本服务,故 @Lazy 断环。 */
    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.chengyinhub.business.service.IClubEventOpsService clubEventOpsService;

    @Autowired
    private IRefundService refundService;

    @Autowired
    private UmsMemberMapper umsMemberMapper;

    @Autowired
    private INotifyService notifyService;

    @Autowired
    private com.chengyinhub.business.service.IImService imService;

    @Autowired
    private com.chengyinhub.common.core.redis.RedisCache redisCache;

    @Autowired
    private com.chengyinhub.business.service.IRefundPayoutService refundPayoutService;

    @Autowired
    private IActivityCancelManualRefundCaseService manualRefundCaseService;

    @Autowired
    private com.chengyinhub.business.mapper.RegistrationEntitlementMapper entitlementMapper;

    /** FX-10:纯积分/从单这几条不经 RefundServiceImpl.markRegistrationRefunded 的退款出口,自己收回首评积分。 */
    @Autowired
    private FirstReviewPointsClawbackService firstReviewPointsClawback;

    @Autowired
    private com.chengyinhub.business.mapper.ClubMapper clubMapper;

    /** P1-PT-8:取消活动前对 PAYING 单查微信真实态。 */
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("wechatPaymentSdkGateway")
    private com.chengyinhub.business.service.WechatPaymentOperations wechatPayService;

    /** P1-PT-8:查到已付时走支付回调同一条补记链路(与 PAYING 超时对账同口径)。 */
    @Autowired
    private com.chengyinhub.business.service.ICmsRegistrationService cmsRegistrationService;

    /** 清单 7:取消路径查单/关单失败要让运营看得到。与对账任务同为必注入;null 只会出现在直接 new 本类的单测里。 */
    @Autowired
    private AlarmService alarmService;
    @Autowired private org.springframework.transaction.PlatformTransactionManager cancellationTransactions;
    @Autowired private WechatVerifiedPaymentFactService verifiedPaymentFacts;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cancelPaidRegistration(Long registrationId, Long operatorMemberId) {
        RestoreDispatchGuard.requireDispatchAllowed();
        CmsRegistration r = cmsRegistrationMapper.selectCmsRegistrationById(registrationId);
        if (r == null) throw new RuntimeException("报名不存在");
        if (operatorMemberId == null || !operatorMemberId.equals(r.getMemberId())) {
            throw new RuntimeException("无权取消他人报名");
        }
        RegistrationTopicFence.LockedOrder lockedOrder = RegistrationTopicFence.lock(
                r, cmsRegistrationMapper, cmsActivityMapper, cmsTopicMapper);
        r = lockedOrder.registration();
        if (r.getPaymentStatus() == null || r.getPaymentStatus() != 2) {
            throw new RuntimeException("仅已支付报名可取消退款");
        }
        // [P0-06] 已核销的处置问 RefundPolicy 这一个真源 —— 原先这里硬编码一句
        // throw「已核销报名不可退款」,把 evaluate 的判定整个绕开了(它在下面第 :93 行才被调),
        // 于是 ③ 的「首核销转人工」永远走不到,展示页说「需人工处理」、点下去说「不可退款」。
        RefundPolicy.Verdict verified = RefundPolicy.verifiedVerdict(r.getPurchaseKind(), r.getVerificationStatus());
        if (verified != null) {
            if (verified.manualReview && recordPartialChapterCase(r, operatorMemberId)) {
                return CANCEL_MANUAL_REVIEW;
            }
            rejectRefund(verified, r);
        }
        String ticketName = null;
        if (r.getOwnerType() != null && r.getOwnerType() == 1L) {
            CmsTopic topic = lockedOrder.topic();
            OmsTicket ticket = r.getTicketId() == null ? null : omsTicketMapper.selectOmsTicketById(r.getTicketId());
            if (ticket != null) ticketName = ticket.getName();
            RefundPolicy.Verdict v = RefundPolicy.evaluateTopicFulfillmentWindow(
                    r.getPaymentStatus(), r.getVerificationStatus(), topic, ticket, new Date());
            if (!v.refundable) {
                rejectRefund(v, r);
            }
        }
        // 活动/场次(ownerType=2):**集合时间前 24 小时可全额退,之后不退**(产品拍板 2026-09-15,5.9)。
        // 集合时间真源 = cms_activity.start_date;窗口由 RefundPolicy 一个判定出,别在这里再减 24h。
        if (r.getOwnerType() != null && r.getOwnerType() == 2L) {
            com.chengyinhub.business.domain.CmsActivity act = lockedOrder.activity();
            if (act != null) {
                // [P0-05] 同上:活动侧同样去掉 deadline != null 的放行条件。
                RefundPolicy.Verdict av = evaluateActivityRefund(r, act);
                if (!av.refundable) {
                    rejectRefund(av, r);
                }
            }
        }
        return refundResolvingOrder(r, ticketName, "用户申请退款", operatorMemberId)
                ? CANCEL_REFUNDED : CANCEL_MANUAL_REVIEW;
    }

    /**
     * 退款闸的唯一抛出点(玩家自助 + 主理人清退共用)。
     *
     * <p>[退款转人工-不再静默] {@code manualReview} 表示「算不出来/该不该退由人判」,但这一档
     * 今天**没有真队列**。拒绝时若只抛一句文案,平台侧零观测 ⇒ 单卡在「需人工核实」上没人知道;
     * 故转人工的判定必须打一条可 grep 的 {@code [退款转人工]} 日志,带上单号与原因。
     * 文案里的下一步指引在 {@link RefundPolicy} 里统一给(联系客服),别在这里另拼一句。
     */
    private boolean recordPartialChapterCase(CmsRegistration seed, Long actorMemberId) {
        if (!Integer.valueOf(com.chengyinhub.common.constant.PurchaseKind.EXPLORE_PASS)
                .equals(seed.getPurchaseKind())) return false;
        List<CmsRegistration> rows = StringUtils.isEmpty(seed.getParentOrderNo())
                ? Collections.singletonList(cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(seed.getId()))
                : cmsRegistrationMapper.selectByParentOrderNoForUpdate(seed.getParentOrderNo(), seed.getOwnerId());
        if (rows == null || rows.isEmpty()) throw new IllegalStateException("报名快照不存在，请刷新后重试");
        Set<Long> paidIds = new HashSet<>();
        for (CmsRegistration row : rows) {
            if (row == null) throw new IllegalStateException("报名快照损坏");
            paidIds.add(row.getId());
        }
        PaidOrderSnapshot order = inspectPaidOrderForCancellation(
                seed, seed.getOwnerType(), seed.getOwnerId(), paidIds);
        String parent = StringUtils.isEmpty(seed.getParentOrderNo())
                ? "REG:" + seed.getId() : seed.getParentOrderNo();
        com.chengyinhub.business.domain.vo.ActivityCancelManualRefundCaseVO existing =
                Long.valueOf(2).equals(seed.getOwnerType())
                    ? manualRefundCaseService.findCase(seed.getOwnerId(), parent)
                    : manualRefundCaseService.findTopicCase(seed.getOwnerId(), parent);
        if (existing != null) {
            if (!existing.getVerifiedRegistrationIds().contains(seed.getId())
                    && !existing.getUnverifiedRegistrationIds().contains(seed.getId())) {
                throw new IllegalStateException("本报名不在现有售后工单范围内，请平台核对");
            }
            return true;
        }
        com.chengyinhub.business.domain.CmsActivity activity = Long.valueOf(2).equals(seed.getOwnerType())
                ? cmsActivityMapper.selectCmsActivityById(seed.getOwnerId()) : null;
        CmsTopic topic = activity == null
                ? (Long.valueOf(1).equals(seed.getOwnerType()) ? cmsTopicMapper.selectCmsTopicById(seed.getOwnerId()) : null)
                : cmsTopicMapper.selectCmsTopicById(activity.getTopicId());
        if (topic == null) throw new IllegalStateException("章节主题不存在，请平台核对");
        com.fasterxml.jackson.databind.node.ArrayNode chapters = JSON.createArrayNode();
        int used = 0, pending = 0;
        for (CmsRegistration registration : order.paidRows) {
            if (!Integer.valueOf(com.chengyinhub.common.constant.PurchaseKind.EXPLORE_PASS)
                    .equals(registration.getPurchaseKind())) return false;
            List<com.chengyinhub.business.domain.RegistrationEntitlement> entitlements =
                    entitlementMapper.selectByRegistrationForUpdate(registration.getId());
            if (entitlements == null || entitlements.isEmpty()) {
                throw new IllegalStateException("章节权益快照缺失，请平台核对");
            }
            for (com.chengyinhub.business.domain.RegistrationEntitlement entitlement : entitlements) {
                if (!registration.getId().equals(entitlement.getRegistrationId())
                        || !topic.getId().equals(entitlement.getTopicId())
                        || entitlement.getChapterId() == null || entitlement.getChapterId() <= 0
                        || !(Integer.valueOf(0).equals(entitlement.getStatus())
                            || Integer.valueOf(1).equals(entitlement.getStatus()))) {
                    throw new IllegalStateException("章节权益快照损坏，请平台核对");
                }
                com.fasterxml.jackson.databind.node.ObjectNode chapter = chapters.addObject();
                chapter.put("registrationId", registration.getId());
                chapter.put("chapterId", entitlement.getChapterId());
                chapter.put("chapterName", entitlement.getChapterName());
                chapter.put("status", entitlement.getStatus());
                if (Integer.valueOf(1).equals(entitlement.getStatus())) used++; else pending++;
            }
        }
        if (used == 0 || pending == 0) return false;
        ActivityCancelManualRefundCase draft = new ActivityCancelManualRefundCase();
        if (Long.valueOf(2).equals(seed.getOwnerType())) {
            if (activity == null) throw new IllegalStateException("场次不存在");
            draft.setActivityId(activity.getId());
            draft.setClubId(activity.getClubId() != null && activity.getClubId() > 0 ? activity.getClubId() : null);
        } else draft.setTopicId(seed.getOwnerId());
        draft.setOwnerMemberId(topic != null ? topic.getMemberId() : activity.getMemberId());
        draft.setActorMemberId(actorMemberId);
        draft.setParentOrderNo(parent);
        draft.setPayerRegistrationId(order.payer.getId());
        draft.setPayerMemberId(order.payer.getMemberId());
        draft.setPaidCashAmountFen(order.paidCashAmountFen);
        draft.setCaseType(ActivityCancelManualRefundCase.TYPE_PARTIAL_CHAPTERS);
        draft.setChapterSnapshotJson(chapters.toString());
        try {
            draft.setVerifiedRegistrationIdsJson(JSON.writeValueAsString(order.verifiedIds));
            draft.setUnverifiedRegistrationIdsJson(JSON.writeValueAsString(order.unverifiedIds));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("售后快照序列化失败", invalid);
        }
        draft.setStatus(ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT);
        manualRefundCaseService.recordCase(draft);
        return true;
    }

    private void rejectRefund(RefundPolicy.Verdict verdict, CmsRegistration registration) {
        if (verdict.manualReview) {
            logger.warn("[退款转人工] registrationId={} purchaseKind={} ownerType={} reason={}",
                    registration.getId(), registration.getPurchaseKind(), registration.getOwnerType(),
                    verdict.reason);
        }
        throw new RuntimeException(verdict.reason);
    }

    /**
     * 场次报名(owner_type=2)的玩家自助退款窗口判据 —— 只给 cancelPaidRegistration 用。
     *
     * <p>⚠️ 主理人清退(cancelPaidRegistrationByOwner)2026-09-15 起**不再走这里**:主办主动
     * 退款不受玩家自助窗口限制(35 问 #8),只保留已核销闸。别把两条路再合并 —— 合并会让
     * 集合前 24h 一过主办想退钱也被拒,变成「可退变不可退」。
     *
     * <p>⚠️ 别复制第二份:{@code RefundSnapshotWiringTest.readersPassSnapshotDeadline} 钉死了
     * 本文件里读下单快照那个 getter 只能出现一次 —— 那条棘轮守的是「读侧真的在读下单时打的快照」,
     * 复制一份就等于给将来「只改了一处、另一处按老政策退」留了口子。(所以这段注释也不写那个
     * getter 的字面量 —— 它是按字符串数的。)
     */
    private RefundPolicy.Verdict evaluateActivityRefund(
            CmsRegistration r, com.chengyinhub.business.domain.CmsActivity activity) {
        return RefundPolicy.evaluate(2L, r.getPaymentStatus(), r.getVerificationStatus(),
                null, null, null, activity.getStartDate(), r.getPurchaseKind(),
                r.getRefundDeadline(), r.getPayableAmount(), r.getPointUsed(), new Date());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cancelPaidRegistrationByOwner(Long registrationId, Long ownerMemberId) {
        RestoreDispatchGuard.requireDispatchAllowed();
        CmsRegistration r = cmsRegistrationMapper.selectCmsRegistrationById(registrationId);
        if (r == null) throw new RuntimeException("报名不存在");
        if (r.getOwnerType() == null) throw new RuntimeException("报名归属不明,不能清退");
        RegistrationTopicFence.LockedOrder lockedOrder = RegistrationTopicFence.lock(
                r, cmsRegistrationMapper, cmsActivityMapper, cmsTopicMapper);
        r = lockedOrder.registration();

        // ★ 2026-09-11:原来这里一句 `ownerType != 1 → 抛`,而俱乐部办团卖的恒是**场次票**
        //   (主理人开一场 → cms_activity → 复制票 → 报名落 owner_type=2),
        //   于是名册上那个「清退退款」是一个**必然失败**的按钮:点了只会得到
        //   「仅主题报名支持主理人退款」,而名册的 canRefund 根本不看 ownerType。
        //   两档都要能走,授权分别按各自的归属判。
        CmsTopic topic;
        com.chengyinhub.business.domain.CmsActivity activity = null;
        if (r.getOwnerType() == 1L) {
            topic = lockedOrder.topic();
            if (topic == null) throw new RuntimeException("主题不存在");
        } else if (r.getOwnerType() == 2L) {
            activity = lockedOrder.activity();
            if (activity == null) throw new RuntimeException("场次不存在");
            topic = lockedOrder.topic();
        } else {
            throw new RuntimeException("这种报名不支持主理人清退");
        }

        // 授权:主题作者本人,或——场次挂在俱乐部名下时——该俱乐部的主理人。
        // ⚠️ 只认主题作者是不够的:合作承接的场次,主题作者是发起方(可能是商家),
        //    真正在现场带团、要清退名单的是承接俱乐部的主理人。
        boolean topicOwner = topic != null && topic.getMemberId() != null
                && topic.getMemberId().equals(ownerMemberId);
        boolean clubOwner = false;
        if (!topicOwner && activity != null && activity.getClubId() != null && activity.getClubId() > 0) {
            com.chengyinhub.business.domain.Club club = clubMapper.selectClubByIdForUpdate(activity.getClubId());
            clubOwner = club != null && club.getMemberId() != null
                    && club.getMemberId().equals(ownerMemberId)
                    && (club.getDelFlag() == null || club.getDelFlag() == 0);
        }
        if (!topicOwner && !clubOwner) {
            throw new RuntimeException("无权操作:只有主题作者或承接俱乐部主理人可以清退");
        }
        if (r.getPaymentStatus() == null || r.getPaymentStatus() != 2) {
            throw new RuntimeException("仅已支付报名可退款");
        }
        // [P0-06] 已核销的处置问 RefundPolicy 这一个真源 —— 原先这里硬编码一句
        // throw「已核销报名不可退款」,把 evaluate 的判定整个绕开了(它在下面第 :93 行才被调),
        // 于是 ③ 的「首核销转人工」永远走不到,展示页说「需人工处理」、点下去说「不可退款」。
        RefundPolicy.Verdict verified = RefundPolicy.verifiedVerdict(r.getPurchaseKind(), r.getVerificationStatus());
        if (verified != null) {
            if (verified.manualReview && recordPartialChapterCase(r, ownerMemberId)) {
                return CANCEL_MANUAL_REVIEW;
            }
            rejectRefund(verified, r);
        }
        String ticketName = null;
        OmsTicket ticket = r.getTicketId() == null ? null : omsTicketMapper.selectOmsTicketById(r.getTicketId());
        if (ticket != null) ticketName = ticket.getName();
        // 2026-09-15 拍板(35 问 #8):主理人清退是主办方主动退款,不受玩家自助退款窗口限制 ——
        // 原先复用 evaluateActivityRefund 后,集合前 24h(存量 00:00 场次实际是前一日 0 点)一过,
        // 主办想退钱也被拒「已错过集合前24小时」,把可退变成不可退。窗口只约束玩家自助路径
        // (见 cancelPaidRegistration);这里只保留上面的已核销闸(verifiedVerdict)。
        return refundResolvingOrder(r, ticketName, "主理人取消报名并退款", ownerMemberId)
                ? CANCEL_REFUNDED : CANCEL_MANUAL_REVIEW;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER, rollbackFor = Exception.class)
    public com.chengyinhub.business.domain.dto.ActivityCancelResult cancelActivityByOwner(
            Long activityId, Long ownerMemberId, String reason) {
        RestoreDispatchGuard.requireDispatchAllowed();
        return cancelActivityOutsideTransaction(activityId, ownerMemberId, ownerMemberId, reason, false);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER, rollbackFor = Exception.class)
    public com.chengyinhub.business.domain.dto.ActivityCancelResult cancelActivityByClubOperator(
            Long activityId, Long ownerMemberId, Long actorMemberId, String reason) {
        RestoreDispatchGuard.requireDispatchAllowed();
        if (actorMemberId == null || actorMemberId <= 0) {
            throw new ServiceException("活动取消操作人无效", HttpStatus.FORBIDDEN);
        }
        return cancelActivityOutsideTransaction(activityId, ownerMemberId, actorMemberId, reason, false);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER, rollbackFor = Exception.class)
    public com.chengyinhub.business.domain.dto.ActivityCancelResult cancelActivityByMerchantHost(
            Long activityId, Long merchantOwnerMemberId, Long actorMemberId, String reason) {
        RestoreDispatchGuard.requireDispatchAllowed();
        if (actorMemberId == null || actorMemberId <= 0) {
            throw new ServiceException("活动取消操作人无效", HttpStatus.FORBIDDEN);
        }
        return cancelActivityOutsideTransaction(activityId, merchantOwnerMemberId, actorMemberId, reason, true);
    }

    /**
     * 主办方才能取消(9-18 拍板):商家承办别人主题生成的场次,场次 member_id 也是商家,
     * 只看场次归属会让承办方单方面把主办方的票全退了。挂在主题下的场次再核一次主题主办。
     * 取消与取消预览共用这一道,别让确认框给出人数、点下去却 403。
     */
    private void requireMerchantHostsSession(com.chengyinhub.business.domain.CmsActivity act,
                                             Long merchantOwnerMemberId) {
        // [RV-p6line F2] 店主兼俱乐部主理人时,俱乐部场次 member_id 也是店主;商家员工无俱乐部岗位,
        // 且这条路不走俱乐部收尾(候补/场次状态/通知)。俱乐部场次只能由俱乐部主理人取消。
        if (act != null && act.getClubId() != null) {
            throw new ServiceException("俱乐部场次请在俱乐部里由主理人取消", HttpStatus.FORBIDDEN);
        }
        if (act != null && act.getTopicId() != null) {
            CmsTopic topic = cmsTopicMapper.selectCmsTopicById(act.getTopicId());
            if (topic == null || merchantOwnerMemberId == null
                    || !merchantOwnerMemberId.equals(topic.getMemberId())) {
                throw new ServiceException("这场是承办场次，需由主题主办方取消", HttpStatus.FORBIDDEN);
            }
        }
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER, rollbackFor = Exception.class)
    public com.chengyinhub.business.domain.dto.ActivityCancelResult cancelTopicByHost(
            Long topicId, Long hostMemberId, Long actorMemberId, String reason) {
        RestoreDispatchGuard.requireDispatchAllowed();
        requireNoCancellationTransaction();
        if (actorMemberId == null || actorMemberId <= 0) {
            throw new ServiceException("主题取消操作人无效", HttpStatus.FORBIDDEN);
        }
        RegistrationCancellationPayments captured = cancellationTransaction(() -> {
            requireHostedTopicForCancel(topicId, hostMemberId, true);
            List<Scope> scopes = new ArrayList<>(captureCancellationPayments(1L, topicId).scopes());
            for (com.chengyinhub.business.domain.CmsActivity shell : topicSessionsToCancel(topicId)) {
                if (shell.getClubId() != null) {
                    scopes.addAll(clubEventOpsService.captureAuthorizedTopicCancellationPayments(
                            actorMemberId, topicId, shell.getClubId(), shell.getId(), reason).scopes());
                } else {
                    requireCancellableActivity(shell.getId(), shell.getMemberId());
                    scopes.addAll(captureCancellationPayments(2L, shell.getId()).scopes());
                }
            }
            return new RegistrationCancellationPayments(scopes);
        });
        RegistrationCancellationPayments observed = observeCancellationPayments(captured);
        return cancellationAfterObservation(observed,
                () -> cancelTopicWithObservedPayments(topicId, hostMemberId, actorMemberId, reason, observed));
    }

    private com.chengyinhub.business.domain.dto.ActivityCancelResult cancelTopicWithObservedPayments(
            Long topicId, Long hostMemberId, Long actorMemberId, String reason, RegistrationCancellationPayments observed) {
        if (actorMemberId == null || actorMemberId <= 0) {
            throw new ServiceException("主题取消操作人无效", HttpStatus.FORBIDDEN);
        }
        CmsTopic topic = requireHostedTopicForCancel(topicId, hostMemberId, true);
        List<com.chengyinhub.business.domain.CmsActivity> sessions = topicSessionsToCancel(topicId);
        observed.requireScope(1L, topicId);
        if (observed.scopes().size() != sessions.size() + 1) throw retryLater();
        for (com.chengyinhub.business.domain.CmsActivity session : sessions) observed.requireScope(2L, session.getId());
        // ① 先停售,再退款:反过来会在退款过程中又卖出新票。同事务,退款失败连停售一起回滚。
        if (Integer.valueOf(1).equals(topic.getUserStatus())
                && cmsTopicService.casUserStatus(topicId, 1, 0) < 1) {
            throw new ServiceException("主题状态刚刚变过，请刷新后重试", HttpStatus.CONFLICT);
        }
        // ② 主题级票(ownerType=1)
        com.chengyinhub.business.domain.dto.ActivityCancelResult topicSales =
                cancelPaidSales(1L, topicId, null, actorMemberId, reason, true, observed);
        int refunded = topicSales.getRefundedOrders();
        int manual = topicSales.getManualOrders();
        int closed = topicSales.getClosedPendingOrders();
        // ③ 逐场取消该主题下的场次(含承办方的场次:主题都取消了,承办场次不能继续卖)。
        //    授权已在主题层判过,这里以场次自己的归属人为 owner 走同一个退款核心。
        //    整个方法一个事务:任一场失败全部回滚,而微信退款只在提交后派发 —— 不会出现退了一半的钱。
        //    俱乐部名下的场次走 6-17 同一套俱乐部收尾(候补终止/领队回收/站内取消通知)。
        //    主题授权已在上面完成，系统收尾以 occurrence 和当前俱乐部主理人为真源，
        //    不再把历史场次创建人的现有岗位当作第二道授权；退款仍是同一个核心、同一个事务。
        for (com.chengyinhub.business.domain.CmsActivity shell : sessions) {
            com.chengyinhub.business.domain.dto.ActivityCancelResult r = shell.getClubId() != null
                    ? clubEventOpsService.cancelAuthorizedTopicWithObservedPayments(
                            actorMemberId, topicId, shell.getClubId(), shell.getId(), reason, observed)
                    : cancelActivity(shell.getId(), shell.getMemberId(), actorMemberId, reason, true, observed);
            if (r == null) {
                throw new ServiceException("场次取消回执为空，无法确认是否退成", HttpStatus.CONFLICT);
            }
            refunded += r.getRefundedOrders();
            manual += r.getManualOrders();
            closed += r.getClosedPendingOrders();
        }
        logger.info("主办方取消主题完成 topicId={} hostMemberId={} actorMemberId={} 自动退款={}单 "
                + "关闭待支付={}单 待人工处置={}单", topicId, hostMemberId, actorMemberId, refunded, closed, manual);
        return new com.chengyinhub.business.domain.dto.ActivityCancelResult(refunded, manual, closed);
    }

    @Override
    public int countPaidPlayersForActivityCancel(Long activityId, Long hostMemberId) {
        com.chengyinhub.business.domain.CmsActivity act = cmsActivityMapper.selectCmsActivityById(activityId);
        if (!isActivityOwnerOrClubOwner(act, hostMemberId)) {
            throw new ServiceException("无权操作该活动", HttpStatus.FORBIDDEN);
        }
        Set<Long> members = new HashSet<>();
        collectPaidUnverifiedMembers(2L, activityId, members);
        return members.size();
    }

    @Override
    public int countPaidPlayersForMerchantActivityCancel(Long activityId, Long merchantOwnerMemberId) {
        requireMerchantHostsSession(cmsActivityMapper.selectCmsActivityById(activityId), merchantOwnerMemberId);
        return countPaidPlayersForActivityCancel(activityId, merchantOwnerMemberId);
    }

    @Override
    public int countPaidPlayersForTopicCancel(Long topicId, Long hostMemberId) {
        requireHostedTopicForCancel(topicId, hostMemberId, false);
        Set<Long> members = new HashSet<>();
        collectPaidUnverifiedMembers(1L, topicId, members);
        for (com.chengyinhub.business.domain.CmsActivity shell : topicSessionsToCancel(topicId)) {
            collectPaidUnverifiedMembers(2L, shell.getId(), members);
        }
        return members.size();
    }

    /** 主题取消的授权:主题主办本人(个人或商家店主);俱乐部主题走俱乐部「结束主题」,那里有岗位权限判定。 */
    private CmsTopic requireHostedTopicForCancel(Long topicId, Long hostMemberId, boolean lock) {
        CmsTopic topic = topicId == null ? null
                : (lock ? cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId) : cmsTopicMapper.selectCmsTopicById(topicId));
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
            throw new ServiceException("主题不存在");
        }
        if (hostMemberId == null || !hostMemberId.equals(topic.getMemberId())) {
            throw new ServiceException("只有主题主办方可以取消主题", HttpStatus.FORBIDDEN);
        }
        if (topic.getClubId() != null) {
            throw new ServiceException("俱乐部主题请在俱乐部「主题设置 → 结束主题」里取消");
        }
        return topic;
    }

    private List<com.chengyinhub.business.domain.CmsActivity> topicSessionsToCancel(Long topicId) {
        com.chengyinhub.business.domain.CmsActivity probe = new com.chengyinhub.business.domain.CmsActivity();
        probe.setTopicId(topicId);
        probe.setExcludeCancelled(1);
        probe.setDelFlag(0);
        List<com.chengyinhub.business.domain.CmsActivity> rows = cmsActivityMapper.selectCmsActivityList(probe);
        List<com.chengyinhub.business.domain.CmsActivity> out = new ArrayList<>();
        if (rows == null) return out;
        Date now = new Date();
        for (com.chengyinhub.business.domain.CmsActivity row : rows) {
            if (row == null || row.getId() == null || !topicId.equals(row.getTopicId())) {
                throw new ServiceException("主题场次范围不一致，请刷新后重试", HttpStatus.CONFLICT);
            }
            // [RV-p6line F1] 已结束的场次一律不动:不取消、不退(没扫码就玩了的也是玩过),
            // 也就不会经退款核心把承办方已结算的钱冲回。取消与确认框人数共用这份清单。
            if (row.getEndDate() != null && !row.getEndDate().after(now)) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    /**
     * 确认框里的「N 位已付款玩家」= 这次会被自动全额退的人,按人去重:已付款、未核销,
     * 且所在多票订单里没有已核销的票(那种单整单转人工,不自动退,不能算进「全额退款」)。
     */
    private void collectPaidUnverifiedMembers(Long ownerType, Long ownerId, Set<Long> out) {
        CmsRegistration q = new CmsRegistration();
        q.setOwnerType(ownerType);
        q.setOwnerId(ownerId);
        q.setPaymentStatus(2);
        List<CmsRegistration> rows = cmsRegistrationMapper.selectCmsRegistrationList(q);
        if (rows == null) return;
        Set<String> partlyVerifiedOrders = new HashSet<>();
        for (CmsRegistration r : rows) {
            if (r != null && Integer.valueOf(1).equals(r.getVerificationStatus())
                    && StringUtils.isNotEmpty(r.getParentOrderNo())) {
                partlyVerifiedOrders.add(r.getParentOrderNo());
            }
        }
        for (CmsRegistration r : rows) {
            if (r != null && r.getMemberId() != null && !Integer.valueOf(1).equals(r.getVerificationStatus())
                    && (StringUtils.isEmpty(r.getParentOrderNo()) || !partlyVerifiedOrders.contains(r.getParentOrderNo()))) {
                out.add(r.getMemberId());
            }
        }
    }

    private com.chengyinhub.business.domain.CmsActivity requireCancellableActivity(Long activityId, Long ownerMemberId) {
        com.chengyinhub.business.domain.CmsActivity act = RegistrationTopicFence.lockActivity(
                cmsActivityMapper.selectCmsActivityById(activityId), cmsActivityMapper, cmsTopicMapper);
        // 这三条抛 ServiceException 而非裸 RuntimeException:文案是写给主办方看的,入口据此原样回显;
        // 非预期异常(NPE/SQL)则走 Exception 分支给中性文案 —— 类型就是「该不该给用户看」的判据。
        if (act == null || (act.getDelFlag() != null && act.getDelFlag() == 2)) {
            throw new ServiceException("活动不存在");
        }
        // 归属校验:活动创建者本人,或——活动挂在俱乐部名下时——该俱乐部当前主理人。
        // S14-4/5-07:cms_activity.member_id 是系列创建时写死的创建人;转让主理人后,
        // event-ops 取消传下来的是**当前**主理人(ClubEventOpsServiceImpl 的 lockedOwner),
        // 只认创建人 ⇒ 新主理人永远取消不了旧场次。判据与 cancelPaidRegistrationByOwner
        // 的俱乐部主理人档同口径(活动创建者的既有行为不变)。
        if (!isActivityOwnerOrClubOwner(act, ownerMemberId, true)) {
            throw new ServiceException("无权操作该活动");
        }
        // 幂等闸:已取消不得重复取消,否则会对已退款的报名再走一遍退款链路
        if (act.getCancelTime() != null) {
            throw new ServiceException("活动已取消,请勿重复操作");
        }

        return act;
    }

    /** @param refuseSettled 主题取消传 true:有订单的钱已结算出去就整次 409,不经退款核心冲回(RV-p6line F1) */
    private com.chengyinhub.business.domain.dto.ActivityCancelResult cancelActivity(
            Long activityId, Long ownerMemberId, Long actorMemberId, String reason, boolean refuseSettled,
            RegistrationCancellationPayments observed) {
        com.chengyinhub.business.domain.CmsActivity act = requireCancellableActivity(activityId, ownerMemberId);
        // 系列场次沿用俱乐部通知任务；独立场次在退款改变报名状态前保留受影响玩家。
        Set<Long> noticeRecipients = act.getClubId() == null || act.getClubId() <= 0
                ? standaloneCancellationRecipients(activityId) : Collections.emptySet();

        com.chengyinhub.business.domain.dto.ActivityCancelResult sales =
                cancelPaidSales(2L, activityId, act, actorMemberId, reason, refuseSettled, observed);

        // 置取消态 + 下架(别让新用户继续报名一个已取消的活动)
        com.chengyinhub.business.domain.CmsActivity upd = new com.chengyinhub.business.domain.CmsActivity();
        upd.setId(activityId);
        upd.setCancelTime(new Date());
        upd.setCancelReason(reason);
        upd.setStatus(0L);
        upd.setPublishStatus(0L);
        upd.setUpdateBy(String.valueOf(actorMemberId));
        upd.setUpdateTime(new Date());
        if (cmsActivityMapper.updateCmsActivity(upd) != 1) {
            throw new ServiceException("活动状态已变化，请刷新后重试", HttpStatus.CONFLICT);
        }
        for (Long memberId : noticeRecipients) {
            String name = StringUtils.isEmpty(act.getName()) ? "你报名的场次" : "“" + act.getName() + "”";
            String content = "场次取消通知\n" + name
                    + "已取消。请在「我的票夹」查看订单状态；退款和未完成权益请联系主办方处理。";
            String extra = "{\"type\":\"ACTIVITY_CANCELLED\",\"activityId\":" + activityId
                    + (act.getTopicId() == null ? "" : ",\"topicId\":" + act.getTopicId()) + "}";
            // REQUIRED 与取消共用事务；失败必须回滚，现金退款仍等提交后才派发。
            Long messageId = imService.sendSystem(memberId, 1, content, extra);
            if (messageId == null || messageId <= 0) {
                throw new ServiceException("取消通知未保存，请重试", HttpStatus.CONFLICT);
            }
        }
        // 每次取消都留一条对账锚点:事后能按 activityId 捞出「退了几单、几单要人管、分别是谁」。
        logger.info("主办方取消活动完成 activityId={} ownerMemberId={} actorMemberId={} 自动退款={}单 "
                        + "关闭待支付={}单 待人工处置={}单",
                activityId, ownerMemberId, actorMemberId, sales.getRefundedOrders(),
                sales.getClosedPendingOrders(), sales.getManualOrders());
        return sales;
    }

    private Set<Long> standaloneCancellationRecipients(Long activityId) {
        CmsRegistration probe = new CmsRegistration();
        probe.setOwnerType(2L);
        probe.setOwnerId(activityId);
        List<CmsRegistration> rows = currentCancellationRows(2L, activityId);
        Set<Long> recipients = new java.util.LinkedHashSet<>();
        if (rows != null) for (CmsRegistration row : rows) {
            if (row == null || !Long.valueOf(2L).equals(row.getOwnerType())
                    || !activityId.equals(row.getOwnerId())) {
                throw new ServiceException("取消通知报名范围不一致，请刷新后重试", HttpStatus.CONFLICT);
            }
            if (!Integer.valueOf(1).equals(row.getRegistrationStatus())
                    && !Integer.valueOf(2).equals(row.getRegistrationStatus())) continue;
            if (row.getMemberId() == null || row.getMemberId() <= 0) {
                throw new ServiceException("取消通知接收人无效，请刷新后重试", HttpStatus.CONFLICT);
            }
            recipients.add(row.getMemberId());
        }
        return recipients;
    }

    /**
     * [FIX-WEATHER] 主办方取消时「卖出去的票」这一侧:PAYING 定性 → 已付款整单全额退 → 待支付关单回补。
     * 场次取消(ownerType=2)与主题级票(ownerType=1)共用这一份,退款仍全走 refundResolvingOrder。
     *
     * <p>已核销口径(9-18 拍板 A,与场次取消同一套):整单全核销不退;部分核销 ⇒ 未核销那几人的钱卡住,
     * 转人工。人工工单表 activity_cancel_manual_refund_case 只认场次(activity_id NOT NULL),
     * 主题级票没处落工单 ⇒ 这种单整次拒绝(fail closed),不静默吞、也不自己编规则。
     *
     * @param act ownerType=2 时为已锁定的场次(落人工工单用);ownerType=1 时为 null
     */
    private com.chengyinhub.business.domain.dto.ActivityCancelResult cancelPaidSales(
            Long ownerType, Long ownerId, com.chengyinhub.business.domain.CmsActivity act,
            Long actorMemberId, String reason, boolean refuseSettled, RegistrationCancellationPayments observed) {
        Long activityId = ownerId; // 下面沿用原变量名的日志/校验:ownerType=1 时它就是 topicId
        boolean parentSettled = refuseSettled && isParentSettled(ownerType, ownerId, act);
        // P1-PT-8:支付中(PAYING)的单既不在下面的已付退款集合、也不在待支付关单集合,
        // 必须先问微信定性 —— 已付的补记后并入退款集合,未付的关单回补,定不了就整次拒绝。
        settlePayingRegistrationsBeforeCancel(ownerType, activityId, actorMemberId, observed);

        // 捞该归属下所有已付款报名(selectCmsRegistrationList 支持 ownerType/ownerId/paymentStatus 过滤)
        CmsRegistration q = new CmsRegistration();
        q.setOwnerType(ownerType);
        q.setOwnerId(activityId);
        q.setPaymentStatus(2);
        List<CmsRegistration> paid = currentCancellationRows(ownerType, activityId).stream()
                .filter(row -> Integer.valueOf(2).equals(row.getPaymentStatus())).toList();

        int refundedOrders = 0;
        java.util.Set<String> handledOrders = new java.util.HashSet<>();
        java.util.Set<String> manualOrders = new java.util.LinkedHashSet<>();
        Set<Long> paidRegistrationIds = new HashSet<>();
        if (paid != null) {
            for (CmsRegistration paidRow : paid) {
                if (paidRow != null && paidRow.getId() != null) {
                    paidRegistrationIds.add(paidRow.getId());
                }
            }
        }
        if (paid != null) {
            for (CmsRegistration r : paid) {
                // 多票同父订单只处理一次 —— refundResolvingOrder 会整单退,重复调会撞幂等/重复回补库存
                String orderKey = StringUtils.isNotEmpty(r.getParentOrderNo())
                        ? r.getParentOrderNo() : ("R" + r.getId());
                if (!handledOrders.add(orderKey)) {
                    continue;
                }
                // 已核销 = 用户已参加、服务已交付,主办方事后取消不该把这笔也退了。
                // 整单退要求「同单无已核销」,否则抛异常。这里必须先探明再决定,不能让它抛 ——
                // 否则同单里一张已核销的从单就会把【整个活动】的取消操作连带回滚,主办方永远取消不了。
                // 该单跳过(留给人工处置),其余订单照退 —— 但跳过必须留痕:静默 continue 等于这笔钱
                // 在系统里人间蒸发,「人工」是谁、从哪知道,全靠猜。
                //
                // ⚠️ 判定挪到 orderKey 认领【之后】:原先「自己已核销就 continue」那道前置闸在认领之前,
                // 已核销行不认领 key ⇒「整单全核销」的订单压根走不到这里,只在这里计数会漏掉它们。
                // 去掉那道闸是等价的:orderHasVerifiedMember 对单票(parentOrderNo 空)就是返回自己的
                // 核销态,对多票查全单(含自己),已经把它完全覆盖。
                PaidOrderSnapshot orderSnapshot = inspectPaidOrderForCancellation(
                        r, ownerType, activityId, paidRegistrationIds);
                if (orderSnapshot.paidRows.isEmpty()) {
                    continue;
                }
                if (orderSnapshot.hasVerified) {
                    // ⚠️ 跳过的原因有两种,语义完全不同,别混成一个数字:
                    //  · 整单都已核销 → 人真去玩过了、服务已交付,本就不该退。终局,不是人工的活。
                    //  · 部分核销 → 同单里【未核销那些人的钱被卡住了】(整单退会抛异常),
                    //    他们该拿到钱却没拿到 ⇒ 这才要人工。
                    // 混在一起的后果:每个到场玩家都刷一条 warn,还把假数字写进给主办方的回执,
                    // 真正需要人管的那几笔反而被噪音淹掉。
                    if (orderSnapshot.hasUnverified) {
                        if (act == null) {
                            throw new ServiceException("有多票订单部分核销，需平台人工处理后再取消主题",
                                    HttpStatus.CONFLICT);
                        }
                        recordManualRefundCaseOnce(act, actorMemberId, orderKey, orderSnapshot);
                        manualOrders.add(orderKey);
                        logger.warn("主办方取消活动,该单部分核销无法整单退,未核销成员的钱被卡住,留待人工处置 "
                                        + "activityId={} orderKey={} registrationId={} memberId={} cancelReason={}",
                                activityId, orderKey, r.getId(), r.getMemberId(), reason);
                    }
                    continue;
                }
                // [RV-p6line F1] 主题取消不冲回已结算的钱:退款核心对已结算明细必冲销,这单的钱已经
                // 结给主办/承办方,自动退就会从对方余额扣回。兜底 fail closed(总控 9-18 选 A),人工处理后再取消。
                if (refuseSettled && (parentSettled || anySettledDetail(orderSnapshot.paidRows))) {
                    throw new ServiceException("有订单的钱已结算给主办或承办方，需平台人工处理后再取消主题",
                            HttpStatus.CONFLICT);
                }
                // 退款核心先锁全组，再在同事务取代未派发旧申请；不能先将旧申请驳回。
                // 上面已在锁内判过「无已核销」,不该走到转人工分支;真走到了说明快照与锁内事实不一致
                if (!refundResolvingOrder(orderSnapshot.payer, orderSnapshot.payer.getTicketName(),
                        "主办方取消活动,全额退款", actorMemberId, true)) {
                    throw new ServiceException("订单核销状态已变化,请刷新后重试", HttpStatus.CONFLICT);
                }
                refundedOrders++;
            }
        }

        // 已创建但尚未支付的订单同样占着真实库存，且支付回调可能随后到达。
        // 先 CAS 关单，再回补库存/积分/权益；若回调抢先把订单变成已支付，整次取消回滚并提示重试，
        // 下一次会把它纳入上面的已付款全额退款集合，绝不留下“活动取消后仍支付成功”的夹层。
        CmsRegistration pendingQuery = new CmsRegistration();
        pendingQuery.setOwnerType(ownerType);
        pendingQuery.setOwnerId(activityId);
        pendingQuery.setPaymentStatus(0);
        pendingQuery.setRegistrationStatus(1);
        List<CmsRegistration> pending = currentCancellationRows(ownerType, activityId).stream()
                .filter(row -> Integer.valueOf(0).equals(row.getPaymentStatus())
                        && Integer.valueOf(1).equals(row.getRegistrationStatus())).toList();
        int closedPendingOrders = 0;
        if (pending != null) {
            for (CmsRegistration registration : pending) {
                if (registration == null || registration.getId() == null || registration.getMemberId() == null
                        || !ownerType.equals(registration.getOwnerType())
                        || !activityId.equals(registration.getOwnerId())) {
                    throw new ServiceException("待支付报名范围不一致，请刷新后重试", HttpStatus.CONFLICT);
                }
                if (verifiedPaymentFacts.hasConfirmedExternalSuccess(registration.getRegistrationNo())) throw retryLater();
                if (cmsRegistrationMapper.cancelUnpaidIfActive(registration.getId(),
                        registration.getMemberId(), "activity-cancel:" + actorMemberId) != 1) {
                    CmsRegistration current = cmsRegistrationMapper
                            .selectCmsRegistrationByIdForUpdate(registration.getId());
                    if (current != null && (Integer.valueOf(3).equals(current.getRegistrationStatus())
                            || Integer.valueOf(4).equals(current.getRegistrationStatus()))) {
                        continue;
                    }
                    throw new ServiceException("报名支付状态已变化，请刷新后重试", HttpStatus.CONFLICT);
                }
                restoreInventory(registration);
                returnPoints(registration);
                releasePerks(registration);
                closedPendingOrders++;
            }
        }

        logger.info("主办方取消·售出侧收尾 ownerType={} ownerId={} actorMemberId={} 自动退款={}单 "
                        + "关闭待支付={}单 待人工处置={}单 待处置订单={}",
                ownerType, activityId, actorMemberId, refundedOrders, closedPendingOrders,
                manualOrders.size(), manualOrders);
        return new com.chengyinhub.business.domain.dto.ActivityCancelResult(
                refundedOrders, manualOrders.size(), closedPendingOrders);
    }

    /**
     * 取消活动的归属判定:独立活动认创建者；俱乐部活动只认该俱乐部的**当前**主理人。
     *
     * <p>为什么不能只比 {@code cms_activity.member_id}:俱乐部场次由系列物化,创建人写死;
     * 转让主理人后,取消入口按俱乐部归属校验(见 ClubEventOpsServiceImpl.cancelOccurrence),
     * 两处口径必须一致。已解散(del_flag≠0)的俱乐部不参与判定。
     */
    private boolean isActivityOwnerOrClubOwner(com.chengyinhub.business.domain.CmsActivity activity,
                                               Long ownerMemberId) {
        return isActivityOwnerOrClubOwner(activity, ownerMemberId, false);
    }

    private boolean isActivityOwnerOrClubOwner(com.chengyinhub.business.domain.CmsActivity activity,
                                               Long ownerMemberId, boolean lock) {
        if (activity == null || ownerMemberId == null) return false;
        Long clubId = activity.getClubId();
        if (clubId == null || clubId <= 0) {
            return activity.getMemberId() != null && activity.getMemberId().equals(ownerMemberId);
        }
        com.chengyinhub.business.domain.Club club = lock ? clubMapper.selectClubByIdForUpdate(clubId)
                : clubMapper.selectClubById(clubId);
        return club != null && club.getMemberId() != null && club.getMemberId().equals(ownerMemberId)
                && (club.getDelFlag() == null || club.getDelFlag() == 0);
    }

    /**
     * P1-PT-8:取消活动前收口所有 PAYING 单(与 OrderCloseTask.reconcileExpiredPaying 同口径)。
     *
     * <p><b>两段式</b>(清单 2):第一段只查单定性、不碰微信也不碰库;任何一单需要拒绝就在这里整次拒掉。
     * 第二段才逐个 closeOrder + 本地关单。原因:{@code closeOrder} 是<b>不可回滚的外部调用</b>,
     * 混在一段里意味着「关了前三单、第四单查单失败 → 事务回滚」⇒ 前三单本地仍 PAYING 而微信已 CLOSED,
     * 玩家在过期前既付不了也退不了,只能等对账任务过期后收敛。两段式把这个夹层压缩到只剩
     * 「第二段里 closeOrder 本身失败」这一种。
     *
     * <ul>
     *   <li>SUCCESS → 按支付回调补记(同事务),随后被已付集合整单退;</li>
     *   <li>NOTPAY / USERPAYING / PAYERROR → 先关微信单,再本地关单回补;</li>
     *   <li>CLOSED → 同事务核对当前尝试后本地关单回补;</li>
     *   <li>ORDER_NOT_EXIST → 保留原号查证，拒绝本次取消，不凭时间推断未执行;</li>
     *   <li>查单失败 / 单号不符 / 未知态 / 关单失败 / 补记未 SUCCESS / 条件关单 0 行 → 拒绝,整次取消回滚。</li>
     * </ul>
     *
     * <p>多票从单停 0 态,由 settlePayingOne 整单一并关。
     */
    private static void requireNoCancellationTransaction() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("取消支付查证必须在原业务事务外执行，不能悬挂持锁调用方");
    }

    private static void requireCancellationTransaction() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("取消支付业务应用必须加入原范围事务");
    }

    private <T> T cancellationTransaction(java.util.function.Supplier<T> work) {
        return new org.springframework.transaction.support.TransactionTemplate(cancellationTransactions)
                .execute(status -> work.get());
    }

    private <T> T cancellationAfterObservation(RegistrationCancellationPayments observed,
                                                java.util.function.Supplier<T> work) {
        try {
            return cancellationTransaction(work);
        } catch (RuntimeException failure) {
            boolean closeIssued = observed.scopes().stream().flatMap(scope -> scope.orders().stream())
                    .anyMatch(order -> order.confirmedClosed()
                            && !"CLOSED".equalsIgnoreCase(order.providerFacts().get("trade_state")));
            if (!closeIssued) throw failure;
            alarmCancelFailure("原支付关闭后本地取消未确认", "原支付身份与已确认关闭意图保留，禁止换号或盲目回补");
            ServiceException uncertain = retryLater().setDetailMessage("CANCELLATION_APPLY_AFTER_CONFIRMED_CLOSE");
            uncertain.initCause(failure);
            throw uncertain;
        }
    }

    private com.chengyinhub.business.domain.dto.ActivityCancelResult cancelActivityOutsideTransaction(
            Long activityId, Long ownerMemberId, Long actorMemberId, String reason, boolean merchantHost) {
        requireNoCancellationTransaction();
        RegistrationCancellationPayments captured = cancellationTransaction(() -> {
            com.chengyinhub.business.domain.CmsActivity act = requireCancellableActivity(activityId, ownerMemberId);
            if (merchantHost) requireMerchantHostsSession(act, ownerMemberId);
            return captureCancellationPayments(2L, activityId);
        });
        RegistrationCancellationPayments observed = observeCancellationPayments(captured);
        return cancellationAfterObservation(observed, () -> {
            if (merchantHost) requireMerchantHostsSession(cmsActivityMapper.selectCmsActivityById(activityId), ownerMemberId);
            return cancelActivity(activityId, ownerMemberId, actorMemberId, reason, false, observed);
        });
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, rollbackFor = Exception.class)
    public com.chengyinhub.business.domain.dto.ActivityCancelResult cancelActivityWithObservedPayments(
            Long activityId, Long ownerMemberId, Long actorMemberId, String reason, boolean refuseSettled,
            RegistrationCancellationPayments observed) {
        RestoreDispatchGuard.requireDispatchAllowed();
        requireCancellationTransaction();
        if (actorMemberId == null || actorMemberId <= 0)
            throw new ServiceException("活动取消操作人无效", HttpStatus.FORBIDDEN);
        return cancelActivity(activityId, ownerMemberId, actorMemberId, reason, refuseSettled, observed);
    }

    private List<CmsRegistration> currentCancellationRows(Long ownerType, Long ownerId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("取消报名当前锁读必须与原范围事务共用");
        List<CmsRegistration> rows = cmsRegistrationMapper.selectCancellationRowsForUpdate(ownerType, ownerId);
        if (rows == null) throw new IllegalStateException("原取消报名列表读取失败");
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (CmsRegistration row : rows) {
            if (row == null || row.getId() == null || !ids.add(row.getId())
                    || !ownerType.equals(row.getOwnerType()) || !ownerId.equals(row.getOwnerId()))
                throw new IllegalStateException("原取消报名当前范围不一致");
        }
        return rows;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public RegistrationCancellationPayments captureCancellationPayments(Long ownerType, Long ownerId) {
        requireCancellationTransaction();
        if (ownerId == null || ownerId <= 0
                || (!Long.valueOf(1).equals(ownerType) && !Long.valueOf(2).equals(ownerType))) throw retryLater();
        Long topicId = ownerId, memberId = null, clubId = null;
        if (Long.valueOf(2).equals(ownerType)) {
            var activity = cmsActivityMapper.selectCmsActivityByIdForUpdate(ownerId);
            if (activity == null || !ownerId.equals(activity.getId())) throw retryLater();
            topicId = activity.getTopicId(); memberId = activity.getMemberId(); clubId = activity.getClubId();
        }
        List<Order> orders = new ArrayList<>();
        for (CmsRegistration row : currentCancellationRows(ownerType, ownerId)) {
            if (!Integer.valueOf(1).equals(row.getPaymentStatus())
                    || !Integer.valueOf(1).equals(row.getRegistrationStatus())) continue;
            if (StringUtils.isBlank(row.getRegistrationNo()) || row.getMemberId() == null
                    || row.getWechatPaymentAmount() == null || row.getWechatPaymentAmount().signum() <= 0) throw retryLater();
            orders.add(new Order(Identity.of(row), null, false));
        }
        return new RegistrationCancellationPayments(
                List.of(new Scope(ownerType, ownerId, topicId, memberId, clubId, orders)));
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public RegistrationCancellationPayments observeCancellationPayments(RegistrationCancellationPayments captured) {
        requireNoCancellationTransaction();
        if (captured == null) throw retryLater();
        java.util.Set<String> scopeKeys = new java.util.HashSet<>();
        java.util.Set<Long> orderIds = new java.util.HashSet<>();
        for (Scope scope : captured.scopes()) {
            if (scope.ownerId() == null || scope.ownerId() <= 0
                    || (!Long.valueOf(1).equals(scope.ownerType()) && !Long.valueOf(2).equals(scope.ownerType()))
                    || !scopeKeys.add(scope.ownerType() + ":" + scope.ownerId())) throw retryLater();
            for (Order order : scope.orders()) {
                Identity original = order.original();
                if (original.id() == null || original.id() <= 0 || !orderIds.add(original.id())
                        || !scope.ownerType().equals(original.ownerType()) || !scope.ownerId().equals(original.ownerId())
                        || original.memberId() == null || original.memberId() <= 0
                        || StringUtils.isBlank(original.registrationNo()) || original.wechatAmount() == null
                        || original.wechatAmount().signum() <= 0 || order.providerFacts() != null || order.confirmedClosed()) {
                    throw retryLater();
                }
            }
        }
        List<Scope> queried = new ArrayList<>();
        // Validate every original query before the first irreversible external close.
        for (Scope scope : captured.scopes()) {
            List<Order> orders = new ArrayList<>();
            for (Order order : scope.orders()) {
                requireNoCancellationTransaction();
                if (wechatPayService == null || !wechatPayService.isReady()) throw retryLater();
                java.util.Map<String,String> facts;
                try { facts = wechatPayService.queryOrderStatus(order.original().registrationNo()); }
                catch (Exception unavailable) {
                    alarmCancelFailure("取消活动前支付查证未确认", "ownerType=" + scope.ownerType()
                            + " ownerId=" + scope.ownerId() + " registrationId=" + order.original().id());
                    throw retryLater();
                }
                if (facts == null || !order.original().registrationNo().equals(facts.get("out_trade_no"))
                        || StringUtils.isBlank(facts.get("wx_out_trade_no"))) throw retryLater();
                String state = facts.get("trade_state");
                if ("SUCCESS".equalsIgnoreCase(state)) {
                    requireOriginalObservedSuccess(order.original(), facts);
                } else if (!"CLOSED".equalsIgnoreCase(state) && !"NOTPAY".equalsIgnoreCase(state)
                        && !"USERPAYING".equalsIgnoreCase(state) && !"PAYERROR".equalsIgnoreCase(state)) {
                    throw retryLater();
                }
                orders.add(new Order(order.original(), facts, "CLOSED".equalsIgnoreCase(state)));
            }
            queried.add(new Scope(scope.ownerType(), scope.ownerId(), scope.topicId(),
                    scope.activityMemberId(), scope.clubId(), orders));
        }
        // Repeat the existing success admission under current original scope locks before
        // closing any other provider attempt. This transaction never applies payment effects.
        cancellationTransaction(() -> {
            for (Scope scope : queried) {
                requireCurrentCancellationScope(scope);
                List<CmsRegistration> current = requireCurrentCancellationOrders(scope);
                for (CmsRegistration row : current) {
                    if (Integer.valueOf(0).equals(row.getPaymentStatus())
                            && Integer.valueOf(1).equals(row.getRegistrationStatus())
                            && verifiedPaymentFacts.hasConfirmedExternalSuccess(row.getRegistrationNo())) throw retryLater();
                }
                for (Order order : scope.orders()) {
                    if ("SUCCESS".equalsIgnoreCase(order.providerFacts().get("trade_state"))) {
                        if (!"SUCCESS".equals(cmsRegistrationService
                                .checkConfirmedWechatPaymentWithinTransaction(order.providerFacts()))) throw retryLater();
                    } else if (verifiedPaymentFacts.hasConfirmedExternalSuccess(
                            order.original().registrationNo())) throw retryLater();
                }
            }
            return null;
        });
        List<Scope> observed = new ArrayList<>();
        for (Scope scope : queried) {
            List<Order> orders = new ArrayList<>();
            for (Order order : scope.orders()) {
                if (!"SUCCESS".equalsIgnoreCase(order.providerFacts().get("trade_state")) && !order.confirmedClosed()) {
                    requireNoCancellationTransaction();
                    boolean closed;
                    try {
                        closed = wechatPayService.closePaymentAttempt(order.original().registrationNo(),
                                order.providerFacts().get("wx_out_trade_no"));
                    } catch (RestoreDispatchGuard.Blocked paused) { throw paused; }
                    catch (Exception uncertain) { closed = false; }
                    if (!closed) {
                        alarmCancelFailure("取消活动前原支付关闭未确认", "registrationId=" + order.original().id()
                                + ";原身份保留,未执行本地回补,请按原号查证");
                        throw retryLater().setDetailMessage("CANCELLATION_PROVIDER_CLOSE_UNKNOWN");
                    }
                    orders.add(new Order(order.original(), order.providerFacts(), true));
                } else orders.add(order);
            }
            observed.add(new Scope(scope.ownerType(), scope.ownerId(), scope.topicId(),
                    scope.activityMemberId(), scope.clubId(), orders));
        }
        return new RegistrationCancellationPayments(observed);
    }

    private void requireOriginalObservedSuccess(Identity original, java.util.Map<String,String> facts) {
        var reference = new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(
                Long.parseLong(facts.get("verified_fact_id")), facts.get("verified_fact_hash"));
        var fact = verifiedPaymentFacts.requireVerified(reference).facts();
        if (fact.kind() != com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT
                || !original.registrationNo().equals(fact.businessOutTradeNo())
                || !fact.wxOutTradeNo().equals(facts.get("wx_out_trade_no"))
                || !fact.transactionId().equals(facts.get("transaction_id"))
                || !fact.mchid().equals(facts.get("mchid")) || !fact.appid().equals(facts.get("appid"))
                || !fact.tradeType().equals(facts.get("trade_type")) || !fact.currency().equals(facts.get("currency"))
                || fact.totalFen() != Long.parseLong(facts.get("total"))
                || fact.totalFen() != original.wechatAmount().movePointRight(2).longValueExact()) throw retryLater();
    }

    private void requireCurrentCancellationScope(Scope scope) {
        requireCancellationTransaction();
        if (scope.topicId() != null) {
            CmsTopic topic = cmsTopicMapper.selectCmsTopicByIdForUpdate(scope.topicId());
            if (topic == null || !scope.topicId().equals(topic.getId())) throw retryLater();
        }
        if (Long.valueOf(2).equals(scope.ownerType())) {
            var activity = cmsActivityMapper.selectCmsActivityByIdForUpdate(scope.ownerId());
            if (activity == null || !java.util.Objects.equals(scope.topicId(), activity.getTopicId())
                    || !java.util.Objects.equals(scope.activityMemberId(), activity.getMemberId())
                    || !java.util.Objects.equals(scope.clubId(), activity.getClubId())) throw retryLater();
        } else if (!java.util.Objects.equals(scope.ownerId(), scope.topicId())) throw retryLater();
    }

    private List<CmsRegistration> requireCurrentCancellationOrders(Scope scope) {
        List<CmsRegistration> current = currentCancellationRows(scope.ownerType(), scope.ownerId());
        java.util.Set<Long> originalIds = new java.util.HashSet<>();
        for (Order order : scope.orders()) {
            if (!originalIds.add(order.original().id()) || order.providerFacts() == null) throw retryLater();
            CmsRegistration row = current.stream().filter(candidate -> order.original().id().equals(candidate.getId()))
                    .findFirst().orElseThrow(this::retryLater);
            if (!order.original().matches(row)) throw retryLater();
        }
        for (CmsRegistration row : current) {
            if (Integer.valueOf(1).equals(row.getPaymentStatus()) && Integer.valueOf(1).equals(row.getRegistrationStatus())
                    && !originalIds.contains(row.getId())) throw retryLater();
        }
        return current;
    }

    private void settlePayingRegistrationsBeforeCancel(Long ownerType, Long ownerId, Long actorMemberId,
                                                        RegistrationCancellationPayments observed) {
        if (observed == null) throw retryLater();
        Scope scope = observed.requireScope(ownerType, ownerId);
        requireCurrentCancellationScope(scope);
        List<CmsRegistration> current = requireCurrentCancellationOrders(scope);
        for (Order order : scope.orders()) {
            CmsRegistration row = current.stream().filter(candidate -> order.original().id().equals(candidate.getId()))
                    .findFirst().orElseThrow(this::retryLater);
            if ("SUCCESS".equalsIgnoreCase(order.providerFacts().get("trade_state"))) {
                requireOriginalObservedSuccess(order.original(), order.providerFacts());
                if (!"SUCCESS".equalsIgnoreCase(cmsRegistrationService
                        .applyConfirmedWechatPaymentWithinTransaction(order.providerFacts()))) throw retryLater();
            } else {
                if (!order.confirmedClosed()) throw retryLater();
                if (Integer.valueOf(1).equals(row.getPaymentStatus()) && Integer.valueOf(1).equals(row.getRegistrationStatus())) {
                    closeLocally(row, actorMemberId, order.providerFacts().get("wx_out_trade_no"));
                } else if (!(Integer.valueOf(2).equals(row.getPaymentStatus()) && Integer.valueOf(2).equals(row.getRegistrationStatus()))
                        && !(Integer.valueOf(0).equals(row.getPaymentStatus()) && Integer.valueOf(1).equals(row.getRegistrationStatus()))
                        && !Integer.valueOf(3).equals(row.getRegistrationStatus())
                        && !Integer.valueOf(4).equals(row.getRegistrationStatus())) {
                    throw retryLater();
                }
            }
        }
    }

    /**
     * 关单 + 回补库存/积分/权益走与对账任务同一份实现(settlePayingOne),别在这再写一份:
     * 两份分叉过一次 —— 积分回补一边 CAS 一边读改写、权益失效原因一边 REFUND 一边 ORGANIZER_CANCEL。
     */
    private void closeLocally(CmsRegistration r, Long actorMemberId, String observedWxNo) {
        // 权益失效原因:枚举 javadoc 把「主办方取消活动」明确列在 ORGANIZER_CANCEL 定义里,与
        // 对账任务真超时同值;registration_status 用 finalStatus=3(已取消)与对账任务的 4(已过期)区分,
        // 这才是玩家看到「已取消」还是「已过期」的真实依据。
        if (!cmsRegistrationService.settleClosedPayingOne(r, observedWxNo, EntitlementInvalidReason.ORGANIZER_CANCEL,
                "activity-cancel:" + actorMemberId, 3)) {
            throw retryLater();
        }
    }

    /**
     * 微信侧无单时距「可按未支付关单」还差多久;<=0 表示已过宽限。
     *
     * <p>★ {@code payExpireTime} 为 null 一律当「宽限没过」拒掉,不能当 0 放行:
     * 没有过期时点就没有任何依据判「微信侧不可能再冒出这笔单」,放行 = 零宽限关单回补。
     * 对账任务侧同一情形是「保持不动、下轮再查」({@code OrderCloseTask} 的 null 判空分支),
     * 两边必须一致,否则 javadoc 里那句「复用对账任务口径」是假的。
     */
    private long graceRemainingMillis(Date payExpireTime) {
        if (payExpireTime == null) {
            return Long.MAX_VALUE;
        }
        return payExpireTime.getTime() + WechatOrderNotExistException.GRACE_MILLIS - System.currentTimeMillis();
    }

    /**
     * 清单 7:取消路径的查单/关单失败,主办方只看到 409、会自己重试,运营侧没有任何信号 ——
     * 微信侧真出了问题就是「主办方一直取消不了、没人知道」。与对账任务同一个 AlarmService。
     * 告警投递失败不得反过来阻断拒绝路径。
     */
    private void alarmCancelFailure(String title, String detail) {
        if (alarmService == null) {
            return;
        }
        try {
            alarmService.alarm(title, detail);
        } catch (Exception alarmFailure) {
            logger.error("取消活动 PAYING 单告警发送失败 {}", title, alarmFailure);
        }
    }

    private ServiceException retryLater() {
        return new ServiceException("有订单支付结果确认中，请稍后重试", HttpStatus.CONFLICT);
    }



    private PaidOrderSnapshot inspectPaidOrderForCancellation(
            CmsRegistration seed, Long ownerType, Long ownerId, Set<Long> paidRegistrationIds) {
        if (seed == null) {
            throw new ServiceException("父订单报名快照损坏，请人工核对", HttpStatus.CONFLICT);
        }
        if (StringUtils.isEmpty(seed.getParentOrderNo())) {
            if (seed.getId() == null || seed.getId() <= 0) {
                throw new ServiceException("单票报名快照损坏，请人工核对", HttpStatus.CONFLICT);
            }
            CmsRegistration locked = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(seed.getId());
            if (locked == null
                    || !seed.getId().equals(locked.getId())
                    || StringUtils.isEmpty(seed.getRegistrationNo())
                    || !seed.getRegistrationNo().equals(locked.getRegistrationNo())
                    || StringUtils.isNotEmpty(locked.getParentOrderNo())
                    || locked.getMemberId() == null || locked.getMemberId() <= 0
                    || !locked.getMemberId().equals(seed.getMemberId())
                    || !ownerType.equals(locked.getOwnerType())
                    || !locked.getOwnerType().equals(seed.getOwnerType())
                    || !ownerId.equals(locked.getOwnerId())
                    || !locked.getOwnerId().equals(seed.getOwnerId())
                    || !java.util.Objects.equals(locked.getTicketId(), seed.getTicketId())
                    || !Integer.valueOf(2).equals(locked.getPaymentStatus())
                    || !Integer.valueOf(2).equals(locked.getRegistrationStatus())
                    || !sameAmount(seed.getPayableAmount(), locked.getPayableAmount())
                    || !sameAmount(seed.getWechatPaymentAmount(), locked.getWechatPaymentAmount())
                    || paidRegistrationIds == null || !paidRegistrationIds.contains(locked.getId())) {
                throw new ServiceException("单票报名锁定快照损坏，请人工核对", HttpStatus.CONFLICT);
            }
            if (!Integer.valueOf(0).equals(locked.getVerificationStatus())
                    && !Integer.valueOf(1).equals(locked.getVerificationStatus())) {
                throw new ServiceException("单票核销状态不明确，请人工核对", HttpStatus.CONFLICT);
            }
            PaidOrderSnapshot single = new PaidOrderSnapshot();
            single.paidRows.add(locked);
            single.payer = locked;
            single.paidCashAmountFen = exactFen(locked);
            single.hasVerified = Integer.valueOf(1).equals(locked.getVerificationStatus());
            single.hasUnverified = !single.hasVerified;
            if (single.hasVerified) {
                single.verifiedIds.add(locked.getId());
            } else {
                single.unverifiedIds.add(locked.getId());
            }
            return single;
        }
        if (seed.getId() == null || seed.getId() <= 0
                || seed.getMemberId() == null || seed.getMemberId() <= 0
                || !ownerType.equals(seed.getOwnerType())
                || !ownerId.equals(seed.getOwnerId())
                || !Integer.valueOf(2).equals(seed.getPaymentStatus())
                || !Integer.valueOf(2).equals(seed.getRegistrationStatus())) {
            throw new ServiceException("父订单报名快照损坏，请人工核对", HttpStatus.CONFLICT);
        }
        List<CmsRegistration> rows = cmsRegistrationMapper.selectByParentOrderNoForUpdate(
                seed.getParentOrderNo(), ownerId);
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException("父订单报名快照为空，请刷新后重试", HttpStatus.CONFLICT);
        }
        PaidOrderSnapshot snapshot = new PaidOrderSnapshot();
        Set<Long> ids = new HashSet<>();
        for (CmsRegistration row : rows) {
            if (row == null || row.getId() == null || row.getId() <= 0
                    || row.getMemberId() == null || row.getMemberId() <= 0
                    || !ownerType.equals(row.getOwnerType())
                    || !ownerId.equals(row.getOwnerId())
                    || !seed.getParentOrderNo().equals(row.getParentOrderNo())
                    || !seed.getMemberId().equals(row.getMemberId())
                    || !Integer.valueOf(2).equals(row.getPaymentStatus())
                    || !Integer.valueOf(2).equals(row.getRegistrationStatus())
                    || !ids.add(row.getId())) {
                throw new ServiceException("父订单报名快照损坏，请人工核对", HttpStatus.CONFLICT);
            }
            if (!Integer.valueOf(0).equals(row.getVerificationStatus())
                    && !Integer.valueOf(1).equals(row.getVerificationStatus())) {
                throw new ServiceException("父订单核销状态不明确，请人工核对", HttpStatus.CONFLICT);
            }
            if (row.getPayableAmount() == null || row.getPayableAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new ServiceException("父订单付款快照损坏，请人工核对", HttpStatus.CONFLICT);
            }
            snapshot.paidRows.add(row);
            if (Integer.valueOf(1).equals(row.getVerificationStatus())) {
                snapshot.hasVerified = true;
                snapshot.verifiedIds.add(row.getId());
            } else {
                snapshot.hasUnverified = true;
                snapshot.unverifiedIds.add(row.getId());
            }
            if (row.getPayableAmount().compareTo(BigDecimal.ZERO) > 0) {
                if (snapshot.payer != null) {
                    throw new ServiceException("父订单存在多个支付主单，请人工核对", HttpStatus.CONFLICT);
                }
                snapshot.payer = row;
            }
        }
        if (!ids.contains(seed.getId())) {
            throw new ServiceException("父订单报名快照缺少当前报名，请人工核对", HttpStatus.CONFLICT);
        }
        if (snapshot.payer == null) {
            // 免费/全积分单没有正数 payable。建单合同把整单金额与积分落在最先插入的行；
            // 锁定查询按 id 排序，取该首行并继续用 exactFen 校验现金，而非跳过金额校验。
            snapshot.payer = rows.get(0);
        }
        if (paidRegistrationIds == null || !paidRegistrationIds.contains(snapshot.payer.getId())) {
            throw new ServiceException("原支付主单不在已支付锁定集合，请刷新后重试", HttpStatus.CONFLICT);
        }
        snapshot.paidCashAmountFen = exactFen(snapshot.payer);
        Collections.sort(snapshot.verifiedIds);
        Collections.sort(snapshot.unverifiedIds);
        return snapshot;
    }

    private boolean sameAmount(BigDecimal listed, BigDecimal locked) {
        return listed != null && locked != null
                && listed.compareTo(BigDecimal.ZERO) >= 0
                && locked.compareTo(BigDecimal.ZERO) >= 0
                && listed.compareTo(locked) == 0;
    }

    private long exactFen(CmsRegistration payer) {
        BigDecimal payable = payer.getPayableAmount();
        BigDecimal cash = payer.getWechatPaymentAmount();
        if (cash == null || cash.compareTo(BigDecimal.ZERO) < 0
                || payable == null || payable.compareTo(cash) != 0) {
            throw new ServiceException("支付主单现金快照不一致，请人工核对", HttpStatus.CONFLICT);
        }
        try {
            return cash.movePointRight(2).longValueExact();
        } catch (ArithmeticException invalidMoney) {
            throw new ServiceException("支付主单现金精度无效，请人工核对", HttpStatus.CONFLICT);
        }
    }

    /**
     * 同一 (活动, 父订单) 只冻结一张人工退款工单。FX-02 起玩家自助退款/主理人清退也会记工单,
     * 之后现场再核销一张、主办方取消整场时,锁内快照已和工单不同 —— 再 recordCase 必撞
     * 「同一父订单已有不同人工退款快照」409,整场取消被连带回滚。已有工单就沿用,人工评估时以现场事实为准。
     */
    private void recordManualRefundCaseOnce(com.chengyinhub.business.domain.CmsActivity activity,
            Long actorMemberId, String orderKey, PaidOrderSnapshot snapshot) {
        if (manualRefundCaseService.findCase(activity.getId(), orderKey) != null) {
            logger.warn("[退款转人工] 该单已有人工退款工单,沿用 activityId={} orderKey={}", activity.getId(), orderKey);
            return;
        }
        ActivityCancelManualRefundCase row = buildManualRefundCase(
                activity.getClubId(), activity.getMemberId(), actorMemberId, orderKey, snapshot);
        row.setActivityId(activity.getId());
        manualRefundCaseService.recordCase(row);
    }

    /** FX-05:主题级票同口径 —— 同一 (主题, 父订单) 只冻结一张工单,工单挂 topic_id。 */
    private void recordTopicManualRefundCaseOnce(CmsTopic topic, Long actorMemberId, String orderKey,
            PaidOrderSnapshot snapshot) {
        if (manualRefundCaseService.findTopicCase(topic.getId(), orderKey) != null) {
            logger.warn("[退款转人工] 该主题单已有人工退款工单,沿用 topicId={} orderKey={}", topic.getId(), orderKey);
            return;
        }
        ActivityCancelManualRefundCase row = buildManualRefundCase(
                topic.getClubId(), topic.getMemberId(), actorMemberId, orderKey, snapshot);
        row.setTopicId(topic.getId());
        manualRefundCaseService.recordCase(row);
    }

    private ActivityCancelManualRefundCase buildManualRefundCase(Long clubId, Long ownerMemberId,
            Long actorMemberId, String orderKey, PaidOrderSnapshot snapshot) {
        if (snapshot.payer == null || snapshot.verifiedIds.isEmpty()
                || snapshot.unverifiedIds.isEmpty() || snapshot.paidCashAmountFen == null) {
            throw new ServiceException("人工退款资格快照不完整，请人工核对", HttpStatus.CONFLICT);
        }
        ActivityCancelManualRefundCase row = new ActivityCancelManualRefundCase();
        row.setClubId(clubId);
        row.setOwnerMemberId(ownerMemberId);
        row.setActorMemberId(actorMemberId);
        row.setParentOrderNo(orderKey);
        row.setPayerRegistrationId(snapshot.payer.getId());
        row.setPayerMemberId(snapshot.payer.getMemberId());
        row.setPaidCashAmountFen(snapshot.paidCashAmountFen);
        try {
            row.setVerifiedRegistrationIdsJson(JSON.writeValueAsString(snapshot.verifiedIds));
            row.setUnverifiedRegistrationIdsJson(JSON.writeValueAsString(snapshot.unverifiedIds));
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new ServiceException("人工退款资格快照序列化失败", HttpStatus.CONFLICT);
        }
        row.setStatus(ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT);
        return row;
    }

    private boolean isParentSettled(Long ownerType, Long ownerId, com.chengyinhub.business.domain.CmsActivity act) {
        if (Long.valueOf(2L).equals(ownerType)) {
            return act != null && Integer.valueOf(1).equals(act.getIsSettlement());
        }
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(ownerId);
        return topic != null && Integer.valueOf(1).equals(topic.getIsSettlement());
    }

    private boolean anySettledDetail(List<CmsRegistration> rows) {
        for (CmsRegistration r : rows) {
            List<com.chengyinhub.business.domain.SettlementDetail> details =
                    settlementDetailMapper.selectSettledDetails("registration", r.getId());
            if (details == null) continue;
            for (com.chengyinhub.business.domain.SettlementDetail d : details) {
                if (Integer.valueOf(1).equals(d.getStatus())) return true;
            }
        }
        return false;
    }

    private static final class PaidOrderSnapshot {
        private final List<CmsRegistration> paidRows = new ArrayList<>();
        private final List<Long> verifiedIds = new ArrayList<>();
        private final List<Long> unverifiedIds = new ArrayList<>();
        private boolean hasVerified;
        private boolean hasUnverified;
        private CmsRegistration payer;
        private Long paidCashAmountFen;
    }

    /**
     * 退款粒度分叉:单票退自己那笔;多票(parent_order_no 非空)整单退。
     * 单票是当前生产唯一活着的路径(前端只发 ticketId 单数),此路径行为与改造前完全一致。
     */
    private boolean refundResolvingOrder(CmsRegistration r, String ticketName, String reason, Long actorMemberId) {
        return refundResolvingOrder(r, ticketName, reason, actorMemberId, false);
    }

    private boolean refundResolvingOrder(CmsRegistration r, String ticketName, String reason, Long actorMemberId,
                                         boolean supersedePending) {
        if (StringUtils.isNotEmpty(r.getParentOrderNo())) {
            return refundWholeOrder(r, ticketName, reason, actorMemberId, supersedePending);
        }
        refundOne(r, ticketName, reason);
        return true;
    }

    /**
     * 整单退(方案 §3.2「一次网关调用」):传入父订单内任意一条 registration,退掉整个父订单。
     *
     * 一笔支付 = 一笔微信交易(挂首单 out_trade_no,见 WechatPayServiceImpl:358 + 只有首单被
     * createOrder),故退款的自然粒度是整单:只对首单发起【一次】原路退款,金额=首单承载的汇总额;
     * 其余从单只标记/返积分/恢复库存 —— 它们的 registrationNo 从来不是 out_trade_no,调了必被微信拒
     * (受理失败 → §6.1 人工兜底队列 → 人工放行即在首单全额退之外再退一笔 = 双花,即 P0-A)。
     *
     * 首单判据 = payableAmount > 0(CmsRegistrationServiceImpl:370 首单=汇总 / :272-273 从单=0)。
     * ⚠️ 刻意不读 wechatPaymentAmount:从单那个值是【假现金】(:277 写了整张票价),整单退无视它,
     * 于是它退化成没人读的死数据 —— P0-A 由此从根消失,无需「从单清零」的外科修法
     * (那个修法会让 refundOne 走 markRefunded 静默退 0、丢人工兜底信号,已被 revert)。
     *
     * 微信约束(§3.2):单笔订单退款 1 次/分钟频控 + 单笔最多 50 次部分退 —— 每票各调必被拒。
     */
    private boolean refundWholeOrder(CmsRegistration anyMember, String ticketName, String reason, Long actorMemberId) {
        return refundWholeOrder(anyMember, ticketName, reason, actorMemberId, false);
    }

    private boolean refundWholeOrder(CmsRegistration anyMember, String ticketName, String reason, Long actorMemberId,
                                     boolean supersedePending) {
        RestoreDispatchGuard.requireDispatchAllowed();
        // 查询按 parent_order_no 过滤,anyMember 自己必在结果里 ⇒ 不会为空,无需兜底分支。
        List<CmsRegistration> order = cmsRegistrationMapper.selectByParentOrderNoForUpdate(anyMember.getParentOrderNo(), Long.valueOf(2L).equals(anyMember.getOwnerType()) ? anyMember.getOwnerId() : null);
        OrderRefundCash.assertOrderScope(order, anyMember);
        // FX-02(9-18 拍板 A,与场次取消同口径):仍已支付的票全核销 → 不退;部分核销 → 转人工工单
        // (整单退会把已用掉那张的钱一起退出去,拆票价又拆不出);都没核销 → 全额退。
        boolean hasVerified = false;
        boolean hasUnverified = false;
        for (CmsRegistration m : order) {
            if (!Integer.valueOf(2).equals(m.getPaymentStatus())) {
                continue;
            }
            if (Integer.valueOf(1).equals(m.getVerificationStatus())) {
                hasVerified = true;
            } else {
                hasUnverified = true;
            }
        }
        if (hasVerified) {
            if (!hasUnverified) {
                throw new RuntimeException("同单的票都已核销,不能退款");
            }
            recordPartiallyVerifiedOrderCase(anyMember, order, actorMemberId);
            return false;
        }
        // 只处理仍处于【已支付】的成员:同单可能已有条目被别的路径退过(如 §6.1 人工兜底放行),
        // 对其重复 restoreInventory 会把库存多回补一张(returnPoints 有 pointsReturned 幂等标记挡着,
        // restoreInventory 没有任何守卫)。
        List<CmsRegistration> refundable = new ArrayList<>();
        for (CmsRegistration m : order) {
            if (m.getPaymentStatus() != null && m.getPaymentStatus() == 2) {
                refundable.add(m);
            }
        }
        // 首单 = payableAmount > 0 的那条(至多一条);纯积分单则无,不调微信
        CmsRegistration payer = null;
        for (CmsRegistration m : refundable) {
            BigDecimal p = m.getPayableAmount();
            if (p == null || p.signum() < 0) {
                throw new ServiceException("父订单支付快照未确认，请通过后台退款工单核对后处理", HttpStatus.CONFLICT);
            }
            if (p.compareTo(BigDecimal.ZERO) > 0) {
                if (payer != null) {
                    throw new ServiceException("父订单现金支付主体不唯一，请通过后台退款工单核对后处理", HttpStatus.CONFLICT);
                }
                payer = m;
            }
        }
        // FX-01:首单承载整单汇总额。同单已有票被别的路径退过时,它的钱已经出去了 ——
        // 首单再按汇总额退 = 那张票退两遍。只退「汇总额 − 同单其余票已批准的退款额」。
        BigDecimal payerCash = null;
        if (payer != null) {
            payerCash = supersedePending
                    ? OrderRefundCash.remainingForSystemSupersession(order, payer, refundService)
                    : OrderRefundCash.remaining(order, payer, refundService);
            if (payerCash.signum() <= 0) {
                payer = null; // 整单现金已退完:首单也只领退款权/回库存/返积分,不再调微信
            }
        }
        if (refundable.isEmpty()) return true;
        if (supersedePending) {
            for (CmsRegistration row : refundable) refundService.supersedePendingRegistrationRefunds(row.getId(), reason);
        }
        // 非现金条目先领取报名退款权，避免纯积分整单并发时重复回库存/返积分。
        // 现金 payer 仍由固定退款号/registration 幂等键的退款申请链领取，失败时本事务整体回滚。
        List<CmsRegistration> claimed = new ArrayList<>();
        for (CmsRegistration m : refundable) {
            if (payer != null && m.getId().equals(payer.getId())) {
                claimed.add(m);
                continue;
            }
            if (cmsRegistrationMapper.claimRegistrationRefunded(
                    m.getId(), "auto-refund", new Date()) == 1) {
                claimed.add(m);
                continue;
            }
            // 1-43:上面的「同单无已核销」只是无锁预读。claim 落空 = 这张票在预读之后被并发核销或退款。
            // 首单退的是整单汇总额、拆不出单张票价,照退就会把这张票的钱一起退出去 ⇒ 整单拒绝、事务回滚
            // (与预读即发现已核销、以及场次取消「部分核销不自动退、留人工」同口径)。
            CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(m.getId());
            if (current != null && Integer.valueOf(1).equals(current.getVerificationStatus())
                    && !Integer.valueOf(4).equals(current.getPaymentStatus())) {
                throw new RuntimeException("同单存在已核销的票,不能整单退");
            }
            if (payer != null) {
                throw new ServiceException("整单退款状态已变化,请刷新后重试", HttpStatus.CONFLICT);
            }
        }
        // 只有 claim winner 才能通过库存回补闸；后续任意异常仍由外层事务一起回滚。
        for (CmsRegistration m : claimed) {
            restoreInventory(m);
        }
        if (payer != null) {
            refundOneAfterInventoryRestored(payer, payerCash, ticketName, reason); // 现金:只此一次网关调用
        }
        // 其余(含纯积分单)只做标记/返积分/恢复库存,绝不单独调微信
        for (CmsRegistration m : claimed) {
            if (payer != null && m.getId().equals(payer.getId())) {
                continue;
            }
            returnPoints(m);
            releasePerks(m);
            firstReviewPointsClawback.onRegistrationRefunded(m);
        }
        return true;
    }

    /**
     * FX-02:部分核销的整单冻结一张人工退款工单,口径与 cancelActivity 完全一致
     * (同一把锁 inspectPaidOrderForCancellation + 同一张 activity_cancel_manual_refund_case)。
     * 工单只冻结资格事实、不写 refund_application、不打款;金额由人工评估。
     * FX-05:主题票(owner_type=1)同口径,工单挂 topic_id(迁移 migration_manual_refund_case_topic_20260918)。
     */
    private void recordPartiallyVerifiedOrderCase(CmsRegistration seed, List<CmsRegistration> order, Long actorMemberId) {
        Set<Long> paidIds = new HashSet<>();
        for (CmsRegistration m : order) {
            if (Integer.valueOf(2).equals(m.getPaymentStatus())) {
                paidIds.add(m.getId());
            }
        }
        if (Long.valueOf(1L).equals(seed.getOwnerType())) {
            CmsTopic topic = cmsTopicMapper.selectCmsTopicById(seed.getOwnerId());
            if (topic == null || topic.getId() == null || topic.getMemberId() == null) {
                throw new ServiceException("主题不存在,请人工核对", HttpStatus.CONFLICT);
            }
            PaidOrderSnapshot snapshot = inspectPaidOrderForCancellation(seed, 1L, topic.getId(), paidIds);
            if (!snapshot.hasVerified || !snapshot.hasUnverified) {
                throw new ServiceException("订单核销状态已变化,请刷新后重试", HttpStatus.CONFLICT);
            }
            recordTopicManualRefundCaseOnce(topic, actorMemberId, seed.getParentOrderNo(), snapshot);
            logger.warn("[退款转人工] 主题票整单部分核销,未核销成员已记人工退款工单 topicId={} parentOrderNo={} actorMemberId={}",
                    topic.getId(), seed.getParentOrderNo(), actorMemberId);
            return;
        }
        if (!Long.valueOf(2L).equals(seed.getOwnerType())) {
            throw new ServiceException("父订单归属不明,请人工核对", HttpStatus.CONFLICT);
        }
        com.chengyinhub.business.domain.CmsActivity act = cmsActivityMapper.selectCmsActivityById(seed.getOwnerId());
        if (act == null || act.getId() == null) {
            throw new ServiceException("场次不存在,请人工核对", HttpStatus.CONFLICT);
        }
        PaidOrderSnapshot snapshot = inspectPaidOrderForCancellation(seed, 2L, act.getId(), paidIds);
        if (!snapshot.hasVerified || !snapshot.hasUnverified) {
            throw new ServiceException("订单核销状态已变化,请刷新后重试", HttpStatus.CONFLICT);
        }
        recordManualRefundCaseOnce(act, actorMemberId, seed.getParentOrderNo(), snapshot);
        logger.warn("[退款转人工] 整单部分核销,未核销成员已记人工退款工单 activityId={} parentOrderNo={} actorMemberId={}",
                act.getId(), seed.getParentOrderNo(), actorMemberId);
    }

    /** Existing current-ticket/paid-recipient wake source. The typed notice owner commits the original
     * BEFORE_START input and one irreversible WeChat attempt. UNKNOWN is only read, never resent.
     * Legacy positive Redis keys are read during cutover; no new TTL key replaces the durable receipt. */
    @Override
    public BeforeStartNotifyResult notifyBeforeStartBatch() {
        Date now = new Date();
        Date to = new Date(now.getTime() + 24L * 3600 * 1000); // original 24-hour admission window
        List<OmsTicket> tickets = omsTicketMapper.selectTeamFormedStartingSoon(now, to);
        int scanned = 0, skipped = 0, delivered = 0, failed = 0;
        if (tickets != null) {
            for (OmsTicket t : tickets) {
                List<CmsRegistration> regs = cmsRegistrationMapper.selectPaidByTicket(t.getId());
                if (regs == null) continue;
                for (CmsRegistration r : regs) {
                    scanned++;
                    String key = "team:beforenotify:" + t.getId() + ":" + r.getMemberId();
                    // Keep pre-cutover positive success keys; never manufacture a durable acceptance from them.
                    // New delivery identity/receipt lives permanently in the existing notice ledger, not this TTL.
                    if (redisCache.getCacheObject(key) != null) {
                        skipped++;
                        continue;
                    }
                    try {
                        com.chengyinhub.business.domain.dto.BeforeStartNotifyResult.Delivery result =
                                notifyService.notifyBeforeStart(t.getId(), r.getMemberId(), t.getName(), t.getStartTime());
                        if (result != null && result.deliveredNow()) delivered++;
                        else if (result != null && result.alreadyDelivered()) skipped++;
                        else failed++; // UNKNOWN/PENDING/not-ready: wake/read original; never assume not executed
                    } catch (Exception e) {
                        logger.warn("出发前提醒原意图待核实 ticketId={} memberId={} reason={}",
                                t.getId(), r.getMemberId(), e.getClass().getSimpleName());
                        failed++;
                    }
                }
            }
        }
        return new BeforeStartNotifyResult(scanned, skipped, delivered, failed);
    }

    private String fmt(Date d) {
        if (d == null) return "";
        // 微信 date 类型要求带年份(审查D §8 P1-1):原 "MM-dd HH:mm" 会被
        // WxSubscribeMsgServiceImpl 截成 "09-16 14:0" ⇒ 47003。这里直接产微信可用的完整形状。
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(d);
    }

    /** 单笔报名退款:结算冲销+标记已退,现金微信原路退(受理失败进§6.1人工兜底队列);积分返还;恢复库存;退款提醒 */
    private void refundOne(CmsRegistration r, String ticketName, String reason) {
        RestoreDispatchGuard.requireDispatchAllowed();
        CmsRegistration observed = r;
        r = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(observed.getId());
        if (r == null || !java.util.Objects.equals(observed.getMemberId(), r.getMemberId())
                || !java.util.Objects.equals(observed.getOwnerType(), r.getOwnerType())
                || !java.util.Objects.equals(observed.getOwnerId(), r.getOwnerId())
                || !java.util.Objects.equals(observed.getParentOrderNo(), r.getParentOrderNo())) {
            throw new ServiceException("原报名归属已变化，请刷新后重试", HttpStatus.CONFLICT);
        }
        if (!Integer.valueOf(2).equals(r.getPaymentStatus()) || Integer.valueOf(1).equals(r.getVerificationStatus())) {
            throw new ServiceException("报名支付或核销状态已变化，请按原退款结果核对", HttpStatus.CONFLICT);
        }
        BigDecimal cash = r.getWechatPaymentAmount();
        // 未知现金不是纯积分单；必须在取消资格、回库存和返积分之前拒绝不确定快照。
        // 核对沿已有后台退款工单，不推定 payableAmount 就是渠道实际收款，不新开出金路径。
        if (cash == null || cash.signum() < 0) {
            throw new ServiceException("支付现金快照未确认，请通过后台退款工单核对后处理", HttpStatus.CONFLICT);
        }
        boolean hasCash = cash.compareTo(BigDecimal.ZERO) > 0;
        if (hasCash) refundService.supersedePendingRegistrationRefunds(r.getId(), reason);
        // 纯积分单没有退款申请唯一键兜底，必须先领取报名退款权；loser 不得碰库存、积分或权益。
        if (!hasCash && cmsRegistrationMapper.claimRegistrationRefunded(
                r.getId(), "auto-refund", new Date()) == 0) {
            // 1-43:claim 现在也排除已核销 —— 输给并发核销时不能当成「已被别人退过」静默成功
            CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(r.getId());
            if (current != null && Integer.valueOf(1).equals(current.getVerificationStatus())
                    && !Integer.valueOf(4).equals(current.getPaymentStatus())) {
                throw new RuntimeException("已核销报名不可退款");
            }
            return;
        }
        if (!hasCash) {
            firstReviewPointsClawback.onRegistrationRefunded(r);   // FX-10:现金路由 markRegistrationRefunded 收回
        }
        // 外部打款不可回滚，先通过库存上限闸，避免“钱已退、库存校验才失败”。
        restoreInventory(r);
        refundOneAfterInventoryRestored(r, cash, ticketName, reason);
    }

    /** 单笔退款在库存已成功回补后的后续流程;cash = 本次要原路退的现金(整单退时已扣掉同单已退部分)。 */
    private void refundOneAfterInventoryRestored(CmsRegistration r, BigDecimal cash, String ticketName, String reason) {
        RestoreDispatchGuard.requireDispatchAllowed();
        boolean hasCash = cash != null && cash.compareTo(BigDecimal.ZERO) > 0;
        if (hasCash) {
            RefundApplication app = new RefundApplication();
            app.setSourceType("registration");
            app.setSourceId(r.getId());
            app.setRefundAmount(cash);
            app.setReason(reason);
            app.setRefundNo("RF" + r.getId());             // = out_refund_no,退款结果通知回调按此反查
            refundService.saveRefund(app);                 // 校验已支付 + 落退款单(回填 app.id)
            RefundReviewDTO rev = new RefundReviewDTO();
            rev.setId(app.getId());
            rev.setStatus(1);                              // 通过 → 结算冲销 + registration.payment_status=4
            rev.setReviewReason(reason);
            refundService.reviewRefund(rev, SYSTEM_OPERATOR);
            // 退款单已以 DISPATCH_PENDING 持久化；微信属于不可回滚外部副作用，必须等库存、报名、
            // 分账等同一事务全部提交后再派发。若提交后进程中断，RefundReconcileTask 会用同一退款号补派。
            dispatchPayoutAfterCommit(app.getId());
        }
        returnPoints(r);                                   // 积分返还
        releasePerks(r);                                   // [P0-15] 释放 ③ 权益容量
        // [P0-退款] 退款完成后置报名状态为已取消,前端才正确反映(隐藏退款按钮/不再显示"报名成功")。
        // 现金路 reviewRefund 的测试替身未必同步报名状态，显式保持旧行为；纯积分 claim 已同时置状态 3。
        if (hasCash) {
            CmsRegistration st = new CmsRegistration();
            st.setId(r.getId());
            st.setRegistrationStatus(3);
            st.setUpdateBy("auto-refund");
            st.setUpdateTime(new Date());
            cmsRegistrationMapper.updateCmsRegistration(st);
        }
        // 这里只完成取消受理；到账通知由退款 SUCCESS 或人工复核终态提交后发送。
    }

    /** 微信退款不能发生在本地可回滚事务内，防止后续一笔库存回补失败而本地回滚、钱已退。 */
    private void dispatchPayoutAfterCommit(final Long refundId) {
        if (refundId == null) {
            return;
        }
        Runnable dispatch = new Runnable() {
            @Override
            public void run() {
                try {
                    refundPayoutService.payoutAfterReview(refundId);
                } catch (Exception e) {
                    // 保留 DISPATCH_PENDING 给定时任务重试；此时本地事务已提交，不能再影响取消结果。
                    logger.error("退款提交后派发失败 refundId={}", refundId, e);
                }
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch.run();
            }
        });
    }

    private void returnPoints(CmsRegistration r) {
        boolean used = r.getPointUsed() != null && r.getPointUsed() != 0;
        if (!used) {
            return;
        }
        final int points;
        try {
            points = Math.toIntExact(r.getPointUsed());
            if (points < 0) throw new ArithmeticException("negative points");
        } catch (ArithmeticException invalidPoints) {
            throw new IllegalStateException("报名积分快照超出支持范围，请人工核对", invalidPoints);
        }
        // 先 CAS 积分回补 marker，winner 才能加积分；同一事务失败会连 marker 一起回滚。
        if (cmsRegistrationMapper.claimPointsReturn(r.getId(), "auto-refund", new Date()) == 0) {
            return;
        }
        if (umsMemberMapper.addPoints(r.getMemberId(), points) != 1) {
            throw new IllegalStateException("报名积分返还失败");
        }
    }

    private void restoreInventory(CmsRegistration r) {
        if (r.getTicketId() == null) {
            return;
        }
        long qty = (r.getOrderNum() != null && r.getOrderNum() > 0) ? r.getOrderNum() : 1L;
        // 原子回补,避免读改写并发丢失更新
        if (omsTicketMapper.restoreInventory(r.getTicketId(), qty) != 1) {
            throw new ServiceException("票务库存回补失败，请刷新后重试", HttpStatus.CONFLICT);
        }
    }

    /**
     * [P0-15] 退款释放 ③ 的权益容量,与 restoreInventory 1:1 镜像。
     *
     * <p><b>接在【审核通过】而不是【微信退款成功回调】</b>,三条理由:
     * <ol>
     *   <li><b>回调接线会永久漏放</b>:失败分支落 PAYOUT_PENDING_MANUAL → 由人工收尾,
     *       **这条路径永远收不到 SUCCESS 回调** ⇒ entitlement 永远停在 status=0 占着容量,而钱其实退了。</li>
     *   <li>「退了钱才放容量」的直觉在这里是反的:release 让 capacity 变大,放早了只会**多卖不会少卖**;
     *       而审核通过时 registration 已是终态(payment_status=4),打款失败只会进人工队列继续退,
     *       不存在「批了又反悔」需要回补 held 的情形。</li>
     *   <li>一致性:库存/积分/分账冲销全在审核通过时落,唯独 entitlement 等回调 = 同一笔退款里
     *       held 与 inventory 口径分裂。</li>
     * </ol>
     *
     * <p>天然幂等,可重复调用:releaseOnRefund 的 SQL 是 {@code where registration_id=? and status=0},
     * 第二次调返 0、不改行、不抛异常 ⇒ 三处落点重叠无害。
     * ⚠️ 返回 0 有两义(「没有可失效的」vs「已经失效过了」),**不能拿 0 当失败处理**。
     */
    private void releasePerks(CmsRegistration r) {
        if (r == null || r.getId() == null) {
            return;
        }
        perkLedgerService.releaseOnRefund(r.getId(), EntitlementInvalidReason.REFUND);
    }

}
