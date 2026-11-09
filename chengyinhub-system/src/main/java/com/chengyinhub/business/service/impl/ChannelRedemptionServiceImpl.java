package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelOrderVerification;
import com.chengyinhub.business.domain.ChannelRedemption;
import com.chengyinhub.business.domain.ChannelVoucher;
import com.chengyinhub.business.domain.ChannelVoucherAccount;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.dto.ChannelGrantCommand;
import com.chengyinhub.business.domain.dto.ChannelGrantResult;
import com.chengyinhub.business.domain.dto.ChannelGrantTopic;
import com.chengyinhub.business.domain.dto.ChannelReceiptRequest;
import com.chengyinhub.business.domain.dto.ChannelReconciliationIssue;
import com.chengyinhub.business.domain.dto.ChannelReconciliationView;
import com.chengyinhub.business.domain.dto.ChannelRedeemRequest;
import com.chengyinhub.business.domain.dto.ChannelRedeemResult;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.mapper.ChannelOrderVerificationMapper;
import com.chengyinhub.business.mapper.ChannelRedemptionMapper;
import com.chengyinhub.business.mapper.ChannelVoucherAccountMapper;
import com.chengyinhub.business.mapper.ChannelVoucherMapper;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IChannelRedemptionService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.support.ChannelGrantPlanner;
import com.chengyinhub.business.service.support.ChannelRedemptionGrantPortResolver;
import com.chengyinhub.business.service.support.ChannelVoucherCodec;
import com.chengyinhub.business.service.support.ChannelVoucherSnapshot;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 渠道码兑换：一次兑换经权威 grant 端口产出 {@code cms_registration} 事实，
 * 同码重放只回执不重发。
 *
 * <p><b>不写营销订单/授权</b>：本类不插入 {@code marketing_product_order}、
 * {@code marketing_entitlement} 或任何“渠道已付”的本地订单行。
 * 外部已收的钱只存在于 {@code marketing_channel_order_verification}；
 * 可玩准入由 M2-F 的注册桥接在同一事务内产出。
 *
 * <p><b>默认关闭</b>：grant 端口未接线时返回 503 {@code GRANT_PORT_NOT_WIRED}，
 * 绝不伪造权益或把码标成已兑换。
 */
@Service
public class ChannelRedemptionServiceImpl implements IChannelRedemptionService {

    private static final int DEFAULT_RECONCILE_LIMIT = 200;

    private final ChannelVoucherMapper voucherMapper;
    private final ChannelRedemptionMapper redemptionMapper;
    private final ChannelOrderVerificationMapper verificationMapper;
    private final ChannelVoucherAccountMapper accountMapper;
    private final MarketingProductMapper productMapper;
    private final IProductRoleService productRoleService;
    private final ICmsTopicService topicService;
    private final ChannelRedemptionGrantPortResolver grantPortResolver;

