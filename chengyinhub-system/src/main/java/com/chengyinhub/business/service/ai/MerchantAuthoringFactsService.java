package com.chengyinhub.business.service.ai;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.domain.PmsSku;
import com.chengyinhub.business.domain.dto.ai.MerchantAuthoringFactsReference;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IPmsProductService;
import com.chengyinhub.business.service.IPmsSkuService;
import com.chengyinhub.common.exception.ServiceException;
import com.github.pagehelper.PageHelper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Canonical identity/text projection for authoring. No stock, price, wallet or sale authority. */
@org.springframework.context.annotation.Profile("w05-merchant-authoring")
@Service
public class MerchantAuthoringFactsService {
    private final IMerchantAccessService access;
    private final IMmsMerchantService merchants;
    private final IPmsProductService products;
    private final IPmsSkuService skus;

    @Autowired
    public MerchantAuthoringFactsService(IMerchantAccessService access, IMmsMerchantService merchants,
                                        IPmsProductService products, IPmsSkuService skus) {
        this.access = access; this.merchants = merchants; this.products = products; this.skus = skus;
    }

    /** The controller preserves its normal PageHelper total before projecting rows. */
    public List<PmsProduct> listMine(Long principal, int page) {
        MerchantAccessContext owner = scope(principal);
        if (page <= 0 || page > Integer.MAX_VALUE / 20 + 1) throw invalid();
        PmsProduct query = new PmsProduct();
        query.setMerchantId(owner.getMerchantId()); query.setDelFlag(0);
        // Never apply pagination to the preceding authority/uniqueness queries.
        PageHelper.startPage(page, 20);
        try { return products.selectPmsProductList(query); }
        finally { PageHelper.clearPage(); }
    }

    public List<Map<String, Object>> projectCatalog(Long principal, List<PmsProduct> rows) {
        MerchantAccessContext owner = scope(principal);
        List<Map<String, Object>> result = new ArrayList<>();
        if (rows != null) for (PmsProduct row : rows) {
            requireProduct(row, owner);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", row.getId()); item.put("productName", text(row.getProductName()));
            item.put("publishStatus", row.getPublishStatus()); item.put("catalogKind", "LEGACY_POINTS_IDENTITY_ONLY");
            result.add(item);
        }
        sameScope(principal, owner);
        return result;
    }

