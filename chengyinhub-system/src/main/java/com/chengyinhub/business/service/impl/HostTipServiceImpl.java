package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.HostTipConfig;
import com.chengyinhub.business.domain.HostTipIntent;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.HostTipConfigRequest;
import com.chengyinhub.business.domain.dto.HostTipPrepareRequest;
import com.chengyinhub.business.domain.dto.HostTipPrepareResult;
import com.chengyinhub.business.exception.BountyTipException;
import com.chengyinhub.business.mapper.HostTipConfigMapper;
import com.chengyinhub.business.mapper.HostTipIntentMapper;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IHostTipService;
import com.chengyinhub.business.service.support.CanonicalDigest;

/**
 * 主持小费实现。
 *
 * <p><b>资金结构(写死在代码里,不靠配置兜)</b>:
 * <ul>
 *   <li>{@code hostSharePercent} 恒为 100:平台不抽成。想抽成必须改代码并重新审批,
 *       不接受"配置里写 90"这种悄悄改政策的路径。</li>
 *   <li>通道手续费由谁承担是<b>独立配置</b>({@code channelFeeBearer}),
 *       不会把代收 host 的资金记成平台支出,也不会在平台"纯支出"里混算。</li>
 *   <li>本类不碰任何余额/钱包/分账 API。小费的收款与转付依赖受支持的通道产品,
 *       当前没有,所以 {@link #prepare} 只会落"通道未启用"并抛可区分的拒绝。</li>
 * </ul>
 *
 * <p><b>顾客自愿</b>:小费不是订单应付项。{@code orderRefId} 只做归属校验,
 * 既不增加该订单金额,也不触发其退款或结算。
 *
 * @author chengyinhub
 */
@Service
public class HostTipServiceImpl implements IHostTipService
{
    private static final Logger log = LoggerFactory.getLogger(HostTipServiceImpl.class);

    /** 支持的通道白名单。为空 = 没有任何可用通道 ⇒ 一律不启用。 */
    private static final java.util.Set<String> SUPPORTED_CHANNELS = java.util.Set.of();

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000.00");

    private final HostTipConfigMapper configMapper;
    private final HostTipIntentMapper intentMapper;
    private final UmsMemberMapper memberMapper;
    private final OmsOrderMapper orderMapper;

    public HostTipServiceImpl(HostTipConfigMapper configMapper,
                              HostTipIntentMapper intentMapper,
                              UmsMemberMapper memberMapper,
                              OmsOrderMapper orderMapper) {
        this.configMapper = configMapper;
        this.intentMapper = intentMapper;
        this.memberMapper = memberMapper;
        this.orderMapper = orderMapper;
    }

    // ------------------------------------------------------------------ 配置

