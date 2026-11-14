package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ViewMemberCart;
import com.chengyinhub.business.domain.vo.MemberCartVO;
import com.chengyinhub.business.service.IUmsMemberCartService;
import com.chengyinhub.business.service.IViewMemberCartService;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/cart/* 端点必须从 App 登录态 (LoginAppUser) 读取会员 ID 与用户名，
 * 不能调用后台管理端的 getLoginUser()（否则真实 App token 全返回 401）。
 */
class ApiCartControllerAppAuthTest {

    private static final Long MEMBER_ID = 9004L;
    private static final String USERNAME = "player_9004";

    private IViewMemberCartService viewMemberCartService;
    private IUmsMemberCartService umsMemberCartService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AppUser appUser = new AppUser();
        appUser.setUserId(MEMBER_ID);
        appUser.setUserName(USERNAME);
        LoginAppUser principal = new LoginAppUser(MEMBER_ID, appUser);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList()));

        viewMemberCartService = mock(IViewMemberCartService.class);
        umsMemberCartService = mock(IUmsMemberCartService.class);
        ApiCartController controller = new ApiCartController();
        ReflectionTestUtils.setField(controller, "viewMemberCartService", viewMemberCartService);
        ReflectionTestUtils.setField(controller, "umsMemberCartService", umsMemberCartService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allSixCartRoutesAuthenticateWithLoginAppUser() throws Exception {
        when(viewMemberCartService.selectViewMemberCartList(any(ViewMemberCart.class)))
                .thenReturn(Collections.emptyList());
        when(umsMemberCartService.addCart(MEMBER_ID, "1", "2", "1", "0")).thenReturn(11L);
        when(umsMemberCartService.updateCart(MEMBER_ID, "11", "2", "2")).thenReturn(1L);
        when(umsMemberCartService.previewSettlement(MEMBER_ID, "11")).thenReturn(new MemberCartVO());
        when(umsMemberCartService.settleCart(MEMBER_ID, USERNAME, "11", "note", "3")).thenReturn(99L);

        mockMvc.perform(post("/api/cart/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/cart/delete").param("cartids", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(umsMemberCartService).deleteCarts(MEMBER_ID, "11");

        mockMvc.perform(post("/api/cart/cart/add")
                        .param("product_id", "1")
                        .param("sku_id", "2")
                        .param("quantity", "1")
                        .param("is_buy", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/cart/update")
                        .param("cart_id", "11")
                        .param("quantity", "2")
                        .param("sku_id", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/cart/settlement").param("cartids", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/cart/order/settlement")
                        .param("cartids", "11")
                        .param("remark", "note")
                        .param("addressid", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(99));
    }
}
