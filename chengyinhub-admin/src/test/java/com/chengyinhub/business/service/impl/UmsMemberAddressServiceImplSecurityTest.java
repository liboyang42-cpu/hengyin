package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMemberAddress;
import com.chengyinhub.business.mapper.UmsMemberAddressMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UmsMemberAddressServiceImplSecurityTest {

    private UmsMemberAddressMapper mapper;
    private UmsMemberAddressServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(UmsMemberAddressMapper.class);
        service = new UmsMemberAddressServiceImpl();
        ReflectionTestUtils.setField(service, "umsMemberAddressMapper", mapper);
    }

    @Test
    void invalidOwnedLookupFailsClosedBeforeSql() {
        assertNull(service.selectOwnedAddressById(0L, 1001L));
        verify(mapper, never()).selectOwnedAddressById(0L, 1001L);
    }

    @Test
    void zeroRowOwnedUpdateDoesNotClearAnotherDefault() {
        UmsMemberAddress address = address(10L, 1);
        when(mapper.updateOwnedAddress(address, 1001L)).thenReturn(0);

        assertEquals(0, service.updateOwnedAddress(address, 1001L));
        verify(mapper, never()).clearOtherOwnedDefaults(10L, 1001L);
    }

    @Test
    void zeroRowSetDefaultDoesNotClearCurrentDefault() {
        when(mapper.setOwnedAddressDefault(10L, 1001L)).thenReturn(0);

        assertEquals(0, service.setOwnedAddressDefault(10L, 1001L));
        verify(mapper, never()).clearOtherOwnedDefaults(10L, 1001L);
    }

    @Test
    void insertForcesAuthenticatedOwnerAndActiveFlag() {
        UmsMemberAddress address = address(null, 0);
        address.setMemberId(9999L);
        address.setDelFlag(2);
        when(mapper.insertUmsMemberAddress(address)).thenReturn(1);

        assertEquals(1, service.insertOwnedAddress(address, 1001L));
        assertEquals(1001L, address.getMemberId());
        assertEquals(0, address.getDelFlag());
        verify(mapper, never()).clearOtherOwnedDefaults(any(), any());
    }

    @Test
    void duplicateCreateRequestReplaysExistingAddressWithoutInsert() {
        UmsMemberAddress input = address(null, 0);
        UmsMemberAddress existing = address(88L, 0);
        when(mapper.selectOwnedAddressByRequestId(1001L, "address_1234567890")).thenReturn(existing);

        assertSame(existing, service.insertOwnedAddressIdempotent(input, 1001L, "address_1234567890"));
        verify(mapper, never()).insertUmsMemberAddress(any());
    }

    @Test
    void invalidCreateRequestIdFailsClosedBeforeInsert() {
        assertNull(service.insertOwnedAddressIdempotent(address(null, 0), 1001L, "short"));
        verify(mapper, never()).insertUmsMemberAddress(any());
    }

    @Test
    void legacyCreateWithoutRequestIdRemainsCompatible() {
        UmsMemberAddress input = address(null, 0);
        when(mapper.insertUmsMemberAddress(input)).thenReturn(1);

        assertSame(input, service.insertOwnedAddressIdempotent(input, 1001L, null));
        verify(mapper).insertUmsMemberAddress(input);
    }

    @Test
    void settingDefaultLocksMemberScopeBeforeMutation() {
        when(mapper.setOwnedAddressDefault(10L, 1001L)).thenReturn(1);
        assertEquals(1, service.setOwnedAddressDefault(10L, 1001L));
        verify(mapper).lockMemberAddressScope(1001L);
        verify(mapper).clearOtherOwnedDefaults(10L, 1001L);
    }

    private static UmsMemberAddress address(Long id, int isDefault) {
        UmsMemberAddress address = new UmsMemberAddress();
        address.setId(id);
        address.setFullName("Alice");
        address.setMobilePhone("13800138000");
        address.setIsDefault(isDefault);
        return address;
    }
}
