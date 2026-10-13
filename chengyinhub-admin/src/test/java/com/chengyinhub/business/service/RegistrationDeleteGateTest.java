package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F7 运营后台物理删已付费报名。
 *
 * <p>病:{@code DELETE /business/registration/{ids}} 三层全裸 —— controller 只有 @PreAuthorize,
 * service 纯透传,mapper 是真 {@code delete from}(这张表连 del_flag 都没有)。运营勾选一条已付款
 * 报名点删除,没有任何东西拦一下:钱不退、退款单都开不出(reviewRefund 查不到 registration 直接抛)、
 * 支付流水蒸发对账无据、结算明细里指向它的行成为悬空引用。
 *
 * ⚠️ 负控已实测:删掉 deleteCmsRegistrationByIds 里的 assertDeletable 调用 ⇒ paid* 系列全红。
 */
class RegistrationDeleteGateTest {

    private CmsRegistrationMapper mapper;
    private CmsRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(CmsRegistrationMapper.class);
        service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", mapper);
    }

    private void givenRegistration(Long id, Integer paymentStatus) {
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        r.setPaymentStatus(paymentStatus);
        when(mapper.selectCmsRegistrationById(id)).thenReturn(r);
    }

    /** 已支付 ⇒ 拒,且**一行都不能删**。 */
    @Test
    void paidRegistration_cannotBeDeleted() {
        givenRegistration(1L, 2);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.deleteCmsRegistrationByIds(new Long[]{1L}));
        assertTrue(e.getMessage().contains("退款"), "错误要能引导到正确出口,实际:" + e.getMessage());
        verify(mapper, never()).deleteCmsRegistrationByIds(any());
    }

    /** 已退款 ⇒ 拒。这行是对账凭证,删了等于毁证。 */
    @Test
    void refundedRegistration_cannotBeDeleted() {
        givenRegistration(1L, 4);

        assertThrows(IllegalStateException.class, () -> service.deleteCmsRegistrationByIds(new Long[]{1L}));
        verify(mapper, never()).deleteCmsRegistrationByIds(any());
    }

    /** 支付中 ⇒ 拒。这是最不能删的时刻 —— 删完回调回来就找不到单了。 */
    @Test
    void payingRegistration_cannotBeDeleted() {
        givenRegistration(1L, 1);

        assertThrows(IllegalStateException.class, () -> service.deleteCmsRegistrationByIds(new Long[]{1L}));
        verify(mapper, never()).deleteCmsRegistrationByIds(any());
    }

    /** 批量里只要有一条碰过钱 ⇒ 整批拒,不能删掉「干净的那几条」留下半截。 */
    @Test
    void batchWithOnePaid_rejectsWholeBatch() {
        givenRegistration(1L, 0);
        givenRegistration(2L, 2);      // 混进一条已支付
        givenRegistration(3L, 3);

        assertThrows(IllegalStateException.class,
                () -> service.deleteCmsRegistrationByIds(new Long[]{1L, 2L, 3L}));
        verify(mapper, never()).deleteCmsRegistrationByIds(any());
    }

    /** 待支付脏单 ⇒ 放行(这才是这个接口该干的事)。 */
    @Test
    void unpaidRegistration_canBeDeleted() {
        givenRegistration(1L, 0);

        service.deleteCmsRegistrationByIds(new Long[]{1L});

        verify(mapper).deleteCmsRegistrationByIds(any());
    }

    /** 支付失败脏单 ⇒ 放行。 */
    @Test
    void paymentFailedRegistration_canBeDeleted() {
        givenRegistration(1L, 3);

        service.deleteCmsRegistrationByIds(new Long[]{1L});

        verify(mapper).deleteCmsRegistrationByIds(any());
    }

    /** 单条删除入口同样有闸(别只堵批量那扇门)。 */
    @Test
    void singleDelete_alsoGated() {
        givenRegistration(1L, 2);

        assertThrows(IllegalStateException.class, () -> service.deleteCmsRegistrationById(1L));
        verify(mapper, never()).deleteCmsRegistrationById(anyLong());
    }

    /** 行已不存在 ⇒ 不拦(让下游返 0 行),别把「已经删干净了」变成报错。 */
    @Test
    void missingRegistration_doesNotBlock() {
        when(mapper.selectCmsRegistrationById(anyLong())).thenReturn(null);

        service.deleteCmsRegistrationByIds(new Long[]{1L});

        verify(mapper).deleteCmsRegistrationByIds(any());
    }
}
