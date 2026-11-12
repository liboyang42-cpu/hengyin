package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.ChannelVoucher;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.dto.ChannelGrantTopic;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.service.ICmsTopicService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 发行时冻结商品期限、条款、作品与实付分摊；兑换不追随目录编辑。 */
public final class ChannelVoucherSnapshot {
    private ChannelVoucherSnapshot() { }

    public static String capture(MarketingProduct product, List<MarketingProductItem> items,
                                 ICmsTopicService topics, BigDecimal amount, int quantity) {
        List<BigDecimal> weights = new ArrayList<>();
        for (MarketingProductItem item : items) {
            CmsTopic topic = topics.selectCmsTopicById(item.getTopicId());
            if (topic == null || topic.getSelfPlayPrice() == null || topic.getSelfPlayPrice().signum() <= 0) {
                throw ChannelVoucherException.conflict("PRODUCT_PRICE_UNAVAILABLE", "作品缺少单买价，无法冻结渠道实付分摊");
            }
            weights.add(topic.getSelfPlayPrice().multiply(BigDecimal.valueOf(quantity)));
        }
        JSONObject snapshot = new JSONObject();
        snapshot.put("product", product);
        snapshot.put("topics", ChannelGrantPlanner.plan(items, weights, amount));
        return JSON.toJSONString(snapshot);
    }

    public static MarketingProduct product(ChannelVoucher voucher) {
        MarketingProduct product = read(voucher).getObject("product", MarketingProduct.class);
        if (product == null || !voucher.getProductId().equals(product.getId())
                || !voucher.getProductRevision().equals(product.getRevision() == null ? 1L : product.getRevision())) {
            throw invalidSnapshot();
        }
        return product;
    }

    public static List<ChannelGrantTopic> topics(ChannelVoucher voucher) {
        List<ChannelGrantTopic> topics = read(voucher).getList("topics", ChannelGrantTopic.class);
        if (topics == null || topics.isEmpty()) throw invalidSnapshot();
        return topics;
    }

    private static JSONObject read(ChannelVoucher voucher) {
        try {
            JSONObject snapshot = JSON.parseObject(voucher.getGrantSnapshotJson());
            if (snapshot != null) return snapshot;
        } catch (RuntimeException malformed) {
            throw invalidSnapshot();
        }
        throw invalidSnapshot();
    }

    private static ChannelVoucherException invalidSnapshot() {
        return ChannelVoucherException.conflict("VOUCHER_SNAPSHOT_UNAVAILABLE", "兑换码缺少可信发行快照，请联系发行方核对");
    }
}
