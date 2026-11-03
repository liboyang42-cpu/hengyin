package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 第七轮 C 第 5 项:订单/结算类订阅消息点开落到对应列表页(不带 id),而不是首页。
 * 走真实渲染与 post 缝,不发网络;页面路径与小程序 app.json 注册表交叉核对。
 */
class WxSubscribeMsgLandingPageTest {
    static class CapturingWx extends WxSubscribeMsgServiceImpl {
        String body;
        @Override String post(String url, String body) { this.body = body; return "{\"errcode\":0}"; }
    }

    private CapturingWx wx;

    @BeforeEach void setup() {
        wx = new CapturingWx();
        IWxContentSecurityService tokens = mock(IWxContentSecurityService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember(); member.setId(100L); member.setWxOpenId("openid-100"); member.setNickname("玩家");
        when(members.selectUmsMemberById(100L)).thenReturn(member);
        when(tokens.getAccessToken()).thenReturn("unit-access-token");
        ReflectionTestUtils.setField(wx, "wxSec", tokens);
        ReflectionTestUtils.setField(wx, "umsMemberService", members);
        ReflectionTestUtils.setField(wx, "defaultPage", "pages/index/index");
        ReflectionTestUtils.setField(wx, "tplRefund", "tpl-refund");
        ReflectionTestUtils.setField(wx, "tplSignupSuccess", "tpl-signup");
        ReflectionTestUtils.setField(wx, "tplBeforeStart", "tpl-before");
        ReflectionTestUtils.setField(wx, "tplCoopSettle", "tpl-settle");
        ReflectionTestUtils.setField(wx, "tplCoopInvited", "tpl-invited");
    }

    private String page() { return JSON.parseObject(wx.body).getString("page"); }

    @Test void refundAndSignupOpenMyOrders() {
        assertTrue(wx.notifyRefund(100L, "报名退款", "12.00"));
        assertEquals("subpackageMember/order/order", page());
        assertTrue(wx.notifySignupSuccess(100L, "老城夜行"));
        assertEquals("subpackageMember/order/order", page());
    }

    @Test void beforeStartOpensMyOrders() {
        assertTrue(wx.notifyBeforeStart(100L, "老城夜行", "2026-11-05 14:00"));
        assertEquals("subpackageMember/order/order", page());
    }

    @Test void settlementOpensMerchantLedger() {
        assertTrue(wx.notifyCoopSettle(100L, "30.00", "2026-11-03 10:00", "合作分润", "NO1"));
        assertEquals("pages/merchant/ledger/index", page());
    }

    @Test void otherNoticesKeepConfiguredDefault() {
        assertTrue(wx.notifyCoopInvited(100L, "主题", "某商家"));
        assertEquals("pages/index/index", page());
    }

    @Test void landingPagesCarryNoIdAndAreRegisteredInMiniProgram() throws Exception {
        for (String p : new String[] { WxSubscribeMsgServiceImpl.ORDER_PAGE, WxSubscribeMsgServiceImpl.MERCHANT_LEDGER_PAGE }) {
            assertFalse(p.contains("?"), "落地页不带参数: " + p);
            assertFalse(p.startsWith("/"), "订阅消息 page 不以 / 开头: " + p);
        }
        Path app = Paths.get("../chengyinhub-xcx/app.json");
        if (!Files.exists(app)) app = Paths.get("chengyinhub-xcx/app.json");
        JSONObject json = JSON.parseObject(Files.readString(app));
        Set<String> routes = new HashSet<>();
        json.getJSONArray("pages").forEach(p -> routes.add(String.valueOf(p)));
        JSONArray subs = json.getJSONArray("subPackages");
        for (int i = 0; i < subs.size(); i++) {
            JSONObject pkg = subs.getJSONObject(i);
            String root = pkg.getString("root").replaceAll("/$", "");
            pkg.getJSONArray("pages").forEach(p -> routes.add(root + "/" + p));
        }
        assertTrue(routes.contains(WxSubscribeMsgServiceImpl.ORDER_PAGE));
        assertTrue(routes.contains(WxSubscribeMsgServiceImpl.MERCHANT_LEDGER_PAGE));
    }
}
