package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.vo.PublicFeaturedActivityVO;
import com.chengyinhub.business.domain.vo.PublicFeaturedCouponVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 4-12 / S19-d:主推卡必须自报「是什么」与「哪一条」。
 *
 * <p>据点页({@code components/cy/scene-roam-poi-detail})按 {@code featuredType === 1 && featuredId}
 * 判断卡片可点、按 {@code featuredType === 2} 跳券夹;统一主页也要按同一合同选跳转目标。
 * 主推 VO 里没有这两个字段时,卡片能显示但 {@code actionable} 恒 false —— 点了没反应。</p>
 */
class PublicFeaturedCardFieldsContractTest {

    @Test
    void featuredActivityCardCarriesOwnTypeAndId() throws Exception {
        CmsActivity activity = new CmsActivity();
        activity.setId(16L);
        activity.setName("周末露营市集");

        String json = new ObjectMapper().writeValueAsString(PublicFeaturedActivityVO.from(activity));

        assertTrue(json.contains("\"featuredType\":1"), json);
        assertTrue(json.contains("\"featuredId\":16"), json);
    }

    @Test
    void featuredCouponCardCarriesOwnTypeAndId() throws Exception {
        SmsCoupon coupon = new SmsCoupon();
        coupon.setId(15L);
        coupon.setName("满减券");
        coupon.setAmount(new BigDecimal("10.00"));

        String json = new ObjectMapper().writeValueAsString(PublicFeaturedCouponVO.from(coupon));

        assertTrue(json.contains("\"featuredType\":2"), json);
        assertTrue(json.contains("\"featuredId\":15"), json);
    }
}
