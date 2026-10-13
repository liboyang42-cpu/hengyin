package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantAccessServiceImpl;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MerchantOperator;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IMerchantAggregateReadService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class ApiMerchantAggregateControllerTest {

    @Test
    void grantedOperatorsReadLegacyAggregatesWithResolvedOwnerScope() throws Exception {
        IMerchantAggregateReadService aggregate = mock(IMerchantAggregateReadService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        MerchantAccessContext manager = new MerchantAccessContext(
                9002L, 11L, 9001L, MerchantRoleCode.MERCHANT_MANAGER);
        when(access.require(9002L, MerchantPermission.COOP_MANAGE)).thenReturn(manager);
        when(access.require(9002L, MerchantPermission.MARKETING_READ)).thenReturn(manager);
        when(aggregate.relationHome(9001L, null, null, null, 10))
                .thenReturn(Collections.<String, Object>singletonMap("ok", true));
        when(aggregate.marketingHome(9001L, 11L, 30))
                .thenReturn(Collections.<String, Object>singletonMap("ok", true));

        ApiMerchantController controller = spy(new ApiMerchantController());
        doReturn(9002L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "merchantAggregateReadService", aggregate);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/api/merchant/relation-home")
                        .contentType("application/json").content("{}"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/api/merchant/marketing-home"))
                .andExpect(jsonPath("$.code").value(200));
        verify(aggregate).relationHome(9001L, null, null, null, 10);
        verify(aggregate).marketingHome(9001L, 11L, 30);
    }

    @Test
    void operatorWhoseOwnerHasTwoActiveStoresIsRejectedBeforeLegacyAggregateRead() throws Exception {
        IMerchantAggregateReadService aggregate = mock(IMerchantAggregateReadService.class);
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MerchantOperatorMapper operators = mock(MerchantOperatorMapper.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        MerchantOperator operator = new MerchantOperator();
        operator.setId(51L);
        operator.setMerchantId(11L);
        operator.setOperatorMemberId(9002L);
        operator.setStatus(MerchantOperator.STATUS_ACTIVE);
        operator.setRoleCode(MerchantRoleCode.MERCHANT_MANAGER);
        MmsMerchant first = activeMerchant(11L, 9001L);
        MmsMerchant second = activeMerchant(12L, 9001L);
        when(operators.selectActiveByMemberId(9002L)).thenReturn(operator);
        when(merchants.selectMmsMerchantById(11L)).thenReturn(first);
        when(merchants.selectMmsMerchantList(any(MmsMerchant.class))).thenAnswer(invocation -> {
            MmsMerchant query = invocation.getArgument(0, MmsMerchant.class);
            return Long.valueOf(9001L).equals(query.getMemberId())
                    ? Arrays.asList(first, second) : Collections.emptyList();
        });
        UmsMember member = new UmsMember();
        member.setId(9002L);
        member.setStatus(1L);
        member.setDelFlag(0);
        when(members.selectUmsMemberById(9002L)).thenReturn(member);
        MerchantAccessServiceImpl realAccess = new MerchantAccessServiceImpl();
        ReflectionTestUtils.setField(realAccess, "merchantService", merchants);
        ReflectionTestUtils.setField(realAccess, "merchantOperatorMapper", operators);
        ReflectionTestUtils.setField(realAccess, "umsMemberMapper", members);

        ApiMerchantController controller = spy(new ApiMerchantController());
        doReturn(9002L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "merchantAggregateReadService", aggregate);
        ReflectionTestUtils.setField(controller, "merchantAccessService", realAccess);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/merchant/relation-home")
                        .contentType("application/json").content("{}"))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/merchant/marketing-home"))
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(aggregate);
    }

    @Test
    void ownerAggregateEndpointsPassOwnerScope() throws Exception {
        IMerchantAggregateReadService aggregate = mock(IMerchantAggregateReadService.class);
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setMemberId(10L);
        merchant.setId(11L);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        when(merchants.selectMmsMerchantByMemberId(10L)).thenReturn(merchant);
        when(aggregate.relationHome(eq(10L), eq(88L), eq(31.2D), eq(121.5D), eq(7)))
                .thenReturn(Collections.<String, Object>singletonMap("ok", true));
        when(aggregate.marketingHome(10L, 11L, 7))
                .thenReturn(Collections.<String, Object>singletonMap("ok", true));
        when(aggregate.funnel(10L, 7))
                .thenReturn(Collections.<String, Object>singletonMap("ok", true));
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        MerchantAccessContext owner = new MerchantAccessContext(
                10L, 11L, 10L, MerchantRoleCode.MERCHANT_OWNER);
        when(access.require(10L, MerchantPermission.COOP_MANAGE)).thenReturn(owner);
        when(access.require(10L, MerchantPermission.MARKETING_READ)).thenReturn(owner);

        ApiMerchantController controller = spy(new ApiMerchantController());
        doReturn(10L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchants);
        ReflectionTestUtils.setField(controller, "merchantAggregateReadService", aggregate);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/merchant/relation-home")
                        .contentType("application/json")
                        .content("{\"topicId\":88,\"originLat\":31.2,\"originLng\":121.5,\"limit\":7}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ok").value(true));
        mvc.perform(post("/api/merchant/marketing-home")
                        .param("windowDays", "7"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ok").value(true));
        mvc.perform(post("/api/merchant/funnel")
                        .param("windowDays", "7"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ok").value(true));
        verify(aggregate).relationHome(10L, 88L, 31.2D, 121.5D, 7);
        verify(aggregate).marketingHome(10L, 11L, 7);
        verify(aggregate).funnel(10L, 7);
    }

    @Test
    void marketingWindowOnlyAllowsSevenOrThirtyDaysAndDefaultsToThirty() throws Exception {
        IMerchantAggregateReadService aggregate = mock(IMerchantAggregateReadService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        MerchantAccessContext owner = new MerchantAccessContext(
                10L, 11L, 10L, MerchantRoleCode.MERCHANT_OWNER);
        when(access.require(10L, MerchantPermission.MARKETING_READ)).thenReturn(owner);
        when(aggregate.marketingHome(10L, 11L, 30))
                .thenReturn(Collections.<String, Object>singletonMap("ok", true));

        ApiMerchantController controller = spy(new ApiMerchantController());
        doReturn(10L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "merchantAggregateReadService", aggregate);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/merchant/marketing-home"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/api/merchant/marketing-home")
                        .param("windowDays", "14"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("windowDays仅支持7或30"));
        verify(aggregate).marketingHome(10L, 11L, 30);
    }

    @Test
    void relationHomeRejectsNonMerchantBeforeReadingAggregate() throws Exception {
        IMerchantAggregateReadService aggregate = mock(IMerchantAggregateReadService.class);
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        when(merchants.selectMmsMerchantByMemberId(10L)).thenReturn(null);

        ApiMerchantController controller = spy(new ApiMerchantController());
        doReturn(10L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchants);
        ReflectionTestUtils.setField(controller, "merchantAggregateReadService", aggregate);
        ReflectionTestUtils.setField(controller, "merchantAccessService", realAccess(merchants));

        MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(post("/api/merchant/relation-home").contentType("application/json").content("{}"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("商家身份无效或权限不足"));
        verifyNoInteractions(aggregate);
    }

    @Test
    void pendingMerchantCannotReadEitherAggregate() throws Exception {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setStatus(0L);
        merchant.setDelFlag(0);
        assertInactiveMerchantRejected(merchant);
    }

    @Test
    void softDeletedMerchantCannotReadEitherAggregate() throws Exception {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setStatus(1L);
        merchant.setDelFlag(1);
        assertInactiveMerchantRejected(merchant);
    }

    @Test
    void relationHomeRejectsMalformedCoordinates() throws Exception {
        IMerchantAggregateReadService aggregate = mock(IMerchantAggregateReadService.class);
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        when(merchants.selectMmsMerchantByMemberId(10L)).thenReturn(merchant);

        ApiMerchantController controller = spy(new ApiMerchantController());
        doReturn(10L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchants);
        ReflectionTestUtils.setField(controller, "merchantAggregateReadService", aggregate);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(10L, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(10L, 11L, 10L,
                        MerchantRoleCode.MERCHANT_OWNER));
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/merchant/relation-home").contentType("application/json")
                        .content("{\"originLat\":\"bad\",\"originLng\":\"bad\"}"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("定位参数错误"));
        mvc.perform(post("/api/merchant/relation-home").contentType("application/json")
                        .content("{\"originLat\":\"NaN\",\"originLng\":121.5}"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("定位参数错误"));
        verifyNoInteractions(aggregate);
    }

    private void assertInactiveMerchantRejected(MmsMerchant merchant) throws Exception {
        IMerchantAggregateReadService aggregate = mock(IMerchantAggregateReadService.class);
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        when(merchants.selectMmsMerchantByMemberId(10L)).thenReturn(merchant);

        ApiMerchantController controller = spy(new ApiMerchantController());
        doReturn(10L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchants);
        ReflectionTestUtils.setField(controller, "merchantAggregateReadService", aggregate);
        ReflectionTestUtils.setField(controller, "merchantAccessService", realAccess(merchants));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/api/merchant/relation-home").contentType("application/json").content("{}"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("商家身份无效或权限不足"));
        mvc.perform(post("/api/merchant/marketing-home"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("商家身份无效或权限不足"));
        verifyNoInteractions(aggregate);
    }

    private IMerchantAccessService realAccess(IMmsMerchantService merchants) {
        MerchantAccessServiceImpl access = new MerchantAccessServiceImpl();
        ReflectionTestUtils.setField(access, "merchantService", merchants);
        MerchantOperatorMapper operators = mock(MerchantOperatorMapper.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        UmsMember activeMember = new UmsMember();
        activeMember.setId(10L);
        activeMember.setStatus(1L);
        activeMember.setDelFlag(0);
        when(members.selectUmsMemberById(10L)).thenReturn(activeMember);
        ReflectionTestUtils.setField(access, "merchantOperatorMapper", operators);
        ReflectionTestUtils.setField(access, "umsMemberMapper", members);
        return access;
    }

    private static MmsMerchant activeMerchant(Long id, Long ownerMemberId) {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(id);
        merchant.setMemberId(ownerMemberId);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        return merchant;
    }
}
