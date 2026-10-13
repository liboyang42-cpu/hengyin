package com.chengyinhub.business.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 出发前提醒 date5 的格式契约(审查D §8 P1-1)。
 *
 * 病灶:`TeamGroupServiceImpl.fmt()` 产 "MM-dd HH:mm",`notifyBeforeStart` 再截前 10 位
 * ⇒ date5="09-16 14:0":无年份、分钟被截断。微信 date 类型要求「年月日(可带24小时制时间)」
 * (官方示例:2019年10月1日 / 2019年10月1日 15:01),这种值会被判 47003(参数格式错误);
 * 而 102 启用后这条提醒是「用户授权才发」的批量链路,一条都发不出去时没有任何信号。
 *
 * 本类只钉一件事:**date5 出去的值必须带年份,绝不能被截断**。
 * 断言读的是真正 POST 出去的 body(走文档化的 post() 测试缝),不是自己写进去再读回来的值。
 */
class BeforeStartDate5FormatContractTest {

    private static final String WECHAT_DATE_TIME = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}";

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;
    private Level originalLevel;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(WxSubscribeMsgServiceImpl.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.WARN); // 兜底路径是 WARN,不放行就抓不到
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
    }

    /** 捕获真正发出去的请求体;post() 缝的 javadoc 就写着「测试覆写此方法注入响应」。 */
    private static class CapturingService extends WxSubscribeMsgServiceImpl {
        String body;

        @Override
        String post(String url, String body) {
            this.body = body;
            return "{\"errcode\":0,\"errmsg\":\"ok\"}";
        }

        String date5() {
            return JSON.parseObject(body).getJSONObject("data").getJSONObject("date5").getString("value");
        }
    }

    private CapturingService service() {
        CapturingService s = new CapturingService();
        IWxContentSecurityService wxSec = mock(IWxContentSecurityService.class);
        when(wxSec.getAccessToken()).thenReturn("token-x");
        UmsMember member = new UmsMember();
        member.setId(100L);
        member.setWxOpenId("openid-x");
        IUmsMemberService members = mock(IUmsMemberService.class);
        when(members.selectUmsMemberById(100L)).thenReturn(member);
        ReflectionTestUtils.setField(s, "wxSec", wxSec);
        ReflectionTestUtils.setField(s, "umsMemberService", members);
        ReflectionTestUtils.setField(s, "tplBeforeStart", "tpl-before-start");
        ReflectionTestUtils.setField(s, "defaultPage", "pages/index/index");
        return s;
    }

    /**
     * 🔴 红点:旧值是 "09-16 14:0" —— 无年份 + 分钟被 substring(0,10) 截断。
     * 微信 date 类型不认无年份的值;这条断言同时钉住「不许截断」。
     */
    @Test
    void legacyMmDdShapeGetsYearInsteadOfTenCharTruncation() {
        CapturingService s = service();
        s.notifyBeforeStart(100L, "上海滨江骑行", "09-16 14:05");

        String date5 = s.date5();
        assertTrue(date5.matches("\\d{4}-09-16 14:05"),
                "date5 必须补年份且不丢分钟,实际=" + date5);
        assertFalse(date5.contains("14:0\n") || date5.endsWith("14:0"),
                "不允许把 \"14:05\" 截成 \"14:0\",实际=" + date5);
    }

    /** 已经是完整日期的输入必须原样放行,不许再截、不许被二次加工。 */
    @Test
    void fullDateInputIsSentUnchanged() {
        CapturingService s = service();
        s.notifyBeforeStart(100L, "上海滨江骑行", "2026-08-21 09:00");

        assertEquals("2026-08-21 09:00", s.date5(), "完整 yyyy-MM-dd HH:mm 不应被改动");
    }

    /** 官方示例形状(2019年10月1日 15:01)同样带年份,应原样放行。 */
    @Test
    void chineseYearMonthDayInputIsSentUnchanged() {
        CapturingService s = service();
        s.notifyBeforeStart(100L, "上海滨江骑行", "2026年8月21日 09:00");

        assertEquals("2026年8月21日 09:00", s.date5(), "带年份的官方示例形状不应被截断");
    }

    /** 自由文本无法当日期用:不得原样发出去,兜底值也必须带年份,并留 WARN。 */
    @Test
    void unparseableInputFallsBackToYearBearingTimeAndWarns() {
        CapturingService s = service();
        s.notifyBeforeStart(100L, "上海滨江骑行", "今天上午");

        assertTrue(s.date5().matches(WECHAT_DATE_TIME),
                "识别不了的文本必须换成带年份的时间兜底,实际=" + s.date5());
        assertFalse(s.date5().contains("今天上午"), "自由文本不许原样进 date5");
        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("date5")),
                "兜底必须留 WARN,不能静默");
    }

    /** null/空输入同样要落到带年份的值,不能把空串塞进 date5。 */
    @Test
    void blankInputFallsBackToYearBearingTime() {
        CapturingService s = service();
        s.notifyBeforeStart(100L, "上海滨江骑行", null);

        assertTrue(s.date5().matches(WECHAT_DATE_TIME),
                "空输入必须兜底成带年份的时间,实际=" + s.date5());
    }
}
