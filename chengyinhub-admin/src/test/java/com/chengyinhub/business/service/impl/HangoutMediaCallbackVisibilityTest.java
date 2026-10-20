package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RoamHangout;
import com.chengyinhub.business.domain.WxContentCallbackClaim;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.RoamHangoutMapper;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.redis.RedisCache;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CL-39：附近的局封面(BIZ_TYPE_HANGOUT=15)的异步机审结论必须落地。
 *
 * <p>RoamHangoutServiceImpl 建局后 submitMediaCheckOne(15, hangoutId, coverUrl)。
 * processMediaCheckResult 早有 15 的隐藏 + 关群分支,但 lockBusinessTarget 与 isCurrentSubmittedMedia
 * 缺 15,回调在 mutation 服务里被当成「目标不存在」抛错释放,隐藏分支永远走不到。
 */
class HangoutMediaCallbackVisibilityTest {

    private static final long HANGOUT_ID = 77L;
    private static final String COVER = "https://oss.example/hangout.jpg";

    private RoamHangoutMapper hangoutMapper;
    private IImService imService;
    private WxContentCallbackInboxMapper inboxMapper;
    private WxContentSecurityServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        hangoutMapper = mock(RoamHangoutMapper.class);
        when(hangoutMapper.selectById(HANGOUT_ID, 0L)).thenReturn(hangout(COVER, RoamHangout.STATUS_OPEN));
        when(hangoutMapper.updateStatus(HANGOUT_ID, RoamHangout.STATUS_HIDDEN)).thenReturn(1);
        imService = mock(IImService.class);
        ObjectProvider<IImService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(imService);
        inboxMapper = mock(WxContentCallbackInboxMapper.class);
        when(inboxMapper.lockBusinessTarget(IWxContentSecurityService.BIZ_TYPE_HANGOUT, HANGOUT_ID)).thenReturn(HANGOUT_ID);
        when(inboxMapper.markDone(anyString(), anyString(), anyLong(), anyInt())).thenReturn(1);
        when(inboxMapper.markIgnoredStale(anyString(), anyString(), anyLong(), anyInt())).thenReturn(1);

        service = new WxContentSecurityServiceImpl();
        ReflectionTestUtils.setField(service, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(service, "roamHangoutMapper", hangoutMapper);
        ReflectionTestUtils.setField(service, "imServiceProvider", provider);
    }

    @Test
    void hangoutCallbackLocksTheHangoutRowForUpdate() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/business/WxContentCallbackInboxMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("bizType", IWxContentSecurityService.BIZ_TYPE_HANGOUT);
        params.put("bizId", HANGOUT_ID);
        String sql = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.WxContentCallbackInboxMapper.lockBusinessTarget")
                .getBoundSql(params).getSql().trim();
        assertEquals("select id from roam_hangout where id = ? for update", sql);
    }

    @Test
    void riskyVerdictHidesTheHangoutAndClosesItsGroup() {
        applyCallback("trace-hangout-risky", hashOf(COVER), true);

        verify(hangoutMapper).updateStatus(HANGOUT_ID, RoamHangout.STATUS_HIDDEN);
        verify(imService).closeHangoutGroup(HANGOUT_ID);
        verify(inboxMapper).markDone(anyString(), anyString(), anyLong(), eq(1));
    }

    @Test
    void staleVerdictOnAReplacedHangoutCoverChangesNothing() {
        when(hangoutMapper.selectById(HANGOUT_ID, 0L))
                .thenReturn(hangout("https://oss.example/new-cover.jpg", RoamHangout.STATUS_OPEN));

        applyCallback("trace-hangout-stale", hashOf(COVER), true);

        verify(inboxMapper).markIgnoredStale(anyString(), anyString(), anyLong(), eq(1));
        verify(inboxMapper, never()).markDone(anyString(), anyString(), anyLong(), anyInt());
        verify(hangoutMapper, never()).updateStatus(anyLong(), anyInt());
        verify(imService, never()).closeHangoutGroup(anyLong());
    }

    @Test
    void passVerdictKeepsTheHangoutOpen() {
        applyCallback("trace-hangout-pass", hashOf(COVER), false);

        verify(inboxMapper).markDone(anyString(), anyString(), anyLong(), eq(0));
        verify(hangoutMapper, never()).updateStatus(anyLong(), anyInt());
        verify(imService, never()).closeHangoutGroup(anyLong());
    }

    private void applyCallback(String traceId, String mediaHash, boolean risky) {
        IWxContentCallbackInboxService inbox = mock(IWxContentCallbackInboxService.class);
        AtomicReference<IWxContentCallbackInboxService.Mutation> captured = new AtomicReference<>();
        when(inbox.isRegistered(traceId)).thenReturn(true);
        when(inbox.process(eq(traceId), eq(risky), any(IWxContentCallbackInboxService.Mutation.class)))
                .thenAnswer(invocation -> {
                    captured.set(invocation.getArgument(2, IWxContentCallbackInboxService.Mutation.class));
                    return true;
                });
        ReflectionTestUtils.setField(service, "callbackInboxService", inbox);
        assertTrue(service.handleMediaCheckResult(traceId, risky));

        WxContentCallbackInbox row = new WxContentCallbackInbox();
        row.setBizType(IWxContentSecurityService.BIZ_TYPE_HANGOUT);
        row.setBizId(HANGOUT_ID);
        row.setMediaHash(mediaHash);
        when(inboxMapper.selectOwnedForUpdate(anyString(), anyString(), anyLong())).thenReturn(row);
        WxContentCallbackMutationService mutationService = new WxContentCallbackMutationService();
        ReflectionTestUtils.setField(mutationService, "mapper", inboxMapper);
        assertTrue(mutationService.apply(WxContentCallbackClaim.acquired("hash", "owner", 1L), risky, captured.get()));
    }

    private static RoamHangout hangout(String cover, int status) {
        RoamHangout h = new RoamHangout();
        h.setId(HANGOUT_ID);
        h.setCoverUrl(cover);
        h.setStatus(status);
        return h;
    }

    private static String hashOf(String url) {
        return WxContentCallbackInboxServiceImpl.sha256Hex(url);
    }
}
