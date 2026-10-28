package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.mapper.MerchantDeskMapper;
import com.chengyinhub.business.service.IMerchantDeskService;
import com.chengyinhub.business.service.MerchantDeskException;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.business.service.support.RedemptionAnchorPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Doc2 F4 商家工作台实现。约定见 {@link IMerchantDeskService}。
 *
 * <p>归属:门店/商品/SKU/锚点按 {@code access.getMerchantId()}(mms_merchant.id),
 * 核销/复核/对账按 {@code access.getOwnerMemberId()}。两者都只取自服务端解析的访问上下文。
 */
@Service
public class MerchantDeskServiceImpl implements IMerchantDeskService {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final String ACTION_PRODUCT_SAVE = "PRODUCT_SAVE";
    static final String ACTION_PRODUCT_DELIST = "PRODUCT_DELIST";
    static final String ACTION_SKU_SAVE = "SKU_SAVE";
    static final String ACTION_ANCHOR_SAVE = "ANCHOR_SAVE";
    static final String ACTION_REVIEW_EVIDENCE = "REVIEW_EVIDENCE";

    /** 四个对账口径 = settlement_claim.settlement_route 的 DDL 取值 */
    static final List<String> FOUR_BASES = Collections.unmodifiableList(
            Arrays.asList("CHAPTER_OFFER", "COOP_ORDER", "REGISTRATION_SPLIT", "NONE"));
    static final int MAX_EVIDENCE_PER_REVIEW = 10;
    static final int MAX_EVIDENCE_MEDIA = 6;
    static final int MAX_ALBUM_PICS = 5;
    /** 锚点离门店地图点的最远距离(米):防止把锚点设到别处去 */
    static final int MAX_ANCHOR_OFFSET_M = 1000;

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern HOURS = Pattern.compile(
            "周(一至周日|[一二三四五六日](?:、[一二三四五六日])*) ([01][0-9]|2[0-3]):([0-5][0-9])-(次日)?([01][0-9]|2[0-3]):([0-5][0-9])");
    private static final String WEEK = "一二三四五六日";

    @Autowired private MerchantDeskMapper mapper;
    @Autowired private AppMediaReferenceGuard mediaReferences;

    // ================= 回执 =================

    @Override
    public Map<String, Object> receipt(MerchantAccessContext access, String requestId) {
        String key = requireRequestId(requestId);
        Map<String, Object> row = mapper.selectReceipt(access.getOwnerMemberId(), key);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("requestId", key);
        if (row == null) {
            // 当前查询未见回执不证明原写未提交（原事务可能仍在运行）；消费者必须保留 UNKNOWN 与原请求号。
            view.put("state", "NOT_FOUND");
            return view;
        }
        view.put("state", "APPLIED");
        view.put("action", row.get("action"));
        view.put("targetType", row.get("targetType"));
        view.put("targetId", row.get("targetId"));
        view.put("createdAt", row.get("createdAt"));
        Long targetId = toLong(row.get("targetId"));
        if (targetId != null && ACTION_SKU_SAVE.equals(row.get("action")) && "pms_sku".equals(row.get("targetType"))) {
            view.put("productId", mapper.selectReceiptSkuProductId(access.getMerchantId(), targetId));
        } else if (targetId != null && ACTION_REVIEW_EVIDENCE.equals(row.get("action"))
                && "verification_review_evidence".equals(row.get("targetType"))) {
            view.put("reviewId", mapper.selectReceiptEvidenceReviewId(access.getOwnerMemberId(), targetId));
        }
        return view;
    }

    // ================= 门店 / 锚点 =================

    @Override
    public List<Map<String, Object>> stores(MerchantAccessContext access) {
        Map<String, Object> store = mapper.selectStore(access.getMerchantId());
        if (store == null) return Collections.emptyList();
        Map<String, Object> view = new LinkedHashMap<>(store);
        view.put("businessHours", parseBusinessHours(str(store.get("businessTime"))));
        Map<String, Object> anchor = mapper.selectAnchor(access.getMerchantId());
        view.put("anchorConfigured", anchor != null);
        // 一个商家账号当前 = 一家门店(mms_merchant 一行);多门店模型见 PR 冲突 C-F4-1
        view.put("editEndpoints", editEndpoints());
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(view);
        return list;
    }

