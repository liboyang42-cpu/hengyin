package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.vo.NearbyMerchantVO;
import com.chengyinhub.business.mapper.MerchantLeadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 附近商家目录单测:canInvite/canCall 计算 + 电话按身份脱敏。
 */
class MerchantLeadServiceImplTest {

    private MerchantLeadMapper mapper;
    private MerchantLeadServiceImpl svc;

    @BeforeEach
    void setUp() {
        mapper = mock(MerchantLeadMapper.class);
        svc = new MerchantLeadServiceImpl();
        ReflectionTestUtils.setField(svc, "merchantLeadMapper", mapper);
    }

    private NearbyMerchantVO lead(Long memberId, String phone, Integer contactOpen, Integer coopOpen) {
        NearbyMerchantVO v = new NearbyMerchantVO();
        v.setMemberId(memberId);
        v.setPhone(phone);
        v.setContactOpen(contactOpen);
        v.setCoopOpen(coopOpen);
        return v;
    }

    private void stub(NearbyMerchantVO... rows) {
        when(mapper.selectNearbyLeads(anyDouble(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>(Arrays.asList(rows)));
    }

    @Test
    void registeredWithPhone_merchantView_canInviteAndCall() {
        stub(lead(9001L, "13800000000", 1, 1));
        NearbyMerchantVO v = svc.nearby(31.2, 121.4, 3000, 20, true).get(0);
        assertTrue(v.getCanInvite(), "已注册+开放协作→可邀请");
        assertTrue(v.getCanCall(), "有电话+开放联系+商家视角→可拨号");
        assertTrue("13800000000".equals(v.getPhone()));
        assertNull(v.getContactOpen(), "不下发原始开关");
    }

    @Test
    void unregisteredWithPhone_onlyCanCall() {
        stub(lead(null, "13900000000", 1, 1));
        NearbyMerchantVO v = svc.nearby(31.2, 121.4, 3000, 20, true).get(0);
        assertFalse(v.getCanInvite(), "未注册(memberId=null)→不能线上邀请");
        assertTrue(v.getCanCall(), "有电话→可拨号");
    }

    @Test
    void registeredNoPhone_canInviteButNotCall() {
        stub(lead(9002L, null, 1, 1));
        NearbyMerchantVO v = svc.nearby(31.2, 121.4, 3000, 20, true).get(0);
        assertTrue(v.getCanInvite());
        assertFalse(v.getCanCall(), "无电话→不能拨号");
    }

    @Test
    void playerView_phoneMaskedAndCannotCall() {
        stub(lead(null, "13700000000", 1, 1));
        NearbyMerchantVO v = svc.nearby(31.2, 121.4, 3000, 20, false).get(0);
        assertNull(v.getPhone(), "玩家端不下发电话");
        assertFalse(v.getCanCall(), "玩家端不能拨号");
    }

    @Test
    void contactClosed_cannotCall_coopClosed_cannotInvite() {
        stub(lead(9003L, "13600000000", 0, 0));
        NearbyMerchantVO v = svc.nearby(31.2, 121.4, 3000, 20, true).get(0);
        assertFalse(v.getCanCall(), "contactOpen=0→不能拨号");
        assertFalse(v.getCanInvite(), "coopOpen=0→不能邀请");
    }
}
