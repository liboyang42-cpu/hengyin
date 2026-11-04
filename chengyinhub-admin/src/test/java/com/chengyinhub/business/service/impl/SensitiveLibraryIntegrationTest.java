package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.ContentSafetyRecheck;
import com.chengyinhub.business.domain.ContentSensitiveHit;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.mapper.ContentSensitiveMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ISensitiveContentLibrary;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.safety.SensitiveWordMatcher;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Round5D:自建词库接入 checkText / 门禁 / 自动复检 / 异步图片路径 / 存量复查;微信关闭或不可用时同样生效。 */
class SensitiveLibraryIntegrationTest {

    private static ISensitiveContentLibrary libraryBlocking(String token) {
        ISensitiveContentLibrary lib = mock(ISensitiveContentLibrary.class);
        SensitiveWordMatcher m = SensitiveWordMatcher.build(Collections.singletonList(
                new SensitiveWordMatcher.Entry(token, "GAMBLING", "BLOCK")), Collections.singletonList(token + "票"));
        when(lib.matchText(anyString())).thenAnswer(i -> m.find(i.getArgument(0)));
        when(lib.blockingCategory(anyString(), any(), any())).thenAnswer(i -> {
            SensitiveWordMatcher.Hit h = m.find(i.getArgument(0));
            return h != null && h.isBlock() ? h.getCategory() : null;
        });
        return lib;
    }

    @Test
    void checkTextRefusesLibraryHitEvenWhenWechatIsOff() {
        WxContentSecurityServiceImpl wx = spy(new WxContentSecurityServiceImpl());
        doReturn(false).when(wx).isEnabled();
        ReflectionTestUtils.setField(wx, "sensitiveLibrary", libraryBlocking("测敏甲乙"));

        assertEquals(ISensitiveContentLibrary.VIOLATION_MESSAGE, wx.checkText("前 测-敏-甲-乙 后", "openid"));
        // 没命中时仍是微信关闭的 fail-close 口径,不是放行
        assertEquals(IWxContentSecurityService.MSG_UNAVAILABLE, wx.checkText("干净文本", "openid"));
        // 放行短语
        assertEquals(IWxContentSecurityService.MSG_UNAVAILABLE, wx.checkText("测敏甲乙票", "openid"));
    }

    @Test
    void gateBlocksLibraryHitWithWechatDownAndNoOpenid() {
        ContentSafetyGateImpl gate = new ContentSafetyGateImpl();
        IWxContentSecurityService wx = mock(IWxContentSecurityService.class);
        when(wx.isEnabled()).thenReturn(false);
        ReflectionTestUtils.setField(gate, "wxContentSecurityService", wx);
        ReflectionTestUtils.setField(gate, "auditTaskService", mock(IAuditTaskService.class));
        ReflectionTestUtils.setField(gate, "sensitiveLibrary", libraryBlocking("测敏甲乙"));
        PublishSafetyRequest req = new PublishSafetyRequest();
        req.setContentType(AuditTask.BIZ_COMMUNITY_POST);
        req.setContentId(5L);
        req.setText("测 敏 甲 乙");
        req.setReviewMode(PublishSafetyRequest.ReviewMode.POST_REVIEW.name());

        SafetyDecision d = gate.decide(req);

        assertTrue(d.isBlock());
        verify(wx, never()).checkText(anyString(), any());
    }

    @Test
    void recheckRejectsLibraryHitWithoutWaitingForWechat() {
        ContentSafetyRecheckServiceImpl svc = new ContentSafetyRecheckServiceImpl();
        IWxContentSecurityService wx = mock(IWxContentSecurityService.class);
        when(wx.isEnabled()).thenReturn(false);
        ReflectionTestUtils.setField(svc, "wx", wx);
        ReflectionTestUtils.setField(svc, "sensitiveLibrary", libraryBlocking("测敏甲乙"));
        ContentSafetyRecheck row = new ContentSafetyRecheck();
        row.setBizType(AuditTask.BIZ_TOPIC);
        row.setBizId(1L);
        row.setTextBody("测敏甲乙");
        String[] code = new String[1];
        assertEquals(ContentSafetyRecheckServiceImpl.Outcome.RISKY, svc.check(row, null, code));
    }

