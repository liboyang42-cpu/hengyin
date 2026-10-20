package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.domain.WxContentCallbackClaim;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.OfficialEventMapper;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.redis.RedisCache;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * AUDIT-G1-4:官方活动封面(BIZ_TYPE_OFFICIAL_EVENT=8)机审违规必须下线,而不是只打 ERROR 日志。
 *
 * <p>此前三处都缺 8:mutation 服务把 8 直接转人工、lockBusinessTarget 没有 official_event、
 * isCurrentSubmittedMedia 没有封面比对、processMediaCheckResult 落 else 只记日志 ——
 * 违规封面的官方活动照常公开报名。
 */
class OfficialEventMediaCallbackTakedownTest {

    private static final long EVENT_ID = 88L;
    private static final String COVER = "https://oss.example/official.jpg";

    private OfficialEventMapper eventMapper;
    private WxContentCallbackInboxMapper inboxMapper;
    private WxContentSecurityServiceImpl service;

    @BeforeEach
    void setUp() {
        eventMapper = mock(OfficialEventMapper.class);
        when(eventMapper.selectOfficialEventById(EVENT_ID)).thenReturn(event(COVER, OfficialEvent.ST_SIGNUP));
        when(eventMapper.updateOfficialEvent(any())).thenReturn(1);
        inboxMapper = mock(WxContentCallbackInboxMapper.class);
        when(inboxMapper.lockBusinessTarget(IWxContentSecurityService.BIZ_TYPE_OFFICIAL_EVENT, EVENT_ID)).thenReturn(EVENT_ID);
        when(inboxMapper.markDone(anyString(), anyString(), anyLong(), anyInt())).thenReturn(1);
        when(inboxMapper.markIgnoredStale(anyString(), anyString(), anyLong(), anyInt())).thenReturn(1);

        service = new WxContentSecurityServiceImpl();
        ReflectionTestUtils.setField(service, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(service, "officialEventMapper", eventMapper);
    }

    @Test
    void officialEventCallbackLocksTheEventRowForUpdate() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/business/WxContentCallbackInboxMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("bizType", IWxContentSecurityService.BIZ_TYPE_OFFICIAL_EVENT);
        params.put("bizId", EVENT_ID);
        String sql = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.WxContentCallbackInboxMapper.lockBusinessTarget")
                .getBoundSql(params).getSql().trim();
        assertEquals("select id from official_event where id = ? for update", sql);
    }

    @Test
    void riskyCoverTakesTheEventOffline() {
        applyCallback("trace-official-risky", hashOf(COVER), true);

        ArgumentCaptor<OfficialEvent> update = ArgumentCaptor.forClass(OfficialEvent.class);
        verify(eventMapper).updateOfficialEvent(update.capture());
        assertEquals(EVENT_ID, update.getValue().getId());
        assertEquals(OfficialEvent.ST_OFFLINE, update.getValue().getStatus());
        verify(inboxMapper).markDone(anyString(), anyString(), anyLong(), eq(1));
    }

    @Test
    void staleVerdictOnAReplacedCoverChangesNothing() {
        when(eventMapper.selectOfficialEventById(EVENT_ID))
                .thenReturn(event("https://oss.example/new-cover.jpg", OfficialEvent.ST_SIGNUP));

        applyCallback("trace-official-stale", hashOf(COVER), true);

        verify(inboxMapper).markIgnoredStale(anyString(), anyString(), anyLong(), eq(1));
        verify(eventMapper, never()).updateOfficialEvent(any());
    }

    @Test
    void alreadyOfflineEventIsConfirmedWithoutRewrite() {
        when(eventMapper.selectOfficialEventById(EVENT_ID)).thenReturn(event(COVER, OfficialEvent.ST_OFFLINE));

        applyCallback("trace-official-again", hashOf(COVER), true);

        verify(eventMapper, never()).updateOfficialEvent(any());
        verify(inboxMapper).markDone(anyString(), anyString(), anyLong(), eq(1));
    }

    @Test
    void passVerdictKeepsTheEventLive() {
        applyCallback("trace-official-pass", hashOf(COVER), false);

        verify(inboxMapper).markDone(anyString(), anyString(), anyLong(), eq(0));
        verify(eventMapper, never()).updateOfficialEvent(any());
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
        row.setBizType(IWxContentSecurityService.BIZ_TYPE_OFFICIAL_EVENT);
        row.setBizId(EVENT_ID);
        row.setMediaHash(mediaHash);
        when(inboxMapper.selectOwnedForUpdate(anyString(), anyString(), anyLong())).thenReturn(row);
        WxContentCallbackMutationService mutationService = new WxContentCallbackMutationService();
        ReflectionTestUtils.setField(mutationService, "mapper", inboxMapper);
        assertTrue(mutationService.apply(WxContentCallbackClaim.acquired("hash", "owner", 1L), risky, captured.get()));
    }

    private static OfficialEvent event(String cover, int status) {
        OfficialEvent e = new OfficialEvent();
        e.setId(EVENT_ID);
        e.setCoverImg(cover);
        e.setStatus(status);
        return e;
    }

    private static String hashOf(String url) {
        return WxContentCallbackInboxServiceImpl.sha256Hex(url);
    }
}
