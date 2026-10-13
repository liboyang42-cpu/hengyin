package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.MemberInvitationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemberInvitationServiceImplTest {

    private MemberInvitationMapper mapper;
    private MemberInvitationServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(MemberInvitationMapper.class);
        service = new MemberInvitationServiceImpl();
        ReflectionTestUtils.setField(service, "memberInvitationMapper", mapper);
    }

    @Test
    void rejectsSelfInvitationBeforeSql() {
        assertFalse(service.bindInviter(1001L, "1001"));
        verify(mapper, never()).bindInviterIfAbsent(1001L, 1001L);
    }

    @Test
    void rejectsMalformedInvitationBeforeSql() {
        assertFalse(service.bindInviter(1001L, "not-a-number"));
        verify(mapper, never()).bindInviterIfAbsent(1001L, 0L);
    }

    @Test
    void zeroAffectedRowsIsFailure() {
        when(mapper.bindInviterIfAbsent(1001L, 2002L)).thenReturn(0);

        assertFalse(service.bindInviter(1001L, "2002"));
    }

    @Test
    void exactlyOneAffectedRowIsSuccess() {
        when(mapper.bindInviterIfAbsent(1001L, 2002L)).thenReturn(1);

        assertTrue(service.bindInviter(1001L, "2002"));
    }
}
