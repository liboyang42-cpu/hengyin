package com.chengyinhub.business.task;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PII 历史回填单测:未配密钥→跳过;启用→按批读legacy id、逐条读明文再密文回填。
 */
class PiiBackfillTaskTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private PiiBackfillTask newTask(CmsRegistrationMapper mapper, OmsOrderMapper orderMapper, PiiCryptoService crypto) {
        PiiBackfillTask t = new PiiBackfillTask();
        ReflectionTestUtils.setField(t, "cmsRegistrationMapper", mapper);
        ReflectionTestUtils.setField(t, "omsOrderMapper", orderMapper);
        ReflectionTestUtils.setField(t, "piiCryptoService", crypto);
        ReflectionTestUtils.setField(t, "batchSize", 500);
        ReflectionTestUtils.setField(t, "maxBatches", 200);
        return t;
    }

    @Test
    void disabled_noKey_skips() {
        CmsRegistrationMapper mapper = mock(CmsRegistrationMapper.class);
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        PiiBackfillTask t = newTask(mapper, orderMapper, new PiiCryptoService("", ""));

        t.run();

        verify(mapper, never()).selectLegacyPiiIds(anyInt());
        verify(orderMapper, never()).selectLegacyReceiverPiiIds(anyInt());
    }

    @Test
    void enabled_backfillsEachLegacyRow() {
        CmsRegistrationMapper mapper = mock(CmsRegistrationMapper.class);
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        // 报名一批 2 条(< batchSize 500 → 跑完即停)
        when(mapper.selectLegacyPiiIds(500)).thenReturn(Arrays.asList(1L, 2L)).thenReturn(Collections.emptyList());
        CmsRegistration r = new CmsRegistration();
        r.setRealName("张三");
        r.setPhone("13812341234");
        when(mapper.selectCmsRegistrationById(any())).thenReturn(r);
        // 订单无 legacy
        when(orderMapper.selectLegacyReceiverPiiIds(500)).thenReturn(Collections.emptyList());

        PiiBackfillTask t = newTask(mapper, orderMapper, new PiiCryptoService(KEY, "h"));
        t.run();

        verify(mapper, times(1)).selectLegacyPiiIds(500);
        verify(mapper).backfillRegistrationPii(eq(1L), any(), any(), any(), any());
        verify(mapper).backfillRegistrationPii(eq(2L), any(), any(), any(), any());
        verify(orderMapper, times(1)).selectLegacyReceiverPiiIds(500);
    }
}