    public ChannelRedemptionServiceImpl(
            ChannelVoucherMapper voucherMapper,
            ChannelRedemptionMapper redemptionMapper,
            ChannelOrderVerificationMapper verificationMapper,
            ChannelVoucherAccountMapper accountMapper,
            MarketingProductMapper productMapper,
            IProductRoleService productRoleService,
            ICmsTopicService topicService,
            ChannelRedemptionGrantPortResolver grantPortResolver) {
        this.voucherMapper = voucherMapper;
        this.redemptionMapper = redemptionMapper;
        this.verificationMapper = verificationMapper;
        this.accountMapper = accountMapper;
        this.productMapper = productMapper;
        this.productRoleService = productRoleService;
        this.topicService = topicService;
        this.grantPortResolver = grantPortResolver;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChannelRedeemResult redeem(long memberId, ChannelRedeemRequest request) {
        requireMember(memberId);
        if (request == null || isBlank(request.getCode())) {
            throw ChannelVoucherException.badRequest("CODE_REQUIRED", "请输入兑换码");
        }
        String requestId = requireText(request.getRequestId(), 64, "REQUEST_ID_REQUIRED");
        String code = request.getCode().trim();
        String fingerprint = ChannelVoucherCodec.sha256Hex(memberId + "|" + code);
        ChannelRedemption previous = redemptionMapper.selectByMemberRequest(memberId, requestId);
        if (previous != null) {
            if (!fingerprint.equals(previous.getRequestFingerprint())) {
                throw ChannelVoucherException.conflict("REQUEST_CONFLICT", "同一请求键对应了不同兑换码");
            }
            return buildResult(previous, true);
        }
        final String publicId;
        try {
            publicId = ChannelVoucherCodec.publicIdOf(code);
        } catch (IllegalArgumentException invalid) {
            throw ChannelVoucherException.badRequest("INVALID_CODE", "兑换码无效");
        }
        ChannelVoucher voucher = voucherMapper.selectByPublicId(publicId);
        if (voucher == null || !ChannelVoucherCodec.matches(code, voucher.getCodeHash())) {
            throw ChannelVoucherException.notFound("INVALID_CODE", "兑换码无效");
        }
        ChannelOrderVerification verification = verificationMapper.selectByIdForUpdate(voucher.getVerificationId());
        voucher = voucherMapper.selectByIdForUpdate(voucher.getId());
        Date now = new Date();
        if (Integer.valueOf(ChannelVoucher.STATUS_REVOKED).equals(voucher.getStatus())) {
            throw ChannelVoucherException.conflict("CODE_REVOKED", "兑换码已撤销");
        }
        if (Integer.valueOf(ChannelVoucher.STATUS_EXPIRED).equals(voucher.getStatus())
                || (voucher.getExpiresAt() != null && !voucher.getExpiresAt().after(now))) {
            throw ChannelVoucherException.conflict("CODE_EXPIRED", "兑换码已过期");
        }
        if (Integer.valueOf(ChannelVoucher.STATUS_REDEEMED).equals(voucher.getStatus())) {
            ChannelRedemption existing = redemptionMapper.selectByVoucherIdForUpdate(voucher.getId());
            if (existing != null && existing.getMemberId() != null && existing.getMemberId() == memberId) {
                return buildResult(existing, true);
            }
            throw ChannelVoucherException.conflict("ALREADY_REDEEMED_BY_OTHER", "兑换码已被使用");
        }
        if (Integer.valueOf(ChannelVoucher.SUBJECT_BOUND_MEMBER).equals(voucher.getSubjectMode())
                && (voucher.getBoundMemberId() == null || voucher.getBoundMemberId() != memberId)) {
            throw ChannelVoucherException.forbidden("SUBJECT_MISMATCH", "该兑换码不属于当前账号");
        }
        ChannelVoucherAccount account = accountMapper.selectById(voucher.getAccountId());
        if (account == null || !account.issuableAt(now)) {
            throw ChannelVoucherException.conflict("CHANNEL_INACTIVE", "渠道未启用或合同失效");
        }
        if (verification == null
                || !Integer.valueOf(ChannelOrderVerification.STATUS_VERIFIED).equals(verification.getStatus())) {
            throw ChannelVoucherException.conflict("VERIFICATION_NOT_VERIFIED", "渠道订单未通过可信验证");
        }
        if (verification.getExpiresAt() != null && !verification.getExpiresAt().after(now)) {
            throw ChannelVoucherException.conflict("VERIFICATION_EXPIRED", "渠道订单验证已过期");
        }
        requireBoundVerification(account, voucher, verification);
        MarketingProduct product = requireProduct(voucher, now);
        String currency = ChannelGrantPlanner.requireBaseCurrency(verification.getCurrency());
        List<ChannelGrantTopic> lines = ChannelVoucherSnapshot.topics(voucher);

        ChannelGrantCommand command = new ChannelGrantCommand();
        command.setSourceType(ChannelGrantCommand.SOURCE_CHANNEL);
        command.setExternalFactId(verification.getId());
        command.setExternalFactHash(verification.getRawHash());
        command.setChannelKey(account.getChannelKey());
        command.setProviderKey(verification.getProviderKey());
        command.setChannelOrderNo(verification.getChannelOrderNo());
        command.setExternalAmount(verification.getAmount());
        command.setExternalCurrency(currency);
        command.setMemberId(memberId);
        command.setProductId(product.getId());
        command.setProductRevision(voucher.getProductRevision());
        command.setQuantity(voucher.getQuantity());
        command.setSeatCount(voucher.getSeatCount());
        command.setSubjectMode(voucher.getSubjectMode());
        command.setBoundMemberId(voucher.getBoundMemberId());
        command.setValidityKind(product.getValidityKind());
        command.setValidDays(product.getValidDays());
        command.setFixedValidFrom(product.getFixedValidFrom());
        command.setFixedValidTo(product.getFixedValidTo());
        command.setExpiresAt(computeValidTo(product, now));
        command.setGrantedAt(now);
        command.setRequestId(requestId);
        command.setRequestFingerprint(fingerprint);
        command.setTermsVersion(product.getTermsVersion());
        command.setTopics(lines);

        if (!grantPortResolver.isWired()) {
            throw ChannelVoucherException.unavailable("GRANT_PORT_NOT_WIRED",
                    "权威注册/权益 grant 端口未接线，渠道兑换暂不启用");
        }
        ChannelGrantResult grant = grantPortResolver.port().grant(command);
        if (grant == null || Boolean.TRUE.equals(grant.getPendingActivation())) {
            throw ChannelVoucherException.conflict("GRANT_INCOMPLETE", "权威授权未完成，不能标记兑换成功");
        }
        if (grant.getRegistrationIds() == null || grant.getRegistrationIds().isEmpty()
                || grant.getPrimaryRegistrationId() == null) {
            throw ChannelVoucherException.conflict("GRANT_INCOMPLETE", "权威注册事实缺失，不能标记兑换成功");
        }

        int redeemed = voucherMapper.markRedeemed(voucher.getId(), now);
        if (redeemed == 0) {
            throw ChannelVoucherException.conflict("ALREADY_REDEEMED_BY_OTHER", "兑换码已被使用");
        }
        if (Integer.valueOf(ChannelVoucher.INVENTORY_RESERVED).equals(voucher.getInventoryState())) {
            voucherMapper.markInventoryConsumed(voucher.getId(), now);
        }
        ChannelRedemption redemption = new ChannelRedemption();
        redemption.setVoucherId(voucher.getId());
        redemption.setMemberId(memberId);
        redemption.setChannelOrderNo(verification.getChannelOrderNo());
        redemption.setRegistrationId(grant.getPrimaryRegistrationId());
        redemption.setRegistrationNo(grant.getPrimaryRegistrationNo());
        redemption.setRegistrationIdsJson(joinIds(grant.getRegistrationIds()));
        redemption.setEntitlementIdsJson(joinIds(grant.getEntitlementIds()));
        redemption.setGrantRef(grant.getGrantRef());
        redemption.setReceiptNo(newReceiptNo(now));
        redemption.setRequestId(requestId);
        redemption.setRequestFingerprint(fingerprint);
        redemption.setStatus(ChannelRedemption.STATUS_ACTIVE);
        redemption.setValidFrom(grant.getValidFrom() == null
                ? computeValidFrom(product, now) : grant.getValidFrom());
        redemption.setValidTo(grant.getValidTo() == null
                ? computeValidTo(product, now) : grant.getValidTo());
        redemption.setRedeemedAt(now);
        redemption.setCreateTime(now);
        redemption.setUpdateTime(now);
        try {
            redemptionMapper.insert(redemption);
        } catch (DuplicateKeyException duplicate) {
            throw ChannelVoucherException.conflict("REQUEST_CONFLICT", "兑换并发冲突，请用同一请求键重试");
        }
        return buildResult(redemption, false);
    }

    @Override
    public ChannelRedeemResult receipt(long memberId, ChannelReceiptRequest request) {
        requireMember(memberId);
        if (request == null || (isBlank(request.getReceiptNo()) && isBlank(request.getRequestId()))) {
            throw ChannelVoucherException.badRequest("RECEIPT_REFERENCE_REQUIRED", "缺少回执编号或请求键");
        }
        ChannelRedemption redemption = isBlank(request.getReceiptNo())
                ? redemptionMapper.selectByMemberRequest(memberId, request.getRequestId().trim())
                : redemptionMapper.selectByReceiptNo(request.getReceiptNo().trim());
        if (redemption == null || redemption.getMemberId() == null || redemption.getMemberId() != memberId) {
            throw ChannelVoucherException.notFound("RECEIPT_NOT_FOUND", "回执不存在");
        }
        return buildResult(redemption, false);
    }

    @Override
    public ChannelReconciliationView reconcile(Long accountId, Date from, Date to) {
        if (accountId == null || accountId <= 0) {
            throw ChannelVoucherException.badRequest("ACCOUNT_REQUIRED", "缺少渠道主体");
        }
        ChannelVoucherAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            throw ChannelVoucherException.notFound("ACCOUNT_NOT_FOUND", "渠道主体不存在");
        }
        Map<String, Object> verified = verificationMapper.selectReconciliationSummary(accountId, from, to);
        Map<String, Object> vouchers = voucherMapper.selectReconciliationSummary(accountId, from, to);
        List<Map<String, Object>> issueRows = voucherMapper.selectReconciliationIssues(
                accountId, from, to, DEFAULT_RECONCILE_LIMIT);
        ChannelReconciliationView view = new ChannelReconciliationView();
        view.setAccountId(accountId);
        view.setChannelKey(account.getChannelKey());
        view.setFrom(from);
        view.setTo(to);
        view.setVerifiedOrderCount(intValue(verified, "verifiedOrderCount"));
        view.setVerifiedAmount(decimalValue(verified, "verifiedAmount"));
        view.setPendingVerificationCount(intValue(verified, "pendingVerificationCount"));
        view.setVoucherCount(intValue(vouchers, "voucherCount"));
        view.setRedeemedCount(intValue(vouchers, "redeemedCount"));
        view.setActiveVoucherCount(intValue(vouchers, "activeVoucherCount"));
        view.setRevokedVoucherCount(intValue(vouchers, "revokedVoucherCount"));
        view.setUnredeemedCount(Math.max(0, view.getVoucherCount() - view.getRedeemedCount()));
        List<ChannelReconciliationIssue> issues = new ArrayList<>();
        if (issueRows != null) {
            for (Map<String, Object> row : issueRows) {
                ChannelReconciliationIssue issue = new ChannelReconciliationIssue();
                issue.setIssueType(stringValue(row, "issueType"));
                issue.setChannelOrderNo(stringValue(row, "channelOrderNo"));
                issue.setVoucherPublicId(stringValue(row, "voucherPublicId"));
                issue.setAmount(decimalValue(row, "amount"));
                issue.setStatus(intValue(row, "status"));
                issue.setOccurredAt(dateValue(row, "occurredAt"));
                issues.add(issue);
            }
        }
        view.setIssues(issues);
        return view;
    }