    @Override
    @Transactional
    public HostTipConfig upsertConfig(long hostMemberId, HostTipConfigRequest request) {
        requireMember(hostMemberId);
        if (request == null) {
            throw BountyTipException.badRequest("REQUEST_REQUIRED", "缺少小费配置");
        }
        long clubId = request.getClubId() == null ? 0L : request.getClubId();
        if (clubId < 0) {
            throw BountyTipException.badRequest("CLUB_INVALID", "俱乐部标识不合法");
        }
        int bearer = request.getChannelFeeBearer() == null
                ? HostTipConfig.FEE_BEARER_HOST : request.getChannelFeeBearer();
        if (bearer != HostTipConfig.FEE_BEARER_HOST && bearer != HostTipConfig.FEE_BEARER_PLATFORM) {
            throw BountyTipException.badRequest("FEE_BEARER_INVALID", "通道手续费承担方不合法");
        }
        BigDecimal minAmount = request.getMinAmount() == null ? BigDecimal.ZERO : request.getMinAmount();
        if (minAmount.signum() < 0) {
            throw BountyTipException.badRequest("MIN_AMOUNT_INVALID", "最低小费不能为负");
        }
        String currency = normalizeCurrency(request.getCurrency());
        String channel = normalizeChannel(request.getPaymentChannel());
        // 启用必须有受支持的通道:否则顾客看到一个点不动的按钮,而且"已启用"是假状态。
        // 通道未支持时明确拒绝,不把 enabled 悄悄写 0(那是静默改写用户意图)。
        boolean wantsEnabled = Boolean.TRUE.equals(request.getEnabled());
        if (wantsEnabled && (channel == null || !SUPPORTED_CHANNELS.contains(channel))) {
            throw BountyTipException.channelNotEnabled(
                    "小费收款通道尚未启用:无法开通小费,可以先把配置保存为未启用");
        }
        boolean enabled = wantsEnabled;

        HostTipConfig existing = configMapper.selectByHostAndClub(hostMemberId, clubId);
        if (existing == null) {
            HostTipConfig row = new HostTipConfig();
            row.setHostMemberId(hostMemberId);
            row.setClubId(clubId);
            row.setEnabled(enabled ? 1 : 0);
            // 固定 100:平台不抽成。
            row.setHostSharePercent(100);
            row.setChannelFeeBearer(bearer);
            row.setMinAmount(minAmount);
            row.setCurrency(currency);
            row.setPaymentChannel(channel);
            row.setConfigVersion(1);
            try {
                configMapper.insertConfig(row);
            } catch (DuplicateKeyException duplicate) {
                // uk_tip_config_host_scope 撞车 = 并发首次配置;读回既有行并按更新处理。
                return updateExisting(configMapper.selectByHostAndClub(hostMemberId, clubId),
                        enabled, bearer, minAmount, currency, channel);
            }
            return configMapper.selectById(row.getId());
        }
        return updateExisting(existing, enabled, bearer, minAmount, currency, channel);
    }

    @Override
    public List<HostTipConfig> listMyConfigs(long hostMemberId) {
        requireMember(hostMemberId);
        return configMapper.selectByHost(hostMemberId);
    }

    private HostTipConfig updateExisting(HostTipConfig existing, boolean enabled, int bearer,
                                        BigDecimal minAmount, String currency, String channel) {
        existing.setEnabled(enabled ? 1 : 0);
        existing.setChannelFeeBearer(bearer);
        existing.setMinAmount(minAmount);
        existing.setCurrency(currency);
        existing.setPaymentChannel(channel);
        configMapper.updateConfig(existing);
        return configMapper.selectById(existing.getId());
    }

    // ------------------------------------------------------------------ 顾客支付意向

