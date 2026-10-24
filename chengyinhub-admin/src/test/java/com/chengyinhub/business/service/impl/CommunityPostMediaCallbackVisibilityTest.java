package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.WxContentCallbackClaim;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.ICommunityPostService;
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
import java.util.Collections;
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
 * 审核分级 P2:社区帖文图片(BIZ_TYPE_COMMUNITY_POST=17)异步机审命中风险 → 帖文生命周期 HIDE。
 *
 * <p>帖文直发带图后 submitMediaCheckOne(17, postId, url)。与附近的局同理,lockBusinessTarget 与
 * isCurrentSubmittedMedia 必须同时认 17,否则回调被当成「目标不存在」释放,隐藏分支永远走不到。
 */
class CommunityPostMediaCallbackVisibilityTest {

    private static final long POST_ID = 7L;
    private static final String IMAGE = "upload/0123456789abcdef0123456789abcdef.jpg";

    private ICommunityPostService communityPostService;
    private WxContentCallbackInboxMapper inboxMapper;
    private WxContentSecurityServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        communityPostService = mock(ICommunityPostService.class);
        when(communityPostService.activeMediaKeys(POST_ID)).thenReturn(Collections.singletonList(IMAGE));
        when(communityPostService.applyRiskyMediaResult(POST_ID)).thenReturn(true);
        ObjectProvider<ICommunityPostService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(communityPostService);
        inboxMapper = mock(WxContentCallbackInboxMapper.class);
        when(inboxMapper.lockBusinessTarget(IWxContentSecurityService.BIZ_TYPE_COMMUNITY_POST, POST_ID)).thenReturn(POST_ID);
        when(inboxMapper.markDone(anyString(), anyString(), anyLong(), anyInt())).thenReturn(1);
        when(inboxMapper.markIgnoredStale(anyString(), anyString(), anyLong(), anyInt())).thenReturn(1);

        service = new WxContentSecurityServiceImpl();
        ReflectionTestUtils.setField(service, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(service, "communityPostServiceProvider", provider);
    }

    @Test
    void communityPostCallbackLocksThePostRowForUpdate() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/business/WxContentCallbackInboxMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("bizType", IWxContentSecurityService.BIZ_TYPE_COMMUNITY_POST);
        params.put("bizId", POST_ID);
        String sql = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.WxContentCallbackInboxMapper.lockBusinessTarget")
                .getBoundSql(params).getSql().trim();
        assertEquals("select id from community_post where id = ? for update", sql);
    }

    @Test
    void riskyVerdictAppliesThePostLifecycleHide() {
        applyCallback("trace-post-risky", hashOf(IMAGE), true);

        verify(communityPostService).applyRiskyMediaResult(POST_ID);
        verify(inboxMapper).markDone(anyString(), anyString(), anyLong(), eq(1));
    }

    @Test
    void staleVerdictOnARemovedImageChangesNothing() {
        when(communityPostService.activeMediaKeys(POST_ID))
                .thenReturn(Collections.singletonList("upload/ffffffffffffffffffffffffffffffff.jpg"));

        applyCallback("trace-post-stale", hashOf(IMAGE), true);

        verify(inboxMapper).markIgnoredStale(anyString(), anyString(), anyLong(), eq(1));
        verify(communityPostService, never()).applyRiskyMediaResult(anyLong());
    }

    @Test
    void passVerdictLeavesThePostAlone() {
        applyCallback("trace-post-pass", hashOf(IMAGE), false);

        verify(inboxMapper).markDone(anyString(), anyString(), anyLong(), eq(0));
        verify(communityPostService, never()).applyRiskyMediaResult(anyLong());
    }

    /** 预签名 URL 只用于拉图;inbox 登记的是稳定对象 key,回调核对才对得上。 */
    @Test
    void presignedSubmissionRegistersTheObjectKeyNotTheExpiringUrl() {
        String preview = "https://private-test.oss-cn-shanghai.aliyuncs.com/" + IMAGE + "?Expires=300&Signature=s";
        IWxContentCallbackInboxService inbox = mock(IWxContentCallbackInboxService.class);
        when(inbox.register(anyString(), anyInt(), anyLong(), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(service, "callbackInboxService", inbox);
        WxContentSecurityServiceImpl spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(true).when(spy).isEnabled();
        org.mockito.Mockito.doReturn("trace-presigned").when(spy).checkMediaAsync(preview, "openid-9");

        assertEquals("trace-presigned", spy.submitMediaCheckOne(
                IWxContentSecurityService.BIZ_TYPE_COMMUNITY_POST, POST_ID, preview, IMAGE, "openid-9"));

        verify(inbox).register("trace-presigned", IWxContentSecurityService.BIZ_TYPE_COMMUNITY_POST, POST_ID, IMAGE);
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
        row.setBizType(IWxContentSecurityService.BIZ_TYPE_COMMUNITY_POST);
        row.setBizId(POST_ID);
        row.setMediaHash(mediaHash);
        when(inboxMapper.selectOwnedForUpdate(anyString(), anyString(), anyLong())).thenReturn(row);
        WxContentCallbackMutationService mutationService = new WxContentCallbackMutationService();
        ReflectionTestUtils.setField(mutationService, "mapper", inboxMapper);
        assertTrue(mutationService.apply(WxContentCallbackClaim.acquired("hash", "owner", 1L), risky, captured.get()));
    }

    private static String hashOf(String url) {
        return WxContentCallbackInboxServiceImpl.sha256Hex(url);
    }
}
