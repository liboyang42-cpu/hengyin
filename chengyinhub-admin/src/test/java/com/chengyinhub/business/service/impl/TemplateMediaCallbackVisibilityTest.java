package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.WxContentCallbackClaim;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.redis.RedisCache;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CL-39 DEFECT_FOUND 证据：模板封面/勋章图(BIZ_TYPE_TEMPLATE=13)的异步机审「违规」结论无处落地。
 *
 * <p>ApiTemplateController.submitTemplateMediaCheck 在模板发布后对封面/勋章图 submitMediaCheck(13, templateId)。
 * 回调链：WxContentSecurityServiceImpl.handleMediaCheckResult → inbox.process →
 * WxContentCallbackMutationService.apply → lockBusinessTarget(13) → isCurrentSubmittedMedia → processMediaCheckResult。
 * 三处都没有 13 的分支，risky 结论既不下架模板投影，也不进人工复核状态；
 * 模板广场是否可见只取决于人工审核单(status/publish_status)，与机审违规结论无关。
 */
class TemplateMediaCallbackVisibilityTest {

    @Test
    void riskyTemplateImageCallbackHasNoLockTargetSoTheVerdictIsNeverApplied_DEFECT_CL39() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/business/WxContentCallbackInboxMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("bizType", IWxContentSecurityService.BIZ_TYPE_TEMPLATE);
        params.put("bizId", 5L);
        String sql = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.WxContentCallbackInboxMapper.lockBusinessTarget")
                .getBoundSql(params).getSql().trim();
        assertEquals("select cast(null as signed)", sql,
                "template callbacks fall into the 'target does not exist' branch");

        // 该分支返回 null → mutation 服务抛错、claim 被释放；没有 DONE、也没有 MANUAL_REVIEW 终态。
        WxContentCallbackInboxMapper mapper = mock(WxContentCallbackInboxMapper.class);
        WxContentCallbackInbox row = templateRow();
        when(mapper.selectOwnedForUpdate(anyString(), anyString(), anyLong())).thenReturn(row);
        when(mapper.lockBusinessTarget(IWxContentSecurityService.BIZ_TYPE_TEMPLATE, 5L)).thenReturn(null);
        WxContentCallbackMutationService mutationService = new WxContentCallbackMutationService();
        ReflectionTestUtils.setField(mutationService, "mapper", mapper);
        IWxContentCallbackInboxService.Mutation mutation = mock(IWxContentCallbackInboxService.Mutation.class);
        assertThrows(RuntimeException.class, () -> mutationService.apply(
                WxContentCallbackClaim.acquired("hash", "owner", 1L), true, mutation));
        verify(mutation, never()).apply(any(), anyBoolean());
        verify(mapper, never()).markDone(anyString(), anyString(), anyLong(), anyInt());
        verify(mapper, never()).markManualReview(anyString(), anyString(), anyLong(), anyInt(), anyString());
    }

    @Test
    void evenIfTheTargetWereLockedTheTemplateMutationIsStaleAndHasNoTakedown_DEFECT_CL39() {
        RedisCache redis = mock(RedisCache.class);
        IWxContentCallbackInboxService inbox = mock(IWxContentCallbackInboxService.class);
        AtomicReference<IWxContentCallbackInboxService.Mutation> captured = new AtomicReference<>();
        when(inbox.isRegistered("trace-template")).thenReturn(true);
        when(inbox.process(eq("trace-template"), eq(true), any(IWxContentCallbackInboxService.Mutation.class)))
                .thenAnswer(invocation -> {
                    captured.set(invocation.getArgument(2, IWxContentCallbackInboxService.Mutation.class));
                    return false;
                });
        WxContentSecurityServiceImpl service = new WxContentSecurityServiceImpl();
        ReflectionTestUtils.setField(service, "redisCache", redis);
        ReflectionTestUtils.setField(service, "callbackInboxService", inbox);

        assertFalse(service.handleMediaCheckResult("trace-template", true));
        IWxContentCallbackInboxService.Mutation mutation = captured.get();
        assertFalse(mutation.isCurrent(templateRow()),
                "no media-hash check for templates → would be marked IGNORED_STALE");
        assertFalse(mutation.apply(templateRow(), true), "no takedown branch for BIZ_TYPE_TEMPLATE");
        assertTrue(mutation.apply(templateRow(), false), "a pass verdict is accepted as a no-op");
    }

    private static WxContentCallbackInbox templateRow() {
        WxContentCallbackInbox row = new WxContentCallbackInbox();
        row.setBizType(IWxContentSecurityService.BIZ_TYPE_TEMPLATE);
        row.setBizId(5L);
        row.setMediaHash("hash-of-cover");
        return row;
    }
}
