package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.annotation.CriticalPath;
import com.chengyinhub.common.observability.CriticalPathCode;
import com.chengyinhub.common.observability.CriticalPathEvents;
import com.chengyinhub.business.domain.AccountDeregistration;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.DeregistrationStatusVO;
import com.chengyinhub.business.mapper.AccountDeregistrationMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IAccountDeregistrationService;
import com.chengyinhub.business.service.ICashWithdrawService;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IUmsMemberWithdrawalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;

@Service
public class AccountDeregistrationServiceImpl implements IAccountDeregistrationService {
    private static final int COOLING_OFF_DAYS = 7;
    /** 注销转 FAILED 时给会员的站内系统消息;不承诺账号状态,解冻失败的行同样适用。 */
    private static final String FAILED_NOTICE = "你的账号注销申请未能完成，本次注销已终止。如仍需注销，可在设置中重新申请；如账号使用异常，请联系客服。";

    @Autowired
    private AccountDeregistrationMapper deregistrationMapper;

    /** [P0-14] 「这张票真的消费完了没有」的唯一真源 —— ③ 首次核销就置 verification_status,裸判会误放注销。 */
    @Autowired
    private com.chengyinhub.business.service.IPerkLedgerService perkLedgerService;
    @Autowired
    private UmsMemberMapper memberMapper;
    @Autowired
    private ClubMapper clubMapper;
    @Autowired
    private MmsMerchantMapper merchantMapper;
    @Autowired
    private OmsOrderMapper orderMapper;
    @Autowired
    private CmsRegistrationMapper registrationMapper;
    @Autowired
    private ICashWithdrawService cashWithdrawService;
    @Autowired
    private IUmsMemberWithdrawalService memberWithdrawalService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private AlarmService alarmService;
    @Autowired
    private IAppSessionRegistryService appSessionRegistryService;
    @Autowired
    private CommunityPostMapper communityPostMapper;
    @Autowired
    private com.chengyinhub.business.service.IRoamHangoutService roamHangoutService;
    @Autowired
    private IImService imService;

    @Override
    public DeregistrationStatusVO precheck(Long memberId) {
        UmsMember member = memberMapper.selectUmsMemberById(memberId);
        if (member == null || Integer.valueOf(2).equals(member.getDelFlag())) {
            return result("BLOCKED", null, Collections.singletonList("账号不存在或已注销"));
        }
        List<String> blockers = collectBlockers(member, memberId);
        DeregistrationStatusVO precheck = result(blockers.isEmpty() ? "ELIGIBLE" : "BLOCKED", null, blockers);
        if ("ELIGIBLE".equals(precheck.getStatus())) {
            precheck.setVerificationMethod(member.getPhone() != null && !member.getPhone().trim().isEmpty()
                    ? "SMS" : member.getWxOpenId() != null && !member.getWxOpenId().trim().isEmpty()
                    ? "WECHAT" : "UNAVAILABLE");
        }
        return precheck;
    }

