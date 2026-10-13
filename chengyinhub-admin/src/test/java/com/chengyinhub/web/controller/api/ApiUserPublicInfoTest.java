package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.vo.PublicMemberVO;
import com.chengyinhub.business.service.IPublicMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POST /api/user/public-info —— 匿名可读的会员公开资料;/api/user/info 继续要求登录。
 */
class ApiUserPublicInfoTest {

    private IPublicMemberService publicMemberService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private ApiUmsMemberController controller() {
        publicMemberService = mock(IPublicMemberService.class);
        ApiUmsMemberController controller = new ApiUmsMemberController();
        ReflectionTestUtils.setField(controller, "publicMemberService", publicMemberService);
        return controller;
    }

    private void loginAs(long memberId) {
        AppUser appUser = new AppUser();
        appUser.setUserId(memberId);
        LoginAppUser loginAppUser = new LoginAppUser(memberId, appUser);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginAppUser, null, Collections.emptyList()));
    }

    private PublicMemberVO member() {
        PublicMemberVO vo = new PublicMemberVO();
        vo.setId(7L);
        vo.setNickname("阿岚");
        vo.setAvatar("avatar.png");
        vo.setIntroduction("城市探索者");
        return vo;
    }

    @Test
    void anonymousReadsWhitelistWithoutViewerRelationLookup() {
        ApiUmsMemberController controller = controller();
        PublicMemberVO vo = member();
        when(publicMemberService.selectPublicMemberById(7L)).thenReturn(vo);

        AjaxResult result = controller.getPublicUserInfo("7");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertSame(vo, result.get(AjaxResult.DATA_TAG));
        // 匿名不查观看者关系:带 viewer 的重载一次都不能被调到
        verify(publicMemberService).selectPublicMemberById(7L);
        verify(publicMemberService, never()).selectPublicMemberById(anyLong(), anyLong());
    }

    @Test
    void anonymousResponseSerializesOnlyPublicMemberWhitelist() throws Exception {
        ApiUmsMemberController controller = controller();
        when(publicMemberService.selectPublicMemberById(7L)).thenReturn(member());

        String json = new ObjectMapper().writeValueAsString(controller.getPublicUserInfo("7"));

        for (String forbidden : new String[]{"phone", "password", "wxOpenId", "openId",
                "status", "delFlag", "userType", "merchant", "tagIds"}) {
            assertFalse(json.contains("\"" + forbidden + "\""), "公开响应泄露字段 " + forbidden + ": " + json);
        }
        // isFollow 的键是 /api/user/{list,invite_list,info} 已冻结键集的一部分,不抽键;
        // 但匿名响应里它必须是 null —— 一旦带上 0/1 就等于泄露了观看者关系。
        assertTrue(json.contains("\"isFollow\":null"), "匿名响应不得带观看者关系: " + json);
        // 2026-08-29 身份标签走**窄投影**：服务端只下发封顶的 identityKind(BIZ/CLUB/null)，
        // 账号级 user_type 仍在上面的禁止列表里一个字没动。
        // 这条正向断言防的是「将来有人把 identityKind 从 VO 删掉，搜索页标签静默消失而无人变红」。
        assertTrue(json.contains("\"identityKind\""), "公开响应应当带 identityKind(搜索页身份标签靠它): " + json);
        assertTrue(json.contains("\"nickname\""));
        assertTrue(json.contains("\"avatar\""));
    }

    @Test
    void loggedInViewerStillGetsRelationAwareProjection() {
        ApiUmsMemberController controller = controller();
        loginAs(42L);
        PublicMemberVO vo = member();
        vo.setIsFollow(1);
        when(publicMemberService.selectPublicMemberById(7L, 42L)).thenReturn(vo);

        AjaxResult result = controller.getPublicUserInfo("7");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(publicMemberService).selectPublicMemberById(7L, 42L);
        verify(publicMemberService, never()).selectPublicMemberById(7L);
    }

    @Test
    void unavailableMemberIsBusinessErrorNotBlankSuccess() {
        ApiUmsMemberController controller = controller();
        // mapper 已强制 status=1 && del_flag=0,禁用/删除会员在这里就是查不到
        when(publicMemberService.selectPublicMemberById(7L)).thenReturn(null);

        assertNotEquals(200, controller.getPublicUserInfo("7").get(AjaxResult.CODE_TAG));
        assertNotEquals(200, controller.getPublicUserInfo("").get(AjaxResult.CODE_TAG));
        assertNotEquals(200, controller.getPublicUserInfo(null).get(AjaxResult.CODE_TAG));
        assertNotEquals(200, controller.getPublicUserInfo("0").get(AjaxResult.CODE_TAG));
        assertNotEquals(200, controller.getPublicUserInfo("-1").get(AjaxResult.CODE_TAG));
    }

    @Test
    void legacyUserInfoStillRequiresLogin() {
        ApiUmsMemberController controller = controller();

        AjaxResult result = controller.getUserInfo("7");

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("请先登录", result.get(AjaxResult.MSG_TAG));
        verify(publicMemberService, never()).selectPublicMemberById(anyLong());
        verify(publicMemberService, never()).selectPublicMemberById(anyLong(), anyLong());
    }
}
