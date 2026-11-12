package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MarketingProductOrderMapper;
import com.chengyinhub.business.service.FulfillmentCommand;
import com.chengyinhub.business.service.FulfillmentOutcome;
import com.chengyinhub.business.service.GrantLine;
import com.chengyinhub.business.service.ChannelRevocationOutcome;
import com.chengyinhub.business.service.IMarketingChannelRevenueReversal;
import com.chengyinhub.business.service.IMarketingProductFulfillmentService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.ChannelRefundCommand;
import com.chengyinhub.business.service.MarketingChannelFactReader;
import com.chengyinhub.business.service.MarketingChannelRefundFactReader;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.PaymentFacts;
import com.chengyinhub.business.service.RegistrationRequestConflictException;
import com.chengyinhub.business.util.RefundPolicy;
import com.chengyinhub.common.constant.EntitlementInvalidReason;
import com.chengyinhub.common.config.RestoreDispatchGuard;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.utils.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 营销商品成交 → 既有订单事实。**唯一 writer**,由 M2-F 维护。
 *
 * <p>落点纪律(全部沿用既有列/既有服务,不新增钱包、不新增权益真源):
 * <ul>
 *   <li>{@code cms_registration} 是唯一可玩授权真源 —— {@code CurrentPlayRegistration} 只认它,
 *       所以 {@code PlayNodeAccessService} 一字不改;</li>
 *   <li>每行 {@code payable_amount} 是**真实分摊**(Σ = 商品实付);</li>
 *   <li>支付渠道总额({@code wechat_payment_amount})与 {@code transaction_id} **只在首行**一处持有;</li>
 *   <li>现金必须经 verified 付款事实复核({@link WechatVerifiedPaymentFactService#requireConsumablePayment}),
 *       绝不按上游自报值置 {@code payment_status=2};</li>
 *   <li>普通 pass **不写** {@code registration_entitlement}:那是 ③ 的章节容量事实,
 *       只有 {@code exploreCapacity=true} 的行才调既有 {@code reserveEntitlements}。</li>
 * </ul>
 */
@Service
public class MarketingProductFulfillmentServiceImpl implements IMarketingProductFulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(MarketingProductFulfillmentServiceImpl.class);

    /** 退款政策无法在成交时表达(没有 code/version 且不是 ③ 探索票)。 */
    /**
     * 订单成交快照缺少已付 shape(每份席位数)。
     *
     * <p>不是"配置没填",而是**这一单无法证明已付了多少席位**:绝不能回落到 live SKU 配置去猜,
     * 那会让作者事后改 SKU 席位就改变旧已付单的准入数量。
     */
    public static final String SHAPE_SNAPSHOT_MISSING = "SHAPE_SNAPSHOT_MISSING";

    /** 退款判定所需的政策事实缺失(不是"没有退款政策",而是这一单没法按原政策判)。 */
    public static final String REFUND_DECISION_POINT_MISSING = "REFUND_DECISION_POINT_MISSING";

    private static final int MONEY_SCALE = 2;

    @Autowired
    private CmsRegistrationMapper cmsRegistrationMapper;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    @Autowired
    private MarketingProductOrderMapper productOrderMapper;

    @Autowired
    private IProductRoleService productRoleService;

    @Autowired
    private IPerkLedgerService perkLedgerService;


    /**
     * M5 收入分配可靠队列:商品成交的每一行都在**本事务**里入队应计(只插任务、不算钱)。
     * 强依赖:缺 bean 启动失败,避免"卖了但永远不记账"的静默漏账。
     */
    @Autowired
    private com.chengyinhub.business.service.IMarketingRevenuePostingService marketingRevenuePostingService;

    /** 现金复核;积分单不触碰。缺失时现金单一律拒绝(fail-closed)。 */
    @Autowired(required = false)
    private WechatVerifiedPaymentFactService verifiedPaymentFacts;

    /** 积分汇率唯一真源(与报名/积分商城同一份配置);缺失时用其代码默认值(0.02)。 */
    @Autowired(required = false)
    private com.chengyinhub.business.service.support.PointsValuePolicy pointsValuePolicy;

    /**
     * 渠道付款事实回读端口(M8 实现)。缺失 = 真实 provider 未配置 ⇒ 渠道成交一律关闭。
     * 绝不因为"调用方给了 hash"就放行。
     */
    @Autowired(required = false)
    private MarketingChannelFactReader channelFactReader;

    /** 渠道**退款已完成**事实回读端口(M8 实现)。缺失 ⇒ 退款撤销关闭(绝不用 reason 当证明)。 */
    @Autowired(required = false)
    private MarketingChannelRefundFactReader channelRefundFactReader;

    /**
     * 渠道净额冲销入队端口(M5/M0 实现)。
     *
     * <p>{@code required=false} 只为了让未接 M5 的环境仍能启动**其它**流程;
     * 但真正走到渠道退款撤销时,它缺失会被 {@code requireChannelReversalPort()} 抛错 ——
     * 宁可整笔回滚,也不做"撤了准入、收入不冲销"的半套账(不是静默跳过)。
     */
    @Autowired(required = false)
    private IMarketingChannelRevenueReversal channelRevenueReversal;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FulfillmentOutcome grant(FulfillmentCommand command) {
        RestoreDispatchGuard.requireDispatchAllowed();
        requireCommand(command);

        MarketingProductOrder order = null;
        String parentOrderNo;
        if (command.payment.mode == PaymentFacts.MODE_CHANNEL) {
            // 渠道成交没有 marketing_product_order(root 禁止第二套订单),parent_order_no = 渠道单号。
            parentOrderNo = requireExternalOrderNo(command);
            FulfillmentOutcome channelReplay = replayByParentOrderNo(parentOrderNo);
            if (channelReplay != null) {
                assertReplayShapeUnchanged(command, rowsOfOrder(parentOrderNo), null);
                return channelReplay;
            }
        } else {
            order = productOrderMapper.selectByMemberRequest(command.memberId, command.requestId);
            if (order == null) {
                throw new IllegalStateException("商品订单不存在,拒绝落单 requestId=" + command.requestId);
            }
            if (!Integer.valueOf(MarketingProductOrder.STATUS_PAID).equals(order.getStatus())) {
                throw new RegistrationRequestConflictException("商品订单未完成支付,不能发放权益");
            }
            if (command.parentOrderNo != null && !command.parentOrderNo.equals(order.getOrderNo())) {
                throw new RegistrationRequestConflictException("商品订单号与幂等键不匹配");
            }
            // 幂等:订单已绑定过准入 ⇒ 读回原行,不新建、不延长、不再扣款。
            // 但"同一父单换一个更大的份数/席位"不是重放,是抬量 ⇒ 拒绝。
            FulfillmentOutcome replay = replay(order);
            if (replay != null) {
                // 站内单的父单号就是本单号;重放形状核对必须用它,不能借用渠道分支的变量。
                assertReplayShapeUnchanged(command, rowsOfOrder(order.getOrderNo()), order);
                return replay;
            }
            parentOrderNo = order.getOrderNo();
        }

        BigDecimal verifiedAmount = verifiedPayableAmount(command, order);
        List<GrantLine> lines = command.lines;
        assertApportionment(lines, verifiedAmount);
        // 份数/席位一次解析,整单一致(不从 lines 数量猜)。
        OrderShape shape = resolveShape(command, order);
        List<CmsTopic> topics = new ArrayList<>(lines.size());
        for (GrantLine line : lines) {
            CmsTopic topic = cmsTopicMapper.selectCmsTopicByIdForUpdate(line.topicId);
            if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
                throw new IllegalStateException("商品覆盖的主题不存在或已删除 topicId=" + line.topicId);
            }
            if (!productRoleService.isPlatformOwned(topic.getId())) {
                throw new IllegalStateException("商品覆盖的主题非平台自营,拒绝代收 topicId=" + topic.getId());
            }
            if (topic.getFeePlatformRate() == null) {
                throw new IllegalStateException("主题缺少平台费率快照,拒绝落单 topicId=" + topic.getId());
            }
            topics.add(topic);
        }

        List<Long> registrationIds = new ArrayList<>();
        List<Long> pendingTopicIds = new ArrayList<>();
        boolean firstRowUsed = false;

        for (int i = 0; i < lines.size(); i++) {
            GrantLine line = lines.get(i);
            CmsTopic topic = topics.get(i);
            if (line.validityKind == GrantLine.VALIDITY_ACTIVATE_ON_FIRST_USE) {
                // 未激活不得以 expires_at=NULL 冒充永久通行 ⇒ 此刻不建行,激活时再落。
                pendingTopicIds.add(line.topicId);
                continue;
            }
            CmsRegistration row = buildRow(command, order, line, topic, parentOrderNo,
                    !firstRowUsed, registrationIds.isEmpty(), shape, null);
            if (cmsRegistrationMapper.insertCmsRegistration(row) != 1 || row.getId() == null) {
                throw new IllegalStateException("商品准入行写入失败 topicId=" + line.topicId);
            }
            firstRowUsed = true;
            registrationIds.add(row.getId());
            // 每行都是真实收入(分摊后的 payable),逐行入队;M5 按 (order_type, order_id) 幂等。
            marketingRevenuePostingService.enqueueAccrual(row.getId());
            if (line.exploreCapacity) {
                // ③ 探索票:章节容量按既有预占事实写,普通 pass 不写。
                perkLedgerService.reserveEntitlements(row.getId(), topic.getId());
            }
        }

        if (registrationIds.isEmpty() && pendingTopicIds.isEmpty()) {
            throw new IllegalStateException("商品未产出任何准入行");
        }
        if (!registrationIds.isEmpty() && order != null) {
            // 只有站内商品单才有可绑定的父订单;渠道成交的 parent_order_no 就是渠道单号本身。
            productOrderMapper.bindRegistrations(order.getId(), registrationIds.get(0),
                    cmsRegistrationMapper.selectCmsRegistrationById(registrationIds.get(0)).getRegistrationNo(),
                    toJson(registrationIds), new Date());
        }

        FulfillmentOutcome outcome = new FulfillmentOutcome();
        outcome.parentOrderNo = parentOrderNo;
        outcome.registrationIds = registrationIds;
        outcome.pendingActivation = !pendingTopicIds.isEmpty();
        outcome.pendingActivationTopicIds = pendingTopicIds;
        log.info("marketing product granted orderNo={} member={} rows={} pending={} payable={}",
                parentOrderNo, command.memberId, registrationIds.size(), pendingTopicIds.size(), verifiedAmount);
        return outcome;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FulfillmentOutcome activateOnFirstUse(String requestId, long memberId, long topicId, Date now) {
        RestoreDispatchGuard.requireDispatchAllowed();
        if (requestId == null || requestId.trim().isEmpty() || memberId <= 0 || topicId <= 0) {
            throw new IllegalArgumentException("激活参数不合法");
        }
        Date at = now == null ? new Date() : now;
        MarketingProductOrder located = productOrderMapper.selectByMemberRequest(memberId, requestId.trim());
        if (located == null) {
            throw new IllegalStateException("商品订单不存在,不能激活 requestId=" + requestId);
        }
        // 同一订单的激活必须串行:A/B 两个覆盖主题并发首次使用,不能各自开出不同的有效期起点。
        MarketingProductOrder order = productOrderMapper.selectByOrderNoForUpdate(located.getOrderNo());
        if (order == null) {
            throw new IllegalStateException("商品订单不存在,不能激活 requestId=" + requestId);
        }
        if (!Integer.valueOf(MarketingProductOrder.STATUS_PAID).equals(order.getStatus())) {
            throw new RegistrationRequestConflictException("商品订单未完成支付,不能激活");
        }
        List<CmsRegistration> granted = rowsOfOrder(order.getOrderNo());
        // ① 本主题已激活过 ⇒ 只读回**这一行**,不延长、不新建(重复 A 的负例)。
        CmsRegistration sameTopic = firstOfTopic(granted, topicId);
        if (sameTopic != null) {
            FulfillmentOutcome replay = outcomeOf(order.getOrderNo(), granted, true);
            replay.registrationIds = new ArrayList<>(java.util.Collections.singletonList(sameTopic.getId()));
            return replay;
        }
        // ② 只读成交时冻结的分摊;不重新定价、不按当前配置重算。
        GrantLine line = productOrderMapper.selectGrantLine(order.getOrderNo(), topicId);
        if (line == null) {
            throw new IllegalStateException("该订单未覆盖此主题,不能激活 topicId=" + topicId);
        }
        if (line.validityKind != GrantLine.VALIDITY_ACTIVATE_ON_FIRST_USE) {
            throw new RegistrationRequestConflictException("该主题不是首次使用型,已在成交时生效");
        }
        // ③ 同一 SKU 的激活时钟只开一次:A 先激活后,B 沿用 A 的激活时刻,不把通行证重开一轮。
        Date activationBase = activationBaseOf(granted, at);
        CmsTopic topic = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
            throw new IllegalStateException("主题不存在或已删除 topicId=" + topicId);
        }
        if (!productRoleService.isPlatformOwned(topic.getId())) {
            throw new IllegalStateException("主题非平台自营,拒绝发准入 topicId=" + topicId);
        }
        // 付款事实复核(非消费):购买时已 requireConsumablePayment 消费过一次,
        // 这里只再确认"事实仍是那条、金额仍一致",绝不二次消费、绝不重复扣款。
        assertOrderPaymentStillVerified(order);

        boolean requestIdFree = !anyRowHoldsRequestId(granted, order.getRequestId());
        // 激活才起算:expires_at = **该 SKU 首次激活时刻** + 冻结天数(B 不会重开一轮)。
        if (line.validDays <= 0) {
            throw new IllegalStateException("首次使用型缺少有效天数 topicId=" + topicId);
        }
        Date activationExpiresAt = DateUtils.addDays(activationBase, line.validDays);
        FulfillmentCommand activationCommand = commandOf(order, line);
        CmsRegistration row = buildRow(activationCommand, order, line, topic, order.getOrderNo(),
                granted.isEmpty(), requestIdFree, resolveShape(activationCommand, order), activationExpiresAt);
        if (cmsRegistrationMapper.insertCmsRegistration(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("激活准入行写入失败 topicId=" + topicId);
        }
        // 首次使用型在**激活**才产生可玩事实,应计也在此入队(购买时没有落行,不能早记)。
        marketingRevenuePostingService.enqueueAccrual(row.getId());
        if (line.exploreCapacity) {
            perkLedgerService.reserveEntitlements(row.getId(), topic.getId());
        }
        // 把激活行挂回商品订单:
        // 首次绑定走 bindRegistrations(置 primary + json);之后的行必须**原子追加**,
        // 否则 bindRegistrations 的 `primary_registration_id is null` 会让它变成 no-op,
        // 激活行永远不进 registration_ids_json ⇒ 退款消费者按 registration 反查订单会漏单。
        if (order.getPrimaryRegistrationId() == null) {
            productOrderMapper.bindRegistrations(order.getId(), row.getId(), row.getRegistrationNo(),
                    toJson(java.util.Collections.singletonList(row.getId())), at);
        } else {
            productOrderMapper.appendRegistrationId(order.getId(), row.getId(), at);
        }

        FulfillmentOutcome outcome = new FulfillmentOutcome();
        outcome.parentOrderNo = order.getOrderNo();
        outcome.registrationIds = new ArrayList<>(java.util.Collections.singletonList(row.getId()));
        log.info("marketing product activated orderNo={} member={} topicId={} expiresAt={}",
                order.getOrderNo(), memberId, topicId, row.getExpiresAt());
        return outcome;
    }

    private List<CmsRegistration> rowsOfOrder(String orderNo) {
        CmsRegistration probe = new CmsRegistration();
        probe.setParentOrderNo(orderNo);
        List<CmsRegistration> rows = cmsRegistrationMapper.selectCmsRegistrationList(probe);
        return rows == null ? new ArrayList<>() : rows;
    }

    private CmsRegistration firstOfTopic(List<CmsRegistration> rows, long topicId) {
        for (CmsRegistration row : rows) {
            if (row != null && row.getId() != null && Long.valueOf(topicId).equals(row.getTopicId())) {
                return row;
            }
        }
        return null;
    }

    private boolean anyRowHoldsRequestId(List<CmsRegistration> rows, String requestId) {
        if (requestId == null) return false;
        for (CmsRegistration row : rows) {
            if (row != null && requestId.equals(row.getRequestId())) return true;
        }
        return false;
    }

    /**
     * 激活时钟:同一父订单下**最早**一行的创建时刻。
     *
     * <p>为什么不是"这次激活的 now":一个通行证覆盖 A/B 两主题时,A 先激活就该定死期限;
     * B 之后再激活如果按自己的 now 起算,等于把同一张通行证续了一轮(用户白赚)。
     */
    private Date activationBaseOf(List<CmsRegistration> rows, Date fallback) {
        Date earliest = null;
        for (CmsRegistration row : rows) {
            if (row == null || row.getCreateTime() == null) continue;
            if (earliest == null || row.getCreateTime().before(earliest)) {
                earliest = row.getCreateTime();
            }
        }
        return earliest == null ? fallback : earliest;
    }

    private FulfillmentOutcome outcomeOf(String orderNo, List<CmsRegistration> rows, boolean replayed) {
        FulfillmentOutcome outcome = new FulfillmentOutcome();
        outcome.parentOrderNo = orderNo;
        for (CmsRegistration row : rows) {
            if (row != null && row.getId() != null) outcome.registrationIds.add(row.getId());
        }
        outcome.replayed = replayed;
        return outcome;
    }

    /**
     * 购买时已消费过的付款事实:激活只做**非消费式**复核(仍存在、是 PAYMENT、金额与订单一致),
     * 不调用 {@code requireConsumablePayment}(那会二次消费并被拒),也绝不重复扣款。
     *
     * <p>按业务单号回查(订单号就是渠道 out_trade_no),因此不需要订单上另存事实哈希。
     */
    private void assertOrderPaymentStillVerified(MarketingProductOrder order) {
        boolean cash = Integer.valueOf(MarketingProductOrder.PAY_MODE_CASH).equals(order.getPayMode());
        if (!cash) {
            return;   // 积分单:扣减事实在购买事务里已完成,这里无事可复核
        }
        if (verifiedPaymentFacts == null) {
            throw new IllegalStateException("付款事实复核未配置,拒绝激活");
        }
        WechatVerifiedPaymentFactService.Stored stored = verifiedPaymentFacts.findPaymentSuccess(order.getOrderNo());
        if (stored == null || stored.facts() == null
                || stored.facts().kind() != WechatVerifiedPaymentFact.Kind.PAYMENT) {
            throw new RegistrationRequestConflictException("付款事实不可用,拒绝激活");
        }
        BigDecimal paid = BigDecimal.valueOf(stored.facts().amountFen()).movePointLeft(2);
        if (order.getTotalCashAmount() != null && money(order.getTotalCashAmount()).compareTo(paid) != 0) {
            throw new RegistrationRequestConflictException("付款事实金额与商品订单不一致,拒绝激活");
        }
    }

    /** 激活路径的最小命令:只为复用 buildRow 的落行口径,金额全部来自冻结行。 */
    private FulfillmentCommand commandOf(MarketingProductOrder order, GrantLine line) {
        FulfillmentCommand command = new FulfillmentCommand();
        command.requestId = order.getRequestId();
        command.parentOrderNo = order.getOrderNo();
        command.memberId = order.getMemberId();
        command.productId = order.getProductId();
        command.payment = PaymentFacts.points(order.getTotalPointsAmount() == null
                ? 0L : order.getTotalPointsAmount());
        if (Integer.valueOf(MarketingProductOrder.PAY_MODE_CASH).equals(order.getPayMode())) {
            command.payment = PaymentFacts.cash(order.getPaymentFactId() == null ? 0L : order.getPaymentFactId(),
                    null, order.getTotalCashAmount(), null);
        }
        command.lines = new ArrayList<>(java.util.Collections.singletonList(line));
        return command;
    }

    @Override
    public List<Long> findGrantedRegistrationIds(String orderNo) {
        List<Long> ids = new ArrayList<>();
        if (orderNo == null || orderNo.trim().isEmpty()) {
            return ids;
        }
        CmsRegistration probe = new CmsRegistration();
        probe.setParentOrderNo(orderNo.trim());
        List<CmsRegistration> rows = cmsRegistrationMapper.selectCmsRegistrationList(probe);
        if (rows == null) {
            return ids;
        }
        for (CmsRegistration row : rows) {
            if (row != null && row.getId() != null) {
                ids.add(row.getId());
            }
        }
        return ids;
    }

    @Override
    public ChannelRevocationOutcome revokeChannel(String parentOrderNo, String reason, String requestId) {
        if (parentOrderNo == null || parentOrderNo.trim().isEmpty()) {
            throw new IllegalArgumentException("渠道单号不能为空");
        }
        String orderNo = parentOrderNo.trim();
        CmsRegistration probe = new CmsRegistration();
        probe.setParentOrderNo(orderNo);
        List<CmsRegistration> rows = cmsRegistrationMapper.selectCmsRegistrationList(probe);
        if (rows == null || rows.isEmpty()) {
            throw new IllegalStateException("渠道单未在本域成交,无可撤销准入 orderNo=" + orderNo);
        }
        ChannelRevocationOutcome outcome = new ChannelRevocationOutcome();
        outcome.parentOrderNo = orderNo;
        List<CmsRegistration> active = new ArrayList<>();
        for (CmsRegistration row : rows) {
            if (row == null || row.getId() == null) continue;
            outcome.registrationIds.add(row.getId());
            if (Integer.valueOf(1).equals(row.getVerificationStatus())) {
                // 已核销 = 玩家已经拿到东西,不能再静默回收。
                outcome.revocable = false;
                outcome.reasonCode = "ALREADY_VERIFIED";
                outcome.message = "该渠道单已核销,不能直接撤销;请走退款/人工复核";
                return outcome;
            }
            if (!Integer.valueOf(3).equals(row.getRegistrationStatus())) {
                active.add(row);
            }
        }
        if (active.isEmpty()) {
            outcome.replayed = true;
            outcome.revocable = false;
            outcome.reasonCode = "ALREADY_REVOKED";
            outcome.message = "该渠道单的准入已全部撤销";
            return outcome;
        }
        // 已付但未核销:撤销必须由原退款链路完成(钱与权益同一条链),本域只交出行 id。
        // 这里不直接 update registration_status,也不调 cancelRegistration(它拒绝已付单)。
        outcome.revocable = true;
        outcome.reasonCode = "REFUND_REQUIRED";
        outcome.message = "可撤销:请交给原退款链路(C07/RefundService)撤销;本域不直接作废已付准入";
        log.info("channel revoke assessed orderNo={} activeRows={} reason={} requestId={}",
                orderNo, active.size(), reason, requestId);
        return outcome;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChannelRevocationOutcome consumeVerifiedChannelRefund(ChannelRefundCommand command) {
        RestoreDispatchGuard.requireDispatchAllowed();
        if (command == null || command.parentOrderNo == null || command.parentOrderNo.trim().isEmpty()) {
            throw new IllegalArgumentException("渠道单号不能为空");
        }
        if (channelRefundFactReader == null) {
            throw new IllegalStateException("渠道退款撤销未启用:未配置退款事实回读端口(真实 provider 未接入)");
        }
        String orderNo = command.parentOrderNo.trim();
        List<CmsRegistration> rows = rowsOfOrder(orderNo);
        if (rows.isEmpty()) {
            throw new IllegalStateException("渠道单未在本域成交 orderNo=" + orderNo);
        }
        // 退款证明只认服务端回读的事实;command 里的金额/币种只作核对。
        MarketingChannelRefundFactReader.ChannelRefundFact fact =
                channelRefundFactReader.requireVerifiedRefund(
                        new MarketingChannelRefundFactReader.ChannelRefundQuery(
                                orderNo, command.providerKey, command.channelRefundNo,
                                command.refundFactId, command.refundFactHash,
                                command.claimedAmount, command.claimedCurrency));
        if (fact == null || !fact.confirmed) {
            throw new RegistrationRequestConflictException("渠道退款尚未确认到账,不得撤销准入");
        }
        if (!orderNo.equals(fact.parentOrderNo)) {
            throw new RegistrationRequestConflictException("渠道退款事实与原订单不匹配");
        }
        if (fact.amount == null || fact.amount.signum() <= 0) {
            throw new RegistrationRequestConflictException("渠道退款事实金额不可用");
        }
        if (command.claimedAmount != null && money(command.claimedAmount).compareTo(money(fact.amount)) != 0) {
            throw new RegistrationRequestConflictException("退款金额与渠道事实不一致");
        }
        if (!fact.fullRefund) {
            // 部分退款:本域没有既定政策(按比例撤权/保留剩余),不擅自决定。
            throw new IllegalStateException("PARTIAL_CHANNEL_REFUND_UNSUPPORTED:"
                    + "渠道部分退款未定义撤权口径,需人工处理 orderNo=" + orderNo);
        }
        ChannelRevocationOutcome outcome = new ChannelRevocationOutcome();
        outcome.parentOrderNo = orderNo;
        List<Long> revoked = new ArrayList<>();
        for (CmsRegistration row : rows) {
            if (row == null || row.getId() == null) continue;
            outcome.registrationIds.add(row.getId());
            if (Integer.valueOf(1).equals(row.getVerificationStatus())) {
                throw new RegistrationRequestConflictException(
                        "已核销准入不可按退款撤销,需人工处理 registrationId=" + row.getId());
            }
            // 走既有 C07 CAS(同一 claimRegistrationRefunded 口径),不新造状态机。
            if (cmsRegistrationMapper.claimRegistrationRefunded(
                    row.getId(), "channel-refund", new Date()) == 1) {
                revoked.add(row.getId());
                // ③ 章节容量释放(幂等,只动 status=0 的行)。
                perkLedgerService.releaseOnRefund(row.getId(), EntitlementInvalidReason.REFUND);
            }
        }
        if (!revoked.isEmpty()) {
            // 净额冲销:与撤销准入同一事务入队(只插任务,不算钱)。
            // 逆序(费用未核 → 尚无 allocation)由 M5 侧持久留在队列并带原因码,**不在这里吞掉**:
            // 本方法不 catch 任何异常 —— 入队失败就整笔回滚,由渠道回调重投。
            requireChannelReversalPort().enqueueChannelReversal(
                    orderNo, fact.providerKey, fact.channelRefundNo, fact.factId, fact.factHash);
        }
        outcome.revocable = false;
        if (revoked.isEmpty()) {
            outcome.replayed = true;
            outcome.reasonCode = "ALREADY_REVOKED";
            outcome.message = "该渠道单的准入已全部处于退款终态";
        } else {
            outcome.reasonCode = "REVOKED";
            outcome.message = "已按渠道退款事实撤销准入";
        }
        log.info("channel refund consumed orderNo={} provider={} refundNo={} revoked={} requestId={}",
                orderNo, command.providerKey, command.channelRefundNo, revoked.size(), command.requestId);
        return outcome;
    }

    /**
     * 渠道冲销端口必须存在:缺失说明 M5 侧未接入。
     * 这里**抛错**而不是跳过 —— 跳过等于"渠道退款成功了,但收入永远不冲销",这种漏账线上查不出来。
     */
    private IMarketingChannelRevenueReversal requireChannelReversalPort() {
        if (channelRevenueReversal == null) {
            throw new IllegalStateException("CHANNEL_REVERSAL_NOT_WIRED:"
                    + "渠道净额冲销端口未实现(M5 enqueueChannelReversal 未接入),拒绝执行半套退款撤销");
        }
        return channelRevenueReversal;
    }

    // ------------------------------------------------------------------ 内部

    private void requireCommand(FulfillmentCommand command) {
        if (command == null) throw new IllegalArgumentException("成交命令不能为空");
        if (command.requestId == null || command.requestId.trim().isEmpty()) {
            throw new IllegalArgumentException("商品订单幂等键不能为空");
        }
        if (command.requestId.trim().length() > 64) {
            throw new IllegalArgumentException("商品订单幂等键长度不能超过64个字符");
        }
        if (command.memberId <= 0) throw new IllegalArgumentException("会员不能为空");
        if (command.payment == null) throw new IllegalArgumentException("缺少付款事实");
        if (command.lines == null || command.lines.isEmpty()) {
            throw new IllegalArgumentException("商品未覆盖任何主题");
        }
        for (GrantLine line : command.lines) {
            if (line == null || line.topicId <= 0) throw new IllegalArgumentException("覆盖主题不合法");
            if (line.listAmount == null || line.listAmount.signum() < 0
                    || line.payableAmount == null || line.payableAmount.signum() < 0) {
                throw new IllegalArgumentException("分摊金额不合法 topicId=" + (line == null ? null : line.topicId));
            }
        }
    }

    /** 现金:用 verified fact 复核金额;积分:用已扣减积分数。返回本次实付总额(元)。 */
    private BigDecimal verifiedPayableAmount(FulfillmentCommand command, MarketingProductOrder order) {
        if (command.payment.mode == PaymentFacts.MODE_CHANNEL) {
            return verifiedChannelAmount(command);
        }
        if (command.payment.mode == PaymentFacts.MODE_POINTS) {
            if (command.payment.pointsUsed <= 0) {
                throw new IllegalArgumentException("积分成交必须给出真实扣减积分数");
            }
            Integer orderPoints = order.getTotalPointsAmount();
            if (orderPoints == null || orderPoints.longValue() != command.payment.pointsUsed) {
                throw new RegistrationRequestConflictException("积分成交数额与商品订单不符");
            }
            // 纯积分单的现金口径为 0:分摊对账用"积分折元"作金额锚,避免全部行 payable=0 而失去守恒校验。
            if (order.getTotalCashAmount() != null && order.getTotalCashAmount().signum() > 0) {
                return money(order.getTotalCashAmount());
            }
            return BigDecimal.valueOf(command.payment.pointsUsed)
                    .multiply(pointsValuePolicyRate()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        if (command.payment.mode != PaymentFacts.MODE_CASH) {
            throw new IllegalArgumentException("未知付款方式 mode=" + command.payment.mode);
        }
        if (verifiedPaymentFacts == null) {
            throw new IllegalStateException("付款事实复核未配置,拒绝按上游自报值发权益");
        }
        WechatVerifiedPaymentFact.Reference reference = new WechatVerifiedPaymentFact.Reference(
                command.payment.verifiedFactId, command.payment.verifiedFactHash);
        WechatVerifiedPaymentFactService.Stored stored = verifiedPaymentFacts.requireConsumablePayment(reference);
        if (stored == null || stored.facts() == null || stored.facts().amountFen() <= 0) {
            throw new RegistrationRequestConflictException("付款事实金额不可用,拒绝发权益");
        }
        // 事实以"分"记账,业务金额以"元"两位小数:只在这里换算一次,不四舍五入到别的精度。
        BigDecimal factAmount = BigDecimal.valueOf(stored.facts().amountFen()).movePointLeft(2);
        // 渠道单号与金额都以事实为准(调用方传入的只作核对,不作真源)。
        command.payment.transactionId = stored.facts().transactionId();
        command.payment.verifiedAmount = factAmount;
        if (command.payment.verifiedAmount != null
                && money(command.payment.verifiedAmount).compareTo(money(factAmount)) != 0) {
            throw new RegistrationRequestConflictException("付款事实金额与调用方自报不一致");
        }
        if (order.getTotalCashAmount() != null
                && money(order.getTotalCashAmount()).compareTo(money(factAmount)) != 0) {
            throw new RegistrationRequestConflictException("付款事实金额与商品订单金额不一致");
        }
        return money(factAmount);
    }

    /**
     * 渠道成交:金额与单号只能来自渠道域服务端回读的事实。
     *
     * <p>调用方给的 hash/金额只用于定位与一致性核对,不构成付款证明;唯一绑定由渠道域判定
     * ({@code fact.bound}) —— 同一渠道单号只允许一次成交。
     */
    private BigDecimal verifiedChannelAmount(FulfillmentCommand command) {
        if (channelFactReader == null) {
            throw new IllegalStateException("渠道成交未启用:未配置渠道事实回读端口(真实 provider 未接入)");
        }
        PaymentFacts payment = command.payment;
        MarketingChannelFactReader.ChannelFact fact = channelFactReader.requireVerified(
                new MarketingChannelFactReader.ChannelFactQuery(
                        payment.externalFactId, payment.sourceProviderKey, payment.externalOrderNo,
                        // 渠道单没有本地 marketing_product_order(裁决 A),shape 只能来自渠道事实;
                        // 传 null 让 resolveShape 走"渠道分支",绝不去读任何商品配置。
                        command.memberId, productIdOf(command), quantityOf(command, null),
                        payment.verifiedAmount, "CNY"));
        if (fact == null) {
            throw new IllegalStateException("渠道事实不存在,拒绝成交");
        }
        if (fact.bound) {
            throw new RegistrationRequestConflictException("该渠道订单已完成成交,不可重复发放");
        }
        if (fact.externalOrderNo == null || !fact.externalOrderNo.equals(payment.externalOrderNo)) {
            throw new RegistrationRequestConflictException("渠道订单号与事实不匹配");
        }
        if (fact.amount == null || fact.amount.signum() <= 0) {
            throw new RegistrationRequestConflictException("渠道事实金额不可用,拒绝发权益");
        }
        if (payment.verifiedAmount != null && money(payment.verifiedAmount).compareTo(money(fact.amount)) != 0) {
            throw new RegistrationRequestConflictException("渠道事实金额与调用方自报不一致");
        }
        // 事实为准:回写交易号与金额,供落行使用。
        payment.verifiedAmount = money(fact.amount);
        payment.transactionId = fact.providerTransactionId;
        return money(fact.amount);
    }

    private String requireExternalOrderNo(FulfillmentCommand command) {
        String orderNo = command.payment.externalOrderNo;
        if (orderNo == null || orderNo.trim().isEmpty()) {
            throw new IllegalArgumentException("渠道成交必须提供渠道订单号");
        }
        String trimmed = orderNo.trim();
        if (command.parentOrderNo != null && !command.parentOrderNo.equals(trimmed)) {
            throw new RegistrationRequestConflictException("渠道订单号与父订单号不一致");
        }
        return trimmed;
    }

    /** 商品 id 由调用方放入命令(渠道校验需要核对 SKU);缺失即拒,不猜。 */
    private long productIdOf(FulfillmentCommand command) {
        if (command.productId == null || command.productId <= 0) {
            throw new IllegalArgumentException("渠道成交必须提供商品 id 以核对 SKU");
        }
        return command.productId;
    }

    /**
     * 份数与席位:**只从「已付事实」取** —— 站内单取成交快照,渠道单取已验证的券事实。
     *
     * <p>三条不许:
     * <ul>
     *   <li>不从内容行数猜({@code lines.size()} 是"覆盖了几个作品",城市包常是 3 个,与份数/席位无关);</li>
     *   <li><b>不读 live SKU 配置</b>(作者成交后改 {@code seat_count} 不能改变旧已付单的准入数量);</li>
     *   <li>不在缺快照时按 1 兜底或回落商品配置(那都是替用户改单)。</li>
     * </ul>
     *
     * <p>站内单席位 = 快照的 {@code seatCount} × 已付份数;调用方另给 seatCount 时必须与之相等,
     * 否则 409(抬量)。渠道单没有本地订单行,席位取自 M8 已验证的券事实(发行时按 SKU×份数算过)。
     */
    private OrderShape resolveShape(FulfillmentCommand command, MarketingProductOrder order) {
        OrderShape shape = new OrderShape();
        PaidShape paid = paidShape(order);
        shape.quantity = resolveQuantity(command, order, paid);
        long expectedSeats = 0L;
        if (paid != null) {
            // 乘法先到 long:seat_count × quantity 在 int 域会静默溢出(N×M 转成负数/小数)。
            expectedSeats = (long) paid.seatCountPerUnit * (long) shape.quantity;
            if (expectedSeats <= 0 || expectedSeats > Integer.MAX_VALUE) {
                throw new RegistrationRequestConflictException("席位数量超出范围,拒绝发放");
            }
        }
        if (command.seatCount > 0) {
            if (paid != null && command.seatCount != (int) expectedSeats) {
                // 站内单:调用方给的席位必须等于**已付快照**推出的席位数;不等 ⇒ 抬量(多领),拒绝。
                throw new RegistrationRequestConflictException(
                        "席位数量与已付订单不符:期望 " + expectedSeats + ",实到 " + command.seatCount);
            }
            shape.seatCount = command.seatCount;
        } else if (paid != null) {
            shape.seatCount = (int) expectedSeats;
        } else {
            // 渠道单:seatCount 由已验证的券事实给出(M8 发行时按 SKU×份数算并做范围校验);
            // 券没给时按"每份至少 1 席"兜底 —— 这是最小值,不是从 live SKU 猜出来的值。
            shape.seatCount = shape.quantity;
        }
        if (shape.seatCount < shape.quantity) {
            shape.seatCount = shape.quantity;
        }
        return shape;
    }

    /**
     * 份数只由服务端事实决定:渠道券冻结值 / 商品订单已付份数。
     *
     * <p>⚠️ 站内单(order != null)若调用方另给了 quantity,它**必须**等于订单已付份数 ——
     * 否则就是"拿已付 1 份的单去要 N 份准入",属于外部可覆盖已付数量多领,一律 409。
     */
    private int resolveQuantity(FulfillmentCommand command, MarketingProductOrder order, PaidShape paid) {
        // 份数以**已付事实**为准:优先成交快照,其次订单列(两者本应一致)。
        Integer snapshotQty = paid == null ? null : paid.quantity;
        Integer ordered = snapshotQty != null && snapshotQty > 0 ? snapshotQty
                : (order == null ? null : order.getQuantity());
        boolean orderedKnown = ordered != null && ordered > 0;
        if (command.quantity > 0) {
            if (orderedKnown && command.quantity != ordered) {
                throw new RegistrationRequestConflictException(
                        "份数与商品订单不符:订单 " + ordered + ",实到 " + command.quantity);
            }
            return command.quantity;
        }
        if (orderedKnown) {
            return ordered;
        }
        return 1;
    }

    /**
     * 重放时调用方声明的 shape 必须与**已落地行**一致:允许重放,不允许"同一父单换一个更大的份数/席位"。
     *
     * <p>以落库行(order_num)为冻结真源,不重算:商品配置之后改了 seat_count 也不能把已有单判成冲突。
     */
    private void assertReplayShapeUnchanged(FulfillmentCommand command, List<CmsRegistration> rows,
                                            MarketingProductOrder order) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        PaidShape paid = paidShape(order);
        Integer ordered = paid != null && paid.quantity != null && paid.quantity > 0 ? paid.quantity
                : (order == null ? null : order.getQuantity());
        if (command.quantity > 0 && ordered != null && ordered > 0 && command.quantity != ordered) {
            throw new RegistrationRequestConflictException(
                    "重放份数与原订单不符:订单 " + ordered + ",实到 " + command.quantity);
        }
        if (command.seatCount > 0) {
            long persistedSeats = 0L;
            for (CmsRegistration row : rows) {
                if (row != null && row.getOrderNum() != null && row.getOrderNum() > persistedSeats) {
                    persistedSeats = row.getOrderNum();
                }
            }
            if (persistedSeats > 0 && command.seatCount != persistedSeats) {
                throw new RegistrationRequestConflictException(
                        "重放席位数与原准入不符:原 " + persistedSeats + ",实到 " + command.seatCount);
            }
        }
    }

    /**
     * 已付 shape:**只读成交时冻结的订单快照**,绝不读 live SKU 配置。
     *
     * <p>为什么必须这样:商品作者可以在成交后改 SKU 的 {@code seat_count},
     * 而 {@code marketing_product_order.product_snapshot_json} 是下单那一刻服务端写死的副本。
     * 若这里回读 live 配置,已经付过钱的旧单在"首次使用激活"或重放时就会跟着改数量 ——
     * 等于事后替用户改单,而且改大就是多领、改小就是少给。
     *
     * <p>找不到快照/快照没有 seatCount 时抛 {@link #SHAPE_SNAPSHOT_MISSING}:
     * 不猜、不回落 live、不按 1 兜。营销订单表尚未上线,不存在需要迁就的真实旧单;
     * 快照里补 {@code seatCount} 是成交侧(MS)的一行改动,见 manifest 的跨域依赖。
     */
    private PaidShape paidShape(MarketingProductOrder order) {
        if (order == null) {
            return null;   // 渠道单:没有本地订单,shape 由已验证的渠道事实给出
        }
        String json = order.getProductSnapshotJson();
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalStateException(SHAPE_SNAPSHOT_MISSING
                    + ":订单缺少成交快照,无法确定已付份数/席位 orderNo=" + order.getOrderNo());
        }
        com.alibaba.fastjson2.JSONObject snapshot;
        try {
            snapshot = com.alibaba.fastjson2.JSON.parseObject(json);
        } catch (RuntimeException broken) {
            throw new IllegalStateException(SHAPE_SNAPSHOT_MISSING
                    + ":订单成交快照无法解析 orderNo=" + order.getOrderNo(), broken);
        }
        Integer seatCount = snapshot == null ? null : snapshot.getInteger("seatCount");
        if (seatCount == null || seatCount <= 0) {
            throw new IllegalStateException(SHAPE_SNAPSHOT_MISSING
                    + ":成交快照未冻结 seatCount(成交侧需写入),拒绝回落到当前商品配置 orderNo="
                    + order.getOrderNo());
        }
        return new PaidShape(seatCount, snapshot.getInteger("quantity"));
    }

    /** 份数(渠道事实核对用):与 {@link #resolveShape} 同一口径。 */
    private int quantityOf(FulfillmentCommand command, MarketingProductOrder order) {
        return resolveShape(command, order).quantity;
    }

    /** 成交时冻结的已付 shape(来自 product_snapshot_json):每份席位数 + 份数。 */
    private static final class PaidShape {
        final int seatCountPerUnit;
        final Integer quantity;

        PaidShape(int seatCountPerUnit, Integer quantity) {
            this.seatCountPerUnit = seatCountPerUnit;
            this.quantity = quantity;
        }
    }

    /** 一批准入行的父级形状(份数/席位),同一订单内所有行一致。 */
    private static final class OrderShape {
        int quantity = 1;
        int seatCount = 1;
    }


    private FulfillmentOutcome replayByParentOrderNo(String parentOrderNo) {
        List<Long> ids = findGrantedRegistrationIds(parentOrderNo);
        if (ids.isEmpty()) {
            return null;
        }
        FulfillmentOutcome outcome = new FulfillmentOutcome();
        outcome.parentOrderNo = parentOrderNo;
        outcome.registrationIds = ids;
        outcome.replayed = true;
        return outcome;
    }

    /** 分摊守恒:Σ payable == 商品实付;每行 payable ≤ 票面。违反即整单拒绝(不落半单)。 */
    private void assertApportionment(List<GrantLine> lines, BigDecimal verifiedAmount) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal listTotal = BigDecimal.ZERO;
        for (GrantLine line : lines) {
            BigDecimal payable = money(line.payableAmount);
            BigDecimal list = money(line.listAmount);
            // 不假造价格:分买价 ≤0、或"有应付却没有票面" ⇒ 上游没有真实价格,整单拒绝(不按 0/1 元补)。
            if (list.signum() <= 0) {
                throw new RegistrationRequestConflictException(
                        "覆盖主题缺少真实分买价,拒绝按 0/1 元假造票面 topicId=" + line.topicId);
            }
            if (payable.compareTo(list) > 0) {
                throw new RegistrationRequestConflictException(
                        "分摊应付不得超过票面 topicId=" + line.topicId);
            }
            total = total.add(payable);
            listTotal = listTotal.add(list);
        }
        if (total.compareTo(money(verifiedAmount)) != 0) {
            throw new RegistrationRequestConflictException(
                    "分摊不守恒:行应付合计 " + total.toPlainString() + " ≠ 实付 " + money(verifiedAmount));
        }
        if (listTotal.signum() < 0) {
            throw new RegistrationRequestConflictException("票面合计为负");
        }
    }

    private CmsRegistration buildRow(FulfillmentCommand command, MarketingProductOrder order, GrantLine line,
                                     CmsTopic topic, String parentOrderNo, boolean firstRow, boolean primaryRow,
                                     OrderShape shape, Date expiresAtOverride) {
        CmsRegistration row = new CmsRegistration();
        row.setRegistrationNo(generateRegistrationNo());
        row.setParentOrderNo(parentOrderNo);
        row.setOwnerType(1L);
        row.setOwnerId(line.topicId);
        row.setTopicId(line.topicId);
        row.setActivityTitle(topic.getName());
        row.setMemberId(command.memberId);
        row.setRealName(null);
        row.setPhone(null);
        row.setOrderNum((long) shape.seatCount);
        row.setTicketId(null);
        row.setPurchaseKind(line.exploreCapacity ? PurchaseKind.EXPLORE_PASS : PurchaseKind.SELF_PLAY_PASS);
        row.setOriginalAmount(money(line.listAmount));
        row.setTotalAmount(money(line.listAmount));
        row.setPayableAmount(money(line.payableAmount));
        row.setDiscountAmount(money(line.listAmount).subtract(money(line.payableAmount)));
        // 有效期:显式声明永久才允许 NULL;其余按天数/固定窗口落死。
        row.setExpiresAt(expiresAtOverride == null ? expiresAtOf(line) : expiresAtOverride);
        // 退款快照:只有 ③ 探索票能由既有 RefundPolicy 生成;其它 SKU 必须由商品配置给 code/version,
        // 否则拒绝 —— 不能用 NULL 让读侧回落现取(会追溯改写已售单的退款窗口)。
        applyRefundSnapshot(row, line, topic);
        row.setPointUsed(firstRow ? pointsOf(command) : 0L);
        row.setPointDeductAmount(BigDecimal.ZERO);
        row.setPointDeductRate(pointsValuePolicyRate());
        row.setPaymentType(command.payment.mode == PaymentFacts.MODE_POINTS ? 0 : 1);
        row.setPaymentStatus(2);
        row.setRegistrationStatus(2);
        row.setPaymentTime(new Date());
        row.setPayExpireTime(row.getPaymentTime());
        // 平台费率是结算依据:缺失就拒单,绝不按 0 兜底(0 = 平台不分账,M5 会错账)。
        if (topic.getFeePlatformRate() == null) {
            throw new IllegalStateException("主题缺少平台费率快照,拒绝落单 topicId=" + topic.getId());
        }
        row.setFeePlatformRate(topic.getFeePlatformRate());
        // 支付渠道总额与事实:仅首行一处持有;其余行为 0 / NULL。
        row.setWechatPaymentAmount(firstRow && command.payment.mode == PaymentFacts.MODE_CASH
                ? money(command.payment.verifiedAmount) : BigDecimal.ZERO);
        row.setTransactionId(firstRow && command.payment.mode == PaymentFacts.MODE_CASH
                ? command.payment.transactionId : null);
        row.setPointPaymentAmount(BigDecimal.ZERO);
        if (primaryRow) {
            row.setRequestId(command.requestId.trim());
        }
        row.setCreateBy(String.valueOf(command.memberId));
        row.setCreateTime(new Date());
        return row;
    }

    private Long pointsOf(FulfillmentCommand command) {
        return command.payment.mode == PaymentFacts.MODE_POINTS ? command.payment.pointsUsed : 0L;
    }

    private BigDecimal pointsValuePolicyRate() {
        return pointsValuePolicy == null
                ? com.chengyinhub.business.service.support.PointsValuePolicy.DEFAULT_RATE
                : pointsValuePolicy.rate();
    }

    private Date expiresAtOf(GrantLine line) {
        switch (line.validityKind) {
            case GrantLine.VALIDITY_NONE:
                return null;
            case GrantLine.VALIDITY_RELATIVE_DAYS:
                if (line.validDays <= 0) {
                    throw new IllegalStateException("按天有效期未配置 validDays topicId=" + line.topicId);
                }
                return DateUtils.addDays(new Date(), line.validDays);
            case GrantLine.VALIDITY_FIXED_WINDOW:
                if (line.fixedValidTo == null) {
                    throw new IllegalStateException("固定窗口商品缺少 validTo topicId=" + line.topicId);
                }
                return line.fixedValidTo;
            default:
                throw new IllegalStateException("未知有效期类型 topicId=" + line.topicId
                        + " kind=" + line.validityKind);
        }
    }

    /**
     * 退款快照:只认 {@link RefundPolicy} 这一个真源,**不新增档位、不任填 code**。
     *
     * <p>逐类复用现状(全部已在生产运行):
     * <ul>
     *   <li><b>③ 探索票</b> ⇒ {@code snapshotOnPurchase(EXPLORE_PASS, 到期点)}:code
     *       {@code EXPLORE_END_100_0} + deadline = 票面有效期。原样照抄。</li>
     *   <li><b>② 自玩/pass</b> ⇒ 原政策事实是「履约窗开始前可退」:
     *       {@code RefundPolicy.evaluateTopicFulfillmentWindow} 用
     *       {@code FulfillmentWindowResolver.resolve(topic, null).getStartInclusive()} 作截止点。
     *       所以这里**冻结同一口钟**(主题履约窗开始时刻),不新造"购买后 N 天"之类退期。
     *       ⚠️ {@link RefundPolicyCode} 至今只有 ③ 的码;给 ② 造一个占位码正是它注释里禁止的事
     *       (会让人 grep 到后以为 ② 已实现)。故 code/version 留空,只落 deadline ——
     *       {@code evaluate} 对「snapshotDeadline 非空」优先使用,不需要 code 参与判定。</li>
     * </ul>
     *
     * <p>履约窗缺失(主题 start/end 未配)⇒ 原政策也判不了(会转人工),此时**该 SKU 不可售**并报精确缺口。
     */
    private void applyRefundSnapshot(CmsRegistration row, GrantLine line, CmsTopic topic) {
        Integer purchaseKind = row.getPurchaseKind();
        if (Integer.valueOf(PurchaseKind.EXPLORE_PASS).equals(purchaseKind)) {
            RefundPolicy.Snapshot snapshot = RefundPolicy.snapshotOnPurchase(purchaseKind, null, row.getExpiresAt());
            // 促销商品发放的 ③ 没有票种开始时刻:不凭空捏三档的钟,沿用到期锚点(EXPLORE_END_100_0)。
            if (snapshot == null) {
                throw new IllegalStateException(REFUND_DECISION_POINT_MISSING
                        + ":③ 探索票缺少到期锚点 topicId=" + line.topicId);
            }
            row.setRefundPolicyCode(snapshot.code);
            row.setRefundPolicyVersion(snapshot.version);
            row.setRefundDeadline(snapshot.deadline);
            return;
        }
        // ② 自玩/pass:沿用 evaluateTopicFulfillmentWindow 的同一口钟。
        com.chengyinhub.business.service.support.FulfillmentWindowResolver.Window window;
        try {
            window = com.chengyinhub.business.service.support.FulfillmentWindowResolver.resolve(topic, null);
        } catch (IllegalStateException missingWindow) {
            throw new IllegalStateException(REFUND_DECISION_POINT_MISSING
                    + ":主题履约窗(startDate/endDate)未配置,退款判定只能转人工,该商品暂不可售 topicId="
                    + line.topicId);
        }
        row.setRefundDeadline(window.getStartInclusive());
        // code/version 留空:政策真源里没有 ② 的码,不伪造(见 {@link RefundPolicyCode} 注释)。
    }

    private FulfillmentOutcome replay(MarketingProductOrder order) {
        if (order.getPrimaryRegistrationId() == null) {
            return null;
        }
        CmsRegistration primary = cmsRegistrationMapper.selectCmsRegistrationById(order.getPrimaryRegistrationId());
        if (primary == null) {
            return null;
        }
        FulfillmentOutcome outcome = replayByPrimary(primary);
        return outcome;
    }

    private FulfillmentOutcome replayByPrimary(CmsRegistration primary) {
        FulfillmentOutcome outcome = new FulfillmentOutcome();
        outcome.parentOrderNo = primary.getParentOrderNo();
        outcome.registrationIds = findGrantedRegistrationIds(primary.getParentOrderNo());
        outcome.replayed = true;
        outcome.pendingActivation = false;
        return outcome;
    }

    private CmsRegistration findRow(long memberId, String requestId) {
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setRequestId(requestId);
        List<CmsRegistration> rows = cmsRegistrationMapper.selectCmsRegistrationList(probe);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private String generateRegistrationNo() {
        String dateStr = DateUtils.parseDateToStr("yyyyMMdd", new Date());
        String randomStr = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return "M" + dateStr + randomStr;   // M = 营销商品单
    }

    private String toJson(List<Long> ids) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) out.append(',');
            out.append(ids.get(i));
        }
        return out.append(']').toString();
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 按主题汇总行数,给对账/客服定位用(同主题多行合并)。 */
    Map<Long, BigDecimal> payableByTopic(List<GrantLine> lines) {
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        if (lines == null) return out;
        for (GrantLine line : lines) {
            out.merge(line.topicId, money(line.payableAmount), BigDecimal::add);
        }
        return out;
    }
}
