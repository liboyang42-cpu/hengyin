package com.chengyinhub.business.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.common.constant.ProductType;

/** 普通自由探索的俱乐部直付主体；不从发布者账号或历史 club_id 猜付款方。 */
public final class ClubDirectMerchantPolicy {
    private ClubDirectMerchantPolicy() { }

    public static Long payerClubId(CmsTopic topic, ProductRole content) {
        if (topic == null || !Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())
                || (topic.getCircleThemeCode() != null && !topic.getCircleThemeCode().trim().isEmpty())
                || content == null || !"CLUB".equals(content.getEntityType())
                || content.getEntityId() == null || content.getEntityId() <= 0) return null;
        return content.getEntityId();
    }
}