    public Map<String, Object> product(Long principal, Long productId) {
        MerchantAccessContext owner = scope(principal);
        PmsProduct product = product(productId, owner);
        PmsSku query = new PmsSku(); query.setProductId(productId);
        List<PmsSku> rows = skus.selectPmsSkuList(query);
        List<Map<String, Object>> options = new ArrayList<>();
        if (rows != null) for (PmsSku row : rows) {
            requireSku(row, productId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skuId", row.getId()); item.put("skuName", text(row.getSkuName()));
            options.add(item);
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("merchantRowId", owner.getMerchantId()); value.put("productId", product.getId());
        value.put("productName", text(product.getProductName())); value.put("skus", options);
        value.put("catalogKind", "LEGACY_POINTS_IDENTITY_ONLY"); value.put("offerConfigured", false);
        sameScope(principal, owner);
        return value;
    }

    public Map<String, Object> facts(Long principal, Long productId, Long skuId) {
        MerchantAccessContext owner = scope(principal);
        PmsProduct product = product(productId, owner);
        positive(skuId);
        PmsSku sku = skus.selectPmsSkuById(skuId); requireSku(sku, productId);
        MmsMerchant merchant = merchants.selectMmsMerchantById(owner.getMerchantId());
        if (merchant == null || !owner.getOwnerMemberId().equals(merchant.getMemberId())) throw unavailable();
        Map<String, Object> value = snapshot(owner, merchant, product, sku);
        sameScope(principal, owner);
        return value;
    }

    /** Mutation callers pass full rows obtained by current locking reads in their transaction. */
    Map<String, Object> requireCurrentSnapshot(MerchantAuthoringFactsReference ref, MerchantAccessContext owner,
            MmsMerchant merchant, PmsProduct product, PmsSku sku) {
        if (ref == null || ref.getFactsHash() == null || !ref.getFactsHash().matches("[a-f0-9]{64}")) throw invalid();
        if (product == null || sku == null || !Objects.equals(ref.getProductId(), product.getId())
                || !Objects.equals(ref.getSkuId(), sku.getId())) throw unavailable();
        Map<String, Object> value = snapshot(owner, merchant, product, sku);
        if (!ref.getFactsHash().equals(value.get("factsHash"))) {
            throw new ServiceException("店铺商品事实已变化，请重新确认", 409);
        }
        return value;
    }

    private Map<String, Object> snapshot(MerchantAccessContext owner, MmsMerchant merchant,
            PmsProduct product, PmsSku sku) {
        requireProduct(product, owner); requireSku(sku, product.getId());
        if (merchant == null || !owner.getMerchantId().equals(merchant.getId())
                || !owner.getOwnerMemberId().equals(merchant.getMemberId())
                || !Long.valueOf(1).equals(merchant.getStatus())
                || !Long.valueOf(1).equals(merchant.getAccountStatus())
                || !Integer.valueOf(0).equals(merchant.getDelFlag())) throw unavailable();
        // Only these source text/identity values enter an AI draft. Price/stock are
        // deliberately absent: the legacy catalog's points price is not a cash offer.
        Map<String, Object> source = new TreeMap<>();
        source.put("schemaVersion", 1); source.put("merchantRowId", owner.getMerchantId());
        source.put("ownerMemberId", owner.getOwnerMemberId()); source.put("shopName", text(merchant.getName()));
        source.put("productId", product.getId()); source.put("productName", text(product.getProductName()));
        source.put("skuId", sku.getId()); source.put("skuName", text(sku.getSkuName()));
        source.put("unit", text(product.getUnit()));
        source.put("merchantUpdatedAt", timestamp(merchant.getUpdateTime()));
        source.put("productUpdatedAt", timestamp(product.getUpdateTime()));
        source.put("skuUpdatedAt", timestamp(sku.getUpdateTime()));
        source.put("publishStatus", product.getPublishStatus());
        source.put("catalogKind", "LEGACY_POINTS_IDENTITY_ONLY"); source.put("offerConfigured", false);
        if (text(merchant.getName()).trim().isEmpty() || text(product.getProductName()).trim().isEmpty()) throw unavailable();
        Map<String, Object> value = new LinkedHashMap<>(source); value.put("factsHash", digest(source));
        return Collections.unmodifiableMap(value);
    }

    public Map<String, Object> requireCurrent(Long principal, MerchantAuthoringFactsReference ref) {
        if (ref == null || ref.getFactsHash() == null || !ref.getFactsHash().matches("[a-f0-9]{64}")) throw invalid();
        Map<String, Object> fresh = facts(principal, ref.getProductId(), ref.getSkuId());
        if (!ref.getFactsHash().equals(fresh.get("factsHash"))) {
            throw new ServiceException("店铺商品事实已变化，请重新确认", 409);
        }
        return fresh;
    }

    private MerchantAccessContext scope(Long principal) {
        positive(principal);
        MerchantAccessContext owner = access.require(principal, MerchantPermission.PROJECT_MANAGE);
        if (owner == null || owner.getMerchantId() == null || owner.getMerchantId() <= 0
                || owner.getOwnerMemberId() == null || owner.getOwnerMemberId() <= 0) throw unavailable();
        return owner;
    }
    private void sameScope(Long principal, MerchantAccessContext captured) {
        MerchantAccessContext current = scope(principal);
        if (!captured.getMerchantId().equals(current.getMerchantId())
                || !captured.getOwnerMemberId().equals(current.getOwnerMemberId())
                || !Objects.equals(captured.getRoleCode(), current.getRoleCode())) {
            throw new ServiceException("店铺权限范围已变化，请重新确认", 409);
        }
    }
    private static long timestamp(Date value) { return value == null ? 0 : value.getTime(); }
    private PmsProduct product(Long id, MerchantAccessContext owner) {
        positive(id); PmsProduct value = products.selectPmsProductById(id); requireProduct(value, owner); return value;
    }
    private static void requireProduct(PmsProduct value, MerchantAccessContext owner) {
        if (value == null || value.getId() == null || value.getId() <= 0
                || !owner.getMerchantId().equals(value.getMerchantId()) || (value.getDelFlag() != null && value.getDelFlag() != 0)
                || value.getPublishStatus() == null || value.getPublishStatus() < 0 || value.getPublishStatus() > 2) throw unavailable();
    }
    private static void requireSku(PmsSku value, Long productId) {
        if (value == null || value.getId() == null || value.getId() <= 0 || !productId.equals(value.getProductId())) throw unavailable();
    }
    private static void positive(Long value) { if (value == null || value <= 0) throw invalid(); }
    private static String text(String value) {
        if (value != null && value.length() > 4096) throw unavailable();
        return value == null ? "" : value;
    }
    private static ServiceException invalid() { return new ServiceException("商品事实参数不合法", 400); }
    private static ServiceException unavailable() { return new ServiceException("商品事实不可用", 404); }
    private static String digest(Map<String, Object> source) {
        try {
            byte[] value = MessageDigest.getInstance("SHA-256").digest(JSON.toJSONString(source).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : value) result.append(String.format("%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