    @Override
    @Transactional
    public HostTipPrepareResult prepare(long payerMemberId, HostTipPrepareRequest request) {
        requireMember(payerMemberId);
        if (request == null) {
            throw BountyTipException.badRequest("REQUEST_REQUIRED", "缺少小费内容");
        }
        Long hostMemberId = request.getHostMemberId();
        if (hostMemberId == null || hostMemberId <= 0) {
            throw BountyTipException.badRequest("HOST_REQUIRED", "必须说明小费给谁");
        }
        if (Objects.equals(hostMemberId, payerMemberId)) {
            throw BountyTipException.badRequest("SELF_TIP_NOT_ALLOWED", "不能给自己打赏");
        }
        requireMember(hostMemberId);

        long clubId = request.getClubId() == null ? 0L : request.getClubId();
        HostTipConfig config = configMapper.selectByHostAndClub(hostMemberId, clubId);
        if (config == null) {
            throw BountyTipException.notFound("TIP_CONFIG_NOT_FOUND", "该主持尚未开通小费");
        }
        if (!Integer.valueOf(1).equals(config.getEnabled())) {
            throw BountyTipException.conflict("TIP_NOT_ENABLED", "该主持未启用小费");
        }
        BigDecimal amount = request.getAmount();
        if (amount == null || amount.signum() <= 0) {
            throw BountyTipException.badRequest("AMOUNT_INVALID", "小费金额必须大于 0");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw BountyTipException.badRequest("AMOUNT_TOO_LARGE", "小费金额超出上限");
        }
        BigDecimal minAmount = config.getMinAmount() == null ? BigDecimal.ZERO : config.getMinAmount();
        if (amount.compareTo(minAmount) < 0) {
            throw BountyTipException.badRequest("AMOUNT_BELOW_MIN", "小费金额低于主持设定的下限");
        }
        amount = amount.setScale(2, java.math.RoundingMode.DOWN);

        int refType = HostTipIntent.REF_NONE;
        Long orderRefId = request.getOrderRefId();
        if (orderRefId != null) {
            OmsOrder order = orderMapper.selectOmsOrderById(orderRefId);
            // 关联订单必须属于付款顾客本人,否则就是借别人的单给小费(还可能被用来探测他人订单)。
            if (order == null || !Objects.equals(order.getMemberId(), payerMemberId)) {
                throw BountyTipException.notFound("ORDER_NOT_FOUND", "关联订单不存在");
            }
            refType = HostTipIntent.REF_OMS_ORDER;
        }

        String requestId = request.getRequestId() == null ? "" : request.getRequestId().trim();
        if (requestId.isEmpty() || requestId.length() > 64) {
            throw BountyTipException.badRequest("REQUEST_ID_REQUIRED", "缺少幂等请求标识");
        }
        String idempotencyKey = CanonicalDigest.join(
                String.valueOf(payerMemberId), requestId);
        HostTipIntent existingIntent = intentMapper.selectByIdempotencyKey(idempotencyKey);
        if (existingIntent != null) {
            // 幂等重放:返回同一笔,不新建第二笔。
            return HostTipPrepareResult.of(existingIntent,
                    Integer.valueOf(HostTipIntent.STATUS_AWAITING_PAYMENT).equals(existingIntent.getStatus()),
                    Integer.valueOf(HostTipIntent.STATUS_CHANNEL_UNSUPPORTED).equals(existingIntent.getStatus())
                            ? "CHANNEL_NOT_ENABLED" : null);
        }

        String channel = normalizeChannel(config.getPaymentChannel());
        boolean channelAvailable = channel != null && SUPPORTED_CHANNELS.contains(channel);

        HostTipIntent intent = new HostTipIntent();
        intent.setConfigId(config.getId());
        intent.setPayerMemberId(payerMemberId);
        intent.setHostMemberId(hostMemberId);
        intent.setClubId(clubId);
        intent.setOrderRefType(refType);
        intent.setOrderRefId(refType == HostTipIntent.REF_OMS_ORDER ? orderRefId : null);
        intent.setAmount(amount);
        intent.setCurrency(config.getCurrency());
        // 配置快照:分成与手续费承担在下单时冻结,后续改配置不影响这单。
        intent.setHostSharePercent(100);
        intent.setChannelFeeBearer(config.getChannelFeeBearer());
        intent.setPaymentChannel(channel);
        intent.setStatus(channelAvailable
                ? HostTipIntent.STATUS_AWAITING_PAYMENT
                : HostTipIntent.STATUS_CHANNEL_UNSUPPORTED);
        intent.setIdempotencyKey(idempotencyKey);
        intent.setNote(trimTo(request.getNote(), 500));
        try {
            intentMapper.insertIntent(intent);
        } catch (DuplicateKeyException duplicate) {
            HostTipIntent winner = intentMapper.selectByIdempotencyKey(idempotencyKey);
            if (winner == null) throw duplicate;
            return HostTipPrepareResult.of(winner,
                    Integer.valueOf(HostTipIntent.STATUS_AWAITING_PAYMENT).equals(winner.getStatus()),
                    null);
        }

        if (!channelAvailable) {
            log.info("[host-tip] intent {} recorded as CHANNEL_UNSUPPORTED: no supported payment channel configured",
                    intent.getId());
            // 明确未启用:501 + 可区分 reasonCode,不假装收款、不加任何余额。
            throw BountyTipException.channelNotEnabled(
                    "小费收款通道尚未启用,已记录意向但不产生支付");
        }
        return HostTipPrepareResult.of(intent, true, null);
    }