    /**
     * 收集注销阻断项。apply 与 executeDue 共用:钱在动的账号(余额/在途提现/进行中订单/有效报名/商家俱乐部身份)不许注销。
     */
    private List<String> collectBlockers(UmsMember member, Long memberId) {
        List<String> blockers = new ArrayList<>();
        if (member.getBalance() != null && member.getBalance().compareTo(BigDecimal.ZERO) != 0) {
            blockers.add("账户余额未处理");
        }
        // 通道A(微信零钱):钱已扣、提交微信但未确认(或待人工),余额可能为 0 而漏判,必经资金域接口拦
        if (cashWithdrawService.hasPendingWithdraw(memberId)) {
            blockers.add("存在处理中的提现，请等到账后再申请注销");
        }
        // 通道B(银行卡人工打款):status=0 待审核 = 钱已扣、未打款,收款人姓名/银行卡是打款依据,
        // 余额被扣光后余额校验会漏判,若不拦 → 匿名化抹掉 PII → 款项卡死无法打款 + 账号无法申诉 = 资损
        if (memberWithdrawalService.hasPendingWithdrawal(memberId)) {
            blockers.add("存在待审核的银行提现，请等到账后再申请注销");
        }
        MmsMerchant ownedMerchant = new MmsMerchant();
        ownedMerchant.setMemberId(memberId);
        ownedMerchant.setDelFlag(0);
        // 默认角色只是当前视角；仍持有经营主体的账号不能注销。
        if ("merchant".equals(member.getRole())
                || !merchantMapper.selectMmsMerchantList(ownedMerchant).isEmpty()) {
            blockers.add("持有商家身份，请先处理商家业务");
        }
        if ("club".equals(member.getRole())
                || !clubMapper.selectClubsByMemberId(memberId).isEmpty()) {
            blockers.add("持有俱乐部身份，请先处理俱乐部业务");
        }
        if (deregistrationMapper.countUnsettledRefunds(memberId) > 0) {
            blockers.add("存在处理中的退款，请等到账后再申请注销");
        }
        if (hasActiveOrder(memberId)) {
            blockers.add("存在进行中订单或退款");
        }
        if (hasActiveRegistration(memberId)) {
            blockers.add("存在待支付或未核销的有效报名");
        }
        return blockers;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CriticalPath(value = CriticalPathCode.CP_DEREGISTRATION_FAILED, op = "apply")
    public DeregistrationStatusVO apply(Long memberId) {
        DeregistrationStatusVO precheck = precheck(memberId);
        if (!"ELIGIBLE".equals(precheck.getStatus())) {
            return precheck;
        }
        AccountDeregistration latest = deregistrationMapper.selectLatestByMemberId(memberId);
        if (latest != null && "PENDING".equals(latest.getStatus())) {
            return result("PENDING", latest.getExecuteAfter(), new ArrayList<String>());
        }
        Date now = new Date();
        Date executeAfter = plusDays(now, COOLING_OFF_DAYS);
        AccountDeregistration deregistration = new AccountDeregistration();
        deregistration.setMemberId(memberId);
        deregistration.setStatus("PENDING");
        deregistration.setApplyTime(now);
        deregistration.setExecuteAfter(executeAfter);
        deregistration.setCreateTime(now);
        String mutationId = appSessionRegistryService.beginMemberMutation(memberId);
        try {
            deregistrationMapper.insert(deregistration);
            deregistrationMapper.markMemberPending(memberId, now, executeAfter);
            appSessionRegistryService.revokeAll(memberId);
            appSessionRegistryService.finishMemberMutationAfterTransaction(memberId, mutationId);
            return result("PENDING", executeAfter, new ArrayList<String>());
        } catch (RuntimeException e) {
            finishFailedMutation(memberId, mutationId, e);
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CriticalPath(value = CriticalPathCode.CP_DEREGISTRATION_FAILED, op = "cancel")
    public DeregistrationStatusVO cancel(Long memberId) {
        AccountDeregistration latest = deregistrationMapper.selectLatestByMemberId(memberId);
        if (latest == null || !"PENDING".equals(latest.getStatus())) {
            throw new IllegalStateException("当前没有可撤销的注销申请");
        }
        // 冷静期满到批次真正执行之间仍允许撤销:executeOne 持行锁并只处理 PENDING,
        // cancelPending 也只改 PENDING,两者互斥;已被批次执行的申请在下面返回 0 并拒绝。
        Date now = new Date();
        if (deregistrationMapper.cancelPending(memberId, now) == 0) {
            throw new IllegalStateException("注销申请状态已变化，请刷新后重试");
        }
        deregistrationMapper.markMemberNormal(memberId);
        return result("CANCELLED", null, new ArrayList<String>());
    }

    @Override
    public DeregistrationStatusVO status(Long memberId) {
        AccountDeregistration latest = deregistrationMapper.selectLatestByMemberId(memberId);
        if (latest == null) {
            return result("NORMAL", null, new ArrayList<String>());
        }
        return result(latest.getStatus(), latest.getExecuteAfter(), new ArrayList<String>());
    }

    /**
     * 冷静期到期执行。★逐账号独立事务 + 执行期重校验 blocker:
     * 冷静期内可能有结算分润/退款到账,若不重校验就匿名化(del_flag=2 + wx_open_id=null 无法再登录),
     * 余额会永久冻死 = 资损。故执行期再命中 blocker → 中止注销、解冻账号、转人工告警,绝不匿名化。
     * 单账号失败不牵连整批(独立事务 + try/catch continue)。
     */
    @Override
    public int executeDue(Date now) {
        List<AccountDeregistration> dueList = deregistrationMapper.selectDue(now);
        if (dueList == null || dueList.isEmpty()) {
            return 0;
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        int completed = 0;
        for (AccountDeregistration deregistration : dueList) {
            List<Long> failedMembers = new ArrayList<>();
            try {
                Boolean done = tx.execute(s -> executeOne(deregistration, now, failedMembers));
                if (Boolean.TRUE.equals(done)) {
                    completed++;
                }
            } catch (Exception e) {
                CriticalPathEvents.failure(CriticalPathCode.CP_DEREGISTRATION_FAILED, "execute", e);
                failedMembers.clear();
                boolean failed;
                try {
                    failed = Boolean.TRUE.equals(tx.execute(status -> {
                        AccountDeregistration current = deregistrationMapper.selectByIdForUpdate(deregistration.getId());
                        if (current != null && "PENDING".equals(current.getStatus())) {
                            if (deregistrationMapper.markFailed(current.getId(),
                                    "执行异常:" + e.getClass().getSimpleName(), now) != 1) {
                                throw new IllegalStateException("注销失败状态未确认");
                            }
                            deregistrationMapper.markMemberNormal(current.getMemberId());
                            return true;
                        }
                        return false;
                    }));
                } catch (RuntimeException recoveryFailure) {
                    alarmService.alarm("注销异常解冻失败", "id=" + deregistration.getId()
                            + " member=" + deregistration.getMemberId()
                            + " cause=" + recoveryFailure.getClass().getSimpleName());
                    failed = markFailedWithoutUnfreeze(tx, deregistration, e, now);
                }
                if (failed) {
                    failedMembers.add(deregistration.getMemberId());
                }
                alarmService.alarm("注销执行异常", "id=" + deregistration.getId() + " member=" + deregistration.getMemberId() + " err=" + safe(e.getMessage()));
            }
            // 事务已提交后再发,站内信失败不影响 FAILED 结论
            for (Long memberId : failedMembers) {
                notifyFailed(memberId);
            }
        }
        return completed;
    }

    /**
     * 解冻事务失败时的兜底:单独事务只把申请移出 PENDING,保证它不再占用 selectDue 的 limit 批次。
     * 拦截器按申请状态放行,FAILED 后会员即可正常使用;ums_member 上残留的冻结字段由告警转人工核对。
     */
    private boolean markFailedWithoutUnfreeze(TransactionTemplate tx, AccountDeregistration deregistration,
            Exception cause, Date now) {
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                AccountDeregistration current = deregistrationMapper.selectByIdForUpdate(deregistration.getId());
                return current != null && "PENDING".equals(current.getStatus())
                        && deregistrationMapper.markFailed(current.getId(),
                        "执行异常且解冻失败:" + cause.getClass().getSimpleName(), now) == 1;
            }));
        } catch (RuntimeException fallbackFailure) {
            alarmService.alarm("注销失败申请移出批次失败", "id=" + deregistration.getId()
                    + " member=" + deregistration.getMemberId()
                    + " cause=" + fallbackFailure.getClass().getSimpleName());
            return false;
        }
    }

