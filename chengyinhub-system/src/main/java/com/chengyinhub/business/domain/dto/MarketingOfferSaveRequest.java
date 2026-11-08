package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.MarketingOffer;
import com.chengyinhub.business.domain.MarketingOfferItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台保存组合包的请求体:供给本体 + 成员行。
 *
 * <p>成员行必须随本体一起提交 —— 分开两个接口会让"改了价但成员还是旧的"这种
 * 半成品配置有可乘之机。保存时成员整体替换,不做增量合并。
 */
public class MarketingOfferSaveRequest extends MarketingOffer {

    private static final long serialVersionUID = 1L;

    private List<MarketingOfferItem> items = new ArrayList<>();

    public List<MarketingOfferItem> getItems() { return items; }

    public void setItems(List<MarketingOfferItem> items) {
        this.items = items == null ? new ArrayList<>() : items;
    }
}
