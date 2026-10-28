package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.ExploreBundleExitCase;
import com.chengyinhub.business.domain.ExploreBundleGrant;
import com.chengyinhub.business.domain.ExploreBundleOrder;
import com.chengyinhub.business.domain.ExploreBundleReservation;
import com.chengyinhub.business.domain.ExploreBundleUnit;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.exception.FreeExploreBundleException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ExploreBundleMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.IFreeExploreBundleService;
import com.chengyinhub.business.mapper.SettlementRuleMapper;
import com.chengyinhub.business.service.support.FreeExploreBundlePolicy;
import com.chengyinhub.business.service.support.FreeExploreBundleSwitches;
import com.chengyinhub.common.constant.ProductType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * F3 套票 Phase 1 账本。见 {@link IFreeExploreBundleService}。
 *
 * <p>余额只从 explore_bundle_unit 的本持有人行算,永不读 merchant_chapter_offer 的共享名额;
 * A、B 买同一套票是两张 registration ⇒ 两组单位,A 核销只 CAS 自己的行。
 */
@Service
public class FreeExploreBundleServiceImpl implements IFreeExploreBundleService {

    static final int UNITS_PER_CHAPTER = 1;
    private static final String UNIT_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private ExploreBundleMapper bundleMapper;
    @Autowired private CmsRegistrationMapper registrationMapper;
    @Autowired private CmsTopicMapper topicMapper;
    @Autowired private CmsTopicChapterMapper chapterMapper;
    @Autowired private MerchantChapterOfferMapper offerMapper;
    @Autowired private SettlementRuleMapper settlementRuleMapper;
    @Autowired private FreeExploreBundleSwitches switches;

    /** 测试注入时钟用;生产恒为系统时间。 */
    Supplier<Date> clock = Date::new;

    // ================================================================ 建单冻结

    @Override
    public ExploreBundleOrder freezeOnOrderCreated(CmsRegistration registration, CmsTopic topic, CmsActivity activity) {
        if (!switches.saleActive() || registration == null || topic == null
                || !Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
            return null;
        }
        ExploreBundleOrder existing = bundleMapper.selectOrderByRegistration(registration.getId());
        if (existing != null) {
            return existing;   // 同一张 registration 只冻结一次;快照一经写入永不改写
        }
        List<CmsTopicChapter> chapters = chapterMapper.selectRequiredByTopic(topic.getId());
        if (chapters == null || chapters.isEmpty()) {
            return null;   // 纯内容探索:没有权益项,不是套票
        }
        Date now = clock.get();
        Date validTo = activity == null ? null : activity.getEndDate();
        if (validTo == null || !validTo.after(now)) {
            throw new FreeExploreBundleException("BUNDLE_VALIDITY_MISSING", "套票缺少有效期,暂不可售");
        }
        List<MerchantChapterOffer> offers = offerMapper.selectByTopic(topic.getId());
        Map<String, Object> product = productSection(topic, activity, registration, chapters);
        List<Map<String, Object>> quota = new ArrayList<>();
        List<Map<String, Object>> contract = new ArrayList<>();
        for (MerchantChapterOffer offer : offers == null ? Collections.<MerchantChapterOffer>emptyList() : offers) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("offerId", offer.getId());
            q.put("chapterId", offer.getChapterId());
            q.put("quotaTotal", offer.getQuotaTotal());
            quota.add(q);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("offerId", offer.getId());
            c.put("chapterId", offer.getChapterId());
            c.put("merchantId", offer.getMerchantId());
            c.put("termsMode", offer.getTermsMode());
            c.put("perHeadFee", plain(offer.getPerHeadFee()));
            c.put("perkId", offer.getPerkId());
            c.put("payerClubId", offer.getPayerClubId());
            c.put("validEnd", offer.getValidEnd() == null ? null : offer.getValidEnd().getTime());
            contract.add(c);
        }
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("version", FreeExploreBundlePolicy.POLICY_VERSION);
        policy.put("freeCancelHours", FreeExploreBundlePolicy.FREE_CANCEL_HOURS);
        policy.put("partialRefundMinRatio", FreeExploreBundlePolicy.PARTIAL_REFUND_MIN_RATIO.toPlainString());
        String ruleVersion = settlementRuleVersion();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("product", product);
        snapshot.put("quota", quota);
        snapshot.put("contract", contract);
        snapshot.put("policy", policy);
        snapshot.put("settlementRuleVersion", ruleVersion);
        String snapshotJson = JSON.toJSONString(snapshot);
        String fingerprint = sha256(snapshotJson);

        ExploreBundleOrder order = new ExploreBundleOrder();
        order.setRegistrationId(registration.getId());
        order.setHolderMemberId(registration.getMemberId());
        order.setTopicId(topic.getId());
        order.setActivityId(activity == null ? null : activity.getId());
        order.setProductVersion(sha256(JSON.toJSONString(product)));
        order.setQuotaVersion(sha256(JSON.toJSONString(quota)));
        order.setPolicyVersion(FreeExploreBundlePolicy.POLICY_VERSION);
        order.setContractVersion(sha256(JSON.toJSONString(contract)));
        order.setSettlementRuleVersion(ruleVersion);
        order.setSnapshotJson(snapshotJson);
        BigDecimal paid = registration.getPayableAmount() == null ? BigDecimal.ZERO : registration.getPayableAmount();
        order.setPaidAmount(paid);
        order.setValidFrom(now);
        order.setValidTo(validTo);
        order.setIdempotencyKey("bundle-order:" + registration.getId());
        order.setRequestFingerprint(fingerprint);
        order.setCreateTime(now);
        bundleMapper.insertOrder(order);

