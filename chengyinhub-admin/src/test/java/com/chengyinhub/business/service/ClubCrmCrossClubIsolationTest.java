package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.vo.ClubCustomerVO;
import com.chengyinhub.business.mapper.ClubCrmMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.impl.ClubCrmAppServiceImpl;
import com.chengyinhub.business.service.impl.ClubMemberServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SC-CL-CUSTOMER-DETAIL：另一家俱乐部的主理人拿着本俱乐部的 clubId + 客户 ID 读详情、改备注，
 * 必须在任何 CRM 查询之前被真实的 canGovernClub 拒掉，回包里不带任何客户数据；
 * 换成自己的 clubId 来读，也只能按自己俱乐部的成交范围查，查不到就拒。
 */
class ClubCrmCrossClubIsolationTest {
    private static final Long CLUB_A = 42L;
    private static final Long OWNER_A = 7L;
    private static final Long CLUB_B = 43L;
    private static final Long OWNER_B = 8L;
    private static final Long CUSTOMER = 41L;
    private static final String PLAIN_PHONE = "13812345621";

    private ClubCrmAppServiceImpl service;
    private ClubCrmMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = mock(ClubCrmMapper.class);
        IClubService clubService = mock(IClubService.class);
        IClubAccessService access = mock(IClubAccessService.class);
        ClubMemberMapper clubMembers = mock(ClubMemberMapper.class);
        ClubMemberServiceImpl memberService = new ClubMemberServiceImpl();
        ReflectionTestUtils.setField(memberService, "clubMemberMapper", clubMembers);
        ReflectionTestUtils.setField(memberService, "clubAccessService", access);

        service = new ClubCrmAppServiceImpl();
        ReflectionTestUtils.setField(service, "clubCrmMapper", mapper);
        ReflectionTestUtils.setField(service, "piiCryptoService",
                new com.chengyinhub.business.security.PiiCryptoService("", ""));
        ReflectionTestUtils.setField(service, "clubService", clubService);
        ReflectionTestUtils.setField(service, "clubMemberService", memberService);
        ReflectionTestUtils.setField(service, "clubAccessService", access);

        when(clubService.selectClubById(CLUB_A)).thenReturn(club(CLUB_A, OWNER_A));
        when(clubService.selectClubById(CLUB_B)).thenReturn(club(CLUB_B, OWNER_B));
        // 真实 ClubAccessServiceImpl 对非本团成员返回 null（见 ClubBanLeftoverRoleAccessTest）
        when(access.resolve(eq(OWNER_B), eq(CLUB_A), any())).thenReturn(null);
        when(access.resolve(eq(OWNER_A), eq(CLUB_A), any())).thenReturn(
                new ClubAccessContext(OWNER_A, CLUB_A, OWNER_A, null, Collections.singleton(ClubRoleCode.CLUB_OWNER)));
        when(access.resolve(eq(OWNER_B), eq(CLUB_B), any())).thenReturn(
                new ClubAccessContext(OWNER_B, CLUB_B, OWNER_B, null, Collections.singleton(ClubRoleCode.CLUB_OWNER)));
        when(mapper.selectClubCustomerSummary(CLUB_A, CUSTOMER)).thenReturn(rawCustomer());
        when(mapper.selectClubCustomerSummary(CLUB_B, CUSTOMER)).thenReturn(null);
        when(mapper.selectClubCustomerRecords(eq(CLUB_A), eq(CUSTOMER), anyInt()))
                .thenReturn(Collections.<ClubCustomerVO.Record>emptyList());
    }

    @Test
    void ownerOfClubASeesOnlyClubAScopedMaskedDetail() {
        ClubCustomerVO.Detail detail = service.customerDetail(OWNER_A, CLUB_A, CUSTOMER);

        assertEquals(CUSTOMER, detail.getSummary().getMemberId());
        assertFalse(detail.getSummary().getPhoneText().contains(PLAIN_PHONE));
        verify(mapper).selectClubCustomerSummary(CLUB_A, CUSTOMER);
        verify(mapper).selectClubCustomerRecords(eq(CLUB_A), eq(CUSTOMER), anyInt());
        verify(mapper).selectProfile(CLUB_A, CUSTOMER);
    }

    @Test
    void organizerOfAnotherClubIsRefusedBeforeAnyCustomerQueryOrNoteWrite() {
        ServiceException read = assertThrows(ServiceException.class,
                () -> service.customerDetail(OWNER_B, CLUB_A, CUSTOMER));
        ServiceException write = assertThrows(ServiceException.class,
                () -> service.saveTagRemark(OWNER_B, CLUB_A, CUSTOMER,
                        Arrays.asList("高复购"), "偷看备注", "req-cross-1"));

        for (ServiceException denied : new ServiceException[] {read, write}) {
            assertFalse(denied.getMessage().contains(PLAIN_PHONE));
            assertFalse(denied.getMessage().contains("486"));
        }
        verify(mapper, never()).selectClubCustomerSummary(anyLong(), anyLong());
        verify(mapper, never()).selectClubCustomerRecords(anyLong(), anyLong(), anyInt());
        verify(mapper, never()).selectProfile(anyLong(), anyLong());
        verify(mapper, never()).upsertProfile(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void organizerOfAnotherClubUsingTheirOwnClubIdCannotReachClubACustomer() {
        assertThrows(ServiceException.class, () -> service.customerDetail(OWNER_B, CLUB_B, CUSTOMER));
        assertThrows(ServiceException.class, () -> service.saveTagRemark(OWNER_B, CLUB_B, CUSTOMER,
                Arrays.asList("高复购"), "跨团建档", "req-cross-2"));

        verify(mapper, never()).selectClubCustomerSummary(eq(CLUB_A), anyLong());
        verify(mapper, never()).selectClubCustomerRecords(anyLong(), anyLong(), anyInt());
        verify(mapper, never()).upsertProfile(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    private static Club club(Long id, Long owner) {
        Club club = new Club();
        club.setId(id);
        club.setMemberId(owner);
        club.setStatus(1L);
        club.setDelFlag(0);
        return club;
    }

    private static ClubCustomerVO.RawCustomer rawCustomer() {
        ClubCustomerVO.RawCustomer raw = new ClubCustomerVO.RawCustomer();
        raw.setMemberId(CUSTOMER);
        raw.setDisplayName("林小满");
        raw.setAvatar("");
        raw.setPhone(PLAIN_PHONE);
        raw.setVerifiedCount(6);
        raw.setPendingCount(0);
        raw.setRefundedCount(1);
        raw.setPaidAmount(new BigDecimal("486.00"));
        raw.setLastInteractionTime(new Date());
        return raw;
    }
}
