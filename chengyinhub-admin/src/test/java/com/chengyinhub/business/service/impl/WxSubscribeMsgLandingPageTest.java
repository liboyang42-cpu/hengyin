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

    /** 第九轮 m2 补充第 7 项:合作、招募、广播不再落首页,按角色落到能处理这件事的页 */
    @Test void coopRecruitAndBroadcastOpenTheRightPagePerRole() {
        ReflectionTestUtils.setField(wx, "tplClubInterest", "tpl-interest");
        ReflectionTestUtils.setField(wx, "tplCoopStatus", "tpl-status");
        ReflectionTestUtils.setField(wx, "tplRecruit", "tpl-recruit");
        ReflectionTestUtils.setField(wx, "tplOfficialEvent", "tpl-official");
        assertTrue(wx.notifyCoopInvited(100L, "主题", "某商家"));
        assertEquals("pages/coop/list/index", page(), "被邀请方看「收到的」");
        assertTrue(wx.notifyClubInterested(100L, "主题", "某俱乐部"));
        assertEquals("pages/coop/list/index", page());
        assertTrue(wx.notifyCoopStatus(100L, "主题", "对方", "已接受", "等待开售"));
        assertEquals("pages/coop/list/index?tab=sent", page(), "邀请方看「我发出的」");
        assertTrue(wx.notifyRecruit(100L, "主题", "2026-11-05 14:00", "招募已满"));
        assertEquals("subpackageA/pages/myproject/index", page(), "发布者看我的项目");
        assertTrue(wx.notifyOfficialBroadcast(100L, "标题", "说明", WxSubscribeMsgServiceImpl.officialBroadcastPage("player", 7L)));
        assertEquals("pages/activity/official-detail/index?id=7", page());
        assertTrue(wx.notifyOfficialBroadcast(100L, "标题", "说明", WxSubscribeMsgServiceImpl.officialBroadcastPage("merchant", null)));
        assertEquals("pages/merchant/index/index", page());
        assertTrue(wx.notifyOfficialBroadcast(100L, "标题", "说明", WxSubscribeMsgServiceImpl.officialBroadcastPage("club", null)));
        assertEquals("pages/club/workbench/index", page());
        assertTrue(wx.notifyOfficialBroadcast(100L, "标题", "说明", WxSubscribeMsgServiceImpl.officialBroadcastPage("player", null)));
        assertEquals("pages/index/index", page(), "玩家纯公告仍落首页");
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
        for (String p : new String[] { WxSubscribeMsgServiceImpl.COOP_RECEIVED_PAGE, WxSubscribeMsgServiceImpl.COOP_SENT_PAGE,
                WxSubscribeMsgServiceImpl.MY_PROJECT_PAGE, WxSubscribeMsgServiceImpl.OFFICIAL_EVENT_PAGE,
                WxSubscribeMsgServiceImpl.MERCHANT_HOME_PAGE, WxSubscribeMsgServiceImpl.CLUB_HOME_PAGE }) {
            assertTrue(routes.contains(p.replaceAll("\\?.*$", "")), "已注册: " + p);
        }
        assertTrue(routes.contains(WxSubscribeMsgServiceImpl.ORDER_PAGE));
        assertTrue(routes.contains(WxSubscribeMsgServiceImpl.MERCHANT_LEDGER_PAGE));
    }
}
