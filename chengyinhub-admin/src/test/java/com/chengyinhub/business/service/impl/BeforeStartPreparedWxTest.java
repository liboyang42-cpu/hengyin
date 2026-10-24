package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IWxSubscribeMsgService.PreparedBeforeStart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real original WX renderer/post seam, no network. Does not claim durable/MySQL validation. */
class BeforeStartPreparedWxTest {
    static class CapturingWx extends WxSubscribeMsgServiceImpl {
        int posts;
        String body;
        boolean loseResponse;
        @Override String post(String url,String body) {
            posts++;this.body=body;
            if(loseResponse) throw new IllegalStateException("response lost");
            return "{\"errcode\":0}";
        }
    }
    private CapturingWx wx;
    private IWxContentSecurityService tokens;
    private IUmsMemberService members;
    private UmsMember member;
    @BeforeEach void setup() {
        wx=new CapturingWx();tokens=mock(IWxContentSecurityService.class);members=mock(IUmsMemberService.class);
        member=new UmsMember();member.setId(100L);member.setWxOpenId("original-openid");member.setNickname("原昵称");
        when(members.selectUmsMemberById(100L)).thenReturn(member);
        when(tokens.getAccessToken()).thenReturn("unit-access-token");
        ReflectionTestUtils.setField(wx,"wxSec",tokens);ReflectionTestUtils.setField(wx,"umsMemberService",members);
        ReflectionTestUtils.setField(wx,"tplBeforeStart","before-start-template");
        ReflectionTestUtils.setField(wx,"tplOfficialEvent","broadcast-must-not-be-used");
        ReflectionTestUtils.setField(wx,"defaultPage","pages/play/index");
    }
    @Test void prepareFreezesTheOriginalFiveFieldsWithoutTokenOrProviderIo() {
        PreparedBeforeStart frozen=wx.prepareBeforeStart(100L,"原票次","2026-10-24 14:05");
        assertEquals(100L,frozen.memberId());assertEquals("original-openid",frozen.openid());
        assertEquals("before-start-template",frozen.templateId());assertEquals("pages/play/index",frozen.page());
        assertEquals(Map.of("thing1","原票次","date5","2026-10-24 14:05","thing8","详见活动详情",
                "thing7","请准时到场","thing12","原昵称"),frozen.fields());
        assertThrows(UnsupportedOperationException.class,() -> frozen.fields().put("thing1","changed"));
        assertEquals(0,wx.posts);verifyNoInteractions(tokens);
    }
    @Test void dispatchDoesNotFollowLaterOpenidNicknameTemplateOrPageChanges() {
        PreparedBeforeStart frozen=wx.prepareBeforeStart(100L,"原票次","2026-10-24 14:05");
        member.setWxOpenId("different-openid");member.setNickname("新昵称");
        ReflectionTestUtils.setField(wx,"tplBeforeStart","changed-template");
        ReflectionTestUtils.setField(wx,"defaultPage","pages/new/page");
        assertTrue(wx.sendPreparedBeforeStart(frozen));
        JSONObject body=JSON.parseObject(wx.body);
        assertEquals("original-openid",body.getString("touser"));
        assertEquals("before-start-template",body.getString("template_id"));
        assertEquals("pages/play/index",body.getString("page"));
        assertEquals(Set.of("thing1","date5","thing8","thing7","thing12"),body.getJSONObject("data").keySet());
        frozen.fields().forEach((key,value) -> assertEquals(value,body.getJSONObject("data").getJSONObject(key).getString("value")));
        verify(members,times(2)).selectUmsMemberById(100L);assertEquals(1,wx.posts);
    }
    @Test void evenAnOriginallyEmptyPageIsFrozenRatherThanUsingLaterDefault() {
        ReflectionTestUtils.setField(wx,"defaultPage","");
        PreparedBeforeStart frozen=wx.prepareBeforeStart(100L,"原票次","2026-10-24 14:05");
        ReflectionTestUtils.setField(wx,"defaultPage","pages/new/page");
        assertTrue(wx.sendPreparedBeforeStart(frozen));assertEquals("",JSON.parseObject(wx.body).getString("page"));
    }
    @Test void missingDedicatedTemplateNeverFallsBackToBroadcastAndDoesNotAcquireToken() {
        ReflectionTestUtils.setField(wx,"tplBeforeStart","");
        assertNull(wx.prepareBeforeStart(100L,"原票次","2026-10-24 14:05"));
        assertEquals(0,wx.posts);verifyNoInteractions(tokens);
    }
    @Test void missingOpenidIsNotPreparedAndNeverCallsProvider() {
        member.setWxOpenId(null);
        assertNull(wx.prepareBeforeStart(100L,"原票次","2026-10-24 14:05"));
        assertEquals(0,wx.posts);verifyNoInteractions(tokens);
    }
    @Test void malformedPreparedInputRefusesBeforeProviderAndLostResponseIsFalse() {
        assertThrows(IllegalArgumentException.class,() -> wx.sendPreparedBeforeStart(
                new PreparedBeforeStart(100L,"openid","template","page",Map.of("thing1","incomplete"))));
        assertEquals(0,wx.posts);verifyNoInteractions(tokens);
        assertThrows(IllegalArgumentException.class,() ->
                new com.chengyinhub.business.domain.dto.BeforeStartNotifyResult.Delivery(null,"ACCEPTED",true));
        PreparedBeforeStart frozen=wx.prepareBeforeStart(100L,"原票次","2026-10-24 14:05");
        wx.loseResponse=true;assertFalse(wx.sendPreparedBeforeStart(frozen));assertEquals(1,wx.posts);
    }
}
