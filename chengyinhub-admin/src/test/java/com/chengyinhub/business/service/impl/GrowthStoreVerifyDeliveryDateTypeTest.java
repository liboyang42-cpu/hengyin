package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.GrowthMission;
import com.chengyinhub.business.domain.GrowthMissionProgress;
import com.chengyinhub.business.mapper.GrowthMissionMapper;
import com.chengyinhub.business.mapper.GrowthMissionProgressMapper;
import com.chengyinhub.business.mapper.MemberGrowthMapper;
import com.chengyinhub.business.mapper.StoreVerifyGrowthDeliveryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 到店核销成长待办:event.createTime 来自 resultType=map 查询,MySQL Connector/J 8 回 LocalDateTime。
 * 旧代码 (Date) 强转在真库下抛 ClassCastException,待办永远发不出去。
 */
class GrowthStoreVerifyDeliveryDateTypeTest {
    private static final long REGISTRATION = 88L;
    private static final long MEMBER = 9L;

    private GrowthServiceImpl service;
    private StoreVerifyGrowthDeliveryMapper deliveryMapper;
    private GrowthMissionProgressMapper progressMapper;

    @BeforeEach
    void setUp() {
        service = new GrowthServiceImpl();
        deliveryMapper = mock(StoreVerifyGrowthDeliveryMapper.class);
        progressMapper = mock(GrowthMissionProgressMapper.class);
        ReflectionTestUtils.setField(service, "storeVerifyGrowthDeliveryMapper", deliveryMapper);
        ReflectionTestUtils.setField(service, "growthMissionProgressMapper", progressMapper);
        ReflectionTestUtils.setField(service, "growthMissionMapper", mock(GrowthMissionMapper.class));
        ReflectionTestUtils.setField(service, "memberGrowthMapper", mock(MemberGrowthMapper.class));
        when(progressMapper.hasRegistrationAward(anyLong(), anyString(), anyLong())).thenReturn(false);
        when(progressMapper.insertMissionProgress(any(GrowthMissionProgress.class))).thenReturn(1);
        when(deliveryMapper.markProcessed(REGISTRATION)).thenReturn(1);
    }

    private Map<String, Object> event(Object createTime) {
        GrowthMission mission = new GrowthMission();
        mission.setMissionCode("store_verify_first");
        mission.setStatus("0");
        mission.setExpReward(0);
        Map<String, Object> event = new HashMap<>();
        event.put("memberId", MEMBER);
        event.put("missionsJson", JSON.toJSONString(Collections.singletonList(mission)));
        event.put("createTime", createTime);
        return event;
    }

    private Date deliveredProgressDate() {
        ArgumentCaptor<GrowthMissionProgress> captor = ArgumentCaptor.forClass(GrowthMissionProgress.class);
        verify(progressMapper).insertMissionProgress(captor.capture());
        return captor.getValue().getProgressDate();
    }

    @Test
    void deliversWithDateCreateTime() {
        Date verifiedAt = new Date(1_700_000_000_000L);
        when(deliveryMapper.selectForUpdate(REGISTRATION)).thenReturn(event(verifiedAt));

        assertEquals(1, service.deliverStoreVerification(REGISTRATION));
        assertEquals(verifiedAt, deliveredProgressDate());
    }

    @Test
    void deliversWithLocalDateTimeCreateTimeFromMysqlDriver() {
        LocalDateTime verifiedAt = LocalDateTime.of(2026, 11, 19, 10, 30, 0);
        when(deliveryMapper.selectForUpdate(REGISTRATION)).thenReturn(event(verifiedAt));

        assertEquals(1, service.deliverStoreVerification(REGISTRATION));
        assertEquals(Date.from(verifiedAt.atZone(ZoneId.systemDefault()).toInstant()), deliveredProgressDate());
    }
}