    @Override
    public Map<String, Object> anchor(MerchantAccessContext access) {
        Map<String, Object> anchor = mapper.selectAnchor(access.getMerchantId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("configured", anchor != null);
        if (anchor != null) {
            view.put("anchorLat", anchor.get("anchorLat"));
            view.put("anchorLng", anchor.get("anchorLng"));
            view.put("radiusM", anchor.get("radiusM"));
            view.put("wifiCount", wifiHashes(anchor).size());
            view.put("version", anchor.get("version"));
            view.put("updatedAt", anchor.get("updatedAt"));
        }
        view.put("enforced", false);
        return view;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveAnchor(MerchantAccessContext access, Map<String, Object> body) {
        BigDecimal lat = decimal(body, "anchorLat");
        BigDecimal lng = decimal(body, "anchorLng");
        Integer radius = integer(body, "radiusM");
        if (!RedemptionAnchorPolicy.validCoordinate(lat, lng)) throw badRequest("请在地图上选择核销锚点");
        if (!RedemptionAnchorPolicy.validRadius(radius)) {
            throw badRequest("核销半径需在 " + RedemptionAnchorPolicy.MIN_RADIUS_M + "-"
                    + RedemptionAnchorPolicy.MAX_RADIUS_M + " 米之间");
        }
        List<String> hashes = RedemptionAnchorPolicy.hashAll(stringList(body, "wifiBssids"));
        if (hashes == null) throw badRequest("WiFi 需填写有效 BSSID(形如 aa:bb:cc:dd:ee:ff),最多 " + RedemptionAnchorPolicy.MAX_WIFI + " 个");
        Integer expectedVersion = integer(body, "expectedVersion");

        Map<String, Object> payload = new TreeMap<>();
        payload.put("anchorLat", lat.stripTrailingZeros().toPlainString());
        payload.put("anchorLng", lng.stripTrailingZeros().toPlainString());
        payload.put("radiusM", radius);
        payload.put("wifi", hashes);
        payload.put("expectedVersion", expectedVersion);
        return idempotent(access, str(body.get("requestId")), ACTION_ANCHOR_SAVE, payload, () -> {
            Map<String, Object> store = requireStore(access);
            BigDecimal storeLat = toDecimal(store.get("locationLat"));
            BigDecimal storeLng = toDecimal(store.get("locationLng"));
            if (RedemptionAnchorPolicy.validCoordinate(storeLat, storeLng)
                    && RedemptionAnchorPolicy.OUT_OF_RANGE.equals(RedemptionAnchorPolicy.locationVerdict(
                            storeLat, storeLng, MAX_ANCHOR_OFFSET_M, lat, lng, null))) {
                throw new MerchantDeskException(422, "F4_ANCHOR_TOO_FAR", "核销锚点需在门店位置 1 公里以内");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("merchantId", access.getMerchantId());
            row.put("ownerMemberId", access.getOwnerMemberId());
            row.put("anchorLat", lat);
            row.put("anchorLng", lng);
            row.put("radiusM", radius);
            row.put("wifiBssidHashes", hashes.isEmpty() ? null : JSON.toJSONString(hashes));
            row.put("updatedBy", access.getPrincipalMemberId());
            row.put("now", new Date());
            Map<String, Object> current = mapper.selectAnchor(access.getMerchantId());
            if (current == null) {
                if (expectedVersion != null) throw versionConflict();
                mapper.insertAnchor(row);
                return new Target("merchant_redemption_anchor", toLong(row.get("id")));
            }
            if (expectedVersion == null) throw versionConflict();
            row.put("expectedVersion", expectedVersion);
            if (mapper.updateAnchor(row) != 1) throw versionConflict();
            return new Target("merchant_redemption_anchor", toLong(current.get("id")));
        });
    }

    @Override
    public Map<String, Object> checkAnchor(MerchantAccessContext access, Map<String, Object> body) {
        Map<String, Object> anchor = mapper.selectAnchor(access.getMerchantId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("configured", anchor != null);
        view.put("enforced", false);
        if (anchor == null) return view;
        BigDecimal accuracyM = decimal(body, "accuracyM");
        Double accuracy = accuracyM == null ? null : accuracyM.doubleValue();
        view.put("location", RedemptionAnchorPolicy.locationVerdict(toDecimal(anchor.get("anchorLat")),
                toDecimal(anchor.get("anchorLng")), toInt(anchor.get("radiusM")),
                decimal(body, "lat"), decimal(body, "lng"), accuracy));
        view.put("wifi", RedemptionAnchorPolicy.wifiVerdict(wifiHashes(anchor), str(body.get("bssid"))));
        return view;
    }

    // ================= 商品 / SKU / 容量 =================

    @Override
    public List<Map<String, Object>> products(MerchantAccessContext access) {
        return mapper.selectProducts(access.getMerchantId());
    }

    @Override
    public Map<String, Object> productDetail(MerchantAccessContext access, Long productId) {
        if (productId == null) throw badRequest("缺少商品");
        Map<String, Object> product = mapper.selectProduct(access.getMerchantId(), productId);
        if (product == null) throw notOwned("商品不存在或不属于本门店");
        Map<String, Object> view = new LinkedHashMap<>(product);
        view.put("skus", mapper.selectSkus(access.getMerchantId(), productId));
        view.put("editable", !Integer.valueOf(1).equals(toInt(product.get("publishStatus"))));
        return view;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveProduct(MerchantAccessContext access, Map<String, Object> body) {
        Long productId = toLong(body.get("productId"));
        String name = trimmed(body, "productName");
        if (name == null || name.isEmpty() || name.length() > 60) throw badRequest("商品名称需 1-60 字");
        Integer price = integer(body, "price");
        Integer originalPrice = integer(body, "originalPrice");
        Integer stock = integer(body, "stock");
        if (price == null || price < 0 || price > 10_000_000) throw badRequest("售价无效");
        if (originalPrice != null && (originalPrice < 0 || originalPrice > 10_000_000)) throw badRequest("原价无效");
        if (stock == null || stock < 0 || stock > 1_000_000) throw badRequest("库存需在 0-1000000 之间");
        String unit = trimmed(body, "unit");
        if (unit != null && unit.length() > 16) throw badRequest("单位最多 16 字");
        String pic = trimmed(body, "pic");
        List<String> album = stringList(body, "albumPics");
        if (album.size() > MAX_ALBUM_PICS) throw badRequest("画册最多 " + MAX_ALBUM_PICS + " 张");

        Map<String, Object> payload = new TreeMap<>();
        payload.put("productId", productId);
        payload.put("productName", name);
        payload.put("price", price);
        payload.put("originalPrice", originalPrice);
        payload.put("stock", stock);
        payload.put("unit", unit);
        payload.put("pic", pic);
        payload.put("albumPics", album);
        return idempotent(access, str(body.get("requestId")), ACTION_PRODUCT_SAVE, payload, () -> {
            requireStore(access);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("merchantId", access.getMerchantId());
            row.put("productName", name);
            row.put("pic", pic == null || pic.isEmpty() ? null
                    : mediaReferences.requireAttachment(access.getPrincipalMemberId(), pic, false));
            row.put("albumPics", album.isEmpty() ? null : mediaReferences.requireAttachments(
                    access.getPrincipalMemberId(), String.join(";", album), false));
            row.put("price", price);
            row.put("originalPrice", originalPrice);
            row.put("stock", stock);
            row.put("unit", unit);
            row.put("actor", String.valueOf(access.getPrincipalMemberId()));
            row.put("now", new Date());
            if (productId == null) {
                mapper.insertProduct(row);
                return new Target("pms_product", toLong(row.get("id")));
            }
            Map<String, Object> current = mapper.selectProductForUpdate(access.getMerchantId(), productId);
            if (current == null) throw notOwned("商品不存在或不属于本门店");
            if (Integer.valueOf(1).equals(toInt(current.get("publishStatus")))) throw liveLocked();
            row.put("productId", productId);
            if (mapper.updateProduct(row) != 1) throw liveLocked();
            return new Target("pms_product", productId);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> delistProduct(MerchantAccessContext access, Map<String, Object> body) {
        Long productId = toLong(body.get("productId"));
        if (productId == null) throw badRequest("缺少商品");
        Map<String, Object> payload = new TreeMap<>();
        payload.put("productId", productId);
        return idempotent(access, str(body.get("requestId")), ACTION_PRODUCT_DELIST, payload, () -> {
            Map<String, Object> current = mapper.selectProductForUpdate(access.getMerchantId(), productId);
            if (current == null) throw notOwned("商品不存在或不属于本门店");
            Integer status = toInt(current.get("publishStatus"));
            if (Integer.valueOf(1).equals(status)) {
                if (mapper.delistProduct(access.getMerchantId(), productId,
                        String.valueOf(access.getPrincipalMemberId()), new Date()) != 1) {
                    throw new MerchantDeskException(409, MerchantDeskException.VERSION_CONFLICT, "商品状态已变化，请刷新");
                }
            } else if (!Integer.valueOf(2).equals(status)) {
                throw new MerchantDeskException(409, "F4_PRODUCT_NOT_LIVE", "商品未上架，无需下架");
            }
            return new Target("pms_product", productId);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveSku(MerchantAccessContext access, Map<String, Object> body) {
        Long productId = toLong(body.get("productId"));
        Long skuId = toLong(body.get("skuId"));
        if (productId == null) throw badRequest("缺少商品");
        String skuName = trimmed(body, "skuName");
        if (skuName == null || skuName.isEmpty() || skuName.length() > 60) throw badRequest("规格名称需 1-60 字");
        BigDecimal price = decimal(body, "price");
        BigDecimal originalPrice = decimal(body, "originalPrice");
        if (price == null || price.signum() < 0 || price.compareTo(new BigDecimal("10000000")) > 0 || price.scale() > 2) {
            throw badRequest("规格售价无效");
        }
        if (originalPrice != null && (originalPrice.signum() < 0 || originalPrice.compareTo(new BigDecimal("10000000")) > 0
                || originalPrice.scale() > 2)) {
            throw badRequest("规格原价无效");
        }
        Integer stock = integer(body, "stock");
        if (stock == null || stock < 0 || stock > 1_000_000) throw badRequest("库存需在 0-1000000 之间");
        String pic = trimmed(body, "pic");
        boolean bookable = Boolean.TRUE.equals(body.get("bookable")) || "1".equals(str(body.get("bookable")))
                || "true".equals(str(body.get("bookable")));
        Integer slotMinutes = integer(body, "slotMinutes");
        Integer capacityPerSlot = integer(body, "capacityPerSlot");
        if (slotMinutes == null) slotMinutes = 60;
        if (capacityPerSlot == null) capacityPerSlot = 0;
        if (slotMinutes < 15 || slotMinutes > 480 || slotMinutes % 15 != 0) throw badRequest("时段长度需为 15-480 分钟且为 15 的倍数");
        if (capacityPerSlot < 0 || capacityPerSlot > 1000) throw badRequest("每时段容量需在 0-1000 之间");
        if (bookable && capacityPerSlot == 0) throw badRequest("可预约规格的每时段容量需大于 0");
        Integer ruleVersion = integer(body, "ruleVersion");

        Map<String, Object> payload = new TreeMap<>();
        payload.put("productId", productId);
        payload.put("skuId", skuId);
        payload.put("skuName", skuName);
        payload.put("price", price.stripTrailingZeros().toPlainString());
        payload.put("originalPrice", originalPrice == null ? null : originalPrice.stripTrailingZeros().toPlainString());
        payload.put("stock", stock);
        payload.put("pic", pic);
        payload.put("bookable", bookable);
        payload.put("slotMinutes", slotMinutes);
        payload.put("capacityPerSlot", capacityPerSlot);
        payload.put("ruleVersion", ruleVersion);
        final int slot = slotMinutes;
        final int capacity = capacityPerSlot;
        return idempotent(access, str(body.get("requestId")), ACTION_SKU_SAVE, payload, () -> {
            Map<String, Object> product = mapper.selectProductForUpdate(access.getMerchantId(), productId);
            if (product == null) throw notOwned("商品不存在或不属于本门店");
            // 上架中的商品 SKU 库存是线上扣减口径(deductStock),商家不能边卖边改
            if (Integer.valueOf(1).equals(toInt(product.get("publishStatus")))) throw liveLocked();
            Date now = new Date();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("merchantId", access.getMerchantId());
            row.put("productId", productId);
            row.put("skuName", skuName);
            row.put("price", price);
            row.put("originalPrice", originalPrice);
            row.put("stock", stock);
            row.put("pic", pic == null || pic.isEmpty() ? null
                    : mediaReferences.requireAttachment(access.getPrincipalMemberId(), pic, false));
            row.put("actor", String.valueOf(access.getPrincipalMemberId()));
            row.put("now", now);
            Long savedSkuId;
            if (skuId == null) {
                if (mapper.insertSku(row) != 1) throw liveLocked();
                savedSkuId = toLong(row.get("id"));
            } else {
                Map<String, Object> sku = mapper.selectSkuForUpdate(access.getMerchantId(), skuId);
                if (sku == null || !productId.equals(toLong(sku.get("productId")))) throw notOwned("规格不存在或不属于本商品");
                row.put("skuId", skuId);
                if (mapper.updateSku(row) != 1) throw liveLocked();
                savedSkuId = skuId;
            }
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("skuId", savedSkuId);
            rule.put("productId", productId);
            rule.put("merchantId", access.getMerchantId());
            rule.put("bookable", bookable ? 1 : 0);
            rule.put("slotMinutes", slot);
            rule.put("capacityPerSlot", capacity);
            rule.put("updatedBy", access.getPrincipalMemberId());
            rule.put("now", now);
            Map<String, Object> currentRule = mapper.selectBookingRule(access.getMerchantId(), savedSkuId);
            if (currentRule == null) {
                if (ruleVersion != null) throw versionConflict();
                mapper.insertBookingRule(rule);
            } else {
                if (ruleVersion == null) throw versionConflict();
                rule.put("expectedVersion", ruleVersion);
                if (mapper.updateBookingRule(rule) != 1) throw versionConflict();
            }
            return new Target("pms_sku", savedSkuId);
        });
    }

    @Override
    public List<Map<String, Object>> capacitySlots(MerchantAccessContext access, Long skuId, String fromDate, Integer days) {
        if (skuId == null) throw badRequest("缺少规格");
        LocalDate from;
        try {
            from = fromDate == null || fromDate.trim().isEmpty() ? LocalDate.now(ZONE) : LocalDate.parse(fromDate.trim());
        } catch (DateTimeParseException e) {
            throw badRequest("日期格式应为 yyyy-MM-dd");
        }
        int span = days == null ? 7 : Math.max(1, Math.min(days, 14));
        return mapper.selectCapacitySlots(access.getMerchantId(), skuId, startOf(from), startOf(from.plusDays(span)));
    }

    @Override
    public Map<String, Object> capacitySlotPage(MerchantAccessContext access, Long skuId, String fromDate,
                                                Integer days, Long afterStartAt, Long afterSlotId) {
        if (skuId == null || skuId <= 0) throw badRequest("缺少有效规格");
        LocalDate from;
        try {
            from = fromDate == null || fromDate.trim().isEmpty() ? LocalDate.now(ZONE) : LocalDate.parse(fromDate.trim());
        } catch (DateTimeParseException e) {
            throw badRequest("日期格式应为 yyyy-MM-dd");
        }
        int span = days == null ? 14 : days;
        if (span < 1 || span > 14) throw badRequest("查询范围需为1至14天");
        Date start = startOf(from), end = startOf(from.plusDays(span));
        if ((afterStartAt == null) != (afterSlotId == null) || afterSlotId != null && (afterSlotId <= 0
                || afterStartAt < start.getTime() || afterStartAt >= end.getTime())) {
            throw badRequest("时段游标无效，请重新读取");
        }
        List<Map<String, Object>> rows = mapper.selectCapacitySlotPage(access.getMerchantId(), skuId, start, end,
                afterStartAt == null ? null : new Date(afterStartAt), afterSlotId);
        boolean hasMore = rows.size() > 100;
        List<Map<String, Object>> page = new ArrayList<>(rows.subList(0, Math.min(rows.size(), 100)));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("skuId", skuId);
        view.put("fromDate", from.toString());
        view.put("days", span);
        view.put("rows", page);
        view.put("hasMore", hasMore);
        Map<String, Object> next = null;
        if (hasMore) {
            Map<String, Object> last = page.get(page.size() - 1);
            Object rawStart = last.get("slotStart");
            long millis;
            if (rawStart instanceof Date date) millis = date.getTime();
            else if (rawStart instanceof java.time.LocalDateTime date) millis = date.atZone(ZONE).toInstant().toEpochMilli();
            else throw new IllegalStateException("时段开始时间类型无效");
            next = new LinkedHashMap<>();
            next.put("afterStartAt", millis);
            next.put("afterSlotId", last.get("slotId"));
        }
        view.put("next", next);
        return view;
    }

    // ================= 今日到店 =================

    @Override
    public Map<String, Object> arrivalsToday(MerchantAccessContext access) {
        LocalDate today = LocalDate.now(ZONE);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("date", today.toString());
        List<Map<String, Object>> redeemed = mapper.selectRedeemedBetween(access.getOwnerMemberId(),
                startOf(today), startOf(today.plusDays(1)));
        view.put("redeemed", redeemed);
        view.put("redeemedCount", redeemed.size());
        view.put("openByChapter", mapper.selectOpenEntitlementsByLiveOffer(access.getOwnerMemberId()));
        return view;
    }

    // ================= 复核结论 / 补充材料 =================

    @Override
    public List<Map<String, Object>> reviews(MerchantAccessContext access, Integer status) {
        if (status != null && (status < 0 || status > 2)) throw badRequest("状态无效");
        List<Map<String, Object>> rows = mapper.selectReviews(access.getOwnerMemberId(), status);
        for (Map<String, Object> row : rows) row.put("verdict", verdict(toInt(row.get("status"))));
        return rows;
    }

    @Override
    public Map<String, Object> reviewDetail(MerchantAccessContext access, Long reviewId) {
        if (reviewId == null) throw badRequest("缺少复核单");
        Map<String, Object> review = mapper.selectReview(access.getOwnerMemberId(), reviewId);
        if (review == null) throw notOwned("复核单不存在或不属于本商家");
        Map<String, Object> view = new LinkedHashMap<>(review);
        Integer status = toInt(review.get("status"));
        view.put("verdict", verdict(status));
        view.put("evidence", mapper.selectEvidence(access.getOwnerMemberId(), reviewId));
        view.put("canSubmitEvidence", Integer.valueOf(0).equals(status));
        return view;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitReviewEvidence(MerchantAccessContext access, Map<String, Object> body) {
        Long reviewId = toLong(body.get("reviewId"));
        if (reviewId == null) throw badRequest("缺少复核单");
        String note = trimmed(body, "note");
        if (note != null && note.length() > 500) throw badRequest("说明最多 500 字");
        List<String> media = stringList(body, "media");
        if (media.size() > MAX_EVIDENCE_MEDIA) throw badRequest("图片最多 " + MAX_EVIDENCE_MEDIA + " 张");
        if ((note == null || note.isEmpty()) && media.isEmpty()) throw badRequest("请填写说明或上传图片");
        Map<String, Object> payload = new TreeMap<>();
        payload.put("reviewId", reviewId);
        payload.put("note", note);
        payload.put("media", media);
        return idempotent(access, str(body.get("requestId")), ACTION_REVIEW_EVIDENCE, payload, () -> {
            Map<String, Object> review = mapper.selectReviewForUpdate(access.getOwnerMemberId(), reviewId);
            if (review == null) throw notOwned("复核单不存在或不属于本商家");
            if (!Integer.valueOf(0).equals(toInt(review.get("status")))) {
                throw new MerchantDeskException(409, MerchantDeskException.REVIEW_CLOSED, "复核已出结论，不能再补充材料");
            }
            if (mapper.selectEvidence(access.getOwnerMemberId(), reviewId).size() >= MAX_EVIDENCE_PER_REVIEW) {
                throw new MerchantDeskException(409, MerchantDeskException.EVIDENCE_LIMIT,
                        "每张复核单最多补充 " + MAX_EVIDENCE_PER_REVIEW + " 次材料");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reviewId", reviewId);
            row.put("ownerMemberId", access.getOwnerMemberId());
            row.put("actorMemberId", access.getPrincipalMemberId());
            row.put("note", note == null || note.isEmpty() ? null : note);
            row.put("mediaRefs", media.isEmpty() ? null : mediaReferences.requireAttachments(
                    access.getPrincipalMemberId(), String.join(";", media), false));
            row.put("requestId", requireRequestId(str(body.get("requestId"))));
            row.put("now", new Date());
            if (mapper.insertEvidence(row) != 1) {
                throw new MerchantDeskException(409, MerchantDeskException.REVIEW_CLOSED, "复核已出结论，不能再补充材料");
            }
            return new Target("verification_review_evidence", toLong(row.get("id")));
        });
    }

    // ================= 对账 / 冻结(只读) =================

    @Override
    public Map<String, Object> reconciliationSummary(MerchantAccessContext access) {
        Map<String, Map<String, Object>> byRoute = new LinkedHashMap<>();
        for (String base : FOUR_BASES) byRoute.put(base, baseRow(base, true));
        List<Map<String, Object>> outside = new ArrayList<>();
        for (Map<String, Object> row : mapper.selectClaimSummary(access.getOwnerMemberId())) {
            String route = str(row.get("route"));
            Map<String, Object> target = byRoute.get(route);
            if (target == null) {
                // DDL 注释只列四个口径,但代码已写出 CLUB_DIRECT 等:如实单列,不吞、不并入四口径
                target = baseRow(route, false);
                byRoute.put(route, target);
                outside.add(target);
            }
            long count = toLong(row.get("claimCount")) == null ? 0L : toLong(row.get("claimCount"));
            target.put("claimCount", (Long) target.get("claimCount") + count);
            @SuppressWarnings("unchecked")
            Map<String, Object> byStatus = (Map<String, Object>) target.get("byStatus");
            byStatus.put(str(row.get("status")), count);
        }
        Map<String, Object> chapterOffer = byRoute.get("CHAPTER_OFFER");
        Map<String, Object> coverage = mapper.selectPayableClaimCoverage(access.getOwnerMemberId());
        chapterOffer.put("payableNetWithClaim", coverage == null ? BigDecimal.ZERO : coverage.get("claimedNet"));
        chapterOffer.put("payableRowsWithoutClaim", coverage == null ? 0 : coverage.get("unclaimedRows"));
        chapterOffer.put("payableNetWithoutClaim", coverage == null ? BigDecimal.ZERO : coverage.get("unclaimedNet"));
        List<Map<String, Object>> bases = new ArrayList<>();
        for (String base : FOUR_BASES) bases.add(byRoute.get(base));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("readOnly", true);
        view.put("bases", bases);
        view.put("outsideFourBases", outside);
        return view;
    }

    @Override
    public Map<String, Object> reconciliationClaims(MerchantAccessContext access, String route, Integer pageNum, Integer pageSize) {
        String r = route == null ? "" : route.trim();
        if (!r.matches("[A-Z_]{2,32}")) throw badRequest("对账口径无效");
        int page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 50);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("route", r);
        view.put("pageNum", page);
        view.put("pageSize", size);
        view.put("rows", mapper.selectClaims(access.getOwnerMemberId(), r, (page - 1) * size, size));
        return view;
    }

    @Override
    public List<Map<String, Object>> frozenBatches(MerchantAccessContext access) {
        List<Map<String, Object>> rows = mapper.selectFrozenBatches(access.getOwnerMemberId());
        for (Map<String, Object> row : rows) {
            row.put("holdState", "FROZEN");
            row.put("effect", "冻结期间暂停对账确认与打款，金额不变");
        }
        return rows;
    }

    @Override
    public Map<String, Object> frozenBatchPage(MerchantAccessContext access, String afterPeriodYm, Long afterBatchId) {
        if ((afterPeriodYm == null) != (afterBatchId == null)
                || afterPeriodYm != null && (!afterPeriodYm.matches("[0-9]{4}-(0[1-9]|1[0-2])") || afterBatchId <= 0)) {
            throw badRequest("冻结批次游标无效，请刷新后重试");
        }
        List<Map<String, Object>> rows = mapper.selectFrozenBatchPage(access.getOwnerMemberId(), afterPeriodYm, afterBatchId);
        boolean hasMore = rows.size() > 24;
        List<Map<String, Object>> page = new ArrayList<>();
        for (Map<String, Object> source : rows.subList(0, Math.min(rows.size(), 24))) {
            Map<String, Object> row = new LinkedHashMap<>(source);
            row.put("holdState", "FROZEN");
            row.put("effect", "冻结期间暂停对账确认与打款，金额不变");
            page.add(row);
        }
        Map<String, Object> next = null;
        if (hasMore) {
            Map<String, Object> last = page.get(page.size() - 1);
            next = new LinkedHashMap<>();
            next.put("afterPeriodYm", last.get("periodYm"));
            next.put("afterBatchId", last.get("batchId"));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rows", page);
        view.put("hasMore", hasMore);
        view.put("next", next);
        return view;
    }

    // ================= 幂等 =================

    record Target(String type, Long id) {
    }

    interface Write {
        Target apply();
    }

    Map<String, Object> idempotent(MerchantAccessContext access, String requestId, String action,
                                   Map<String, Object> payload, Write write) {
        String key = requireRequestId(requestId);
        String fingerprint = fingerprint(action, payload);
        Map<String, Object> existing = mapper.selectReceipt(access.getOwnerMemberId(), key);
        if (existing != null) {
            if (!action.equals(existing.get("action")) || !fingerprint.equals(existing.get("fingerprint"))) {
                throw new MerchantDeskException(409, MerchantDeskException.IDEMPOTENCY_CONFLICT,
                        "同一请求号已用于不同内容，请刷新后重试");
            }
            return receiptView(key, action, str(existing.get("targetType")), toLong(existing.get("targetId")), true);
        }
        Target target = write.apply();
        if (target == null || target.id() == null) throw new IllegalStateException("F4 写入未返回目标 id action=" + action);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("ownerMemberId", access.getOwnerMemberId());
        receipt.put("requestId", key);
        receipt.put("action", action);
        receipt.put("fingerprint", fingerprint);
        receipt.put("targetType", target.type());
        receipt.put("targetId", target.id());
        receipt.put("actorMemberId", access.getPrincipalMemberId());
        receipt.put("createdAt", new Date());
        // 并发同键:唯一键冲突抛 DuplicateKeyException,整笔事务回滚,端上按 UNKNOWN 走 receipt 读回
        mapper.insertReceipt(receipt);
        return receiptView(key, action, target.type(), target.id(), false);
    }

    static String fingerprint(String action, Map<String, Object> payload) {
        String canonical = action + "|" + JSON.toJSONString(new TreeMap<>(payload));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static Map<String, Object> receiptView(String requestId, String action, String targetType, Long targetId,
                                                   boolean replayed) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("requestId", requestId);
        view.put("state", "APPLIED");
        view.put("replayed", replayed);
        view.put("action", action);
        view.put("targetType", targetType);
        view.put("targetId", targetId);
        return view;
    }

    // ================= 营业时间(只读解析,写仍走 /api/merchant/update) =================

    /** 解析 /api/merchant/update 校验过的营业时间串;解析不了返回 parsed=false 并原样带回。 */
    static Map<String, Object> parseBusinessHours(String raw) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("raw", raw);
        Matcher m = raw == null ? null : HOURS.matcher(raw.trim());
        if (m == null || !m.matches()) {
            view.put("parsed", false);
            return view;
        }
        List<Integer> days = new ArrayList<>();
        if ("一至周日".equals(m.group(1))) {
            for (int i = 1; i <= 7; i++) days.add(i);
        } else {
            for (String d : m.group(1).split("、")) days.add(WEEK.indexOf(d) + 1);
        }
        view.put("parsed", true);
        view.put("weekdays", days);
        view.put("open", m.group(2) + ":" + m.group(3));
        view.put("close", m.group(5) + ":" + m.group(6));
        view.put("overnight", m.group(4) != null);
        return view;
    }

    private static Map<String, Object> editEndpoints() {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("location", "/api/merchant/decor/save");
        endpoints.put("businessHours", "/api/merchant/update");
        endpoints.put("businessStatus", "/api/merchant/business-status/update");
        endpoints.put("anchor", "/api/merchant/desk/anchor/save");
        return endpoints;
    }

    // ================= 小工具 =================

    private Map<String, Object> requireStore(MerchantAccessContext access) {
        Map<String, Object> store = mapper.selectStore(access.getMerchantId());
        if (store == null || !Long.valueOf(1L).equals(toLong(store.get("status")))
                || !Long.valueOf(1L).equals(toLong(store.get("accountStatus")))) {
            throw new MerchantDeskException(403, MerchantDeskException.STORE_NOT_ACTIVE, "门店未通过审核或已停用");
        }
        return store;
    }

    private static Map<String, Object> baseRow(String route, boolean documented) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("route", route);
        row.put("documented", documented);
        row.put("claimCount", 0L);
        row.put("byStatus", new LinkedHashMap<String, Object>());
        return row;
    }

    private static String verdict(Integer status) {
        if (Integer.valueOf(1).equals(status)) return "CONFIRMED_NORMAL";
        if (Integer.valueOf(2).equals(status)) return "CONFIRMED_ABNORMAL";
        return "PENDING";
    }

    private static List<String> wifiHashes(Map<String, Object> anchor) {
        String json = str(anchor.get("wifiBssidHashes"));
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            return JSON.parseArray(json, String.class);
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    static String requireRequestId(String requestId) {
        String key = requestId == null ? "" : requestId.trim();
        if (!REQUEST_ID.matcher(key).matches()) throw badRequest("缺少请求号 requestId(8-64 位字母数字)");
        return key;
    }

    private static Date startOf(LocalDate day) {
        return Date.from(day.atStartOfDay(ZONE).toInstant());
    }

    private static MerchantDeskException badRequest(String message) {
        return new MerchantDeskException(400, MerchantDeskException.BAD_REQUEST, message);
    }

    private static MerchantDeskException notOwned(String message) {
        // 不存在与不属于本商家同一个回答,免得被拿来探测别家数据
        return new MerchantDeskException(404, MerchantDeskException.NOT_OWNER, message);
    }

    private static MerchantDeskException liveLocked() {
        return new MerchantDeskException(409, MerchantDeskException.PRODUCT_LIVE_LOCKED, "商品上架中，请先下架再修改");
    }

    private static MerchantDeskException versionConflict() {
        return new MerchantDeskException(409, MerchantDeskException.VERSION_CONFLICT, "配置已被修改，请刷新后重试");
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trimmed(Map<String, Object> body, String key) {
        String value = str(body.get(key));
        return value == null ? null : value.trim();
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer toInt(Object value) {
        Long v = toLong(value);
        return v == null || v > Integer.MAX_VALUE || v < Integer.MIN_VALUE ? null : v.intValue();
    }

    private static Integer integer(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).trim().isEmpty()) return null;
        Integer parsed = toInt(value);
        if (parsed == null) throw badRequest("参数 " + key + " 需为整数");
        return parsed;
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal b) return b;
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimal(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).trim().isEmpty()) return null;
        BigDecimal parsed = toDecimal(value);
        if (parsed == null) throw badRequest("参数 " + key + " 需为数字");
        return parsed;
    }

    private static List<String> stringList(Map<String, Object> body, String key) {
        Object value = body.get(key);
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        Collection<?> items = value instanceof Collection<?> c ? c : Arrays.asList(String.valueOf(value).split("[;,]"));
        for (Object item : items) {
            if (item == null) continue;
            String s = String.valueOf(item).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
