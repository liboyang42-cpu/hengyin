package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.IMmsMerchantService;

/**
 * check-1104 j1 F14:商家自己发的活动,主办方是店铺,不是发起人昵称(「城*」「城瘾用户 · 主理人」)。
 *
 * <p>判定与 MerchantBusinessStatusServiceImpl 的「场次归属商家」同一口径:场次不挂俱乐部,且创建者是
 * 生效商家(审核通过、账号正常、未删除)。俱乐部场次仍由俱乐部名作主办方,这里返回 null。
 */
public final class ActivityShopOrganizer {

    private ActivityShopOrganizer() {
    }

    /** 主办店铺;不是商家场次时返回 null。 */
    public static MmsMerchant shopOf(IMmsMerchantService merchants, CmsActivity activity) {
        if (merchants == null || activity == null || activity.getMemberId() == null || activity.getMemberId() <= 0
                || (activity.getClubId() != null && activity.getClubId() > 0)) {
            return null;
        }
        MmsMerchant shop = merchants.selectMmsMerchantByMemberId(activity.getMemberId());
        boolean active = shop != null && shop.getName() != null && !shop.getName().trim().isEmpty()
                && Long.valueOf(1L).equals(shop.getStatus())
                && Long.valueOf(1L).equals(shop.getAccountStatus())
                && Integer.valueOf(0).equals(shop.getDelFlag());
        return active ? shop : null;
    }
}
