package com.chengyinhub.business.service;

import com.chengyinhub.common.constant.ValidationMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完成方式是跨端契约:编辑器按它渲染字段块、播放端按它挑壳、arrive 按它决定放不放行。
 *
 * <p>本测试钉两件事:新增的三种完成方式(估数/比价/找东西)有名字、
 * 以及 arrive 那道闸是白名单而不是黑名单。
 */
class ValidationMethodContractTest {

    @Test
    void newPlayKitMethodsHaveNames() {
        assertEquals("估数题", ValidationMethod.label(ValidationMethod.ESTIMATE));
        assertEquals("价格连击", ValidationMethod.label(ValidationMethod.PRICE_PAIR));
        assertEquals("找东西", ValidationMethod.label(ValidationMethod.HIDDEN_OBJECT));
    }

    @Test
    void existingMethodsKeepTheirNames() {
        // 这几个名字已经在货架详情、报名单、后台列表上露过面,不能改
        assertEquals("文字验证", ValidationMethod.label(1));
        assertEquals("拍照打卡", ValidationMethod.label(ValidationMethod.PHOTO));
        assertEquals("选项验证", ValidationMethod.label(3));
        assertEquals("扫码打卡", ValidationMethod.label(4));
        assertEquals("GPS到达", ValidationMethod.label(5));
    }

    @Test
    void unknownMethodFallsBackToEmptyInsteadOfThrowing() {
        assertEquals("", ValidationMethod.label(null));
        assertEquals("", ValidationMethod.label(99));
    }

    /**
     * ★ 这条是本文件的重点。
     *
     * <p>原来 arrive 那道闸写成"拒绝 1/2/3/4/6/7",于是每加一个新完成方式都默认放行 ——
     * 新玩法一上线就能被 arrive 绕过自己的判定,而且零报错。
     * 改成白名单后,不在名单里的一律拦下。
     */
    @Test
    void arriveGateIsAWhitelistSoUnknownMethodsAreRefused() {
        assertFalse(ValidationMethod.completesOnArrive(99),
                "没见过的完成方式必须被拦,而不是默认放行");
        assertFalse(ValidationMethod.completesOnArrive(1), "文字题要答过才算");
        assertFalse(ValidationMethod.completesOnArrive(ValidationMethod.PHOTO));
        assertFalse(ValidationMethod.completesOnArrive(3));
        assertFalse(ValidationMethod.completesOnArrive(4), "扫码要真扫");
        assertFalse(ValidationMethod.completesOnArrive(ValidationMethod.PREFERENCE_GROUP));
        assertFalse(ValidationMethod.completesOnArrive(ValidationMethod.APP_SENSOR));
    }

    /** 判定动作不在节点本身的几种:到达就能收尾,玩没玩完由 completeGate 兜。 */
    @Test
    void methodsWhoseJudgementLivesElsewhereMayCompleteOnArrive() {
        assertTrue(ValidationMethod.completesOnArrive(0), "到点即完成");
        assertTrue(ValidationMethod.completesOnArrive(5), "GPS 到达");
        assertTrue(ValidationMethod.completesOnArrive(ValidationMethod.ESTIMATE));
        assertTrue(ValidationMethod.completesOnArrive(ValidationMethod.PRICE_PAIR));
        assertTrue(ValidationMethod.completesOnArrive(ValidationMethod.HIDDEN_OBJECT));
        assertTrue(ValidationMethod.completesOnArrive(null), "没配就是到点即完成");
    }
}
