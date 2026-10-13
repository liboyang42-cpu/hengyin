package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.service.ISmsCouponService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A3-4(P0):优惠券运行时统计计数器封口。
 *
 * <p>发行/领取/使用量由领取与核销的原子自增(tryIncrementReceiveCount / incrementUseCount)维护,
 * 是真实统计。钉住:edit 把这三个计数器剥成 null(mapper 动态 <if> 跳过=保库中真值),
 * 后台无法经通用 PUT 伪造发放/使用数据;券的配置字段照常提交。
 */
class SmsCouponControllerSafetyTest {

    @Test
    void editStripsRuntimeStatCounters() {
        ISmsCouponService service = mock(ISmsCouponService.class);
        SmsCouponController controller = new SmsCouponController();
        ReflectionTestUtils.setField(controller, "smsCouponService", service);

        SmsCoupon submitted = new SmsCoupon();
        submitted.setId(5L);
        submitted.setPublishCount(999L);
        submitted.setReceiveCount(999L);
        submitted.setUseCount(999L);

        controller.edit(submitted);

        verify(service).updateSmsCoupon(argThat(c ->
                c.getPublishCount() == null
                        && c.getReceiveCount() == null
                        && c.getUseCount() == null
                        && Long.valueOf(5L).equals(c.getId())));
    }
}
