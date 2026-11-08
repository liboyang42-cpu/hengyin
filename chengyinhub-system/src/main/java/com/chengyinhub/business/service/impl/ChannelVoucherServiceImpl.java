package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelOrderVerification;
import com.chengyinhub.business.domain.ChannelRedemption;
import com.chengyinhub.business.domain.ChannelVoucher;
import com.chengyinhub.business.domain.ChannelVoucherAccount;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.ChannelGrantResult;
import com.chengyinhub.business.domain.dto.ChannelGrantRevocation;
import com.chengyinhub.business.domain.dto.ChannelVoucherIssueRequest;
import com.chengyinhub.business.domain.dto.ChannelVoucherRevokeRequest;
import com.chengyinhub.business.domain.dto.ChannelVoucherView;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.mapper.ChannelOrderVerificationMapper;
import com.chengyinhub.business.mapper.ChannelRedemptionMapper;
import com.chengyinhub.business.mapper.ChannelVoucherAccountMapper;
import com.chengyinhub.business.mapper.ChannelVoucherMapper;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.IChannelVoucherService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.support.ChannelGrantPlanner;
import com.chengyinhub.business.service.support.ChannelRedemptionGrantPortResolver;
import com.chengyinhub.business.service.support.ChannelVoucherCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 渠道兑换码发行/撤销。
 *
 * <p>发行只消费已 VERIFIED 的渠道订单；商品与数量取自验证事实，运营不能改。
 * 撤销按三条边界执行：
 * <ul>
 *   <li>未兑换：只释放库存预留，不产生任何权益变化；</li>
 *   <li>已兑换：调用权威 grant 端口撤销原注册事实；端口未接线则整体失败，不静默；</li>
 *   <li>已使用：端口必须拒绝回收（已核销不复活），本类原样上抛。</li>
 * </ul>
 *
 * <p>本类不依赖 {@code MarketingEntitlement}/{@code MarketingProductOrder}：
 * 商品表只用于目录/绑定读取；可玩与资金事实全部由 M2-F 的注册桥接产出。
 */
@Service
public class ChannelVoucherServiceImpl implements IChannelVoucherService {

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;
    private static final long DEFAULT_VALID_DAYS = 30L;
    private static final long MAX_VALID_DAYS = 90L;

    private final ChannelVoucherAccountMapper accountMapper;
    private final ChannelOrderVerificationMapper verificationMapper;
    private final ChannelVoucherMapper voucherMapper;
    private final ChannelRedemptionMapper redemptionMapper;
    private final MarketingProductMapper productMapper;
    private final OmsTicketMapper ticketMapper;
    private final IProductRoleService productRoleService;
    private final ChannelRedemptionGrantPortResolver grantPortResolver;

