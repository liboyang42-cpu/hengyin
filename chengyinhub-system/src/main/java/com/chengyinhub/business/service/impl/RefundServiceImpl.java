package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.annotation.CriticalPath;
import com.chengyinhub.common.observability.CriticalPathCode;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.WxPaymentCallbackInbox;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.IWxPaymentCallbackInboxService;
import com.chengyinhub.business.util.OrderRefundCash;
import com.chengyinhub.common.constant.EntitlementInvalidReason;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

@Service
public class RefundServiceImpl implements IRefundService {
    /** 系统自动退款的审核人(与 TeamGroupServiceImpl.SYSTEM_OPERATOR 同值)。 */
    private static final Long SYSTEM_OPERATOR = 0L;

    private static final Logger logger = LoggerFactory.getLogger(RefundServiceImpl.class);

    /** refund_application.payout_receipt_url 的列宽,见 migration_refund_manual_fallback_20260714.sql。 */
    private static final int RECEIPT_URL_MAX_LENGTH = 512;

    /**
     * 人工打款租约时长(分钟)。只把「多长」传给 SQL,过期时刻由 MySQL 自己 NOW() 算 ——
     * 绝不在 JVM 侧算好 Date 再传进去比。payout_lease_expires_at 会被 SQL 拿去跟 now() 比
     * (claimManualPayout / takeoverExpiredManualPayout / completeManualPayout 三处),
     * 一旦 JVM 与 MySQL 时区不一致,偏差以小时计而窗口只有 15 分钟:
     *   - 正向偏差 ⇒ 在途租约恒判过期,B 可以接管 A 正在打款的单 ⇒ 同一笔线下重复打款;
     *   - 反向偏差 ⇒ completeManualPayout 恒判过期,合法处理人永远点不动。
     * 与 RefundPayoutServiceImpl 的派发租约同源同治。
     */
    private static final long MANUAL_LEASE_MINUTES = 15L;

    /**
     * [R6] 人工退款回执允许的绝对地址 host 白名单(逗号分隔,精确匹配)。默认只放自有站点域名 ——
     * 生产对象存储/CDN 域名未知,不猜:没配就只能用站内 /profile 路径或本域链接,其余一律拒。
     */
    @Value("${chengyin.refund.payout-receipt.allowed-hosts:www.chengyinhub.com}")
    private String payoutReceiptAllowedHosts;

    @Autowired
    private RefundApplicationMapper refundApplicationMapper;

    /** [P0-15] ③ 退款释放权益容量。后台审核路不经 TeamGroupServiceImpl,必须自己接。 */
    @Autowired
    private com.chengyinhub.business.service.IPerkLedgerService perkLedgerService;

    /** FX-10:退款后收回这张票换来的首评积分,见 markRegistrationRefunded。 */
    @Autowired
    private FirstReviewPointsClawbackService firstReviewPointsClawback;

    /** [赏金] 退款致构成激活的核销失效时追回赏金,见 markRegistrationRefunded。 */
    @Autowired
    private MerchantReferralBountyService merchantReferralBountyService;

    @Autowired
    private CmsRegistrationMapper cmsRegistrationMapper;

    @Autowired
    private CmsActivityMapper cmsActivityMapper;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    /** [P0-1] 主题路的真实放款凭据在 coop_settlement.status,退款冲销前要问它打没打过款。 */
    @Autowired
    private CoopSettlementMapper coopSettlementMapper;

    /** [P0-1] 主题分 coop / 存量两条放款路,分流条件与 settleTopic:547 同源 —— 有没有生效合作单。 */
    @Autowired
    private CoopOrderMapper coopOrderMapper;

    @Autowired
    private SettlementDetailMapper settlementDetailMapper;

    /** 客诉期提现闸:结算时涉诉暂扣的报名单,商家并没拿到这单的钱。 */
    @Autowired
    private com.chengyinhub.business.mapper.ActivitySettlementHoldMapper activitySettlementHoldMapper;

    /** 冲销扣不动的差额记欠款(9-16 拍板第 2 条),不再只打告警。 */
    @Autowired
    private com.chengyinhub.business.service.support.SettlementDebtLedger settlementDebtLedger;

    @Autowired
    private UmsMemberMapper umsMemberMapper;

    @Autowired
    private UmsMemberBalanceDetailMapper umsMemberBalanceDetailMapper;

    @Autowired
    private com.chengyinhub.business.service.INotifyService notifyService;

    @Autowired
    private AlarmService alarmService;

    /** P1:退款回调先按 notify_hash 落 inbox 去重;状态推进仍由下方既有 CAS 负责。 */
    @Autowired
    private IWxPaymentCallbackInboxService wxPaymentCallbackInboxService;

    /**
     * best-effort 告警,失败不影响主流程 —— 但不能静默:资金类告警(待人工/接管/ABNORMAL/冲销失败)
     * 投递挂了若无痕迹,线上等于"没人知道有单待处理"。
     *
     * <p>兜底层次:{@link AlarmService#deliver} 自己已接住 webhook 投递异常并落 [ALARM] 日志,
     * 所以这里接到的是它之外的残余路径(host 解析、响应解析、日志出口本身)。留痕不重复它的职责,
     * 只记稳定的告警 title 与原始异常。
     *
     * <p>⚠️ 这里不写 msg,是为了不让退款单据正文经本类二次外泄;但 AlarmService 会无条件
     * 打 {@code [ALARM] title - detail},msg 本来就在日志里 —— 别把这条当成脱敏措施。
     */
    private void alarm(String title, String msg) {
        try {
            alarmService.alarm(title, msg);
        } catch (Exception e) {
            logger.error("[ALARM] 退款告警投递失败 title={}", title, e);
        }
    }

    @Override
    public List<RefundApplication> selectRefundList(RefundApplication query) {
        return refundApplicationMapper.selectRefundApplicationList(query);
    }