    @Test
    void asyncImagePathTakesDownKnownBadImageThroughTheRiskyPath() throws Exception {
        WxContentSecurityServiceImpl wx = spy(new WxContentSecurityServiceImpl());
        ISensitiveContentLibrary lib = mock(ISensitiveContentLibrary.class);
        when(lib.hasImageEntries()).thenReturn(true);
        when(lib.matchImage(any())).thenReturn("PORN");
        ReflectionTestUtils.setField(wx, "sensitiveLibrary", lib);
        doReturn(false).when(wx).isEnabled();
        doReturn(new byte[]{1, 2, 3}).when(wx).fetchImageBytes("https://cdn.example/x.jpg");
        doReturn(true).when(wx).takeDownForSafety(anyInt(), anyLong());

        assertNull(wx.submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_ACTIVITY, 9L,
                "https://cdn.example/x.jpg", "openid"));

        verify(lib).recordHit("IMAGE", "WX_BIZ_2", 9L, "PORN", "REMOVED");
        assertTrue(wx.knownBadImage(IWxContentSecurityService.BIZ_TYPE_ACTIVITY, 9L, "https://cdn.example/x.jpg"));
    }

    // ---------- 存量复查 ----------

    private SensitiveContentRescanServiceImpl rescan(ContentSensitiveMapper mapper, IWxContentSecurityService wx,
                                                     RedisCache redis, String removeSwitch) {
        SensitiveContentRescanServiceImpl svc = new SensitiveContentRescanServiceImpl();
        ISensitiveContentLibrary lib = libraryBlocking("测敏甲乙");
        when(lib.currentVersion()).thenReturn("v1");
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectConfigByKey("content.sensitive.rescan.remove")).thenReturn(removeSwitch);
        ReflectionTestUtils.setField(svc, "mapper", mapper);
        ReflectionTestUtils.setField(svc, "library", lib);
        ReflectionTestUtils.setField(svc, "wx", wx);
        ReflectionTestUtils.setField(svc, "redisCache", redis);
        ReflectionTestUtils.setField(svc, "configService", config);
        return svc;
    }

    private static Map<String, Object> row(long id, String text) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("id", id);
        m.put("text", text);
        return m;
    }

    private ContentSensitiveMapper mapperWithTopicRows() {
        ContentSensitiveMapper mapper = mock(ContentSensitiveMapper.class);
        when(mapper.selectPublicText(anyString(), anyLong(), anyInt())).thenReturn(Collections.<Map<String, Object>>emptyList());
        List<Map<String, Object>> topics = Arrays.asList(row(11L, "正常"), row(12L, "含 测.敏.甲.乙"), row(13L, "测敏甲乙票"));
        when(mapper.selectPublicText(eq("TOPIC"), eq(0L), anyInt())).thenReturn(topics);
        when(mapper.selectPublicText(eq("MERCHANT_PROFILE"), eq(0L), anyInt()))
                .thenReturn(Collections.singletonList(row(21L, "测敏甲乙")));
        return mapper;
    }

    @Test
    void rescanReportOnlyByDefaultRecordsHitsAndRemovesNothing() {
        ContentSensitiveMapper mapper = mapperWithTopicRows();
        IWxContentSecurityService wx = mock(IWxContentSecurityService.class);
        RedisCache redis = mock(RedisCache.class);

        com.chengyinhub.business.service.ISensitiveContentRescanService.Result r =
                rescan(mapper, wx, redis, "false").runOnce();

        assertEquals(2, r.hits);
        assertEquals(0, r.removed);
        assertTrue(r.finished);
        verify(wx, never()).takeDownForSafety(anyInt(), anyLong());
        ArgumentCaptor<ContentSensitiveHit> hits = ArgumentCaptor.forClass(ContentSensitiveHit.class);
        verify(mapper, times(2)).insertHit(hits.capture());
        for (ContentSensitiveHit h : hits.getAllValues()) {
            assertEquals("REPORT", h.getAction());
            assertEquals("RESCAN", h.getSource());
            assertEquals(SensitiveContentRescanServiceImpl.scanKey("v1"), h.getScanVersion());
        }
    }

    @Test
    void rescanRemoveModeTakesDownThroughSafetyPathAndIsIdempotent() {
        ContentSensitiveMapper mapper = mapperWithTopicRows();
        IWxContentSecurityService wx = mock(IWxContentSecurityService.class);
        when(wx.takeDownForSafety(IWxContentSecurityService.BIZ_TYPE_TOPIC, 12L)).thenReturn(true);
        RedisCache redis = mock(RedisCache.class);
        // 同一版本、同一模式已处理过的 21 号商家资料不重复记
        when(mapper.countRescanHit("MERCHANT_PROFILE", 21L, SensitiveContentRescanServiceImpl.scanKey("v1"), "REMOVED")).thenReturn(1);

        com.chengyinhub.business.service.ISensitiveContentRescanService.Result r =
                rescan(mapper, wx, redis, "true").runOnce();

        assertEquals(1, r.removed);
        assertEquals(1, r.hits);
        verify(wx).takeDownForSafety(IWxContentSecurityService.BIZ_TYPE_TOPIC, 12L);
        verify(wx, never()).takeDownForSafety(anyInt(), eq(13L));

        // 扫完后同版本同模式再跑一轮:什么都不做
        ArgumentCaptor<Object> saved = ArgumentCaptor.forClass(Object.class);
        verify(redis).setCacheObject(eq(SensitiveContentRescanServiceImpl.STATE_KEY), saved.capture());
        when(redis.getCacheObject(SensitiveContentRescanServiceImpl.STATE_KEY)).thenReturn(saved.getValue());
        com.chengyinhub.business.service.ISensitiveContentRescanService.Result again =
                rescan(mapper, wx, redis, "true").runOnce();
        assertEquals(0, again.scanned);
        verify(wx, times(1)).takeDownForSafety(anyInt(), anyLong());
    }

    /** 第十轮 f3 j5 H3:复查是文字命中,下架调用必须带上文字标记,作者通知才不会写成图片原因。 */
    @Test
    void rescanTakedownIsMarkedAsATextHit() {
        ContentSensitiveMapper mapper = mapperWithTopicRows();
        IWxContentSecurityService wx = mock(IWxContentSecurityService.class);
        List<Boolean> marked = new java.util.ArrayList<>();
        when(wx.takeDownForSafety(IWxContentSecurityService.BIZ_TYPE_TOPIC, 12L)).thenAnswer(inv -> {
            marked.add(com.chengyinhub.business.service.support.SystemNoticeCard.isTextHit());
            return true;
        });

        rescan(mapper, wx, mock(RedisCache.class), "true").runOnce();

        assertEquals(Collections.singletonList(Boolean.TRUE), marked);
        assertFalse(com.chengyinhub.business.service.support.SystemNoticeCard.isTextHit());
    }

    /** Round5E rig:真实词库版本串(三表 条数@最后修改时间)约 66 字符,超出 scan_version VARCHAR(64),命中写库失败整轮中断。 */
    @Test
    void rescanScanVersionFitsTheColumnForARealisticLibraryVersion() {
        ContentSensitiveMapper mapper = mapperWithTopicRows();
        RedisCache redis = mock(RedisCache.class);
        SensitiveContentRescanServiceImpl svc = rescan(mapper, mock(IWxContentSecurityService.class), redis, "false");
        ISensitiveContentLibrary lib = (ISensitiveContentLibrary) ReflectionTestUtils.getField(svc, "library");
        String real = "1200@2026-11-03 15:24:56|38@2026-11-03 15:21:35|17@2026-11-03 15:24:27";
        when(lib.currentVersion()).thenReturn(real);

        svc.runOnce();

        ArgumentCaptor<ContentSensitiveHit> hits = ArgumentCaptor.forClass(ContentSensitiveHit.class);
        verify(mapper, times(2)).insertHit(hits.capture());
        for (ContentSensitiveHit h : hits.getAllValues()) {
            assertTrue(h.getScanVersion().length() <= 64, h.getScanVersion());
            assertEquals(SensitiveContentRescanServiceImpl.scanKey(real), h.getScanVersion());
        }
        assertFalse(SensitiveContentRescanServiceImpl.scanKey(real).equals(
                SensitiveContentRescanServiceImpl.scanKey(real.replace("1200@", "1201@"))));
    }

    @Test
    void rescanHonoursThePerRunLimit() {
        ContentSensitiveMapper mapper = mapperWithTopicRows();
        RedisCache redis = mock(RedisCache.class);
        SensitiveContentRescanServiceImpl svc = rescan(mapper, mock(IWxContentSecurityService.class), redis, "false");
        ReflectionTestUtils.setField(svc, "perRunLimit", 2);
        when(mapper.selectPublicText(eq("TOPIC"), eq(0L), eq(2))).thenReturn(Arrays.asList(row(11L, "正常"), row(12L, "测敏甲乙")));

        com.chengyinhub.business.service.ISensitiveContentRescanService.Result r = svc.runOnce();

        assertEquals(2, r.scanned);
        assertFalse(r.finished);
    }
}
