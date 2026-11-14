package com.chengyinhub.web.controller.business;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.common.core.domain.AjaxResult;

class CmsMemberTemplateControllerTest {

    @Test
    void getInfoHandlesNullCouponIdSafely() {
        ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);
        ISmsCouponService couponService = mock(ISmsCouponService.class);
        CmsMemberTemplateController controller = new CmsMemberTemplateController();
        ReflectionTestUtils.setField(controller, "cmsMemberTemplateService", templateService);
        ReflectionTestUtils.setField(controller, "smsCouponService", couponService);

        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(992501L);
        template.setCouponId(null);
        when(templateService.selectCmsMemberTemplateById(992501L)).thenReturn(template);

        AjaxResult result = controller.getInfo(992501L);
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        CmsMemberTemplate data = (CmsMemberTemplate) result.get(AjaxResult.DATA_TAG);
        assertSame(template, data);
        assertNull(data.getSmsCoupon());
        verifyNoInteractions(couponService);
    }

    @Test
    void getInfoSkipsNonPositiveCouponIdAndMissingTemplate() {
        ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);
        ISmsCouponService couponService = mock(ISmsCouponService.class);
        CmsMemberTemplateController controller = new CmsMemberTemplateController();
        ReflectionTestUtils.setField(controller, "cmsMemberTemplateService", templateService);
        ReflectionTestUtils.setField(controller, "smsCouponService", couponService);

        CmsMemberTemplate zeroCoupon = new CmsMemberTemplate();
        zeroCoupon.setId(992502L);
        zeroCoupon.setCouponId(0L);
        when(templateService.selectCmsMemberTemplateById(992502L)).thenReturn(zeroCoupon);
        when(templateService.selectCmsMemberTemplateById(992599L)).thenReturn(null);

        assertSame(zeroCoupon, controller.getInfo(992502L).get(AjaxResult.DATA_TAG));
        assertNull(controller.getInfo(992599L).get(AjaxResult.DATA_TAG));
        verifyNoInteractions(couponService);
    }

    @Test
    void getInfoLoadsSmsCouponWhenCouponIdPositive() {
        ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);
        ISmsCouponService couponService = mock(ISmsCouponService.class);
        CmsMemberTemplateController controller = new CmsMemberTemplateController();
        ReflectionTestUtils.setField(controller, "cmsMemberTemplateService", templateService);
        ReflectionTestUtils.setField(controller, "smsCouponService", couponService);

        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(992503L);
        template.setCouponId(88L);
        SmsCoupon coupon = new SmsCoupon();
        coupon.setId(88L);
        when(templateService.selectCmsMemberTemplateById(992503L)).thenReturn(template);
        when(couponService.selectSmsCouponById(88L)).thenReturn(coupon);

        AjaxResult result = controller.getInfo(992503L);
        CmsMemberTemplate data = (CmsMemberTemplate) result.get(AjaxResult.DATA_TAG);
        assertSame(coupon, data.getSmsCoupon());
        verify(couponService).selectSmsCouponById(88L);
    }
}
