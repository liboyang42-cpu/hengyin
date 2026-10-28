package com.chengyinhub.business.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.utils.http.HttpRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * send() 的 errcode 解析必须被测试锁住。
 *
 * 微信 send 失败也返 HTTP 200 + body {"errcode":40003,...} ⇒ 不解析 errcode 就是把失败当成功记
 * (WxSubscribeMsgServiceImpl:110-124 修的正是这个)。那条修复本身是**真观测** —— 它读的是微信
 * 服务器返回的 errcode,不是自己写进去再读回来的值,不属于「回执≠观测」要打的恒真断言。
 *
 * 但 2026-07-17 自审做变异测试发现:把 {@code if (errcode != 0)} 整个禁用后,当时全套测试
 * **2/2 全绿** ⇒ 这条观测没有任何测试锁定。唯一路过 send() 的用例只断言 getAccessToken 被调用,
 * 跟 errcode 处理毫无关系。以后谁把它改坏(条件写反 / 字段名打错 / 重构掉),测试不会红,
 * 静默退回「假装成功」—— 正是这个 PR 想修掉的那个状态。**本类就是补那把锁。**
 *
 * 🔴 负控:禁用 {@code if (errcode != 0)} 后,{@link #failureIsLoggedAsError} 与四类业务上下文契约
 * 必须红；网络异常走独立 catch 闸,不应被这次变异影响。
 *
 * 注:走 {@code post()} 缝注入响应,**不打真实微信 API** —— 该缝的 javadoc 写着「测试覆写此方法
 * 注入响应」,但在本类之前从没有测试用过它。
 */
class WxSubscribeMsgSendErrcodeTest {

    private static final String SENSITIVE_TEMPLATE = "template-secret-token-13812345678";
    private static final String CONFIGURED_TEMPLATE = "configured-template";
    private static final Long RECIPIENT_MEMBER_ID = 7102L;
    private static final String RECIPIENT_OPENID = "openid-recipient-7102";

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;
    private Level originalLevel;
    private IWxContentSecurityService wxSec;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(WxSubscribeMsgServiceImpl.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO); // 「send ok」是 info 级,不放行 INFO 就抓不到成功路径
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
    }

    /** post() 被覆写成返回固定响应,故不产生任何网络请求。 */
    private WxSubscribeMsgServiceImpl serviceReturning(String stubbedResponse) {
        wxSec = mock(IWxContentSecurityService.class);
        when(wxSec.getAccessToken()).thenReturn("token-x");

        WxSubscribeMsgServiceImpl s = new WxSubscribeMsgServiceImpl() {
            @Override
            String post(String url, String body) {
                return stubbedResponse;
            }
        };
        ReflectionTestUtils.setField(s, "wxSec", wxSec);
        ReflectionTestUtils.setField(s, "defaultPage", "pages/index/index");
        return s;
    }

    private WxSubscribeMsgServiceImpl businessServiceReturning(String stubbedResponse,
                                                                String templateField,
                                                                String templateValue) {
        WxSubscribeMsgServiceImpl service = serviceReturning(stubbedResponse);
        UmsMember member = new UmsMember();
        member.setId(RECIPIENT_MEMBER_ID);
        member.setWxOpenId(RECIPIENT_OPENID);
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        when(memberService.selectUmsMemberById(RECIPIENT_MEMBER_ID)).thenReturn(member);
        ReflectionTestUtils.setField(service, "umsMemberService", memberService);
        ReflectionTestUtils.setField(service, templateField, templateValue);
        return service;
    }

    private void send(String stubbedResponse) {
        serviceReturning(stubbedResponse).send("openid-x", SENSITIVE_TEMPLATE,
                "pages/index/index", new HashMap<>());
    }

    private void sendNetworkFailure() {
        IWxContentSecurityService wxSec = mock(IWxContentSecurityService.class);
        when(wxSec.getAccessToken()).thenReturn("token-x");
        WxSubscribeMsgServiceImpl service = new WxSubscribeMsgServiceImpl() {
            @Override
            String post(String url, String body) {
                throw new HttpRequestException("upstream https://provider.invalid/private-path "
                        + "token=subscribe-token-secret phone=13812345678", -1, true);
            }
        };
        ReflectionTestUtils.setField(service, "wxSec", wxSec);
        ReflectionTestUtils.setField(service, "defaultPage", "pages/index/index");

        service.send("openid-x", SENSITIVE_TEMPLATE, "pages/index/index", new HashMap<>());
    }

    private boolean logged(Level level, String needle) {
        return logs.list.stream()
                .anyMatch(e -> e.getLevel() == level && e.getFormattedMessage().contains(needle));
    }

    private boolean loggedAnywhere(String needle) {
        return logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains(needle));
    }

    private void assertBusinessFailureContext(String templateField, String templateKey,
                                              String businessEvent,
                                              Consumer<WxSubscribeMsgServiceImpl> action) {
        String response = "{\"errcode\":43101,\"errmsg\":\"user refuse to accept the msg "
                + RECIPIENT_OPENID + "\"}";
        WxSubscribeMsgServiceImpl service = businessServiceReturning(
                response, templateField, CONFIGURED_TEMPLATE);

        action.accept(service);

        ILoggingEvent failure = logs.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .filter(event -> event.getFormattedMessage().contains("errcode=43101"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("errcode!=0 的业务发送闸必须产生 ERROR 记录"));
        String message = failure.getFormattedMessage();
        assertTrue(message.contains("templateKey=" + templateKey), "日志须指认模板键");
        assertTrue(message.contains("businessEvent=" + businessEvent), "日志须指认业务事件");
        assertTrue(message.contains("memberId=" + RECIPIENT_MEMBER_ID), "日志须用非 PII memberId 指认收件人");
        assertTrue(message.contains("errmsg=user refuse to accept the msg"), "日志须保留可诊断 errmsg");
        assertFalse(message.contains(RECIPIENT_OPENID), "errmsg 中的 openid 必须脱敏");
        assertFalse(message.contains(CONFIGURED_TEMPLATE), "不得记录模板 ID");
        assertFalse(loggedAnywhere("send ok"), "微信明确拒绝不得冒充成功");
    }

    private void assertMissingTemplateSkippedOnce(String templateField, String templateKey,
                                                  String businessEvent,
                                                  Consumer<WxSubscribeMsgServiceImpl> action) {
        WxSubscribeMsgServiceImpl service = businessServiceReturning(
                "{\"errcode\":0,\"errmsg\":\"ok\"}", templateField, "");

        action.accept(service);
        action.accept(service);

        verify(wxSec, never()).getAccessToken();
        long warnings = logs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("templateKey=" + templateKey))
                .filter(message -> message.contains("businessEvent=" + businessEvent))
                .filter(message -> message.contains("action=skip"))
                .count();
        assertEquals(1L, warnings, "同一空模板每 JVM 只警一次,既留痕又不刷屏");
    }

    @Test
    void failureIsLoggedAsError() {
        String phone = "13812345678";
        String token = "subscribe-token-secret";
        String body = "private subscribe body";
        String upstreamUrl = "https://provider.invalid/private-path";
        send("{\"errcode\":40003,\"errmsg\":\"" + phone + " " + token + " "
                + body + " " + upstreamUrl + "\"}");

        assertTrue(logged(Level.ERROR, "40003"), "errcode!=0 必须被 ERROR 记录,否则失败等于没发生");
        assertFalse(loggedAnywhere("send ok"), "失败绝不能被记成 send ok —— 那正是本 PR 要修的假成功");
        assertFalse(loggedAnywhere(phone));
        assertFalse(loggedAnywhere(token));
        assertFalse(loggedAnywhere(body));
        assertFalse(loggedAnywhere(upstreamUrl));
        assertFalse(loggedAnywhere(SENSITIVE_TEMPLATE));
    }

    /** 网络层失败由 HttpUtils 显式抛出，但 best-effort 通知不得把异常冒泡给业务调用方。 */
    @Test
    void networkFailureIsSafelyDegraded() {
        sendNetworkFailure();

        assertTrue(logged(Level.WARN, "发送失败"), "网络失败必须可观测并在本服务安全降级");
        assertFalse(loggedAnywhere("send ok"), "网络失败绝不能被记成 send ok");
        assertTrue(logs.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .allMatch(event -> event.getThrowableProxy() == null),
                "网络异常日志不得附带可能含上游 URL/token/body 的 throwable");
        assertFalse(loggedAnywhere("provider.invalid"));
        assertFalse(loggedAnywhere("subscribe-token-secret"));
        assertFalse(loggedAnywhere("13812345678"));
        assertFalse(loggedAnywhere(SENSITIVE_TEMPLATE));
    }

    /** 正控:别把闸修成「一律报失败」—— 成功必须仍被认成成功。 */
    @Test
    void successIsNotMisreportedAsFailure() {
        send("{\"errcode\":0,\"errmsg\":\"ok\"}");

        assertTrue(loggedAnywhere("send ok"), "errcode==0 是成功,必须照常记 send ok");
        assertFalse(logged(Level.ERROR, "发送失败"), "成功不得被误报成失败");
    }

    @Test
    void coopInviteFailureCarriesBusinessContext() {
        assertBusinessFailureContext("tplCoopInvited", "tplCoopInvited", "coop_invited",
                service -> service.notifyCoopInvited(RECIPIENT_MEMBER_ID, "主题", "发起方"));
    }

    @Test
    void recruitFailureCarriesBusinessContext() {
        assertBusinessFailureContext("tplRecruit", "tplRecruit", "recruit_status",
                service -> service.notifyRecruit(RECIPIENT_MEMBER_ID, "主题", "明天", "招募结束"));
    }

    @Test
    void settlementFailureCarriesBusinessContext() {
        assertBusinessFailureContext("tplCoopSettle", "tplCoopSettle", "coop_settle",
                service -> service.notifyCoopSettle(
                        RECIPIENT_MEMBER_ID, "12.00", "今天", "已结算", "ORDER-1"));
    }

    @Test
    void configuredClubInterestFailureCarriesBusinessContext() {
        assertBusinessFailureContext("tplClubInterest", "tplClubInterest", "club_interest",
                service -> service.notifyClubInterested(RECIPIENT_MEMBER_ID, "主题", "俱乐部"));
    }

    @Test
    void missingClubInterestTemplateSkipsWechatAndWarnsOnce() {
        assertMissingTemplateSkippedOnce("tplClubInterest", "tplClubInterest", "club_interest",
                service -> service.notifyClubInterested(RECIPIENT_MEMBER_ID, "主题", "俱乐部"));
    }

    @Test
    void missingRoamRecallTemplateSkipsWechatAndWarnsOnce() {
        assertMissingTemplateSkippedOnce("tplRoamRecall", "tplRoamRecall", "roam_recall",
                service -> service.notifyRoamRecall(RECIPIENT_MEMBER_ID, 1200, 4));
    }
    @org.junit.jupiter.api.Test
    void officialTypedCodesPreserveOnlyExplicitNumericProviderOutcomes() {
        var accepted = WxSubscribeMsgServiceImpl.officialBroadcastResponse("{\"errcode\":0,\"errmsg\":\"private fixture\"}");
        org.junit.jupiter.api.Assertions.assertEquals("ACCEPTED", accepted.state().name());
        org.junit.jupiter.api.Assertions.assertEquals(Integer.valueOf(0), accepted.errcode());
        for (int code : new int[]{40003, 43101, -1}) {
            var rejected = WxSubscribeMsgServiceImpl.officialBroadcastResponse("{\"errcode\":" + code + "}");
            org.junit.jupiter.api.Assertions.assertEquals("REJECTED", rejected.state().name());
            org.junit.jupiter.api.Assertions.assertEquals(Integer.valueOf(code), rejected.errcode());
        }
        org.junit.jupiter.api.Assertions.assertFalse(accepted.toString().contains("private fixture"));
    }

    @org.junit.jupiter.api.Test
    void officialMalformedResponsesStayUnknownWithoutDiagnosticPayload() {
        for (String raw : new String[]{"", " ", "null", "[]", "broken private fixture", "{}",
                "{\"errcode\":null}", "{\"errcode\":\"0\"}", "{\"errcode\":false}",
                "{\"errcode\":0.0}", "{\"errcode\":2147483648}", "{\"errcode\":{}}"}) {
            var result = WxSubscribeMsgServiceImpl.officialBroadcastResponse(raw);
            org.junit.jupiter.api.Assertions.assertEquals("UNKNOWN", result.state().name(), raw);
            org.junit.jupiter.api.Assertions.assertNull(result.errcode());
            org.junit.jupiter.api.Assertions.assertFalse(result.toString().contains("private fixture"));
        }
    }

    @org.junit.jupiter.api.Test
    void officialTypedPreflightDoesNotPostOrBorrowRecruitTemplate() {
        var missing = businessServiceReturning("{\"errcode\":0}", "tplRecruit", "recruit-only");
        org.junit.jupiter.api.Assertions.assertEquals("TEMPLATE_MISSING", missing.notifyOfficialBroadcastResult(RECIPIENT_MEMBER_ID,"title","sub").reason().name());
        var service = businessServiceReturning("{\"errcode\":0}", "tplOfficialEvent", SENSITIVE_TEMPLATE);
        org.junit.jupiter.api.Assertions.assertEquals("IDENTITY_MISSING", service.notifyOfficialBroadcastResult(null,"title","sub").reason().name());
        when(wxSec.getAccessToken()).thenReturn("");
        org.junit.jupiter.api.Assertions.assertEquals("TOKEN_MISSING", service.notifyOfficialBroadcastResult(RECIPIENT_MEMBER_ID,"title","sub").reason().name());
    }

    @org.junit.jupiter.api.Test
    void officialTypedUsesExistingPostSeamAndExactTemplateFields() {
        final java.util.List<String> bodies = new java.util.ArrayList<>();
        var service = new WxSubscribeMsgServiceImpl() {
            @Override String post(String url,String body) { bodies.add(body);return "{\"errcode\":43101,\"errmsg\":\"private fixture\"}"; }
        };
        var members=mock(IUmsMemberService.class);var member=new UmsMember();member.setWxOpenId(RECIPIENT_OPENID);
        when(members.selectUmsMemberById(RECIPIENT_MEMBER_ID)).thenReturn(member);
        var tokens=mock(IWxContentSecurityService.class);when(tokens.getAccessToken()).thenReturn("token-fixture");
        ReflectionTestUtils.setField(service,"umsMemberService",members);ReflectionTestUtils.setField(service,"wxSec",tokens);
        ReflectionTestUtils.setField(service,"tplOfficialEvent",SENSITIVE_TEMPLATE);ReflectionTestUtils.setField(service,"defaultPage","pages/index/index");
        var result=service.notifyOfficialBroadcastResult(RECIPIENT_MEMBER_ID,"title","sub");
        org.junit.jupiter.api.Assertions.assertEquals("REJECTED",result.state().name());
        org.junit.jupiter.api.Assertions.assertEquals(Integer.valueOf(43101),result.errcode());
        var body=com.alibaba.fastjson2.JSON.parseObject(bodies.get(0));
        org.junit.jupiter.api.Assertions.assertEquals(SENSITIVE_TEMPLATE,body.getString("template_id"));
        org.junit.jupiter.api.Assertions.assertEquals("pages/index/index",body.getString("page"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("thing1","time2","thing3"),body.getJSONObject("data").keySet());
        org.junit.jupiter.api.Assertions.assertFalse(result.toString().contains(RECIPIENT_OPENID));
        org.junit.jupiter.api.Assertions.assertFalse(result.toString().contains("private fixture"));
    }

    @org.junit.jupiter.api.Test
    void officialChannelUsesTypedOnlyAndPreservesOtherBooleanEvents() {
        var wx=mock(com.chengyinhub.business.service.IWxSubscribeMsgService.class);
        var channel=new com.chengyinhub.business.notify.WxSubscribeChannel();ReflectionTestUtils.setField(channel,"wx",wx);
        when(wx.notifyOfficialBroadcastResult(anyLong(),anyString(),anyString())).thenThrow(new RuntimeException("private fixture"));
        org.junit.jupiter.api.Assertions.assertEquals("UNKNOWN",channel.sendOfficialBroadcast(7L,"t","s").state().name());
        verify(wx,never()).notifyOfficialBroadcast(anyLong(),anyString(),anyString());
        when(wx.notifyRefund(7L,"t","1")).thenReturn(false);
        org.junit.jupiter.api.Assertions.assertFalse(channel.send(com.chengyinhub.business.notify.NotifyMessage.of(com.chengyinhub.business.notify.NotifyEvent.REFUND,7L).with("topicName","t").with("amount","1")));
        verify(wx).notifyRefund(7L,"t","1");
    }
    @Test
    void typedTransportExceptionIsUnknownButPreparationExceptionIsNotSubmitted() {
        var service = new WxSubscribeMsgServiceImpl() {
            @Override String post(String url,String body) { throw new RuntimeException("private network fixture"); }
        };
        var members=mock(IUmsMemberService.class);var member=new UmsMember();member.setWxOpenId(RECIPIENT_OPENID);
        when(members.selectUmsMemberById(RECIPIENT_MEMBER_ID)).thenReturn(member);
        var tokens=mock(IWxContentSecurityService.class);when(tokens.getAccessToken()).thenReturn("fixture");
        ReflectionTestUtils.setField(service,"umsMemberService",members);ReflectionTestUtils.setField(service,"wxSec",tokens);
        ReflectionTestUtils.setField(service,"tplOfficialEvent",SENSITIVE_TEMPLATE);ReflectionTestUtils.setField(service,"defaultPage","pages/index/index");
        var result=service.notifyOfficialBroadcastResult(RECIPIENT_MEMBER_ID,"t","s");
        assertEquals("UNKNOWN",result.state().name());assertFalse(result.toString().contains("private"));
        when(tokens.getAccessToken()).thenThrow(new RuntimeException("private preflight fixture"));
        result=service.notifyOfficialBroadcastResult(RECIPIENT_MEMBER_ID,"t","s");
        assertEquals("NOT_SUBMITTED",result.state().name());assertFalse(result.toString().contains("private"));
    }
}
