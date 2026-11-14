package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A5.7:同一次漫游新发现地点数设上限,超出抛错(事务回滚);已发现过的重复请求不受影响。 */
class RoamPoiFoundSessionCapTest {
    private static final long MEMBER = 7L, SESSION = 70L, POI = 9L;
    private RoamMapper mapper;
    private RoamSessionWriteService service;

    @BeforeEach
    void setUp() {
        mapper = mock(RoamMapper.class);
        service = new RoamSessionWriteService();
        ReflectionTestUtils.setField(service, "roamMapper", mapper);
        ReflectionTestUtils.setField(service, "locationConsent", mock(RoamLocationConsentService.class));
        RoamSession session = new RoamSession();
        session.setMemberId(MEMBER);
        session.setStatus(0);
        when(mapper.selectOwnedSessionForUpdate(SESSION, MEMBER)).thenReturn(session);
    }

    @Test
    void allowsUpToTheCap() {
        when(mapper.insertPoiFoundIgnore(any(), any(), any(), any())).thenReturn(1);
        when(mapper.countPoisBySession(SESSION)).thenReturn(RoamSessionWriteService.MAX_POIS_PER_SESSION);
        assertEquals(1, service.recordPoiFound(MEMBER, SESSION, POI));
    }

    @Test
    void rejectsTheNewDiscoveryBeyondTheCap() {
        when(mapper.insertPoiFoundIgnore(any(), any(), any(), any())).thenReturn(1);
        when(mapper.countPoisBySession(SESSION)).thenReturn(RoamSessionWriteService.MAX_POIS_PER_SESSION + 1);
        assertThrows(ServiceException.class, () -> service.recordPoiFound(MEMBER, SESSION, POI));
    }

    @Test
    void repeatDiscoveryOfAlreadyFoundPoiIsNotCounted() {
        when(mapper.insertPoiFoundIgnore(any(), any(), any(), any())).thenReturn(0);
        when(mapper.countPoisBySession(SESSION)).thenReturn(RoamSessionWriteService.MAX_POIS_PER_SESSION + 5);
        assertEquals(0, service.recordPoiFound(MEMBER, SESSION, POI));
    }
}
