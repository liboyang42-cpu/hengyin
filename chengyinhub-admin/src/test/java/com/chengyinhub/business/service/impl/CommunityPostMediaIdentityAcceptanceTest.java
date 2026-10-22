package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.domain.WxContentCallbackClaim;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICommunityMediaReceiptService;
import com.chengyinhub.business.service.ICommunityPostService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.business.service.CommunityTrustService;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 社区帖图送检身份验收(Lane B / #377):稳定 OSS 对象 key 是审核身份,短时预签名 URL 只给微信拉图。
 *
 * <p>真实串起 CommunityPostServiceImpl.publish → WxContentSecurityServiceImpl.submitMediaCheckOne →
 * WxContentCallbackInboxServiceImpl(register/process) → WxContentCallbackMutationService → applyRiskyMediaResult;
 * 只把 MySQL mapper 换成内存实现、微信接口与 OSS 签名换成桩。对象 key 规范化走真实的
 * FileOssUploadUtils.communityMediaObjectKey(reference, bucket, endpoint)。
 */
class CommunityPostMediaIdentityAcceptanceTest {

    private static final String BUCKET = "private-test";
    private static final String ENDPOINT = "https://oss-cn-shanghai.aliyuncs.com";
    private static final String HOST = "https://" + BUCKET + ".oss-cn-shanghai.aliyuncs.com/";
    private static final String KEY = "upload/0123456789abcdef0123456789abcdef.jpg";
    private static final String NEW_KEY = "upload/fedcba9876543210fedcba9876543210.jpg";
    private static final String OTHER_USER_KEY = "upload/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png";
    private static final long POST_ID = 7L;
    private static final long AUTHOR_ID = 9L;
    private static final long OTHER_POST_ID = 8L;
    private static final long OTHER_AUTHOR_ID = 10L;

    private final Map<Long, CommunityPost> posts = new HashMap<>();
    private final Map<Long, List<Map<String, Object>>> media = new HashMap<>();
    private final Map<String, WxContentCallbackInbox> inbox = new HashMap<>();
    private final List<String> fetchUrlsSentToWechat = new ArrayList<>();
    private final AtomicInteger signatures = new AtomicInteger();
    private final AtomicInteger traces = new AtomicInteger();
    private final AtomicInteger fences = new AtomicInteger();
    private final AtomicInteger visibilityChanges = new AtomicInteger();

    private MockedStatic<FileOssUploadUtils> storage;
    private CommunityPostMapper postMapper;
    private IAuditTaskService auditTaskService;
    private IImService im;
    private CommunityPostServiceImpl postService;
    private WxContentSecurityServiceImpl wx;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        storage = Mockito.mockStatic(FileOssUploadUtils.class, storageAnswer());

        postMapper = mock(CommunityPostMapper.class);
        auditTaskService = mock(IAuditTaskService.class);
        IContentSafetyGate safetyGate = mock(IContentSafetyGate.class);
        when(safetyGate.decide(any())).thenReturn(SafetyDecision.pass());
        postService = new CommunityPostServiceImpl(postMapper, new CommunityPostLifecyclePolicy(), safetyGate,
                auditTaskService, mock(CommunityTrustService.class), mock(ICommunityMediaReceiptService.class));
        im = mock(IImService.class);
        ObjectProvider<IImService> imProvider = mock(ObjectProvider.class);
        when(imProvider.getIfAvailable()).thenReturn(im);
        postService.setImServiceProvider(imProvider);
        stubPostMapper();

        WxContentCallbackInboxMapper inboxMapper = inMemoryInboxMapper();
        WxContentCallbackMutationService mutationService = new WxContentCallbackMutationService();
        ReflectionTestUtils.setField(mutationService, "mapper", inboxMapper);
        WxContentCallbackInboxServiceImpl inboxService = new WxContentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(inboxService, "mapper", inboxMapper);
        ReflectionTestUtils.setField(inboxService, "claimService", inMemoryClaims());
        ReflectionTestUtils.setField(inboxService, "mutationService", mutationService);

        ObjectProvider<ICommunityPostService> postProvider = mock(ObjectProvider.class);
        when(postProvider.getObject()).thenReturn(postService);
        WxContentSecurityServiceImpl real = new WxContentSecurityServiceImpl();
        ReflectionTestUtils.setField(real, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(real, "communityPostServiceProvider", postProvider);
        ReflectionTestUtils.setField(real, "callbackInboxService", inboxService);
        wx = Mockito.spy(real);
        Mockito.doReturn(true).when(wx).isEnabled();
        Mockito.doAnswer(invocation -> {
            fetchUrlsSentToWechat.add(invocation.getArgument(0));
            return "trace-" + traces.incrementAndGet();
        }).when(wx).checkMediaAsync(anyString(), any());
        ObjectProvider<IWxContentSecurityService> wxProvider = mock(ObjectProvider.class);
        when(wxProvider.getIfAvailable()).thenReturn(wx);
        postService.setWxContentSecurityProvider(wxProvider);

        posts.put(POST_ID, post(POST_ID, AUTHOR_ID, CommunityPost.LIFECYCLE_DRAFT, 3));
        media.put(POST_ID, mediaRows(KEY));
        posts.put(OTHER_POST_ID, post(OTHER_POST_ID, OTHER_AUTHOR_ID, CommunityPost.LIFECYCLE_PUBLISHED, 1));
        media.put(OTHER_POST_ID, mediaRows(OTHER_USER_KEY));
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    /** 同一对象 key、不同签名 → 同一审核身份;回调到达时 URL 已重签也照样命中。 */
    @Test
    void sameKeyWithDifferentSignaturesIsTheSameIdentity() {
        String first = FileOssUploadUtils.generateCommunityMediaPresignedUrl(KEY);
        String second = FileOssUploadUtils.generateCommunityMediaPresignedUrl(KEY);
        assertNotEquals(first, second);
        assertEquals(KEY, FileOssUploadUtils.communityMediaObjectKey(first));
        assertEquals(KEY, FileOssUploadUtils.communityMediaObjectKey(second));

        publish();

        assertEquals(1, fetchUrlsSentToWechat.size());
        String sent = fetchUrlsSentToWechat.get(0);
        assertTrue(sent.startsWith(HOST + KEY + "?"), "微信拿到的应是预签名拉图地址");
        WxContentCallbackInbox row = inbox.get(WxContentCallbackInboxServiceImpl.sha256Hex("trace-1"));
        assertNotNull(row);
        assertEquals(WxContentCallbackInboxServiceImpl.sha256Hex(KEY), row.getMediaHash());
        assertNotEquals(WxContentCallbackInboxServiceImpl.sha256Hex(sent), row.getMediaHash());
        // 之后读帖子会重签出新 URL,活动身份仍是同一个 key。
        assertEquals(Collections.singletonList(KEY), postService.activeMediaKeys(POST_ID));

        assertTrue(wx.handleMediaCheckResult("trace-1", true));
        assertEquals(CommunityPost.LIFECYCLE_HIDDEN, posts.get(POST_ID).getLifecycle());
        assertEquals(WxContentCallbackInbox.STATE_DONE, row.getState());
    }

    /** 帖子换图(对象 key 变了)后,旧图的回调按「媒体已变」作废,不动帖子。 */
    @Test
    void oldCallbackIsRejectedAfterTheKeyChanges() {
        publish();
        media.put(POST_ID, mediaRows(NEW_KEY));

        assertTrue(wx.handleMediaCheckResult("trace-1", true));

        assertEquals(CommunityPost.LIFECYCLE_PUBLISHED, posts.get(POST_ID).getLifecycle());
        assertEquals(WxContentCallbackInbox.STATE_IGNORED_STALE,
                inbox.get(WxContentCallbackInboxServiceImpl.sha256Hex("trace-1")).getState());
        assertEquals(0, visibilityChanges.get());
        verify(im, never()).sendSystemInNestedTransaction(anyLong(), any(), anyString(), anyString());
        verify(auditTaskService, never()).submitSafetyHold(anyString(), anyLong(), anyLong(), anyString(), anyString());
    }

    /** 别人帖子的对象、或登记在别的业务对象上的同一张图,都不能改动当前帖子。 */
    @Test
    void anotherUsersObjectOrWrongBusinessObjectDoesNotAffectTheCurrentPost() {
        publish();

        // 1) 别人的对象:登记在当前帖子名下,但媒体身份是别人的 key → 当前帖子不含这张图,作废。
        registerTrace("trace-foreign-object", IWxContentSecurityService.BIZ_TYPE_COMMUNITY_POST, POST_ID, OTHER_USER_KEY);
        assertTrue(wx.handleMediaCheckResult("trace-foreign-object", true));
        assertEquals(WxContentCallbackInbox.STATE_IGNORED_STALE,
                inbox.get(WxContentCallbackInboxServiceImpl.sha256Hex("trace-foreign-object")).getState());

        // 2) 别人帖子的违规回调:只处置别人那条,当前帖子不动。
        registerTrace("trace-other-post", IWxContentSecurityService.BIZ_TYPE_COMMUNITY_POST, OTHER_POST_ID, OTHER_USER_KEY);
        assertTrue(wx.handleMediaCheckResult("trace-other-post", true));
        assertEquals(CommunityPost.LIFECYCLE_HIDDEN, posts.get(OTHER_POST_ID).getLifecycle());

        // 3) 错误业务对象:同 id、同 key,但登记成评论(bizType=10) → 不进社区帖子分支,不改当前帖子。
        registerTrace("trace-wrong-biz", IWxContentSecurityService.BIZ_TYPE_COMMENT, POST_ID, KEY);
        assertFalse(wx.handleMediaCheckResult("trace-wrong-biz", true));

        // 4) 同一 trace 不能被改登记到别的帖子上。
        assertFalse(registerTrace("trace-1", IWxContentSecurityService.BIZ_TYPE_COMMUNITY_POST, OTHER_POST_ID, KEY));

        assertEquals(CommunityPost.LIFECYCLE_PUBLISHED, posts.get(POST_ID).getLifecycle());
        assertEquals(1, visibilityChanges.get());
        verify(im, times(1)).sendSystemInNestedTransaction(eq(OTHER_AUTHOR_ID), any(), anyString(), anyString());
        verify(im, never()).sendSystemInNestedTransaction(eq(AUTHOR_ID), any(), anyString(), anyString());
    }

    /** 同一帖子两张图都违规:可见性只变一次、复核单只建一次、作者只收到一次通知。 */
    @Test
    void violatingImagesChangeVisibilityOnceAndNotifyOnce() {
        media.put(POST_ID, mediaRows(KEY, NEW_KEY));
        publish();
        assertEquals(2, fetchUrlsSentToWechat.size());

        assertTrue(wx.handleMediaCheckResult("trace-1", true));
        assertTrue(wx.handleMediaCheckResult("trace-2", true));

        assertEquals(CommunityPost.LIFECYCLE_HIDDEN, posts.get(POST_ID).getLifecycle());
        assertEquals(1, visibilityChanges.get());
        verify(auditTaskService, times(1)).submitSafetyHold(eq(AuditTask.BIZ_COMMUNITY_POST), eq(POST_ID),
                eq(AUTHOR_ID), eq("HIGH"), anyString());
        verify(im, times(1)).sendSystemInNestedTransaction(eq(AUTHOR_ID), any(), anyString(), anyString());
    }

    /** 微信重复推送同一 trace:第二次在 claim 处即判 DONE,不再进入业务 mutation。 */
    @Test
    void duplicateCallbacksCauseNoDuplicateConsequences() {
        publish();

        assertTrue(wx.handleMediaCheckResult("trace-1", true));
        assertTrue(wx.handleMediaCheckResult("trace-1", true));
        assertTrue(wx.handleMediaCheckResult("trace-1", false));

        assertEquals(CommunityPost.LIFECYCLE_HIDDEN, posts.get(POST_ID).getLifecycle());
        assertEquals(1, visibilityChanges.get());
        verify(postMapper, times(1)).insertOutbox(anyString(), eq(POST_ID), eq("POST_MEDIA_RISKY_HIDDEN"),
                anyString(), any());
        verify(auditTaskService, times(1)).submitSafetyHold(anyString(), anyLong(), anyLong(), anyString(), anyString());
        verify(im, times(1)).sendSystemInNestedTransaction(anyLong(), any(), anyString(), anyString());
    }

    /** 四参旧入口:身份即送检地址本身,行为与改动前一致(其它业务不受影响)。 */
    @Test
    void legacyFourArgumentCallerRegistersTheSubmittedUrl() {
        String legacy = "https://cdn.example/topic/cover.jpg";

        assertEquals("trace-1", wx.submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_TOPIC, 3L, legacy, "openid"));

        assertEquals(Collections.singletonList(legacy), fetchUrlsSentToWechat);
        assertEquals(WxContentCallbackInboxServiceImpl.sha256Hex(legacy),
                inbox.get(WxContentCallbackInboxServiceImpl.sha256Hex("trace-1")).getMediaHash());
    }

    private void publish() {
        CommunityPostRequests.State state = new CommunityPostRequests.State();
        state.setExpectedVersion(3);
        state.setRequestId("publish-request-1");
        postService.publish(AUTHOR_ID, null, POST_ID, state);
    }

    private boolean registerTrace(String traceId, int bizType, long bizId, String identity) {
        IWxContentCallbackInboxService registrar =
                (IWxContentCallbackInboxService) ReflectionTestUtils.getField(wx, "callbackInboxService");
        return registrar.register(traceId, bizType, bizId, identity);
    }

    private void stubPostMapper() {
        when(postMapper.countActiveMemberEnforcement(anyLong(), anyList(), any())).thenReturn(0);
        when(postMapper.countRecentPosts(anyLong(), any())).thenReturn(0);
        when(postMapper.selectCommandReceipt(anyLong(), anyString())).thenReturn(null);
        when(postMapper.insertCommandReceipt(anyLong(), anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(1);
        when(postMapper.insertOutbox(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(postMapper.insertTypedOutbox(anyString(), anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(1);
        when(postMapper.selectActiveGuideline()).thenReturn(Collections.<String, Object>singletonMap("ID", 5L));
        when(postMapper.countGuidelineAck(AUTHOR_ID, 5L)).thenReturn(1);
        when(postMapper.selectMemberWxOpenId(AUTHOR_ID)).thenReturn("openid-9");
        when(postMapper.selectReferences(anyLong())).thenReturn(Collections.emptyList());
        when(postMapper.selectMedia(anyLong())).thenReturn(Collections.emptyList());
        when(postMapper.selectMentionedMemberIds(anyLong())).thenReturn(Collections.emptyList());
        when(postMapper.selectByIdForUpdate(anyLong())).thenAnswer(i -> copy(posts.get(i.<Long>getArgument(0))));
        when(postMapper.selectById(anyLong(), anyLong())).thenAnswer(i -> copy(posts.get(i.<Long>getArgument(0))));
        when(postMapper.selectMediaForSafety(anyLong())).thenAnswer(i -> {
            List<Map<String, Object>> rows = media.get(i.<Long>getArgument(0));
            return rows == null ? Collections.emptyList() : rows;
        });
        when(postMapper.transitionCas(anyLong(), anyString(), anyString(), anyInt(), any(), any(),
                anyInt(), anyInt(), anyInt(), any())).thenAnswer(i -> {
            CommunityPost current = posts.get(i.<Long>getArgument(0));
            if (current == null || !current.getLifecycle().equals(i.getArgument(1))
                    || current.getVersion() != i.<Integer>getArgument(3).intValue()) {
                return 0;
            }
            current.setLifecycle(i.getArgument(2));
            current.setVersion(current.getVersion() + 1);
            if (CommunityPost.LIFECYCLE_HIDDEN.equals(i.getArgument(2))) visibilityChanges.incrementAndGet();
            return 1;
        });
    }

    /** 内存版 inbox 表:与 MySQL 版同口径(trace_hash 唯一、owner/fence 校验、终态不可再改)。 */
    private WxContentCallbackInboxMapper inMemoryInboxMapper() {
        WxContentCallbackInboxMapper mapper = mock(WxContentCallbackInboxMapper.class);
        when(mapper.insertIgnore(any())).thenAnswer(i -> {
            WxContentCallbackInbox row = i.getArgument(0);
            if (inbox.containsKey(row.getTraceHash())) return 0;
            inbox.put(row.getTraceHash(), row);
            return 1;
        });
        when(mapper.selectByTraceHash(anyString())).thenAnswer(i -> inbox.get(i.<String>getArgument(0)));
        when(mapper.selectOwnedForUpdate(anyString(), anyString(), anyLong())).thenAnswer(i -> owned(
                i.getArgument(0), i.getArgument(1), i.getArgument(2)));
        when(mapper.lockBusinessTarget(anyInt(), anyLong())).thenAnswer(i ->
                i.<Integer>getArgument(0) == IWxContentSecurityService.BIZ_TYPE_COMMUNITY_POST
                        && posts.containsKey(i.<Long>getArgument(1)) ? i.<Long>getArgument(1) : null);
        when(mapper.markDone(anyString(), anyString(), anyLong(), anyInt())).thenAnswer(i ->
                terminal(i.getArgument(0), i.getArgument(1), i.getArgument(2), WxContentCallbackInbox.STATE_DONE));
        when(mapper.markIgnoredStale(anyString(), anyString(), anyLong(), anyInt())).thenAnswer(i ->
                terminal(i.getArgument(0), i.getArgument(1), i.getArgument(2),
                        WxContentCallbackInbox.STATE_IGNORED_STALE));
        when(mapper.markManualReview(anyString(), anyString(), anyLong(), anyInt(), anyString())).thenAnswer(i ->
                terminal(i.getArgument(0), i.getArgument(1), i.getArgument(2),
                        WxContentCallbackInbox.STATE_MANUAL_REVIEW));
        return mapper;
    }

    private WxContentCallbackClaimService inMemoryClaims() {
        WxContentCallbackClaimService claims = mock(WxContentCallbackClaimService.class);
        when(claims.claim(anyString(), Mockito.anyBoolean())).thenAnswer(i -> {
            String traceHash = i.getArgument(0);
            WxContentCallbackInbox row = inbox.get(traceHash);
            if (row == null) return WxContentCallbackClaim.of(WxContentCallbackClaim.State.MISSING, traceHash);
            if (isTerminal(row.getState())) return WxContentCallbackClaim.done(traceHash);
            row.setState(WxContentCallbackInbox.STATE_PROCESSING);
            row.setOwnerToken("owner-" + traceHash.substring(0, 8));
            row.setFenceToken((long) fences.incrementAndGet());
            return WxContentCallbackClaim.acquired(traceHash, row.getOwnerToken(), row.getFenceToken());
        });
        when(claims.release(anyString(), anyString(), anyLong())).thenAnswer(i -> {
            WxContentCallbackInbox row = owned(i.getArgument(0), i.getArgument(1), i.getArgument(2));
            if (row == null) return false;
            row.setState(WxContentCallbackInbox.STATE_RETRYABLE);
            row.setOwnerToken(null);
            return true;
        });
        return claims;
    }

    private WxContentCallbackInbox owned(String traceHash, String owner, long fence) {
        WxContentCallbackInbox row = inbox.get(traceHash);
        return row != null && owner.equals(row.getOwnerToken()) && row.getFenceToken() != null
                && row.getFenceToken() == fence && !isTerminal(row.getState()) ? row : null;
    }

    private int terminal(String traceHash, String owner, long fence, int state) {
        WxContentCallbackInbox row = owned(traceHash, owner, fence);
        if (row == null) return 0;
        row.setState(state);
        return 1;
    }

    private static boolean isTerminal(Integer state) {
        return state != null && (state == WxContentCallbackInbox.STATE_DONE
                || state == WxContentCallbackInbox.STATE_IGNORED_STALE
                || state == WxContentCallbackInbox.STATE_MANUAL_REVIEW);
    }

    /** OSS 桩:签名每次不同;对象 key 规范化走真实实现(固定测试桶/endpoint)。 */
    private org.mockito.stubbing.Answer<Object> storageAnswer() throws NoSuchMethodException {
        Method parser = FileOssUploadUtils.class.getDeclaredMethod(
                "communityMediaObjectKey", String.class, String.class, String.class);
        parser.setAccessible(true);
        return invocation -> {
            String name = invocation.getMethod().getName();
            Object[] args = invocation.getArguments();
            if ("generateCommunityMediaPresignedUrl".equals(name)) {
                return HOST + args[0] + "?Expires=300&OSSAccessKeyId=test&Signature=sig" + signatures.incrementAndGet();
            }
            if ("communityMediaObjectKey".equals(name) && args.length == 1) {
                try {
                    return parser.invoke(null, args[0], BUCKET, ENDPOINT);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
            return invocation.callRealMethod();
        };
    }

    private static List<Map<String, Object>> mediaRows(String... keys) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String key : keys) {
            Map<String, Object> row = new HashMap<>();
            row.put("object_key", key);
            row.put("derived_object_key", key);
            rows.add(row);
        }
        return rows;
    }

    private static CommunityPost post(long id, long authorId, String lifecycle, int version) {
        CommunityPost post = new CommunityPost();
        post.setId(id);
        post.setAuthorId(authorId);
        post.setBody("正文");
        post.setAudience("PUBLIC");
        post.setCommentPolicy("EVERYONE");
        post.setLocationPrecision("NONE");
        post.setLifecycle(lifecycle);
        post.setInteractionEnabled(1);
        post.setGuidelineVersionId(5L);
        post.setVersion(version);
        return post;
    }

    private static CommunityPost copy(CommunityPost source) {
        if (source == null) return null;
        CommunityPost post = post(source.getId(), source.getAuthorId(), source.getLifecycle(), source.getVersion());
        post.setTitle(source.getTitle());
        return post;
    }
}