    /** best-effort:复用系统会话站内信告知会员本次注销未完成,失败只告警不抛。 */
    private void notifyFailed(Long memberId) {
        try {
            Long id = imService.sendSystem(memberId, ImMessage.MSG_TEXT, FAILED_NOTICE, null);
            if (id == null || id <= 0) {
                alarmService.alarm("注销失败站内信未送达", "member=" + memberId + " type=NO_MESSAGE_ID");
            }
        } catch (RuntimeException failure) {
            try {
                alarmService.alarm("注销失败站内信未送达", "member=" + memberId
                        + " type=" + failure.getClass().getSimpleName());
            } catch (RuntimeException ignored) {
                // 告警通道自身失败不再外抛,不能影响后续申请
            }
        }
    }

    /** 单账号注销执行(在独立事务内)。返回 true=已匿名化完成,false=阻断/跳过。 */
    private boolean executeOne(AccountDeregistration deregistration, Date now, List<Long> failedMembers) {
        // 批次查询不持锁；进入独立事务后重新确认，避免撤销成功后仍按旧快照匿名化。
        deregistration = deregistrationMapper.selectByIdForUpdate(deregistration.getId());
        if (deregistration == null || !"PENDING".equals(deregistration.getStatus())
                || deregistration.getExecuteAfter() == null || deregistration.getExecuteAfter().after(now)) {
            return false;
        }
        Long memberId = deregistration.getMemberId();
        UmsMember member = memberMapper.selectUmsMemberById(memberId);
        if (member == null) {
            deregistrationMapper.deleteMemberAddresses(memberId);
            return completeDeregistration(deregistration.getId(), now); // 账号已不存在,直接结案
        }
        // 已注销过(幂等):直接结案
        if (Integer.valueOf(2).equals(member.getDelFlag())) {
            deregistrationMapper.deleteMemberAddresses(memberId);
            return completeDeregistration(deregistration.getId(), now);
        }
        // ★执行期重校验:冷静期内可能有资金/订单变化
        List<String> blockers = collectBlockers(member, memberId);
        if (!blockers.isEmpty()) {
            // 有资金/在途 → 中止注销、解冻账号、转人工,绝不匿名化(避免余额冻死)
            deregistrationMapper.markMemberNormal(memberId);
            if (deregistrationMapper.markFailed(deregistration.getId(), "执行期阻断:" + String.join(";", blockers), now) == 1) {
                failedMembers.add(memberId);
            }
            alarmService.alarm("注销执行阻断", "member=" + memberId + " 冷静期内出现:" + blockers);
            return false;
        }
        String mutationId = appSessionRegistryService.beginMemberMutation(memberId);
        try {
            deregistrationMapper.anonymizeMember(memberId);
            deregistrationMapper.anonymizeRegistrations(memberId);
            deregistrationMapper.anonymizeOrders(memberId);
            // ★清最高敏感度 PII:提现姓名/银行卡/手机号(明文+密文+掩码)+ openid
            deregistrationMapper.anonymizeMemberWithdrawal(memberId);
            deregistrationMapper.anonymizeCashWithdraw(memberId);
            deregistrationMapper.anonymizeMerchantLead(memberId);
            communityPostMapper.prepareAccountPostsForErasure(memberId, now);
            communityPostMapper.scrubAccountPostRevisions(memberId);
            communityPostMapper.scrubAccountAppeals(memberId);
            communityPostMapper.scrubAccountGovernanceContent(memberId);
            communityPostMapper.anonymizeAccountAppeals(memberId);
            communityPostMapper.scrubAccountOutbox(memberId);
            communityPostMapper.orphanAccountMedia(memberId, plusDays(now, 30), now);
            communityPostMapper.enqueueAccountErasureJobs(memberId,
                    "account-deregister-" + deregistration.getId(), now);
            communityPostMapper.anonymizeAccountPostAuthors(memberId, now);
            communityPostMapper.anonymizeAccountComments(memberId, now);
            communityPostMapper.clearAccountReplyTargets(memberId, now);
            communityPostMapper.deleteAccountPostActions(memberId);
            communityPostMapper.deleteAccountCommentActions(memberId);
            communityPostMapper.deleteAccountMutes(memberId);
            communityPostMapper.deleteAccountNotificationActors(memberId);
            communityPostMapper.reconcileNotificationsAfterActorErasure(memberId, now);
            communityPostMapper.deleteAccountNotifications(memberId);
            communityPostMapper.deleteAccountNotificationPreferences(memberId);
            communityPostMapper.deleteAccountGovernanceState(memberId);
            communityPostMapper.deleteAccountCommandReceipts(memberId);
            communityPostMapper.deleteAccountGuidelineAcks(memberId);
            communityPostMapper.deleteAccountMentions(memberId);
            // ★清发布者实名登记(姓名+身份证号):注销后人已经不是人了,这行密文没有留存理由
            deregistrationMapper.deleteMemberIdentity(memberId);
            deregistrationMapper.deleteMemberAddresses(memberId);
            roamHangoutService.closeAllByMember(memberId);   // 附近的局:局主没了,局转关、群转关
            appSessionRegistryService.revokeAll(memberId);
            appSessionRegistryService.finishMemberMutationAfterTransaction(memberId, mutationId);
            return completeDeregistration(deregistration.getId(), now);
        } catch (RuntimeException e) {
            finishFailedMutation(memberId, mutationId, e);
            throw e;
        }
    }

