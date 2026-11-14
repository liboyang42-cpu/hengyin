package com.chengyinhub.business.domain.vo;

/**
 * 第九轮 m4:游客(未登录)打开分享来的据点时的公开字段投影。
 * 只留地点本身的公开信息(名称、介绍、图片、坐标、地址、商家名与 logo、聚合评分、玩法名称);
 * 去掉商家电话、券、答题题面与本人进度(完成/收藏/待核销)。游客不能在这里互动,这些字段对他没有用。
 * 只看已上架、已审核且商家仍在营业的据点,由 RoamMapper.selectPlayerMerchantNodeDetail 在 memberId=0 时把关。
 */
public final class CityNodeGuestProjection {
    private CityNodeGuestProjection() {
    }

    public static CityNodeVO apply(CityNodeVO vo) {
        if (vo == null) return null;
        vo.setMerchantPhone(null);
        vo.setCouponId(null);
        vo.setNeedRedeem(null);
        vo.setCompleted(null);
        vo.setFavorited(null);
        vo.setQuestionName(null);
        vo.setQuestionA(null);
        vo.setQuestionB(null);
        vo.setQuestionC(null);
        vo.setQuestionD(null);
        return vo;
    }
}