    public ChannelVoucherServiceImpl(
            ChannelVoucherAccountMapper accountMapper,
            ChannelOrderVerificationMapper verificationMapper,
            ChannelVoucherMapper voucherMapper,
            ChannelRedemptionMapper redemptionMapper,
            MarketingProductMapper productMapper,
            OmsTicketMapper ticketMapper,
            IProductRoleService productRoleService,
            ChannelRedemptionGrantPortResolver grantPortResolver) {
        this.accountMapper = accountMapper;
        this.verificationMapper = verificationMapper;
        this.voucherMapper = voucherMapper;
        this.redemptionMapper = redemptionMapper;
        this.productMapper = productMapper;
        this.ticketMapper = ticketMapper;
        this.productRoleService = productRoleService;
        this.grantPortResolver = grantPortResolver;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChannelVoucherView issue(long operatorUserId, ChannelVoucherIssueRequest request) {
        requireOperator(operatorUserId);
        if (request == null || request.getVerificationId() == null || request.getVerificationId() <= 0) {
            throw ChannelVoucherException.badRequest("VERIFICATION_ID_REQUIRED", "缺少已验证渠道订单");
        }
        String requestId = requireText(request.getRequestId(), 64, "REQUEST_ID_REQUIRED");
        Date now = new Date();
        Date expiresAt = request.getExpiresAt() == null
                ? new Date(now.getTime() + TimeUnit.DAYS.toMillis(DEFAULT_VALID_DAYS))
                : request.getExpiresAt();
        if (!expiresAt.after(now)) {
            throw ChannelVoucherException.badRequest("EXPIRES_AT_INVALID", "兑换码有效期必须晚于当前时间");
        }
        if (expiresAt.getTime() > now.getTime() + TimeUnit.DAYS.toMillis(MAX_VALID_DAYS)) {
            throw ChannelVoucherException.badRequest("EXPIRES_AT_INVALID", "兑换码有效期不能超过 90 天");
        }
        int subjectMode = request.getSubjectMode() == null
                ? ChannelVoucher.SUBJECT_OPEN : request.getSubjectMode();
        if (subjectMode != ChannelVoucher.SUBJECT_OPEN
                && subjectMode != ChannelVoucher.SUBJECT_BOUND_MEMBER) {
            throw ChannelVoucherException.badRequest("SUBJECT_MODE_INVALID", "持有人规则不合法");
        }
        if (subjectMode == ChannelVoucher.SUBJECT_BOUND_MEMBER
                && (request.getBoundMemberId() == null || request.getBoundMemberId() <= 0)) {
            throw ChannelVoucherException.badRequest("BOUND_MEMBER_REQUIRED", "绑定持有人时必须指定会员");
        }
        if (subjectMode == ChannelVoucher.SUBJECT_OPEN && request.getBoundMemberId() != null) {
            throw ChannelVoucherException.badRequest("BOUND_MEMBER_INVALID", "开放持有人码不能绑定会员");
        }
        Long inventoryTicketId = request.getInventoryTicketId();
        Integer inventoryQuantity = request.getInventoryQuantity();
        if (inventoryTicketId != null && inventoryTicketId <= 0) {
            throw ChannelVoucherException.badRequest("INVENTORY_TICKET_INVALID", "库存票种不合法");
        }
        if (inventoryTicketId != null && (inventoryQuantity == null || inventoryQuantity <= 0)) {
            throw ChannelVoucherException.badRequest("INVENTORY_QUANTITY_REQUIRED", "库存预留数量必须大于 0");
        }
        if (inventoryTicketId == null && inventoryQuantity != null) {
            throw ChannelVoucherException.badRequest("INVENTORY_TICKET_REQUIRED", "预留库存必须指定票种");
        }

        ChannelOrderVerification verification = verificationMapper.selectByIdForUpdate(request.getVerificationId());
        if (verification == null) {
            throw ChannelVoucherException.notFound("VERIFICATION_NOT_FOUND", "验证记录不存在");
        }
        if (!Integer.valueOf(ChannelOrderVerification.STATUS_VERIFIED).equals(verification.getStatus())) {
            throw ChannelVoucherException.conflict("VERIFICATION_NOT_VERIFIED", "渠道订单未通过可信验证，不能发码");
        }
        if (verification.getAccountId() == null) {
            throw ChannelVoucherException.conflict("ACCOUNT_NOT_FOUND", "验证记录未绑定渠道主体");
        }
        ChannelVoucherAccount account = accountMapper.selectByIdForUpdate(verification.getAccountId());
        if (account == null || !account.issuableAt(now)) {
            throw ChannelVoucherException.conflict("CHANNEL_INACTIVE", "渠道未启用、合同缺失或不在生效窗口");
        }
        Long productId = verification.getProductId();
        Integer quantity = verification.getQuantity();
        if (productId == null || productId <= 0 || quantity == null || quantity <= 0) {
            throw ChannelVoucherException.conflict("VERIFICATION_INCOMPLETE", "验证记录缺少商品或数量映射");
        }
        String fingerprint = ChannelVoucherCodec.sha256Hex(String.valueOf(verification.getId()) + "|"
                + expiresAt.getTime() + "|" + subjectMode + "|" + valueOf(request.getBoundMemberId()) + "|"
                + valueOf(inventoryTicketId) + "|" + valueOf(inventoryQuantity));
        ChannelVoucher byRequest = voucherMapper.selectByAccountRequest(account.getId(), requestId);
        if (byRequest != null) {
            if (!fingerprint.equals(byRequest.getRequestFingerprint())) {
                throw ChannelVoucherException.conflict("REQUEST_CONFLICT", "同一发行请求对应了不同参数");
            }
            ChannelVoucherView view = toView(byRequest);
            view.setReplayed(Boolean.TRUE);
            return view;
        }
        ChannelVoucher byVerification = voucherMapper.selectByVerification(verification.getId());
        if (byVerification != null) {
            throw ChannelVoucherException.conflict("VOUCHER_ALREADY_ISSUED", "该渠道订单已发行兑换码");
        }
        MarketingProduct product = requireProduct(productId, now);
        int seatCount = seatCount(product, quantity);

        ChannelVoucher voucher = new ChannelVoucher();
        voucher.setAccountId(account.getId());
        voucher.setVerificationId(verification.getId());
        voucher.setPublicId(ChannelVoucherCodec.newPublicId());
        String code = ChannelVoucherCodec.issue(voucher.getPublicId());
        voucher.setCodeHash(ChannelVoucherCodec.sha256Hex(code));
        voucher.setProductId(product.getId());
        voucher.setProductRevision(product.getRevision() == null ? 1L : product.getRevision());
        voucher.setQuantity(quantity);
        voucher.setSeatCount(seatCount);
        voucher.setSubjectMode(subjectMode);
        voucher.setBoundMemberId(request.getBoundMemberId());
        voucher.setInventoryTicketId(inventoryTicketId);
        voucher.setInventoryQuantity(inventoryTicketId == null ? null : inventoryQuantity);
        voucher.setInventoryState(ChannelVoucher.INVENTORY_NONE);
        voucher.setStatus(ChannelVoucher.STATUS_ACTIVE);
        voucher.setIssuedAt(now);
        voucher.setExpiresAt(expiresAt);
        voucher.setIssuedBy(operatorUserId);
        voucher.setRequestId(requestId);
        voucher.setRequestFingerprint(fingerprint);
        voucher.setCreateTime(now);
        voucher.setUpdateTime(now);
        if (inventoryTicketId != null) {
            OmsTicket ticket = ticketMapper.selectOmsTicketById(inventoryTicketId);
            if (ticket == null) {
                throw ChannelVoucherException.conflict("INVENTORY_TICKET_NOT_FOUND", "库存票种不存在");
            }
            if (ticketMapper.deductInventory(inventoryTicketId, inventoryQuantity.longValue()) == 0) {
                throw ChannelVoucherException.conflict("INVENTORY_UNAVAILABLE", "库存不足，无法预留渠道席位");
            }
            voucher.setInventoryState(ChannelVoucher.INVENTORY_RESERVED);
        }
        try {
            voucherMapper.insert(voucher);
        } catch (DuplicateKeyException duplicate) {
            throw ChannelVoucherException.conflict("VOUCHER_CONFLICT", "兑换码并发发行，请用同一请求重试");
        }
        ChannelVoucherView view = toView(voucher);
        view.setCode(code);
        view.setCodeAvailable(Boolean.TRUE);
        view.setReplayed(Boolean.FALSE);
        return view;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChannelVoucherView revoke(long operatorUserId, Long voucherId, ChannelVoucherRevokeRequest request) {
        requireOperator(operatorUserId);
        if (voucherId == null || voucherId <= 0) {
            throw ChannelVoucherException.badRequest("VOUCHER_ID_REQUIRED", "缺少兑换码 id");
        }
        if (request == null) {
            throw ChannelVoucherException.badRequest("REVOKE_REASON_REQUIRED", "缺少撤销原因");
        }
        String reason = requireText(request.getReason(), 120, "REVOKE_REASON_REQUIRED");
        String source = isBlank(request.getSource()) ? "OPERATOR" : request.getSource().trim();
        if (!source.matches("[A-Z_]{2,32}")) {
            throw ChannelVoucherException.badRequest("REVOKE_SOURCE_INVALID", "撤销来源不合法");
        }
        return revokeInternal(voucherId, reason, source, request.getRequestId());
    }

    @Override
    public ChannelVoucherView get(long operatorUserId, Long voucherId) {
        requireOperator(operatorUserId);
        if (voucherId == null || voucherId <= 0) {
            throw ChannelVoucherException.badRequest("VOUCHER_ID_REQUIRED", "缺少兑换码 id");
        }
        ChannelVoucher voucher = voucherMapper.selectById(voucherId);
        if (voucher == null) {
            throw ChannelVoucherException.notFound("VOUCHER_NOT_FOUND", "兑换码不存在");
        }
        return toView(voucher);
    }

    @Override
    public List<ChannelVoucherView> list(Long accountId, Date from, Date to, int limit) {
        if (accountId == null || accountId <= 0) {
            throw ChannelVoucherException.badRequest("ACCOUNT_REQUIRED", "缺少渠道主体");
        }
        List<ChannelVoucher> rows = voucherMapper.selectByAccount(accountId, from, to, normalizeLimit(limit));
        List<ChannelVoucherView> views = new ArrayList<>();
        if (rows != null) {
            for (ChannelVoucher row : rows) {
                views.add(toView(row));
            }
        }
        return views;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChannelVoucherView revokeByVerification(Long verificationId, String reason, String source) {
        if (verificationId == null || verificationId <= 0) {
            throw ChannelVoucherException.badRequest("VERIFICATION_ID_REQUIRED", "缺少验证记录 id");
        }
        ChannelVoucher voucher = voucherMapper.selectByVerification(verificationId);
        if (voucher == null) {
            return null;
        }
        return revokeInternal(voucher.getId(), requireText(reason, 120, "REVOKE_REASON_REQUIRED"),
                isBlank(source) ? "CHANNEL" : source.trim(), null);
    }

    private ChannelVoucherView revokeInternal(Long voucherId, String reason, String source, String requestId) {
        ChannelVoucher voucher = voucherMapper.selectByIdForUpdate(voucherId);
        if (voucher == null) {
            throw ChannelVoucherException.notFound("VOUCHER_NOT_FOUND", "兑换码不存在");
        }
        Date now = new Date();
        if (Integer.valueOf(ChannelVoucher.STATUS_REVOKED).equals(voucher.getStatus())) {
            return toView(voucher);
        }
        if (Integer.valueOf(ChannelVoucher.STATUS_REDEEMED).equals(voucher.getStatus())) {
            ChannelRedemption redemption = redemptionMapper.selectByVoucherIdForUpdate(voucher.getId());
            if (redemption == null) {
                throw ChannelVoucherException.conflict("REDEMPTION_MISSING", "已兑换码缺少回执，禁止静默撤销");
            }
            if (Integer.valueOf(ChannelRedemption.STATUS_REVOKED).equals(redemption.getStatus())) {
                voucherMapper.markRevoked(voucher.getId(), source, now);
                voucher.setStatus(ChannelVoucher.STATUS_REVOKED);
                voucher.setRevokeSource(source);
                releaseInventory(voucher, now);
                return toView(voucher);
            }
            if (!grantPortResolver.isWired()) {
                throw ChannelVoucherException.unavailable("GRANT_PORT_NOT_WIRED",
                        "权威注册/权益 grant 端口未接线，不能撤销已兑换权益");
            }
            ChannelGrantRevocation revocation = new ChannelGrantRevocation();
            revocation.setPrimaryRegistrationId(redemption.getRegistrationId());
            revocation.setRegistrationIds(parseIds(redemption.getRegistrationIdsJson(),
                    redemption.getRegistrationId()));
            revocation.setChannelOrderNo(redemption.getChannelOrderNo());
            revocation.setReason(reason);
            revocation.setRequestId("channel-revoke-" + voucher.getId() + "-" + now.getTime());
            ChannelGrantResult assessment = grantPortResolver.port().revoke(revocation);
            if (assessment == null) {
                throw ChannelVoucherException.conflict("CHANNEL_REVOKE_UNKNOWN", "渠道撤销判定无结果");
            }
            if (Boolean.TRUE.equals(assessment.getReplayed())) {
                // 原注册已处于撤销终态，本地码与回执同步收敛，不重复动作。
                redemptionMapper.markRevoked(redemption.getId(), reason, now, now);
                redemption.setStatus(ChannelRedemption.STATUS_REVOKED);
                redemption.setRevokedAt(now);
                voucherMapper.markRevoked(voucher.getId(), source, now);
                voucher.setStatus(ChannelVoucher.STATUS_REVOKED);
                voucher.setRevokeSource(source);
                releaseInventory(voucher, now);
                return toView(voucher);
            }
            if (!Boolean.TRUE.equals(assessment.getRevocable())) {
                throw ChannelVoucherException.conflict(
                        assessment.getReasonCode() == null ? "CHANNEL_NOT_REVOCABLE" : assessment.getReasonCode(),
                        assessment.getMessage() == null ? "该渠道订单当前不可撤销" : assessment.getMessage());
            }
            // 渠道已收款：先走原退款链路，退款成功事实到达后才撤准入。此处只回“待退款”，
            // 不改码、不回执、不撤权，避免“钱不退权益先没”。
            String requestKey = isBlank(requestId)
                    ? "channel-revoke-" + voucher.getId() + "-" + now.getTime()
                    : requestId.trim();
            if (voucher.getRevokeRequestedAt() == null) {
                voucherMapper.markRevocationRequested(voucher.getId(), reason, requestKey, now, now);
                voucher.setRevokeReason(reason);
                voucher.setRevokeRequestId(requestKey);
                voucher.setRevokeRequestedAt(now);
            }
            ChannelVoucherView pending = toView(voucher);
            pending.setRevokePending(Boolean.TRUE);
            pending.setRevokeReasonCode(assessment.getReasonCode() == null
                    ? "REFUND_REQUIRED" : assessment.getReasonCode());
            return pending;
        }
        if (Integer.valueOf(ChannelVoucher.STATUS_EXPIRED).equals(voucher.getStatus())) {
            releaseInventory(voucher, now);
            return toView(voucher);
        }
        int updated = voucherMapper.markRevoked(voucher.getId(), source, now);
        if (updated == 0) {
            ChannelVoucher current = voucherMapper.selectById(voucher.getId());
            if (current == null) {
                throw ChannelVoucherException.conflict("VOUCHER_CONFLICT", "兑换码状态冲突");
            }
            return toView(current);
        }
        voucher.setStatus(ChannelVoucher.STATUS_REVOKED);
        voucher.setRevokeSource(source);
        voucher.setUpdateTime(now);
        releaseInventory(voucher, now);
        return toView(voucher);
    }

    private void releaseInventory(ChannelVoucher voucher, Date now) {
        if (voucher.getInventoryTicketId() == null || voucher.getInventoryQuantity() == null
                || voucher.getInventoryQuantity() <= 0) {
            return;
        }
        Integer state = voucher.getInventoryState();
        if (!Integer.valueOf(ChannelVoucher.INVENTORY_RESERVED).equals(state)
                && !Integer.valueOf(ChannelVoucher.INVENTORY_CONSUMED).equals(state)) {
            return;
        }
        if (ticketMapper.restoreInventory(voucher.getInventoryTicketId(),
                voucher.getInventoryQuantity().longValue()) == 0) {
            throw ChannelVoucherException.conflict("INVENTORY_RELEASE_FAILED", "库存回收失败，需人工核对");
        }
        voucherMapper.markInventoryReleased(voucher.getId(), now);
        voucher.setInventoryState(ChannelVoucher.INVENTORY_RELEASED);
    }

    private MarketingProduct requireProduct(Long productId, Date now) {
        MarketingProduct product = productMapper.selectById(productId);
        if (product == null) {
            throw ChannelVoucherException.conflict("PRODUCT_UNAVAILABLE", "渠道映射的商品不存在");
        }
        if (!Integer.valueOf(MarketingProduct.STATUS_ON_SALE).equals(product.getStatus())) {
            throw ChannelVoucherException.conflict("PRODUCT_UNAVAILABLE", "商品未在售，不能发行渠道码");
        }
        if (product.getSaleStartAt() != null && product.getSaleStartAt().after(now)) {
            throw ChannelVoucherException.conflict("PRODUCT_UNAVAILABLE", "商品尚未开始销售");
        }
        if (product.getSaleEndAt() != null && !product.getSaleEndAt().after(now)) {
            throw ChannelVoucherException.conflict("PRODUCT_UNAVAILABLE", "商品已结束销售");
        }
        if (product.getCashPrice() == null || product.getCashPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw ChannelVoucherException.conflict("PRODUCT_PRICE_UNAVAILABLE", "渠道商品缺少现金价");
        }
        ChannelGrantPlanner.requireSupportedValidity(product);
        List<MarketingProductItem> items = productMapper.selectItems(productId);
        if (items == null || items.isEmpty()) {
            throw ChannelVoucherException.conflict("PRODUCT_UNAVAILABLE", "商品未绑定任何作品，不能发码");
        }
        for (MarketingProductItem item : items) {
            if (item.getTopicId() == null || !productRoleService.isPlatformOwned(item.getTopicId())) {
                throw ChannelVoucherException.conflict("PRODUCT_UNAVAILABLE",
                        "渠道兑换只允许平台自营内容，第三方履约保持关闭");
            }
        }
        return product;
    }

    private int seatCount(MarketingProduct product, int quantity) {
        int base = product.getSeatCount() == null || product.getSeatCount() <= 0 ? 1 : product.getSeatCount();
        long total = (long) base * (long) quantity;
        if (total <= 0 || total > Integer.MAX_VALUE) {
            throw ChannelVoucherException.badRequest("SEAT_COUNT_INVALID", "席位数量超出范围");
        }
        return (int) total;
    }

    private List<Long> parseIds(String json, Long fallbackPrimary) {
        List<Long> ids = new ArrayList<>();
        if (json != null) {
            for (String part : json.replace("[", "").replace("]", "").split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    ids.add(Long.valueOf(trimmed));
                } catch (NumberFormatException invalid) {
                    throw ChannelVoucherException.conflict("REDEMPTION_SNAPSHOT_INVALID", "兑换回执快照损坏");
                }
            }
        }
        if (ids.isEmpty() && fallbackPrimary != null && fallbackPrimary > 0) {
            ids.add(fallbackPrimary);
        }
        if (ids.isEmpty()) {
            throw ChannelVoucherException.conflict("REDEMPTION_SNAPSHOT_INVALID", "兑换回执缺少原注册事实");
        }
        return ids;
    }

    private ChannelVoucherView toView(ChannelVoucher voucher) {
        ChannelVoucherView view = new ChannelVoucherView();
        view.setId(voucher.getId());
        view.setPublicId(voucher.getPublicId());
        view.setCodeAvailable(Boolean.FALSE);
        view.setAccountId(voucher.getAccountId());
        view.setVerificationId(voucher.getVerificationId());
        view.setProductId(voucher.getProductId());
        view.setProductRevision(voucher.getProductRevision());
        view.setQuantity(voucher.getQuantity());
        view.setSeatCount(voucher.getSeatCount());
        view.setSubjectMode(voucher.getSubjectMode());
        view.setBoundMemberId(voucher.getBoundMemberId());
        view.setStatus(voucher.getStatus());
        view.setIssuedAt(voucher.getIssuedAt());
        view.setExpiresAt(voucher.getExpiresAt());
        view.setInventoryTicketId(voucher.getInventoryTicketId());
        view.setInventoryQuantity(voucher.getInventoryQuantity());
        view.setInventoryState(voucher.getInventoryState());
        return view;
    }

    private void requireOperator(long operatorUserId) {
        if (operatorUserId <= 0) {
            throw ChannelVoucherException.unauthorized("LOGIN_REQUIRED", "请先登录管理端");
        }
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) return DEFAULT_LIST_LIMIT;
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    private String requireText(String value, int maxLength, String reasonCode) {
        if (value == null || value.trim().isEmpty()) {
            throw ChannelVoucherException.badRequest(reasonCode, "缺少必填参数");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw ChannelVoucherException.badRequest(reasonCode, "参数长度超限");
        }
        return trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String valueOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
