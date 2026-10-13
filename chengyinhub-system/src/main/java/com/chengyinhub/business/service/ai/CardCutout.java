package com.chengyinhub.business.service.ai;

/**
 * 一次抠图转存的结果:自家桶里的贴纸地址 + 物品在原照片里的位置。
 *
 * <p>位置是「x,y,w,h」四个比例(0 到 1,各四位小数),相对玩家那张原照片。当场揭晓时背景散掉、
 * 物品留在原位,靠的就是它;两样总是一起产出、一起落库,所以不拆成两个返回值。
 */
public final class CardCutout {

    private final String url;
    private final String box;

    public CardCutout(String url, String box) {
        this.url = url;
        this.box = box;
    }

    public String getUrl() {
        return url;
    }

    public String getBox() {
        return box;
    }
}