    private boolean completeDeregistration(Long id, Date now) {
        if (deregistrationMapper.complete(id, now) != 1) {
            throw new IllegalStateException("注销完成状态未保存");
        }
        return true;
    }

    private void finishFailedMutation(Long memberId, String mutationId, RuntimeException failure) {
        try {
            appSessionRegistryService.finishMemberMutation(memberId, mutationId);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private boolean hasActiveOrder(Long memberId) {
        OmsOrder query = new OmsOrder();
        query.setMemberId(memberId);
        List<OmsOrder> orders = orderMapper.selectOmsOrderList(query);
        if (orders == null) return false;
        for (OmsOrder order : orders) {
            Long status = order.getStatus();
            Long aftersaleStatus = order.getAftersaleStatus();
            if ((status != null && status >= 0 && status <= 3)
                    || (aftersaleStatus != null && (aftersaleStatus == 2 || aftersaleStatus == 3))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasActiveRegistration(Long memberId) {
        CmsRegistration query = new CmsRegistration();
        query.setMemberId(memberId);
        List<CmsRegistration> registrations = registrationMapper.selectCmsRegistrationList(query);
        if (registrations == null) return false;
        Date now = new Date();
        for (CmsRegistration registration : registrations) {
            Integer paymentStatus = registration.getPaymentStatus();
            if (paymentStatus != null && (paymentStatus == 0 || paymentStatus == 1)) {
                return true;
            }
            // ⚠️ [P0-14] 「还没消费完」不能直接看 verification_status —— ③ **首次核销就置位**
            // (退款闸 RefundPolicy.verifiedVerdict 的前提),它对 ③ 只表示「被核销过至少一次」。
            // 裸判 verification_status != 1 会让「核销 1 章、还欠 4 章」的 ③ 玩家被当成已消费
            // ⇒ 允许他注销账号带走 4 章已付未兑的权益,而退款同时被锁死 = 钱货两空,零告警。
            // ⇒ 判据收进 isTicketFullyConsumed(与 issueDynCode 同一真源,别在这重写)。
            if (paymentStatus != null && paymentStatus == 2
                    && !isConsumed(registration)
                    && (registration.getExpiresAt() == null || registration.getExpiresAt().after(now))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这张票消费完了没有。
     *
     * <p>⚠️ 先用 purchase_kind 短路:只有 ③ 的 verification_status 有「被核销过 ≠ 消费完」这个歧义,
     * ①② 直接看 verification_status 即可。不短路的话,本方法在 hasActiveRegistration 的循环里
     * 会对**每一张已核销的 ① 票**都多查一次 entitlement —— 一个老玩家几百张单就是几百次白查。
     * ⚠️ purchase_kind 为 NULL(存量未回填 / 后台手工建单)⇒ 走 isTicketFullyConsumed 兜底,
     * 它内部会查一次;fail-safe 方向:多查一次总好过误判成已消费而放行注销。
     */
    private boolean isConsumed(CmsRegistration registration) {
        Integer pk = registration.getPurchaseKind();
        if (pk != null && pk != com.chengyinhub.common.constant.PurchaseKind.EXPLORE_PASS) {
            return Integer.valueOf(1).equals(registration.getVerificationStatus());
        }
        return perkLedgerService.isTicketFullyConsumed(
                registration.getVerificationStatus(), registration.getId());
    }

    private Date plusDays(Date now, int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now);
        calendar.add(Calendar.DAY_OF_YEAR, days);
        return calendar.getTime();
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private DeregistrationStatusVO result(String status, Date executeAfter, List<String> blockers) {
        DeregistrationStatusVO result = new DeregistrationStatusVO();
        result.setStatus(status);
        result.setExecuteAfter(executeAfter);
        result.setBlockers(blockers);
        return result;
    }
}