    /**
     * 兑换前回读并绑定：验证事实必须与渠道主体、码上冻结的商品/数量一致。
     * raw_hash 不是证明；这里的判据是服务端写入的 VERIFIED 行及其字段。
     */
    private void requireBoundVerification(ChannelVoucherAccount account, ChannelVoucher voucher,
                                          ChannelOrderVerification verification) {
        if (verification.getAccountId() == null
                || !verification.getAccountId().equals(account.getId())
                || !account.getChannelKey().equals(verification.getChannelKey())) {
            throw ChannelVoucherException.conflict("CHANNEL_FACT_MISMATCH", "验证事实与渠道主体不一致");
        }
        if (isBlank(verification.getProviderKey()) || isBlank(verification.getChannelOrderNo())) {
            throw ChannelVoucherException.conflict("CHANNEL_FACT_MISMATCH", "验证事实缺少 provider 或渠道单号");
        }
        if (verification.getProductId() == null
                || !verification.getProductId().equals(voucher.getProductId())) {
            throw ChannelVoucherException.conflict("PRODUCT_MISMATCH", "验证事实商品与兑换码不一致");
        }
        if (verification.getQuantity() == null
                || !verification.getQuantity().equals(voucher.getQuantity())) {
            throw ChannelVoucherException.conflict("QUANTITY_MISMATCH", "验证事实数量与兑换码不一致");
        }
        List<ChannelOrderVerification> matchingOrders = verificationMapper.selectVerifiedByChannelOrderNo(verification.getChannelOrderNo());
        if (matchingOrders == null || matchingOrders.size() != 1
                || !verification.getId().equals(matchingOrders.get(0).getId())) {
            throw ChannelVoucherException.conflict("CHANNEL_ORDER_AMBIGUOUS", "渠道单号存在跨主体歧义，不能发放准入");
        }
        if (verification.getAmount() == null || verification.getAmount().signum() <= 0) {
            throw ChannelVoucherException.conflict("AMOUNT_INVALID", "验证事实金额无效");
        }
    }