    @Override
    @Transactional
    public HostTipIntent cancel(long payerMemberId, long intentId) {
        requireMember(payerMemberId);
        HostTipIntent intent = intentMapper.selectById(intentId);
        if (intent == null || !Objects.equals(intent.getPayerMemberId(), payerMemberId)) {
            throw BountyTipException.notFound("TIP_INTENT_NOT_FOUND", "小费记录不存在");
        }
        if (intentMapper.cancelIfOpen(intentId, payerMemberId) != 1) {
            throw BountyTipException.conflict("CANCEL_NOT_ALLOWED", "该小费当前状态不允许取消");
        }
        return intentMapper.selectById(intentId);
    }

    @Override
    public List<HostTipIntent> listMine(long payerMemberId) {
        requireMember(payerMemberId);
        return intentMapper.selectByPayer(payerMemberId);
    }

    @Override
    public List<HostTipIntent> listForHost(long hostMemberId) {
        requireMember(hostMemberId);
        return intentMapper.selectByHost(hostMemberId);
    }

    // ------------------------------------------------------------------ 通道入口

    @Override
    @Transactional
    public HostTipIntent confirmPaidByChannel(long intentId, String externalTradeNo, String paymentChannel) {
        HostTipIntent intent = intentMapper.selectById(intentId);
        if (intent == null) {
            throw BountyTipException.notFound("TIP_INTENT_NOT_FOUND", "小费记录不存在");
        }
        // 通道必须在白名单里:这是唯一能写 PAID 的入口,不能由内部调用方"手动标已付"。
        String channel = normalizeChannel(paymentChannel);
        if (channel == null || !SUPPORTED_CHANNELS.contains(channel)) {
            throw BountyTipException.channelNotEnabled("收款通道未启用:不得手动标记已支付");
        }
        String tradeNo = externalTradeNo == null ? "" : externalTradeNo.trim();
        if (tradeNo.isEmpty()) {
            throw BountyTipException.badRequest("TRADE_NO_REQUIRED", "缺少通道交易号");
        }
        if (intentMapper.markPaidIfAwaiting(intentId, tradeNo) != 1) {
            throw BountyTipException.conflict("PAY_STATE_CONFLICT", "该小费当前状态不能置为已支付");
        }
        return intentMapper.selectById(intentId);
    }

    @Override
    @Transactional
    public HostTipIntent confirmRefundedByChannel(long intentId, String settlementRef, String paymentChannel) {
        HostTipIntent intent = intentMapper.selectById(intentId);
        if (intent == null) {
            throw BountyTipException.notFound("TIP_INTENT_NOT_FOUND", "小费记录不存在");
        }
        String channel = normalizeChannel(paymentChannel);
        if (channel == null || !SUPPORTED_CHANNELS.contains(channel)) {
            throw BountyTipException.channelNotEnabled("收款通道未启用:不得确认退款");
        }
        if (intentMapper.markRefundedIfPaid(intentId, trimTo(settlementRef, 64)) != 1) {
            throw BountyTipException.conflict("REFUND_STATE_CONFLICT", "该小费当前状态不能退款");
        }
        return intentMapper.selectById(intentId);
    }

    // ------------------------------------------------------------------ 内部

    private void requireMember(long memberId) {
        if (memberId <= 0) {
            throw BountyTipException.unauthorized("LOGIN_REQUIRED", "请先登录");
        }
        UmsMember member = memberMapper.selectUmsMemberById(memberId);
        // 停用账号不能收款/付款;这与登录态是两件事,单独校验。
        if (member == null || !Long.valueOf(1L).equals(member.getStatus())) {
            throw BountyTipException.forbidden("MEMBER_UNAVAILABLE", "账号不可用");
        }
    }

    private static String normalizeCurrency(String currency) {
        String normalized = currency == null ? "" : currency.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw BountyTipException.badRequest("CURRENCY_INVALID", "币种必须是 3 位大写 ISO-4217 代码");
        }
        return normalized;
    }

    /** 通道标识:只有白名单里的才算"受支持";其他一律视为未启用。 */
    private static String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) return null;
        String normalized = channel.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() > 32 || !normalized.matches("[a-z0-9][a-z0-9_-]*")) return null;
        return normalized;
    }

    private static String trimTo(String text, int max) {
        if (text == null) return null;
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
