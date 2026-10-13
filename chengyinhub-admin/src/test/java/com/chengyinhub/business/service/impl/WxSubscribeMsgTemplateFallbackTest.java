package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺陷⑪ 「未配模板 = no-op」必须是真的。
 *
 * 原先 tplOfficialEvent 未配时回退 tplRecruit,而 application.yml:216 的
 * tplRecruit 是真实可用的模板 ID ⇒ send 不是 no-op,是拿「新活动招募」模板真发:
 * 模板语义错配(招募模板发给玩家做官方广播),且烧掉用户为「招募」授权的配额
 * (微信订阅消息一次授权=一次发送)。notifyOfficialBroadcast 是活的同步调用
 * (OfficialBroadcastServiceImpl:122),没有任何「暂停态」保护它。
 *
 * 注:本类原先还覆盖 notifyRecall 的同一件事,随召回功能于 2026-07-17 整体拆除
 * (模板从未申请、授权从未做 ⇒ 功能是死的,不在生产库留僵尸 job)而一并下线。
 *
 * 断言方式:未配专用模板时不得走到取 access_token 那一步 —— 那是真发的必经之路。
 */
class WxSubscribeMsgTemplateFallbackTest {

    private static final Long MEMBER_ID = 100L;
    private static final String REAL_RECRUIT_TPL = "pVihLGRWi3uA4wGvA-Hv-NsuOGvtj1Ts3kV1GmU56Dw";

    private IWxContentSecurityService wxSec;

    /** 招募模板已配(线上就是这样),专用模板由入参决定配没配。 */
    private WxSubscribeMsgServiceImpl service(String tplOfficialEvent) {
        wxSec = mock(IWxContentSecurityService.class);
        when(wxSec.getAccessToken()).thenReturn("token-would-really-send");

        UmsMember m = new UmsMember();
        m.setId(MEMBER_ID);
        m.setWxOpenId("openid-x");
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        when(memberService.selectUmsMemberById(anyLong())).thenReturn(m);

        WxSubscribeMsgServiceImpl s = new WxSubscribeMsgServiceImpl();
        ReflectionTestUtils.setField(s, "wxSec", wxSec);
        ReflectionTestUtils.setField(s, "umsMemberService", memberService);
        ReflectionTestUtils.setField(s, "tplRecruit", REAL_RECRUIT_TPL);
        ReflectionTestUtils.setField(s, "tplOfficialEvent", tplOfficialEvent);
        ReflectionTestUtils.setField(s, "tplRoamRecall", "");
        ReflectionTestUtils.setField(s, "defaultPage", "pages/index/index");
        return s;
    }

    @Test
    void officialBroadcastWithoutOwnTemplateSendsNothing() {
        service("").notifyOfficialBroadcast(MEMBER_ID, "官方活动", "来玩");
        verify(wxSec, never()).getAccessToken();
    }

    @Test
    void officialBroadcastWithOwnTemplateStillSends() {
        // 负控的另一半:配了专用模板就该照发,别把功能一起关掉
        service("tpl-official-configured").notifyOfficialBroadcast(MEMBER_ID, "官方活动", "来玩");
        verify(wxSec).getAccessToken();
    }

    @Test
    void roamRecallWithoutOwnTemplateSendsNothingAndReportsFailure() {
        WxSubscribeMsgServiceImpl service = service("");
        org.junit.jupiter.api.Assertions.assertFalse(service.isRoamRecallReady());
        org.junit.jupiter.api.Assertions.assertFalse(service.notifyRoamRecall(MEMBER_ID, 1200, 4));
        verify(wxSec, never()).getAccessToken();
    }
}