    private MarketingProduct requireProduct(ChannelVoucher voucher, Date now) {
        MarketingProduct product = ChannelVoucherSnapshot.product(voucher);
        if (Integer.valueOf(MarketingProduct.VALIDITY_FIXED_WINDOW).equals(product.getValidityKind())
                && (product.getFixedValidTo() == null || !product.getFixedValidTo().after(now))) {
            throw ChannelVoucherException.conflict("PRODUCT_UNAVAILABLE", "商品固定有效期已结束");
        }
        ChannelGrantPlanner.requireSupportedValidity(product);
        for (ChannelGrantTopic topic : ChannelVoucherSnapshot.topics(voucher)) {
            if (topic.getTopicId() == null || !productRoleService.isPlatformOwned(topic.getTopicId())) {
                throw ChannelVoucherException.conflict("PRODUCT_UNAVAILABLE", "渠道兑换只允许平台自营内容，第三方履约保持关闭");
            }
        }
        return product;
    }

    private Date computeValidFrom(MarketingProduct product, Date now) {
        if (Integer.valueOf(MarketingProduct.VALIDITY_FIXED_WINDOW).equals(product.getValidityKind())) {
            return product.getFixedValidFrom();
        }
        if (Integer.valueOf(MarketingProduct.VALIDITY_RELATIVE_DAYS).equals(product.getValidityKind())) {
            return now;
        }
        return null;
    }