        List<BigDecimal> values = FreeExploreBundlePolicy.splitValue(paid, chapters.size() * UNITS_PER_CHAPTER);
        int valueIndex = 0;
        for (CmsTopicChapter chapter : chapters) {
            ExploreBundleGrant grant = new ExploreBundleGrant();
            grant.setBundleOrderId(order.getId());
            grant.setRegistrationId(registration.getId());
            grant.setHolderMemberId(registration.getMemberId());
            grant.setTopicId(topic.getId());
            grant.setChapterId(chapter.getId());
            grant.setGrantNo(1);
            grant.setGrantType("PURCHASE");
            grant.setUnitsTotal(UNITS_PER_CHAPTER);
            grant.setValidFrom(now);
            grant.setValidTo(validTo);
            grant.setCreateTime(now);
            bundleMapper.insertGrant(grant);
            for (int seq = 1; seq <= UNITS_PER_CHAPTER; seq++) {
                ExploreBundleUnit unit = new ExploreBundleUnit();
                unit.setGrantId(grant.getId());
                unit.setRegistrationId(registration.getId());
                unit.setHolderMemberId(registration.getMemberId());
                unit.setChapterId(chapter.getId());
                unit.setUnitSeq(seq);
                unit.setUnitCode(newUnitCode());
                unit.setUnitValue(values.get(valueIndex++));
                unit.setCreateTime(now);
                bundleMapper.insertUnit(unit);
            }
        }
        return order;
    }

    private Map<String, Object> productSection(CmsTopic topic, CmsActivity activity, CmsRegistration registration,
                                               List<CmsTopicChapter> chapters) {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("topicId", topic.getId());
        product.put("topicName", topic.getName());
        product.put("productType", topic.getProductType());
        product.put("activityId", activity == null ? null : activity.getId());
        product.put("activityName", activity == null ? null : activity.getName());
        product.put("ticketId", registration.getTicketId());
        product.put("payableAmount", plain(registration.getPayableAmount()));
        List<Map<String, Object>> items = new ArrayList<>();
        for (CmsTopicChapter chapter : chapters) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chapterId", chapter.getId());
            item.put("name", chapter.getName());
            item.put("termsMode", chapter.getTermsMode());
            item.put("maxMerchant", chapter.getMaxMerchant());
            item.put("perkMinValue", plain(chapter.getPerkMinValue()));
            item.put("maxPerHeadFee", plain(chapter.getMaxPerHeadFee()));
            item.put("unitsPerHolder", UNITS_PER_CHAPTER);
            items.add(item);
        }
        product.put("items", items);
        return product;
    }

    private String settlementRuleVersion() {
        SettlementRule rule = settlementRuleMapper.selectActiveRule(0L, "topic");
        if (rule == null || rule.getId() == null) return "rule:none";
        Date stamp = rule.getUpdateTime() != null ? rule.getUpdateTime() : rule.getCreateTime();
        return "rule:" + rule.getId() + "@" + (stamp == null ? 0L : stamp.getTime());
    }

    // ================================================================ 核销 / 撤销 / 退款钩子

    @Override
    public void onChapterRedeemed(Long registrationId, Long chapterId, Long merchantId, Long redemptionId) {
        if (!switches.ledgerEnabled()) return;
        ExploreBundleOrder order = bundleMapper.selectOrderByRegistration(registrationId);
        if (order == null) return;   // 非套票单:既有一次性权益照旧
        List<ExploreBundleUnit> units = bundleMapper.selectUnitsForUpdate(
                registrationId, order.getHolderMemberId(), chapterId);
        if (units == null || units.isEmpty()) {
            throw new FreeExploreBundleException("BUNDLE_NO_ITEM", "该套票不含本章节权益");
        }
        Date now = clock.get();
        ExploreBundleUnit chosen = null;
        ExploreBundleReservation booking = null;
        boolean reservedElsewhere = false;
        boolean expired = false;
        for (ExploreBundleUnit unit : units) {
            if (!"RESERVED".equals(unit.getStatus()) || unit.getReservationId() == null) continue;
            ExploreBundleReservation reservation = bundleMapper.selectReservationByIdForUpdate(unit.getReservationId());
            if (reservation != null && "BOOKED".equals(reservation.getStatus())) {
                if (merchantId.equals(reservation.getMerchantId())) {
                    chosen = unit;
                    booking = reservation;
                    break;
                }
                reservedElsewhere = true;
            }
        }
        if (chosen == null) {
            for (ExploreBundleUnit unit : units) {
                if (!"AVAILABLE".equals(unit.getStatus())) continue;
                if (!withinValidity(unit, now)) {
                    expired = true;
                    continue;
                }
                chosen = unit;
                break;
            }
        }
        if (chosen == null) {
            if (reservedElsewhere) {
                throw new FreeExploreBundleException("BUNDLE_RESERVED_ELSEWHERE", "该权益已预约其他门店,请先取消预约");
            }
            if (expired) throw new FreeExploreBundleException("BUNDLE_EXPIRED", "该套票权益已过有效期");
            throw new FreeExploreBundleException("BUNDLE_NO_BALANCE", "该套票本章节余额不足");
        }
        if (!withinValidity(chosen, now)) {
            throw new FreeExploreBundleException("BUNDLE_EXPIRED", "该套票权益已过有效期");
        }
        if (bundleMapper.casUnitStatus(chosen.getId(), chosen.getVersion(), chosen.getStatus(), "REDEEMED",
                redemptionId, null, "REDEEMED") != 1) {
            throw new FreeExploreBundleException("BUNDLE_CONCURRENT", "套票余额已变化,请重新扫码");
        }
        if (booking != null && bundleMapper.casReservationStatus(
                booking.getId(), booking.getVersion(), "BOOKED", "FULFILLED") != 1) {
            throw new FreeExploreBundleException("BUNDLE_CONCURRENT", "预约状态已变化,请重新扫码");
        }
    }

    @Override
    public void onRedemptionReversed(Long redemptionId) {
        if (!switches.ledgerEnabled() || redemptionId == null) return;
        ExploreBundleUnit unit = bundleMapper.selectUnitByRedemptionForUpdate(redemptionId);
        if (unit == null) return;
        CmsRegistration registration = registrationMapper.selectCmsRegistrationById(unit.getRegistrationId());
        // 与 restoreToPending 同口径白名单:仅已支付(2)的票退回可用;其余(已退款等)直接置 REFUNDED,不让死票复活。
        boolean paid = registration != null && Integer.valueOf(2).equals(registration.getPaymentStatus());
        if (bundleMapper.casUnitStatus(unit.getId(), unit.getVersion(), "REDEEMED", paid ? "AVAILABLE" : "REFUNDED",
                null, null, "REDEMPTION_REVERSED") != 1) {
            throw new IllegalStateException("套票单位状态已变化,撤销已回滚");
        }
    }

    @Override
    public void onRegistrationRefunded(Long registrationId) {
        if (!switches.ledgerEnabled() || registrationId == null) return;
        if (bundleMapper.selectOrderByRegistration(registrationId) == null) return;
        bundleMapper.cancelBookedByRegistration(registrationId);
        bundleMapper.refundOpenUnits(registrationId);
    }

    // ================================================================ 只读

    @Override
    public Map<String, Object> catalog(Long topicId) {
        CmsTopic topic = topicId == null ? null : topicMapper.selectCmsTopicById(topicId);
        if (topic == null || !Integer.valueOf(0).equals(topic.getDelFlag())) {
            throw new FreeExploreBundleException("BUNDLE_NOT_FOUND", "主题不存在");
        }
        if (!Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
            throw new FreeExploreBundleException("BUNDLE_NOT_FREE_EXPLORE", "该主题不是自由探索产品");
        }
        boolean onSale;
        try {
            onSale = TopicLifecycleState.fromCode(topic.getLifecycle()).isDirectSaleOpen();
        } catch (IllegalArgumentException unknown) {
            onSale = false;
        }
        if (!onSale) throw new FreeExploreBundleException("BUNDLE_NOT_ON_SALE", "该主题暂未开售");
        List<CmsTopicChapter> chapters = chapterMapper.selectRequiredByTopic(topicId);
        List<MerchantChapterOffer> offers = offerMapper.selectByTopic(topicId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (CmsTopicChapter chapter : chapters == null ? Collections.<CmsTopicChapter>emptyList() : chapters) {
            List<Map<String, Object>> stores = new ArrayList<>();
            for (MerchantChapterOffer offer : offers == null ? Collections.<MerchantChapterOffer>emptyList() : offers) {
                if (!chapter.getId().equals(offer.getChapterId())) continue;
                Map<String, Object> store = new LinkedHashMap<>();
                store.put("offerId", offer.getId());
                store.put("merchantName", offer.getMerchantName());
                store.put("validEnd", offer.getValidEnd());
                stores.add(store);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chapterId", chapter.getId());
            item.put("name", chapter.getName());
            item.put("termsMode", chapter.getTermsMode());
            item.put("unitsPerHolder", UNITS_PER_CHAPTER);
            item.put("stores", stores);
            items.add(item);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("topicId", topic.getId());
        view.put("topicName", topic.getName());
        view.put("productType", topic.getProductType());
        view.put("items", items);
        view.put("policy", policyView());
        view.put("saleEnabled", switches.saleActive());
        view.put("reservationEnabled", switches.reservationActive());
        return view;
    }

    @Override
    public Map<String, Object> orderView(Long memberId, Long registrationId) {
        requireLedger();
        CmsRegistration registration = requireOwnRegistration(memberId, registrationId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("registrationId", registration.getId());
        view.put("registrationNo", registration.getRegistrationNo());
        view.put("parentOrderNo", registration.getParentOrderNo());
        view.put("paymentStatus", registration.getPaymentStatus());
        view.put("registrationStatus", registration.getRegistrationStatus());
        ExploreBundleOrder order = bundleMapper.selectOrderByRegistration(registration.getId());
        if (order == null || !memberId.equals(order.getHolderMemberId())) {
            view.put("bundle", null);
            return view;
        }
        List<ExploreBundleUnit> units = bundleMapper.selectUnitsByRegistration(registration.getId());
        List<ExploreBundleGrant> grants = bundleMapper.selectGrantsByRegistration(registration.getId());
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("order", order);
        bundle.put("balances", balances(grants, units, clock.get()));
        bundle.put("units", units);
        bundle.put("reservations", bundleMapper.selectReservationsByRegistration(registration.getId()));
        bundle.put("exitCases", bundleMapper.selectExitCasesByRegistration(registration.getId()));
        bundle.put("policy", policyView());
        view.put("bundle", bundle);
        return view;
    }

    @Override
    public Map<String, Object> orderReceiptByRequestId(Long memberId, String requestId) {
        if (memberId == null || requestId == null || requestId.trim().isEmpty() || requestId.trim().length() > 64) {
            throw new FreeExploreBundleException("BUNDLE_BAD_REQUEST", "幂等键无效");
        }
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setRequestId(requestId.trim());
        List<CmsRegistration> rows = registrationMapper.selectCmsRegistrationList(probe);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("requestId", requestId.trim());
        if (rows == null || rows.isEmpty()) {
            // 未落库 = 原请求没有成功;客户端可用同一幂等键重试,不会产生第二张单。
            receipt.put("receipt", "NOT_FOUND");
            return receipt;
        }
        if (rows.size() != 1 || !memberId.equals(rows.get(0).getMemberId())) {
            throw new FreeExploreBundleException("BUNDLE_RECEIPT_AMBIGUOUS", "同一幂等键命中多条报名,已拒绝不确定回读");
        }
        receipt.put("receipt", "FOUND");
        receipt.put("order", orderView(memberId, rows.get(0).getId()));
        return receipt;
    }

    @Override
    public FreeExploreBundlePolicy.RefundQuote refundQuote(Long memberId, Long registrationId) {
        requireLedger();
        CmsRegistration registration = requireOwnRegistration(memberId, registrationId);
        ExploreBundleOrder order = requireOwnOrder(memberId, registration.getId());
        return FreeExploreBundlePolicy.refundQuote(order.getPaidAmount(),
                bundleMapper.selectUnitsByRegistration(registration.getId()));
    }

    @Override
    public ExploreBundleReservation reservationByIdempotencyKey(Long memberId, String idempotencyKey) {
        requireLedger();
        String key = requireKey(idempotencyKey);
        return bundleMapper.selectReservationByIdempotencyKey(memberId, key);
    }

    @Override
    public List<ExploreBundleReservation> merchantReservations(Long merchantOwnerMemberId, Date from, Date to) {
        requireLedger();
        if (merchantOwnerMemberId == null || from == null || to == null || !to.after(from)
                || to.getTime() - from.getTime() > 31L * 24 * 3600_000L) {
            throw new FreeExploreBundleException("BUNDLE_BAD_REQUEST", "查询区间无效(最长31天)");
        }
        return bundleMapper.selectMerchantReservations(merchantOwnerMemberId, from, to);
    }

    // ================================================================ 预约

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExploreBundleReservation reserve(Long memberId, Long registrationId, Long unitId, Long offerId,
                                            Date startAt, String idempotencyKey) {
        requireReservation();
        String key = requireKey(idempotencyKey);
        if (registrationId == null || unitId == null || offerId == null || startAt == null) {
            throw new FreeExploreBundleException("BUNDLE_BAD_REQUEST", "预约参数缺失");
        }
        String fingerprint = sha256(registrationId + "|" + unitId + "|" + offerId + "|" + startAt.getTime());
        ExploreBundleReservation replay = bundleMapper.selectReservationByIdempotencyKey(memberId, key);
        if (replay != null) return replayOrConflict(replay.getRequestFingerprint(), fingerprint, replay);

        CmsRegistration registration = requireOwnRegistration(memberId, registrationId);
        requirePaid(registration);
        requireOwnOrder(memberId, registrationId);
        ExploreBundleUnit unit = bundleMapper.selectUnitByIdForUpdate(unitId);
        if (unit == null || !registrationId.equals(unit.getRegistrationId()) || !memberId.equals(unit.getHolderMemberId())) {
            throw new FreeExploreBundleException("BUNDLE_NOT_FOUND", "套票单位不存在");
        }
        if (!"AVAILABLE".equals(unit.getStatus())) {
            throw new FreeExploreBundleException("BUNDLE_UNIT_UNAVAILABLE", "该权益当前不可预约");
        }
        Date now = clock.get();
        if (!startAt.after(now)) throw new FreeExploreBundleException("BUNDLE_BAD_REQUEST", "预约时间须晚于当前时间");
        if (startAt.before(unit.getValidFrom()) || !startAt.before(unit.getValidTo())) {
            throw new FreeExploreBundleException("BUNDLE_EXPIRED", "预约时间超出套票有效期");
        }
        MerchantChapterOffer offer = requireLiveOffer(offerId, unit.getChapterId(), startAt);
        ExploreBundleReservation reservation = new ExploreBundleReservation();
        reservation.setUnitId(unit.getId());
        reservation.setRegistrationId(registrationId);
        reservation.setHolderMemberId(memberId);
        reservation.setChapterId(unit.getChapterId());
        reservation.setOfferId(offer.getId());
        reservation.setMerchantId(offer.getMerchantId());
        reservation.setStartAt(startAt);
        reservation.setIdempotencyKey(key);
        reservation.setRequestFingerprint(fingerprint);
        reservation.setCreateTime(now);
        bundleMapper.insertReservation(reservation);
        if (bundleMapper.casUnitStatus(unit.getId(), unit.getVersion(), "AVAILABLE", "RESERVED",
                null, reservation.getId(), "RESERVED") != 1) {
            throw new FreeExploreBundleException("BUNDLE_CONCURRENT", "权益状态已变化,请刷新后重试");
        }
        return bundleMapper.selectReservationById(reservation.getId());
    }

    @Override
    public Map<String, Object> cancellationQuote(Long memberId, Long reservationId, boolean useWaiver) {
        requireLedger();
        ExploreBundleReservation reservation = reservationId == null ? null : bundleMapper.selectReservationById(reservationId);
        if (reservation == null || !Objects.equals(memberId, reservation.getHolderMemberId()) || memberId == null) {
            throw new FreeExploreBundleException("BUNDLE_NOT_FOUND", "预约不存在");
        }
        requireOwnRegistration(memberId, reservation.getRegistrationId());
        Date now = clock.get();
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("reservationId", exactId(reservation.getId()));
        quote.put("registrationId", exactId(reservation.getRegistrationId()));
        quote.put("holderMemberId", exactId(reservation.getHolderMemberId()));
        quote.put("version", reservation.getVersion());
        quote.put("status", reservation.getStatus());
        quote.put("asOf", now.getTime());
        quote.put("useWaiver", useWaiver);
        quote.put("outcome", cancellationOutcome(reservation.getStartAt(), now, useWaiver));
        quote.put("expectedOutcome", cancellationConfirmation(reservation.getStartAt(), now, useWaiver));
        quote.put("canCancel", switches.reservationActive() && "BOOKED".equals(reservation.getStatus()));
        // Quote is not a waiver reservation: the unique monthly row is acquired only in the write transaction.
        quote.put("waiverMonth", FreeExploreBundlePolicy.waiverMonth(now));
        return quote;
    }

    private static String cancellationOutcome(Date startAt, Date now, boolean useWaiver) {
        return FreeExploreBundlePolicy.freeCancel(startAt, now) ? "CANCELLED" : useWaiver ? "WAIVED" : "LATE_CANCELLED";
    }

    private static String cancellationConfirmation(Date startAt, Date now, boolean useWaiver) {
        String outcome = cancellationOutcome(startAt, now, useWaiver);
        return "WAIVED".equals(outcome) ? outcome + ":" + FreeExploreBundlePolicy.waiverMonth(now) : outcome;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExploreBundleReservation cancelReservation(Long memberId, Long reservationId, boolean useWaiver) {
        return cancelReservation(memberId, reservationId, useWaiver, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExploreBundleReservation cancelReservation(Long memberId, Long reservationId, boolean useWaiver, String expectedOutcome) {
        requireReservation();
        ExploreBundleReservation reservation = reservationId == null ? null
                : bundleMapper.selectReservationByIdForUpdate(reservationId);
        if (reservation == null || !reservation.getHolderMemberId().equals(memberId)) {
            throw new FreeExploreBundleException("BUNDLE_NOT_FOUND", "预约不存在");
        }
        if (!"BOOKED".equals(reservation.getStatus())) return reservation;   // 已终态:幂等回读
        ExploreBundleUnit unit = requireReservedUnit(reservation);
        Date now = clock.get();
        if (expectedOutcome != null && !expectedOutcome.equals(cancellationConfirmation(reservation.getStartAt(), now, useWaiver))) {
            throw new FreeExploreBundleException("BUNDLE_CANCEL_QUOTE_CHANGED", "取消条件已变化，请重新试算并确认");
        }
        String reservationTo;
        String unitTo;
        if (FreeExploreBundlePolicy.freeCancel(reservation.getStartAt(), now)) {
            reservationTo = "CANCELLED";
            unitTo = "AVAILABLE";
        } else if (useWaiver) {
            if (bundleMapper.insertWaiverIgnore(memberId, FreeExploreBundlePolicy.waiverMonth(now), reservation.getId()) != 1) {
                throw new FreeExploreBundleException("BUNDLE_WAIVER_USED", "本月主动豁免次数已用完");
            }
            reservationTo = "WAIVED";
            unitTo = "AVAILABLE";
        } else {
            // <12h 取消:只消耗该单位,不收现金,不写 redemption ⇒ 不产生商家分成。
            reservationTo = "LATE_CANCELLED";
            unitTo = "FORFEITED";
        }
        transition(reservation, unit, reservationTo, unitTo);
        return bundleMapper.selectReservationById(reservation.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExploreBundleReservation markNoShow(Long merchantOwnerMemberId, Long reservationId) {
        requireReservation();
        ExploreBundleReservation reservation = reservationId == null ? null
                : bundleMapper.selectReservationByIdForUpdate(reservationId);
        if (reservation == null || merchantOwnerMemberId == null
                || !merchantOwnerMemberId.equals(reservation.getMerchantId())) {
            throw new FreeExploreBundleException("BUNDLE_NOT_FOUND", "预约不存在");
        }
        if ("NO_SHOW".equals(reservation.getStatus())) return reservation;
        if (!"BOOKED".equals(reservation.getStatus())) {
            throw new FreeExploreBundleException("BUNDLE_RESERVATION_CLOSED", "预约已结束,不能记爽约");
        }
        if (clock.get().before(reservation.getStartAt())) {
            throw new FreeExploreBundleException("BUNDLE_TOO_EARLY", "预约开始前不能记爽约");
        }
        ExploreBundleUnit unit = requireReservedUnit(reservation);
        // 爽约:只消耗该单位;不是真实核销 ⇒ 不写 redemption、不计提、不产生商家分成。
        transition(reservation, unit, "NO_SHOW", "FORFEITED");
        return bundleMapper.selectReservationById(reservation.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExploreBundleExitCase decideMerchantExit(Long memberId, Long unitId, String decision, Long targetOfferId,
                                                    String idempotencyKey) {
        requireReservation();
        String key = requireKey(idempotencyKey);
        if (!"SWAP_CONSENTED".equals(decision) && !"REFUND_UNFULFILLED".equals(decision)) {
            throw new FreeExploreBundleException("BUNDLE_BAD_REQUEST", "处置方式无效");
        }
        if ("SWAP_CONSENTED".equals(decision) == (targetOfferId == null)) {
            throw new FreeExploreBundleException("BUNDLE_BAD_REQUEST", "同意换店须且只须指定目标门店");
        }
        String fingerprint = sha256(unitId + "|" + decision + "|" + targetOfferId);
        ExploreBundleExitCase replay = bundleMapper.selectExitCaseByIdempotencyKey(memberId, key);
        if (replay != null) return replayOrConflict(replay.getRequestFingerprint(), fingerprint, replay);

        ExploreBundleUnit unit = unitId == null ? null : bundleMapper.selectUnitByIdForUpdate(unitId);
        if (unit == null || !memberId.equals(unit.getHolderMemberId())) {
            throw new FreeExploreBundleException("BUNDLE_NOT_FOUND", "套票单位不存在");
        }
        requireOwnOrder(memberId, unit.getRegistrationId());
        ExploreBundleReservation booking = null;
        Long exitedOfferId;
        if ("RESERVED".equals(unit.getStatus()) && unit.getReservationId() != null) {
            booking = bundleMapper.selectReservationByIdForUpdate(unit.getReservationId());
            if (booking == null || !"BOOKED".equals(booking.getStatus()) || isLive(offerMapper.selectById(booking.getOfferId()))) {
                throw new FreeExploreBundleException("BUNDLE_MERCHANT_NOT_EXITED", "预约门店仍在营业,无需处置");
            }
            exitedOfferId = booking.getOfferId();
        } else if ("AVAILABLE".equals(unit.getStatus())) {
            if (hasLiveOfferForChapter(unit)) {
                throw new FreeExploreBundleException("BUNDLE_MERCHANT_NOT_EXITED", "该章节仍有在营门店,无需处置");
            }
            exitedOfferId = 0L;   // 章节已无任何在营门店
        } else {
            throw new FreeExploreBundleException("BUNDLE_UNIT_UNAVAILABLE", "该权益当前不可处置");
        }
        Date now = clock.get();
        String unitTo;
        if ("SWAP_CONSENTED".equals(decision)) {
            MerchantChapterOffer target = requireLiveOffer(targetOfferId, unit.getChapterId(), now);
            // 不缩短有效期:目标门店的供给须覆盖到本单位有效期止。
            if (target.getValidEnd() != null && target.getValidEnd().before(unit.getValidTo())) {
                throw new FreeExploreBundleException("BUNDLE_SWAP_SHORTENS_VALIDITY", "目标门店供给期短于套票有效期,不能换");
            }
            unitTo = "AVAILABLE";   // 单位与有效期原样保留;持有人再自行向目标门店预约
        } else {
            unitTo = "REFUND_PENDING";   // 未履约部分进入退款处置;资金动作走既有售后/退款路径
        }
        ExploreBundleExitCase exitCase = new ExploreBundleExitCase();
        exitCase.setUnitId(unit.getId());
        exitCase.setRegistrationId(unit.getRegistrationId());
        exitCase.setHolderMemberId(memberId);
        exitCase.setExitedOfferId(exitedOfferId);
        exitCase.setReservationId(booking == null ? null : booking.getId());
        exitCase.setDecision(decision);
        exitCase.setTargetOfferId(targetOfferId);
        exitCase.setIdempotencyKey(key);
        exitCase.setRequestFingerprint(fingerprint);
        exitCase.setCreateTime(now);
        bundleMapper.insertExitCase(exitCase);
        if (booking != null && bundleMapper.casReservationStatus(
                booking.getId(), booking.getVersion(), "BOOKED", "MERCHANT_EXITED") != 1) {
            throw new FreeExploreBundleException("BUNDLE_CONCURRENT", "预约状态已变化,请刷新后重试");
        }
        if (bundleMapper.casUnitStatus(unit.getId(), unit.getVersion(), unit.getStatus(), unitTo,
                null, null, "MERCHANT_EXIT") != 1) {
            throw new FreeExploreBundleException("BUNDLE_CONCURRENT", "权益状态已变化,请刷新后重试");
        }
        return exitCase;
    }

    @Override
    public Map<String, Object> merchantExitOptions(Long memberId, Long registrationId, Long unitId) {
        requireLedger();
        CmsRegistration registration = requireOwnRegistration(memberId, registrationId);
        ExploreBundleOrder order = requireOwnOrder(memberId, registrationId);
        ExploreBundleUnit unit = bundleMapper.selectUnitsByRegistration(registrationId).stream()
                .filter(row -> Objects.equals(unitId, row.getId()) && Objects.equals(memberId, row.getHolderMemberId()))
                .findFirst().orElseThrow(() -> new FreeExploreBundleException("BUNDLE_NOT_FOUND", "套票单位不存在"));
        boolean exited = false;
        if ("RESERVED".equals(unit.getStatus()) && unit.getReservationId() != null) {
            ExploreBundleReservation booking = bundleMapper.selectReservationById(unit.getReservationId());
            exited = booking != null && "BOOKED".equals(booking.getStatus())
                    && Objects.equals(unit.getId(), booking.getUnitId())
                    && !isLive(offerMapper.selectById(booking.getOfferId()));
        } else if ("AVAILABLE".equals(unit.getStatus())) {
            exited = !hasLiveOfferForChapter(unit);
        }
        List<Map<String, Object>> targets = new ArrayList<>();
        boolean actionable = exited && withinValidity(unit, clock.get()) && switches.reservationActive()
                && Integer.valueOf(2).equals(registration.getPaymentStatus())
                && Integer.valueOf(2).equals(registration.getRegistrationStatus());
        if (actionable) {
            List<MerchantChapterOffer> offers = offerMapper.selectByTopic(order.getTopicId());
            for (MerchantChapterOffer offer : offers == null ? Collections.<MerchantChapterOffer>emptyList() : offers) {
                if (!Objects.equals(unit.getChapterId(), offer.getChapterId()) || !isLive(offer)
                        || offer.getValidEnd() != null && offer.getValidEnd().before(unit.getValidTo())) continue;
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("offerId", exactId(offer.getId()));
                target.put("merchantName", offer.getMerchantName());
                target.put("validEnd", offer.getValidEnd());
                targets.add(target);
            }
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("registrationId", exactId(registrationId));
        options.put("unitId", exactId(unitId));
        options.put("holderMemberId", exactId(memberId));
        options.put("merchantExited", exited);
        options.put("targets", targets);
        options.put("refundSubmissionAvailable", false); // No new fund/after-sales route is authorized by this read API.
        return options;
    }

    // ================================================================ 内部

    static List<Map<String, Object>> balances(List<ExploreBundleGrant> grants, List<ExploreBundleUnit> units, Date now) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExploreBundleGrant grant : grants == null ? Collections.<ExploreBundleGrant>emptyList() : grants) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String s : new String[] {"AVAILABLE", "RESERVED", "REDEEMED", "FORFEITED", "HELD",
                    "REFUND_PENDING", "REFUNDED"}) counts.put(s, 0);
            for (ExploreBundleUnit unit : units == null ? Collections.<ExploreBundleUnit>emptyList() : units) {
                if (grant.getId().equals(unit.getGrantId())) counts.merge(unit.getStatus(), 1, Integer::sum);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("grantId", grant.getId());
            row.put("holderMemberId", grant.getHolderMemberId());
            row.put("chapterId", grant.getChapterId());
            row.put("grantNo", grant.getGrantNo());
            row.put("grantType", grant.getGrantType());
            row.put("unitsTotal", grant.getUnitsTotal());
            row.put("counts", counts);
            row.put("validFrom", grant.getValidFrom());
            row.put("validTo", grant.getValidTo());
            row.put("expired", now.before(grant.getValidFrom()) || !now.before(grant.getValidTo()));
            rows.add(row);
        }
        return rows;
    }

    private void transition(ExploreBundleReservation reservation, ExploreBundleUnit unit,
                            String reservationTo, String unitTo) {
        if (bundleMapper.casReservationStatus(reservation.getId(), reservation.getVersion(), "BOOKED", reservationTo) != 1
                || bundleMapper.casUnitStatus(unit.getId(), unit.getVersion(), "RESERVED", unitTo,
                null, null, reservationTo) != 1) {
            throw new FreeExploreBundleException("BUNDLE_CONCURRENT", "预约状态已变化,请刷新后重试");
        }
    }

    private ExploreBundleUnit requireReservedUnit(ExploreBundleReservation reservation) {
        ExploreBundleUnit unit = bundleMapper.selectUnitByIdForUpdate(reservation.getUnitId());
        if (unit == null || !"RESERVED".equals(unit.getStatus()) || !reservation.getId().equals(unit.getReservationId())) {
            throw new IllegalStateException("预约与套票单位状态不一致,请人工核对");
        }
        return unit;
    }

    private MerchantChapterOffer requireLiveOffer(Long offerId, Long chapterId, Date at) {
        MerchantChapterOffer offer = offerMapper.selectById(offerId);
        if (offer == null || !chapterId.equals(offer.getChapterId()) || !isLive(offer)
                || (offer.getValidEnd() != null && !offer.getValidEnd().after(at))) {
            throw new FreeExploreBundleException("BUNDLE_STORE_UNAVAILABLE", "该门店当前不提供本章节权益");
        }
        return offer;
    }

    private boolean isLive(MerchantChapterOffer offer) {
        return offer != null && Integer.valueOf(1).equals(offer.getStatus())
                && (offer.getValidEnd() == null || offer.getValidEnd().after(clock.get()));
    }

    private boolean hasLiveOfferForChapter(ExploreBundleUnit unit) {
        ExploreBundleOrder order = bundleMapper.selectOrderByRegistration(unit.getRegistrationId());
        List<MerchantChapterOffer> offers = order == null ? null : offerMapper.selectByTopic(order.getTopicId());
        if (offers == null) return false;
        for (MerchantChapterOffer offer : offers) {
            if (unit.getChapterId().equals(offer.getChapterId()) && isLive(offer)) return true;
        }
        return false;
    }

    private static boolean withinValidity(ExploreBundleUnit unit, Date now) {
        return unit.getValidFrom() != null && unit.getValidTo() != null
                && !now.before(unit.getValidFrom()) && now.before(unit.getValidTo());
    }

    private CmsRegistration requireOwnRegistration(Long memberId, Long registrationId) {
        CmsRegistration registration = registrationId == null ? null
                : registrationMapper.selectCmsRegistrationById(registrationId);
        // 不区分「不存在」与「不是你的」:防按 id 枚举他人订单。
        if (registration == null || memberId == null || !memberId.equals(registration.getMemberId())) {
            throw new FreeExploreBundleException("BUNDLE_NOT_FOUND", "订单不存在");
        }
        return registration;
    }

    private ExploreBundleOrder requireOwnOrder(Long memberId, Long registrationId) {
        ExploreBundleOrder order = bundleMapper.selectOrderByRegistration(registrationId);
        if (order == null || !memberId.equals(order.getHolderMemberId())) {
            throw new FreeExploreBundleException("BUNDLE_NOT_FOUND", "套票订单不存在");
        }
        return order;
    }

    private static void requirePaid(CmsRegistration registration) {
        if (!Integer.valueOf(2).equals(registration.getPaymentStatus())
                || !Integer.valueOf(2).equals(registration.getRegistrationStatus())) {
            throw new FreeExploreBundleException("BUNDLE_ORDER_NOT_ACTIVE", "订单未支付或已失效");
        }
    }

    private void requireLedger() {
        if (!switches.ledgerEnabled()) {
            throw new FreeExploreBundleException("BUNDLE_LEDGER_OFF", "自由探索套票暂未开放");
        }
    }

    private void requireReservation() {
        if (!switches.reservationActive()) {
            throw new FreeExploreBundleException("BUNDLE_RESERVATION_OFF", "自由探索套票预约暂未开放");
        }
    }

    private static String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty() || idempotencyKey.trim().length() > 64) {
            throw new FreeExploreBundleException("BUNDLE_BAD_REQUEST", "幂等键无效");
        }
        return idempotencyKey.trim();
    }

    private static <T> T replayOrConflict(String stored, String fingerprint, T replay) {
        if (!Objects.equals(stored, fingerprint)) {
            throw new FreeExploreBundleException("BUNDLE_IDEMPOTENCY_CONFLICT", "该幂等键已用于不同的请求内容");
        }
        return replay;
    }

    private static Map<String, Object> policyView() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("version", FreeExploreBundlePolicy.POLICY_VERSION);
        policy.put("freeCancelHours", FreeExploreBundlePolicy.FREE_CANCEL_HOURS);
        policy.put("partialRefundMinRatio", FreeExploreBundlePolicy.PARTIAL_REFUND_MIN_RATIO);
        policy.put("monthlyVoluntaryWaivers", 1);
        return policy;
    }

    private static String exactId(Long value) {
        return value == null ? null : value.toString();
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    static String newUnitCode() {
        StringBuilder code = new StringBuilder("FX");
        for (int i = 0; i < 18; i++) code.append(UNIT_CODE_ALPHABET.charAt(RANDOM.nextInt(UNIT_CODE_ALPHABET.length())));
        return code.toString();
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