    @Override
    public RefundApplication getRefundById(Long id) {
        return refundApplicationMapper.selectRefundApplicationById(id);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    @CriticalPath(value = CriticalPathCode.CP_REFUND_FAILED, op = "payout_result")
    public void markPayoutResult(Long refundId, String leaseToken, boolean wxAccepted, String remark) {
        if (refundId == null || leaseToken == null || leaseToken.trim().isEmpty()) {
            throw new IllegalArgumentException("退款派发结果缺少 lease fence");
        }
        int payout = wxAccepted ? RefundApplication.PAYOUT_PROCESSING : RefundApplication.PAYOUT_PENDING_MANUAL;
        int rows = refundApplicationMapper.updatePayoutResultWithLease(refundId, leaseToken, payout, remark);
        if (rows == 0) {
            RefundApplication current = refundApplicationMapper.selectRefundApplicationById(refundId);
            boolean acceptedAlreadySucceeded = wxAccepted && current != null
                    && Integer.valueOf(RefundApplication.PAYOUT_SUCCESS).equals(current.getPayoutStatus());
            if (current == null || (!Integer.valueOf(payout).equals(current.getPayoutStatus())
                    && !acceptedAlreadySucceeded)) {
                throw new IllegalStateException("退款派发结果未持久化 refundId=" + refundId);
            }
        }
        if (!wxAccepted) {
            // 进待人工队列即告警,不靠人肉刷页面发现(防用户退款被静默拖欠)
            alarm("退款待人工", "原路退款受理失败 refundId=" + refundId + " remark=" + remark);
        }
    }

    @Override
    public List<RefundApplication> selectManualPayoutQueue() {
        return refundApplicationMapper.selectManualPayoutQueue();
    }

    @Override
    public String claimManualPayout(Long refundId, Long handlerId) {
        if (refundId == null || handlerId == null) {
            throw new IllegalArgumentException("退款单与处理人不能为空");
        }
        String leaseToken = UUID.randomUUID().toString();
        return refundApplicationMapper.claimManualPayout(
                refundId, handlerId, leaseToken, MANUAL_LEASE_MINUTES) == 1 ? leaseToken : null;
    }

    @Override
    public boolean releaseManualPayoutClaim(Long refundId, Long handlerId, String leaseToken) {
        if (refundId == null || handlerId == null || StringUtils.isBlank(leaseToken)) {
            return false;
        }
        return refundApplicationMapper.releaseManualPayoutClaim(refundId, handlerId, leaseToken) == 1;
    }

    @Override
    public String takeoverExpiredManualPayout(Long refundId, Long handlerId) {
        if (refundId == null || handlerId == null) {
            throw new IllegalArgumentException("退款单与处理人不能为空");
        }
        RefundApplication current = refundApplicationMapper.selectRefundApplicationById(refundId);
        if (current == null || current.getPayoutLeaseOwner() == null
                || handlerId.equals(current.getPayoutLeaseOwner())) {
            return null;
        }
        String leaseToken = UUID.randomUUID().toString();
        // 前一个 owner 必须留在备注里:接管等于把他的 token 作废,冲突要看得见而不是悄悄换人。
        String remark = "人工处理权已由 " + handlerId + " 接管(原处理人 " + current.getPayoutLeaseOwner()
                + ",租约已过期)";
        if (refundApplicationMapper.takeoverExpiredManualPayout(
                refundId, handlerId, leaseToken, MANUAL_LEASE_MINUTES, remark) != 1) {
            return null;
        }
        alarm("退款处理权接管", "refundId=" + refundId + " " + remark);
        return leaseToken;
    }

    @Override
    public int handleManualPayout(Long refundId, String method, String receiptUrl, String remark,
                                  Long handlerId, String leaseToken) {
        if (refundId == null || handlerId == null) {
            throw new IllegalArgumentException("退款单与处理人不能为空");
        }
        if (method == null || method.trim().isEmpty()) {
            throw new IllegalArgumentException("请选择人工退款方式");
        }
        if (StringUtils.isBlank(leaseToken)) {
            throw new IllegalArgumentException("人工退款处理权已失效，请重新领取");
        }
        // [R6] 回执链接会被另一名财务在人工队列里直接点开,校验必须在落库之前、且只认服务端合同。
        String safeReceiptUrl = normalizePayoutReceiptUrl(receiptUrl, payoutReceiptAllowedHosts);
        int rows = refundApplicationMapper.completeManualPayout(refundId, method, safeReceiptUrl, handlerId,
                DateUtils.getNowDate(), remark, leaseToken);
        if (rows == 1) {
            return 1;
        }
        RefundApplication current = refundApplicationMapper.selectRefundApplicationById(refundId);
        if (current != null && Integer.valueOf(RefundApplication.PAYOUT_MANUAL_HANDLED)
                .equals(current.getPayoutStatus())
                && handlerId.equals(current.getPayoutHandlerId())
                && leaseToken.equals(current.getPayoutLeaseToken())
                && sameText(method, current.getPayoutMethod())
                // 比落库值必须用归一化后的同一个值,否则首次写入 trim 过、重放读回来就永远对不上
                && sameText(safeReceiptUrl, current.getPayoutReceiptUrl())) {
            return 1;
        }
        return 0;
    }

    /**
     * [R6] 人工退款回执 URL 安全合同(fail-closed)。空白沿用既有语义(回执非必填);
     * 非空必须是①白名单 host 上的 HTTPS 绝对地址,或②单斜杠站内路径(本机上传回执落在 /profile/**)。
     * 拒绝 javascript:/data:/file: 等协议注入、明文 http、userinfo、显式端口(含 :443)、
     * scheme-relative //host、反斜杠、控制字符与路径穿越;host 只做精确匹配,不认通配符/后缀,
     * 防 www.x.com.evil.com 这类仿冒。
     */
    static String normalizePayoutReceiptUrl(String receiptUrl, String allowedHostsConfig) {
        if (StringUtils.isBlank(receiptUrl)) {
            return receiptUrl;
        }
        String candidate = receiptUrl.trim();
        if (candidate.length() > RECEIPT_URL_MAX_LENGTH) {
            // 列宽 512:超长在 MySQL 上要么报错要么被截断,资金留痕不能是半截链接
            throw new IllegalArgumentException("回执链接过长(上限 " + RECEIPT_URL_MAX_LENGTH + " 字符)");
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '\\') {
                throw new IllegalArgumentException("回执链接含非法字符(控制字符或反斜杠)");
            }
        }
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("回执链接不是合法 URL");
        }
        if (uri.isAbsolute()) {
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("回执链接只接受 HTTPS 绝对地址或站内路径,当前为 "
                        + uri.getScheme() + ":");
            }
            if (uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("回执链接不得携带 userinfo(user@host 会伪装真实域名)");
            }
            if (uri.getPort() != -1) {
                throw new IllegalArgumentException("回执链接不得携带端口(含标准 443 端口的显式写法)");
            }
            String host = uri.getHost();
            if (StringUtils.isBlank(host)) {
                throw new IllegalArgumentException("回执链接缺少合法域名");
            }
            if (!parseAllowedHosts(allowedHostsConfig).contains(host.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("回执链接域名不在白名单内:" + host
                        + "(自有对象存储请先配置 chengyin.refund.payout-receipt.allowed-hosts)");
            }
            return candidate;
        }
        if (!candidate.startsWith("/") || candidate.startsWith("//")) {
            throw new IllegalArgumentException("回执链接必须是 HTTPS 绝对地址,或以单个 / 开头的站内路径");
        }
        if (uri.getAuthority() != null) {
            throw new IllegalArgumentException("回执站内路径不得带域名");
        }
        // 用解码后的 path 判穿越,%2e%2e 这类编码形态一并盖住
        String path = uri.getPath();
        if (path == null || path.contains("..")) {
            throw new IllegalArgumentException("回执站内路径不得包含路径穿越");
        }
        return candidate;
    }

    private static Set<String> parseAllowedHosts(String allowedHostsConfig) {
        Set<String> hosts = new HashSet<String>();
        if (StringUtils.isBlank(allowedHostsConfig)) {
            return hosts;
        }
        for (String host : allowedHostsConfig.split(",")) {
            String trimmed = host.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                hosts.add(trimmed);
            }
        }
        return hosts;
    }

    private boolean sameText(String expected, String actual) {
        return expected == null ? actual == null : expected.equals(actual);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int verifyManualPayout(Long refundId, Long reviewer2Id) {
        if (refundId == null || reviewer2Id == null) {
            throw new IllegalArgumentException("退款单与复核人不能为空");
        }
        RefundApplication refund = refundApplicationMapper.selectRefundApplicationById(refundId);
        if (refund == null) {
            throw new IllegalArgumentException("退款单不存在");
        }
        // 双人复核:复核人须≠处理人(应用层显式拦,mapper WHERE 再兜一层防绕过)
        if (reviewer2Id.equals(refund.getPayoutHandlerId())) {
            throw new IllegalStateException("复核人不能与处理人为同一人(双人复核)");
        }
        int rows = refundApplicationMapper.markPayoutVerified(refundId, reviewer2Id, DateUtils.getNowDate());
        if (rows == 1) {
            notifyRefundAfterCommit(refund);
        }
        return rows;
    }

    @Override
    @CriticalPath(value = CriticalPathCode.CP_REFUND_FAILED, op = "notify")
    public boolean handleRefundNotify(Map<String, String> facts) {
        if (facts == null) {
            return false;
        }
        String outRefundNo = facts.get("outRefundNo");
        String refundStatus = facts.get("status");
        // 非本系统退款号/未知号(保证金由验签入口单独路由，不能在这里丢弃):回 true 让微信 ACK,避免重试风暴
        if (outRefundNo == null || outRefundNo.trim().isEmpty()) {
            return true;
        }
        RefundApplication refund = refundApplicationMapper.selectByRefundNo(outRefundNo);
        if (refund == null) {
            // 本系统退款号("RF"+regId)查不到 → 极可能是外层批量事务尚未提交(read-your-writes),
            // 回 false 让微信按重试策略稍后重投,那时行已提交才不丢失失败通知;不属于 RF/RDEP 的其他退款号才 ACK。
            return !outRefundNo.startsWith("RF") && !outRefundNo.startsWith("RDEP");
        }
        if (!matchesRefundFacts(refund, facts)) {
            return false;
        }
        // P1:回调先落 inbox 去重(表 wx_payment_callback_inbox,notify_hash 唯一键);
        // 重投只多一次 insert ignore,不影响下方 ACK 与状态推进语义。
        // 同一 notify 已登记为另一笔业务 = 身份冲突(register 内已告警):回 FAIL,不推进状态。
        try {
            wxPaymentCallbackInboxService.register(
                    "REFUND:" + outRefundNo + ":" + (refundStatus == null ? "" : refundStatus),
                    WxPaymentCallbackInbox.BIZ_TYPE_REFUND, refund.getId(), refundStatus);
        } catch (IWxPaymentCallbackInboxService.IdentityConflictException conflict) {
            return false;
        }
        // updatePayoutResult 的 WHERE 只允许从待派发/处理中/待人工(5/0/2)置位,
        // 不会覆盖人工已处理(3/4)的成果
        if ("SUCCESS".equalsIgnoreCase(refundStatus)) {
            return updatePayoutTerminal(refund, RefundApplication.PAYOUT_SUCCESS,
                    "原路退款成功(回调确认到账)");
        } else if ("ABNORMAL".equalsIgnoreCase(refundStatus)) {
            if (!updatePayoutTerminal(refund, RefundApplication.PAYOUT_PENDING_MANUAL,
                    "原路退款异常(原卡+零钱均注销),待人工异常退款")) {
                return false;
            }
            alarm("退款待人工", "原路退款 ABNORMAL refundId=" + refund.getId() + " refundNo=" + outRefundNo);
        } else if ("CLOSED".equalsIgnoreCase(refundStatus)) {
            if (!updatePayoutTerminal(refund, RefundApplication.PAYOUT_PENDING_MANUAL,
                    "退款超时关闭(出资账户余额不足),待人工")) {
                return false;
            }
            alarm("退款待人工", "原路退款 CLOSED refundId=" + refund.getId() + " refundNo=" + outRefundNo);
        }
        // PROCESSING:保持处理中,不动(定时对账任务兜底拉取终态)
        return true;
    }

    private boolean matchesRefundFacts(RefundApplication refund, Map<String, String> facts) {
        if (!"registration".equals(refund.getSourceType()) || refund.getSourceId() == null) {
            return false;
        }
        CmsRegistration registration = cmsRegistrationMapper.selectCmsRegistrationById(refund.getSourceId());
        if (registration == null || StringUtils.isBlank(registration.getRegistrationNo())
                || !registration.getRegistrationNo().equals(facts.get("outTradeNo"))
                || !"CNY".equals(facts.get("currency"))) {
            return false;
        }
        try {
            long expectedRefund = refund.getRefundAmount().movePointRight(2).longValueExact();
            long expectedTotal = registration.getWechatPaymentAmount().movePointRight(2).longValueExact();
            return expectedRefund > 0 && expectedTotal > 0 && expectedRefund <= expectedTotal
                    && expectedRefund == Long.parseLong(facts.get("refund"))
                    && expectedTotal == Long.parseLong(facts.get("total"));
        } catch (ArithmeticException | NumberFormatException | NullPointerException invalidAmount) {
            return false;
        }
    }

    private boolean updatePayoutTerminal(RefundApplication refund, int expectedStatus, String message) {
        if (refundApplicationMapper.updatePayoutResult(refund.getId(), expectedStatus, message) == 1) {
            if (expectedStatus == RefundApplication.PAYOUT_SUCCESS) notifyRefundAfterCommit(refund);
            return true;
        }
        RefundApplication current = refundApplicationMapper.selectByRefundNo(refund.getRefundNo());
        return current != null && Integer.valueOf(expectedStatus).equals(current.getPayoutStatus());
    }

    /** 仅首次到账终态调用；沿用 best-effort 通道，不把发送失败伪装成送达或回滚资金。 */
    private void notifyRefundAfterCommit(RefundApplication refund) {
        final Long refundId = refund.getId();
        final Long memberId = refund.getMemberId();
        final BigDecimal amount = refund.getRefundAmount();
        Runnable notify = () -> {
            if (memberId == null || memberId <= 0 || amount == null || amount.signum() <= 0) {
                logger.error("退款已完成但通知收件人或金额无效 refundId={}", refundId);
                return;
            }
            try {
                if (!notifyService.notifyRefund(memberId, "报名退款", amount.toPlainString())) {
                    logger.warn("退款到账通知未送达 refundId={} memberId={}", refundId, memberId);
                }
            } catch (Exception e) {
                logger.error("退款到账通知异常 refundId={} memberId={}", refundId, memberId, e);
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
    @Transactional(rollbackFor = Exception.class)
    public int saveRefund(RefundApplication refund) {
        if (refund == null || refund.getSourceType() == null || refund.getSourceId() == null
                || refund.getRefundAmount() == null || refund.getRefundAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("source and refundAmount are required");
        }
        // DECIMAL(18,2) must not silently round the request before the provider's exact-cent guard.
        // Trailing zeroes (1.230) are valid; fractional cents (1.001) must fail before any insert.
        try {
            refund.getRefundAmount().setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException fractionalCent) {
            throw new IllegalArgumentException("退款金额必须精确到分，不能包含不足一分的金额", fractionalCent);
        }
        // FX-14:只建单不改单。带 id 原先会走 updateRefundApplication 直接改已有单的 status/金额/审核人;
        // 新单自带 status=1 则绕过 reviewRefund 的双人闸,被 RefundReconcileTask 当成待派发真打款。
        // 审核态只能由 reviewRefund 流转,派发态只能由派发编排流转。
        if (refund.getId() != null) {
            throw new IllegalArgumentException("退款单只能新增,不能通过新增接口修改已有退款单");
        }
        refund.setStatus(0);
        refund.setDelFlag(0);
        // 自动退款在同一事务里先落一条可重试的派发记录；事务提交后才允许调用外部微信。
        // 不能再依赖库默认的 PROCESSING(0)：若提交后进程在 afterCommit 前中断，0 只能查状态、
        // 无法区分“微信已受理”与“请求尚未发出”，会把退款永久遗失。
        refund.setPayoutStatus(RefundApplication.PAYOUT_DISPATCH_PENDING);
        enrichRegistrationRefund(refund);
        // [K块补丁] uk_refund_idempotency 建在裸列上,MySQL UNIQUE 对多个 NULL 不去重。
        // enrichRegistrationRefund 只兜底 sourceType=registration 的一条路径;后台运营端点
        // (RefundController.add,受 business:refund:review 权限保护)允许直接传任意 RefundApplication,
        // 请求体不带 idempotencyKey 时会落 NULL,唯一约束对这条路径形同虚设。
        // 这里在所有来源上统一兜底,风格与 enrichRegistrationRefund 的 "registration:"+id 一致,
        // 但因为无来源方专属的天然去重键(不像 registration 有 1:1 的 sourceId),兜底值本身带 UUID,
        // 即"生成一个从不重复的默认值"而非"生成一个能去重的默认值"——它不能让唯一约束对同一笔业务请求生效去重,
        // 只保证列不再是 NULL、约束本身重新起作用(挡住"NULL 完全绕过约束"这个缺口),
        // 且值本身仍可追溯到来源(sourceType+sourceId 前缀)。
        if (refund.getIdempotencyKey() == null || refund.getIdempotencyKey().trim().isEmpty()) {
            refund.setIdempotencyKey(refund.getSourceType() + ":" + refund.getSourceId() + ":" + UUID.randomUUID());
        }
        // HF-04(A-32): 退款额不得超过实付额,防客户端传超额 refundAmount。
        // [方案 §3.3 应用层先校验] 原条件含 `paidAmount > 0`,导致【实付 0 的单整条短路】——
        // 而实付 0 恰恰是最该拦的情形:多票从单 payableAmount=0,enrichRegistrationRefund
        // 会把 paidAmount 填成 0 ⇒ 闸门失效 ⇒ 任意金额的从单退款单都能落库。
        // 0 与正数一并纳入比较。注:refundAmount<=0 已被上方前置闸拒,故此处无需 0 值特例。
        if (refund.getPaidAmount() != null
                && refund.getRefundAmount().compareTo(refund.getPaidAmount()) > 0) {
            throw new IllegalArgumentException("退款金额不得超过实付金额");
        }
        int rows = refundApplicationMapper.insertRefundApplication(refund);
        // 后续审批、渠道派发及恢复均依赖原退款单ID；缺写入回执不能继续取消或资金副作用。
        // 包括实际已插入但生成键未回填的情况：抛错让同一事务整体回滚，不能重建另一退款意图。
        if (rows != 1 || refund.getId() == null || refund.getId() <= 0) {
            throw new IllegalStateException("退款申请写入回执异常，请核对原申请后重试");
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveBackOfficeRefund(RefundApplication refund) {
        ensureWithinOrderCash(refund);
        return saveRefund(refund);
    }

    /**
     * FX-04:多票订单的现金只挂在首单上(payableAmount = 整单汇总额)。后台给首单建单/批单时,
     * 与小程序整单退(FX-01)同一口径:只能退同单还没退、也没核销的票 —— 额度 = 汇总额 − 同单其余票已批准退款额
     * ({@link OrderRefundCash#remaining}),其余票挂着待审退款单 / 已退查不到退款单 ⇒ 409。
     * 同单仍已支付的票里有已核销的 ⇒ 单票实付拆不出,不猜金额,409 转人工工单。
     * 只管首单;从单、单票订单维持原有闸(saveRefund 的「退款额 ≤ 实付」)。
     */
    private void ensureWithinOrderCash(RefundApplication refund) {
        if (refund == null || !"registration".equals(refund.getSourceType())
                || refund.getSourceId() == null || refund.getRefundAmount() == null) {
            return;   // 入参合同由 saveRefund 统一拒
        }
        CmsRegistration payer = lockRefundRegistration(refund.getSourceId());
        if (payer == null || StringUtils.isEmpty(payer.getParentOrderNo())
                || payer.getPayableAmount() == null || payer.getPayableAmount().signum() <= 0) {
            return;
        }
        List<CmsRegistration> order = cmsRegistrationMapper.selectByParentOrderNoForUpdate(payer.getParentOrderNo(), Long.valueOf(2L).equals(payer.getOwnerType()) ? payer.getOwnerId() : null);
        if (order == null || order.isEmpty()) {
            throw new ServiceException("父订单报名快照为空,请人工核对", HttpStatus.CONFLICT);
        }
        for (CmsRegistration m : order) {
            if (Integer.valueOf(2).equals(m.getPaymentStatus()) && Integer.valueOf(1).equals(m.getVerificationStatus())) {
                throw new ServiceException("同单有票已核销,不能按整单退款,请走人工退款工单", HttpStatus.CONFLICT);
            }
        }
        BigDecimal remaining = OrderRefundCash.remaining(order, payer, this, refund.getId());
        if (refund.getRefundAmount().compareTo(remaining) > 0) {
            throw new ServiceException("退款金额超过同单尚未退款的金额(剩余 " + remaining.max(BigDecimal.ZERO).toPlainString() + ")",
                    HttpStatus.CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CriticalPath(value = CriticalPathCode.CP_REFUND_FAILED, op = "review")
    public int reviewRefund(RefundReviewDTO request, Long operatorId) {
        if (request == null || request.getId() == null) {
            throw new IllegalArgumentException("refund id is required");
        }
        if (!Integer.valueOf(1).equals(request.getStatus()) && !Integer.valueOf(2).equals(request.getStatus())) {
            throw new IllegalArgumentException("refund status must be approved or rejected");
        }
        RefundApplication refund = refundApplicationMapper.selectRefundApplicationById(request.getId());
        if (refund == null) {
            throw new IllegalArgumentException("refund not found");
        }
        // A1-1(P0)maker≠checker:后台建单人不得自审自批(照抄人工兜底 reviewer2≠handler 的双人纪律)。
        // 仅拦「批准」(status=1,会触发真金打款);建单人为空=C端/系统发起,不受约束。
        if (Integer.valueOf(1).equals(request.getStatus())
                && refund.getCreatedByUserId() != null
                && refund.getCreatedByUserId().equals(operatorId)) {
            throw new IllegalStateException("审批人不能与申请人为同一人(退款双人复核)");
        }
        CmsRegistration registration = loadRegistration(refund);
        if (registration != null) {
            for (RefundApplication persisted : registrationRefundHistory(registration.getId())) {
                if (refund.getId().equals(persisted.getId())) {
                    if (!Integer.valueOf(0).equals(persisted.getStatus())) {
                        if (request.getStatus().equals(persisted.getStatus())) return 0;
                        throw new IllegalStateException("退款申请已处理，不能改变审核结果");
                    }
                    refund = persisted;
                    break;
                }
            }
        } else if (refund.getStatus() != null && !Integer.valueOf(0).equals(refund.getStatus())) {
            if (request.getStatus().equals(refund.getStatus())) return 0;
            throw new IllegalStateException("退款申请已处理，不能改变审核结果");
        }
        if (Integer.valueOf(1).equals(request.getStatus())) {
            if (registration != null) {
                List<RefundApplication> history = registrationRefundHistory(registration.getId());
                if (Integer.valueOf(4).equals(registration.getPaymentStatus())
                        && approvedRefundCash(history, refund.getId()).signum() == 0) {
                    throw new IllegalStateException("该报名已退款且缺少剩余额凭证，请人工核实");
                }
                BigDecimal paid = firstNonNull(registration.getPayableAmount(), registration.getTotalAmount(), registration.getOriginalAmount());
                if (paid == null || paid.signum() < 0) throw new IllegalStateException("报名实付快照缺失，请人工核实");
                assertRemainingRefundCash(refund, paid, history, false);
            }
            ensureRefundable(registration);
            // FX-04:人工批准的单(含 A1-1 之前建、created_by 为空的旧后台单),建单到审批之间同单别的票
            // 可能被退/被核销 ⇒ 批准(真打款)前按同一口径再核一次。
            // 系统整单退(operator=0,TeamGroupServiceImpl.SYSTEM_OPERATOR)同事务内已按 FX-01 算好金额、
            // 且先 claim 了从单(已退无退款单),再核会误判 409,不走这里。
            if (!SYSTEM_OPERATOR.equals(operatorId)) {
                ensureWithinOrderCash(refund);
            }
        }
        int rows = refundApplicationMapper.updateRefundStatusIfPending(
                request.getId(), request.getStatus(), operatorId, DateUtils.getNowDate(), request.getReviewReason());
        if (rows <= 0 || !Integer.valueOf(1).equals(request.getStatus())) {
            return rows;
        }

        markRegistrationRefunded(registration);
        // P0-B 多方分账退款漏冲:一笔支付拆 merchant/platform/creator 多条明细,必须逐条负向冲销。
        // 旧 selectSettledDetail 的 `order by id desc limit 1` 只冲最大 id 一条(商家 id 最小 → 永远漏冲),
        // 改用 selectSettledDetails 取全部已结算明细逐条冲销;退款比例按订单口径统一算一次再套到每条。
        List<SettlementDetail> settledList = settlementDetailMapper.selectSettledDetails(
                refund.getSourceType(), refund.getSourceId());
        if (settledList != null && !settledList.isEmpty()) {
            settledList = new ArrayList<>(settledList);
            settledList.sort(Comparator.comparing(SettlementDetail::getReceiverId,
                    Comparator.nullsFirst(Long::compareTo)).thenComparing(SettlementDetail::getId,
                    Comparator.nullsFirst(Long::compareTo)));
            BigDecimal totalGross = BigDecimal.ZERO;
            for (SettlementDetail d : settledList) {
                totalGross = totalGross.add(defaultZero(d.getGrossAmount()));
            }
            BigDecimal ratio = refundRatio(refund, totalGross);
            // 父级打款态只解析一次,再按收款方逐条套用(见 resolveSystem1PaidCheck)
            Predicate<SettlementDetail> paidBySystem1 = resolveSystem1PaidCheck(registration, settledList);
            for (SettlementDetail settled : settledList) {
                boolean system1Paid = paidBySystem1.test(settled);
                // 历史活路径:系统1已真打款,但支付时落的明细仍停在 status=0。
                // 这时仍须冲销并扣回;反之,未真打款的 status=0 只作废,不能凭空造一笔已结算冲销。
                if (Integer.valueOf(1).equals(settled.getStatus()) || system1Paid) {
                    createSettlementAdjustment(refund, system1Paid, settled, ratio);
                }
            }
        }
        // 无论是否产生冲销,退款后都必须作废遗留 status=0,防后续批次把已退款报名重新纳入。
        settlementDetailMapper.voidPendingBySource(refund.getSourceType(), refund.getSourceId());
        return rows;
    }

    private CmsRegistration loadRegistration(RefundApplication refund) {
        if (!"registration".equals(refund.getSourceType())) {
            return null;
        }
        CmsRegistration registration = lockRefundRegistration(refund.getSourceId());
        if (registration == null) {
            throw new IllegalArgumentException("registration not found");
        }
        return registration;
    }

    private CmsRegistration lockRefundRegistration(Long registrationId) {
        CmsRegistration observed = cmsRegistrationMapper.selectCmsRegistrationById(registrationId);
        if (observed != null && StringUtils.isNotEmpty(observed.getParentOrderNo())) {
            List<CmsRegistration> order = cmsRegistrationMapper.selectByParentOrderNoForUpdate(observed.getParentOrderNo(), Long.valueOf(2L).equals(observed.getOwnerType()) ? observed.getOwnerId() : null);
            OrderRefundCash.assertOrderScope(order, observed);
            for (CmsRegistration registration : order) {
                if (registrationId.equals(registration.getId())) return registration;
            }
            throw new IllegalStateException("报名不在原父订单中");
        }
        return cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(registrationId);
    }

    private void enrichRegistrationRefund(RefundApplication refund) {
        if (!"registration".equals(refund.getSourceType()) || refund.getSourceId() == null) {
            return;
        }
        CmsRegistration registration = lockRefundRegistration(refund.getSourceId());
        if (registration == null) {
            throw new IllegalArgumentException("registration not found");
        }
        // 退款归属与收件人只认已锁定的原报名；后台填入其他买家/owner也不能改变资金来源身份。
        // 历史字段缺失时保留缺失，不让调用方替不存在的服务端事实补一个任意身份。
        refund.setOwnerType(registration.getOwnerType());
        refund.setOwnerId(registration.getOwnerId());
        refund.setMemberId(registration.getMemberId());
        // FX-06(9-18 拍板) / FX-14:实付只认服务端,客户端传的 paidAmount 一律覆盖 —— 原来只在为空/≤0 时覆盖,
        // 后台手填就能把从单(payable=0)的上限抬起来(抬高 paidAmount 即可绕过「退款额 ≤ 实付」)。
        // 多票首单的「同单还剩多少」另由 ensureWithinOrderCash(OrderRefundCash.remaining)在后台建单/批准时封顶。
        refund.setPaidAmount(firstNonNull(registration.getPayableAmount(), registration.getTotalAmount(), registration.getOriginalAmount()));
        refund.setMerchantId(resolveRegistrationMerchantId(registration));
        List<RefundApplication> history = registrationRefundHistory(registration.getId());
        BigDecimal approved = approvedRefundCash(history, null);
        BigDecimal paid = refund.getPaidAmount();
        if (paid == null || paid.signum() < 0) throw new IllegalStateException("报名实付快照缺失，请人工核实");
        boolean paidRegistration = Integer.valueOf(2).equals(registration.getPaymentStatus());
        boolean partiallyRefunded = Integer.valueOf(4).equals(registration.getPaymentStatus())
                && approved.signum() > 0 && approved.compareTo(paid) < 0;
        if (!paidRegistration && !partiallyRefunded) throw new IllegalStateException("仅已支付或尚未退足的报名可发起退款");
        assertRemainingRefundCash(refund, paid, history);
        long previousId = 0L;
        for (RefundApplication prior : history) {
            if (prior.getId() != null) previousId = Math.max(previousId, prior.getId());
        }
        String suffix = previousId == 0L ? "" : ":" + previousId;
        if (refund.getIdempotencyKey() == null || refund.getIdempotencyKey().trim().isEmpty()) {
            refund.setIdempotencyKey("registration:" + registration.getId() + suffix);
        }
        // 首次保留旧退款号；后续申请用已持久化前序单号区分，重试仍命中同一退款号。
        refund.setRefundNo("RF" + registration.getId() + (previousId == 0L ? "" : "-" + previousId));
    }

    @Override
    public List<RefundApplication> selectRegistrationHistoryForUpdate(Long registrationId) {
        return registrationRefundHistory(registrationId);
    }

    /**
     * F-L4-02：待审核申请占着同一报名，系统发起的全额退款会被「已有退款申请待审核」拒绝，
     * 取消活动的整段事务随之回滚。待审核单尚未审批、未派发打款，由全额退款取代后按 CAS 置驳回，
     * 已审批/已打款的单一概不动。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int supersedePendingRegistrationRefunds(Long registrationId, String reason) {
        if (registrationId == null) {
            return 0;
        }
        String note = "已由全额退款取代" + (StringUtils.isEmpty(reason) ? "" : ":" + reason);
        if (note.length() > 200) {
            note = note.substring(0, 200);
        }
        int superseded = 0;
        Date now = DateUtils.getNowDate();
        for (RefundApplication prior : registrationRefundHistory(registrationId)) {
            if (prior.getId() == null || !Integer.valueOf(0).equals(prior.getStatus())) {
                continue;
            }
            if (!Integer.valueOf(RefundApplication.PAYOUT_DISPATCH_PENDING).equals(prior.getPayoutStatus())) {
                throw new IllegalStateException("待审退款派发事实无法确认，不能自动取代，请后台核对原意图");
            }
            if (refundApplicationMapper.updateRefundStatusIfPending(prior.getId(), 2, SYSTEM_OPERATOR, now, note) != 1) {
                throw new IllegalStateException("待审核退款申请状态已变化，请刷新后重试");
            }
            logger.warn("[退款取代] 待审核退款申请由系统全额退款取代 refundId={} registrationId={}",
                    prior.getId(), registrationId);
            superseded++;
        }
        return superseded;
    }

    private List<RefundApplication> registrationRefundHistory(Long registrationId) {
        List<RefundApplication> history = refundApplicationMapper.selectRegistrationHistoryForUpdate(registrationId);
        return history == null ? java.util.Collections.emptyList() : history;
    }

    private BigDecimal approvedRefundCash(List<RefundApplication> history, Long excludeId) {
        BigDecimal approved = BigDecimal.ZERO;
        for (RefundApplication prior : history) {
            if (excludeId != null && excludeId.equals(prior.getId())) continue;
            if (Integer.valueOf(1).equals(prior.getStatus())) {
                if (prior.getRefundAmount() == null || prior.getRefundAmount().signum() <= 0) {
                    throw new IllegalStateException("历史退款金额缺失，请人工核实");
                }
                approved = approved.add(prior.getRefundAmount());
            }
        }
        return approved;
    }

    private void assertRemainingRefundCash(RefundApplication refund, BigDecimal paid,
                                          List<RefundApplication> history) {
        assertRemainingRefundCash(refund, paid, history, true);
    }

    private void assertRemainingRefundCash(RefundApplication refund, BigDecimal paid,
                                          List<RefundApplication> history, boolean requireNoPending) {
        for (RefundApplication prior : history) {
            if (refund.getId() != null && refund.getId().equals(prior.getId())) continue;
            if (requireNoPending && Integer.valueOf(0).equals(prior.getStatus())) {
                throw new IllegalStateException("该报名已有退款申请待审核，请先处理原申请");
            }
        }
        BigDecimal remaining = paid.subtract(approvedRefundCash(history, refund.getId()));
        if (refund.getRefundAmount().compareTo(remaining) > 0) {
            throw new IllegalArgumentException("累计退款金额不得超过实付金额");
        }
    }

    private Long resolveRegistrationMerchantId(CmsRegistration registration) {
        if (registration == null || registration.getOwnerType() == null || registration.getOwnerId() == null) {
            return null;
        }
        if (Long.valueOf(2L).equals(registration.getOwnerType())) {
            CmsActivity activity = cmsActivityMapper.selectCmsActivityById(registration.getOwnerId());
            return activity == null ? null : activity.getMemberId();
        }
        if (Long.valueOf(1L).equals(registration.getOwnerType())) {
            CmsTopic topic = cmsTopicMapper.selectCmsTopicById(registration.getOwnerId());
            return topic == null ? null : topic.getMemberId();
        }
        return null;
    }

    private BigDecimal firstNonNull(BigDecimal first, BigDecimal second, BigDecimal third) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return third;
    }

    /**
     * 后台运营审核路的可退闸(仅 reviewRefund 用)。
     *
     * <p>⚠️ <b>本方法是「运营覆盖路」,与玩家自助路刻意不同口径</b>:
     * {@code verifiedVerdict} 返回的 Verdict 若标了 {@code manualReview},含义是
     * 「玩家不能自助退,**转人工**」—— 那么运营在后台按下批准,就**是**那个人工。
     * 这里若照抛,人工队列的终点自己把门焊死了:玩家侧三个入口拒(对的),
     * 运营侧唯一那个按钮也拒 ⇒ 系统内外**没有任何路径**能退这张票,
     * 而报错文案还写着「需人工处理」—— 指向一个不存在的流程。
     *
     * <p>③ 的现实场景就需要它:店员误扫、商家没兑现承诺的权益。
     * {@code RefundPolicy} 自己的 javadoc 也写着这些「仍然该退」。
     *
     * <p>⚠️ 玩家自助那三条路(TeamGroupServiceImpl 的两处 + cancelPaidRegistration)**不动** ——
     * 「玩家只要核销一个就不能退款」那条拍板由它们承担,本方法不参与。
     */
    private void ensureRefundable(CmsRegistration registration) {
        if (registration == null) {
            return;
        }
        // [P0-06] 同 TeamGroupServiceImpl:已核销的处置问 RefundPolicy 唯一真源,别在这里另写一套。
        com.chengyinhub.business.util.RefundPolicy.Verdict verified =
                com.chengyinhub.business.util.RefundPolicy.verifiedVerdict(
                        registration.getPurchaseKind(), registration.getVerificationStatus());
        if (verified == null) {
            return;   // 未核销,放行
        }
        if (verified.manualReview) {
            // 转人工的,运营就是人工 ⇒ 放行,并留痕(谁在什么时候覆盖了自助闸)
            logger.warn("[P0-14 运营覆盖] 已核销订单经后台审核放行退款 registrationId={} purchaseKind={} reason={}",
                    registration.getId(), registration.getPurchaseKind(), verified.reason);
            return;
        }
        throw new IllegalStateException(verified.reason);
    }

    private void markRegistrationRefunded(CmsRegistration registration) {
        if (registration == null) {
            return;
        }
        if (Integer.valueOf(4).equals(registration.getPaymentStatus())
                && Integer.valueOf(3).equals(registration.getRegistrationStatus())) return;
        Date refundedAt = DateUtils.getNowDate();
        if (cmsRegistrationMapper.claimRegistrationRefunded(
                registration.getId(), "refund-review", refundedAt) == 0) {
            CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(registration.getId());
            // FX-03:已被别的路径(整单退/另一张退款单)退掉 ⇒ 本单不能再批。旧实现静默 return,
            // 审批照常通过、controller 见 rows>0 又打一次款 = 同一张票退两遍。抛出 ⇒ 审核态随事务回滚、不打款。
            if (current != null && Integer.valueOf(4).equals(current.getPaymentStatus())
                    && Integer.valueOf(3).equals(current.getRegistrationStatus())) {
                throw new IllegalStateException("该报名已退款,不能重复批准退款,请驳回本退款单");
            }
            if (current != null && Integer.valueOf(1).equals(current.getVerificationStatus())) {
                throw new IllegalStateException("已核销报名不可退款");   // 1-43:并发核销先落了位
            }
            throw new IllegalStateException("退款报名主状态更新失败");
        }
        registration.setPaymentStatus(4);
        registration.setRegistrationStatus(3);
        // [P0-15] 释放 ③ 的权益容量。
        // ⚠️ 这处不能省:后台运营审核路(RefundController → reviewRefund → 本方法)**压根不经过
        // TeamGroupServiceImpl**,只接玩家自退那两处会漏掉整条后台退款路 —— 钱退了、库存回补了,
        // 唯独容量永远占着,且零告警。
        // 幂等:releaseOnRefund 的 SQL 只动 status=0 的行,与其它两处重叠调用无害。
        if (registration.getId() != null) {
            perkLedgerService.releaseOnRefund(registration.getId(), EntitlementInvalidReason.REFUND);
        }

        // [赏金] 追回。必须在 registration_status=3 落库**之后**:countStillValidAmong 读的就是这一列,
        // 早调会数到还没置 3 的记录 ⇒ stillValid 偏高 ⇒ 该追的追不回。
        //
        // ★ 这是 clawback 今天**唯一真正跑得到**的入口(2026-07-17 rebase 时更正,原注释说反了):
        //   本分支上一版写的是「今天这行实际打不到,上游 ensureRefundable 会对任何已核销单抛」——
        //   那是对着 merge-base 624b95db 得出的,而 PR #95(P0-14)恰好把 ensureRefundable 改了:
        //   verifiedVerdict 返 manualReview 的(= ③ EXPLORE_PASS),运营在后台按批准就**是**那个人工 ⇒ 放行。
        //   于是 ③ 已核销单经后台审核可退 → 走到这里 → 赏金追回真的会发生。
        //   ⚠️ 仅 ③:①GUIDED_TICKET / ②SELF_PLAY_PASS 的已核销单 manualReview=false,仍硬拒。
        //
        // verifierId 为 null(从没核销过)时 onRefund 自身 no-op —— 没核销就不可能有赏金凭它发出去。
        merchantReferralBountyService.onRefund(registration.getVerifierId());
        // FX-10:registration_status=3 已落库,再判「还参与过没有」。
        firstReviewPointsClawback.onRegistrationRefunded(registration);
    }

    private void createSettlementAdjustment(RefundApplication refund, boolean system1Paid,
                                            SettlementDetail settled, BigDecimal ratio) {
        // HF-04(A-32): 按退款比例冲销——部分退款只回扣对应比例,不再无视 refundAmount 整单全额回扣。
        // P0-B: 逐条(每个收款方)冲销,幂等键含收款方类型+id;靠 settlement_detail 的 uk_detail_idempotency
        //       + insert ignore 天然去重(重复退款审批返回 0 行→不重复扣回),不再用 existsAdjustment 粗粒度闸拦住 2..N 条。
        BigDecimal adjGross = defaultZero(settled.getGrossAmount()).multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal adjCommission = defaultZero(settled.getCommissionAmount()).multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal adjSettlement = defaultZero(settled.getSettlementAmount()).multiply(ratio).setScale(2, RoundingMode.HALF_UP);

        SettlementDetail adjustment = new SettlementDetail();
        adjustment.setBatchId(0L);
        adjustment.setMerchantId(settled.getMerchantId());
        adjustment.setSourceType("refund");
        adjustment.setSourceId(refund.getId());
        adjustment.setReceiverType(settled.getReceiverType());
        adjustment.setReceiverId(settled.getReceiverId());
        adjustment.setGrossAmount(adjGross.negate());
        adjustment.setCommissionAmount(adjCommission.negate());
        adjustment.setSettlementAmount(adjSettlement.negate());
        // 冲销明细直接落已结算态，代表这笔回款已在余额层面完成
        adjustment.setStatus(1);
        adjustment.setFailReason("退款冲销");
        adjustment.setIdempotencyKey("refund:" + refund.getId() + ":" + settled.getReceiverType() + ":" + settled.getReceiverId());
        int inserted = settlementDetailMapper.insertAdjustment(adjustment);
        if (inserted <= 0) {
            SettlementDetail current = settlementDetailMapper.selectByIdempotencyKeyForUpdate(
                    adjustment.getIdempotencyKey());
            if (!sameAdjustment(current, adjustment)) {
                throw new IllegalStateException("退款冲销幂等键终态不一致 key=" + adjustment.getIdempotencyKey());
            }
            return;
        }
        // ★★ [P0-1 单边扣款] 冲销台账照落,但**扣余额之前必须先问系统1 到底打过款没有**。
        //
        // settled.status=1 的真实含义只是「运营在后台点过**对账确认**」(confirmBatch),
        // 不是「钱到了收款方账上」—— PR#78 已把系统2 的贷方(addBalance)整个拆除防双花,
        // 借方却原样留着 ⇒ 运营点一次确认 + 玩家退一次款 = 商家凭空少一笔从没收到过的钱。
        //
        // 判据问**父级打款态**,不问余额流水:系统1 的流水 event_id 是 activityId/topicId,
        // 压根不挂 registration ⇒ 从 registrationId 配不到流水,绕不开父级这一跳;
        // 而父级本来就有专为「打没打过」而设的状态位,比反查流水更直接也更快(热路径)。
        if (!system1Paid) {
            logger.info("[P0-1] 系统1 未对该单打款,只落冲销台账不扣余额 refundId={} receiverType={} receiverId={} amount={}",
                    refund.getId(), settled.getReceiverType(), settled.getReceiverId(), adjSettlement);
            return;
        }
        debitReceiverBalance(settled.getReceiverType(), settled.getReceiverId(), adjSettlement, refund.getId());
    }

    private boolean sameAdjustment(SettlementDetail current, SettlementDetail expected) {
        return current != null
                && "refund".equals(current.getSourceType())
                && sameNumber(expected.getSourceId(), current.getSourceId())
                && sameText(expected.getReceiverType(), current.getReceiverType())
                && sameNumber(expected.getReceiverId(), current.getReceiverId())
                && sameMoney(expected.getGrossAmount(), current.getGrossAmount())
                && sameMoney(expected.getCommissionAmount(), current.getCommissionAmount())
                && sameMoney(expected.getSettlementAmount(), current.getSettlementAmount())
                && Integer.valueOf(1).equals(current.getStatus());
    }

    private boolean sameMoney(BigDecimal expected, BigDecimal actual) {
        return expected == null ? actual == null : actual != null && expected.compareTo(actual) == 0;
    }

    private boolean sameNumber(Number expected, Number actual) {
        return expected == null ? actual == null : actual != null && expected.longValue() == actual.longValue();
    }

    /**
     * 解析「系统1(唯一真实打款方)为这单打过款没有」,返回一个按收款方逐条套用的判定。
     *
     * <p><b>父级只解析一次</b>:一笔退款最多 3 个收款方(merchant/platform/creator),逐条各查一遍父级是白费 I/O;
     * 而拼团解散({@code TeamGroupServiceImpl.refundOne})会对 N 个成员各跑一次 reviewRefund
     * ⇒ 原本 3N 次父级查询降到 N 次。coop 路「按收款方」那一查省不掉(逐收款方放款),故留在 lambda 内。
     *
     * <p>两条路各有专用的打款凭据:
     * <ul>
     *   <li><b>活动</b>(owner_type=2)→ 商家仍以 {@code cms_activity.is_settlement==1} 作父级凭据；
     *       creator 还必须回读 event_type=6 的实际正向现金流水。H8 已取消 creator 现金，
     *       不能再把整单结算标记误当成 creator 收款凭据。
     *       {@code settleActivity} 以 {@code claimActivityForSettlement} 原子把它由 0 翻 1 之后才发钱,
     *       翻不动就整个跳过 ⇒ 这一位就是「settleActivity 跑过」的凭据。
     *       <br>时序天然对齐:{@code selectTotalSalesByActivityId} 的口径是「已支付且**未退款**」
     *       ⇒ 退款早于结算 → 系统1 本来就不为这单付钱 → 不该扣(返 false,正确);
     *       退款晚于结算 → 已付 → 该扣(返 true,正确)。</li>
     *   <li><b>主题</b>(owner_type=1)→ {@code settleTopic:547} 自己就分两条路放款,这里必须**用同一个
     *       分支条件**(有没有生效 coop_order)分流,否则一半的单判错:
     *       <ul>
     *         <li><b>coop 主题</b>(有生效合作单)→ {@code settleCoopTopic} 只挂账,真放款是
     *             {@code payoutDueCoopSettlements} 在 T+7 到期后 addBalance 成功才把
     *             {@code coop_settlement.status} 置 1 ⇒ 按收款方查该行。</li>
     *         <li><b>无合作单主题</b>→ 历史版本可能已给发布者 addBalance(event_type=1),
     *             H8 后则不再发这笔现金。因此凭据改为发布者本人的 event_type=1 正向流水，
     *             既能逆向冲回历史已付款，也不会扣到 H8 下仅拿积分的发布者。
     *             且<b>只有发布者本人</b>可能存在该历史流水:
     *             同路的 {@code distributeMerchantCommissions} 已于 2026-07-17 作为假账路径拆除,
     *             merchant 收款方从来没被打过款。</li>
     *       </ul>
     *       ⚠️ 不能拿 {@code cms_topic.is_settlement} 当 coop 路的凭据:{@code settleTopic:555}
     *       两条分支**共用**这一行置位,coop 单在 T+7 未到时它就已经是 1 了,照它扣 = 本 P0 原样复发。</li>
     * </ul>
     *
     * <p>⚠️ <b>其余一律返 false(不扣)</b>,这是刻意的保守方向,不是兜底偷懒:
     * <ul>
     *   <li>{@code source_type='coupon'} 的明细由 {@code recordCouponVerification} 落账,
     *       但系统1 <b>没有任何优惠券打款路径</b>(只有 settleActivity / coop 两条)
     *       ⇒ 券的分成从来没人打过 ⇒ 退款时扣它必然是单边扣款。</li>
     *   <li>owner_type 缺失(存量脏数据)⇒ 判不出打没打过 ⇒ 判不出就别扣。
     *       错扣 = 本 P0(商家凭空少钱,且商家自己发现不了);
     *       错不扣 = 账面留在冲销台账上,对账查得出来、追得回来。两种错的代价不对称。</li>
     * </ul>
     * receiverType='platform'(receiverId=0)不必特判:{@code debitReceiverBalance} 自身就 no-op。
     */
    private Predicate<SettlementDetail> resolveSystem1PaidCheck(CmsRegistration registration,
                                                                 List<SettlementDetail> settledList) {
        if (registration == null || registration.getOwnerType() == null || registration.getOwnerId() == null) {
            return settled -> false;
        }
        Long parentId = registration.getOwnerId();

        if (Long.valueOf(2L).equals(registration.getOwnerType())) {
            CmsActivity activity = cmsActivityMapper.selectCmsActivityById(parentId);
            // 商家仍由整单结算标记判定；H8 不再付 creator 现金，其历史逆向只认实际流水。
            // 客诉期提现闸:结算时涉诉暂扣、尚未补结的报名单,商家没拿到这单的钱 ⇒ 不扣。
            boolean paid = activity != null && Integer.valueOf(1).equals(activity.getIsSettlement())
                    && (registration.getId() == null
                        || activitySettlementHoldMapper.countHeldByRegistrationId(registration.getId()) == 0);
            Set<Long> paidCreators = paid
                    ? positiveIncomeReceivers(settledList, 6L, parentId) : new HashSet<Long>();
            return settled -> paid && (!"creator".equals(settled.getReceiverType())
                    || paidCreators.contains(settled.getReceiverId()));
        }

        if (Long.valueOf(1L).equals(registration.getOwnerType())) {
            // 分流条件与 settleTopic:547 同源:有生效合作单走 coop 放款,否则走存量发布者分润
            if (!coopOrderMapper.selectEffectiveOrdersByTopicId(parentId).isEmpty()) {
                // coop 路是**逐收款方**放款(A 放了 B 可能没放),这一次查省不掉
                return settled -> coopSettlementMapper.countPaidByTopicAndPayee(parentId, settled.getReceiverId()) > 0;
            }
            CmsTopic topic = cmsTopicMapper.selectCmsTopicById(parentId);
            boolean settledTopic = topic != null && Integer.valueOf(1).equals(topic.getIsSettlement());
            Long publisherId = topic == null ? null : topic.getMemberId();
            Set<Long> paidCreators = settledTopic
                    ? positiveIncomeReceivers(settledList, 1L, parentId) : new HashSet<Long>();
            return settled -> settledTopic
                    && "creator".equals(settled.getReceiverType())
                    && settled.getReceiverId() != null
                    && settled.getReceiverId().equals(publisherId)
                    && paidCreators.contains(publisherId);
        }

        return settled -> false;
    }

    private Set<Long> positiveIncomeReceivers(List<SettlementDetail> settledList, Long eventType, Long eventId) {
        Set<Long> checked = new HashSet<>();
        Set<Long> paid = new HashSet<>();
        for (SettlementDetail settled : settledList) {
            Long receiverId = settled == null ? null : settled.getReceiverId();
            if (settled != null && "creator".equals(settled.getReceiverType())
                    && receiverId != null && checked.add(receiverId)
                    && hasPositiveIncome(receiverId, eventType, eventId)) {
                paid.add(receiverId);
            }
        }
        return paid;
    }

    private boolean hasPositiveIncome(Long memberId, Long eventType, Long eventId) {
        if (memberId == null) {
            return false;
        }
        BigDecimal income = umsMemberBalanceDetailMapper.selectIncomeByEventTypeAndEventId(
                memberId, eventType, eventId);
        return income != null && income.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * HF-04(A-32): 退款冲销比例 = refundAmount / 实付额,夹取到 [0,1]。
     * 优先用退款单实付额 paidAmount 作分母(退款是实付的一部分);缺失时退回结算gross;仍无则按全额(1)兜底。
     */
    private BigDecimal refundRatio(RefundApplication refund, BigDecimal settledGross) {
        BigDecimal refundAmount = defaultZero(refund.getRefundAmount());
        BigDecimal base = defaultZero(refund.getPaidAmount());
        if (base.compareTo(BigDecimal.ZERO) <= 0) {
            base = settledGross;
        }
        if (base.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        BigDecimal ratio = refundAmount.divide(base, 6, RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        if (ratio.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return ratio;
    }

    /** 已打款的收款方（商家/创作者）在冲销时把对应金额扣回；平台账(receiverId=0)只记账。 */
    private void debitReceiverBalance(String receiverType, Long receiverId, BigDecimal amount, Long refundId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        boolean toMemberBalance = ("merchant".equals(receiverType) || "creator".equals(receiverType))
                && receiverId != null && receiverId > 0;
        if (!toMemberBalance) {
            return;
        }
        if (umsMemberMapper.lockMemberById(receiverId) == null) {
            throw new IllegalStateException("退款冲销收款方不存在 receiverId=" + receiverId);
        }
        UmsMember member = umsMemberMapper.selectUmsMemberById(receiverId);
        BigDecimal before = member == null || member.getBalance() == null ? BigDecimal.ZERO : member.getBalance();

        // [D-3] 余额下限:收款方把分成提现走之后再退款,原先直接 addBalance(-amount) 会把余额扣成负数。
        // 负余额既不是可追偿的债权凭据(没有工单、没人知道),又会被后续结算/提现当成正常余额参与运算。
        // 口径与同 codebase 的 CoopComplaintServiceImpl:87-97 一致:只扣可扣部分;差额记欠款(SettlementDebtLedger),从该收款方后续结算抵扣,逾期未清限制发布。
        BigDecimal actual = before.compareTo(amount) >= 0 ? amount : before;
        if (actual.compareTo(BigDecimal.ZERO) <= 0) {
            // 一分都扣不动(余额已为 0 或负):不写零额流水污染台账,直接告警转人工
            alarm("退款冲销扣款失败", "收款方余额为 " + before + ",应扣 " + amount
                    + " 全额记欠款,从后续结算抵扣 refundId=" + refundId + " receiverId=" + receiverId);
            settlementDebtLedger.record(receiverId,
                    com.chengyinhub.business.service.support.SettlementDebtLedger.SOURCE_REFUND + ":" + receiverType,
                    refundId, amount);
            return;
        }
        if (umsMemberMapper.deductBalance(receiverId, actual) != 1) {
            throw new IllegalStateException("退款冲销余额原子扣减失败 receiverId=" + receiverId);
        }

        UmsMemberBalanceDetail flow = new UmsMemberBalanceDetail();
        flow.setMemberId(receiverId);
        flow.setCurrentBalance(before);
        flow.setAfterBalance(before.subtract(actual));
        flow.setChangeBalance(actual.negate());
        flow.setChangeReason("退款冲销");
        flow.setEventType(6L);
        flow.setEventId(refundId);
        flow.setChangeType(2L);
        flow.setDelFlag(0L);
        flow.setCreateTime(new Date());
        if (umsMemberBalanceDetailMapper.insertUmsMemberBalanceDetail(flow) != 1) {
            throw new IllegalStateException("退款冲销余额流水写入失败 receiverId=" + receiverId);
        }

        // 部分扣回:冲销明细已按应扣额(amount)落账,余额只扣回 actual ⇒ 账实差额 = amount-actual。
        // 这个差额没有任何下游会自己发现,不告警就是静默挂账。
        if (actual.compareTo(amount) < 0) {
            alarm("退款冲销扣款不足", "应扣 " + amount + " 实扣 " + actual + ",差额 "
                    + amount.subtract(actual) + " 记欠款,从后续结算抵扣 refundId=" + refundId + " receiverId=" + receiverId);
            settlementDebtLedger.record(receiverId,
                    com.chengyinhub.business.service.support.SettlementDebtLedger.SOURCE_REFUND + ":" + receiverType,
                    refundId, amount.subtract(actual));
        }
    }

    private BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