    private Date computeValidTo(MarketingProduct product, Date now) {
        if (Integer.valueOf(MarketingProduct.VALIDITY_FIXED_WINDOW).equals(product.getValidityKind())) {
            return product.getFixedValidTo();
        }
        if (Integer.valueOf(MarketingProduct.VALIDITY_RELATIVE_DAYS).equals(product.getValidityKind())
                && product.getValidDays() != null && product.getValidDays() > 0) {
            return new Date(now.getTime() + java.util.concurrent.TimeUnit.DAYS.toMillis(product.getValidDays()));
        }
        return null;
    }

    private ChannelRedeemResult buildResult(ChannelRedemption redemption, boolean replayed) {
        ChannelVoucher voucher = voucherMapper.selectById(redemption.getVoucherId());
        MarketingProduct product = voucher == null ? null : productMapper.selectById(voucher.getProductId());
        ChannelRedeemResult result = new ChannelRedeemResult();
        result.setReceiptNo(redemption.getReceiptNo());
        result.setStatus(redemption.getStatus());
        result.setVoucherId(redemption.getVoucherId());
        result.setVoucherPublicId(voucher == null ? null : voucher.getPublicId());
        result.setChannelOrderNo(redemption.getChannelOrderNo());
        result.setProductId(voucher == null ? null : voucher.getProductId());
        result.setProductName(product == null ? null : product.getProductName());
        result.setQuantity(voucher == null ? null : voucher.getQuantity());
        result.setSeatCount(voucher == null ? null : voucher.getSeatCount());
        result.setRegistrationId(redemption.getRegistrationId());
        result.setRegistrationNo(redemption.getRegistrationNo());
        result.setEntitlementIds(parseIds(redemption.getEntitlementIdsJson()));
        result.setValidFrom(redemption.getValidFrom());
        result.setValidTo(redemption.getValidTo());
        result.setRedeemedAt(redemption.getRedeemedAt());
        result.setRevokedAt(redemption.getRevokedAt());
        result.setReplayed(replayed);
        return result;
    }

    private List<Long> parseIds(String json) {
        List<Long> ids = new ArrayList<>();
        if (json == null) return ids;
        for (String part : json.replace("[", "").replace("]", "").split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                ids.add(Long.valueOf(trimmed));
            } catch (NumberFormatException invalid) {
                throw ChannelVoucherException.conflict("REDEMPTION_SNAPSHOT_INVALID", "兑换回执快照损坏");
            }
        }
        return ids;
    }

    private String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return null;
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) out.append(',');
            out.append(ids.get(i));
        }
        return out.append(']').toString();
    }

    private String newReceiptNo(Date now) {
        return "CR" + new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(now)
                + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private void requireMember(long memberId) {
        if (memberId <= 0) {
            throw ChannelVoucherException.unauthorized("LOGIN_REQUIRED", "请先登录");
        }
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

    private int intValue(Map<String, Object> map, String key) {
        if (map == null) return 0;
        Object value = map.get(key);
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private BigDecimal decimalValue(Map<String, Object> map, String key) {
        if (map == null) return BigDecimal.ZERO;
        Object value = map.get(key);
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Number) return BigDecimal.valueOf(((Number) value).doubleValue());
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException invalid) {
            return BigDecimal.ZERO;
        }
    }

    private String stringValue(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Date dateValue(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        return value instanceof Date ? (Date) value : null;
    }
}
