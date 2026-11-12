package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppMediaMachineReviewDispatchTest {
    @Test void audioDispatchWaitsForCommittedReceiptAndKeepsStableVersionIdentity() {
        AppMediaAssetMapper mapper = mock(AppMediaAssetMapper.class);
        IAuditTaskService audits = mock(IAuditTaskService.class);
        ObjectProvider<IAuditTaskService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(audits);
        AppMediaAssetService service = new AppMediaAssetService(mapper, provider);
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember(); member.setWxOpenId("verified-audio-author");
        when(members.selectUmsMemberById(9L)).thenReturn(member);
        when(security.canSubmitMedia("verified-audio-author")).thenReturn(true);
        String key = "upload/0123456789abcdef0123456789abcdef.mp3";
        when(mapper.insert(anyMap())).thenAnswer(call -> {
            call.<Map<String,Object>>getArgument(0).put("id", 12L); return 1;
        });
        when(mapper.select(12L)).thenReturn(Map.of("objectKey", key, "version", 0, "mediaType", "AUDIO"));
        when(audits.submitSafetyHold(eq("MEDIA_ASSET"), eq(12L), eq(9L), eq("NORMAL"), anyString())).thenReturn(101L);
        when(mapper.bindAuditTask(12L, 0, 101L)).thenReturn(1);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(
                new DriverManagerDataSource("jdbc:h2:mem:dec_c_audio_dispatch", "sa", ""));
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        ReflectionTestUtils.setField(service, "textSecurity", security);
        ReflectionTestUtils.setField(service, "members", members);
        try (var signing = mockStatic(FileOssUploadUtils.class)) {
            signing.when(() -> FileOssUploadUtils.generateShortLivedPresignedUrl(key, 3600))
                    .thenReturn("https://scanner.invalid/audio-fetch");
            new TransactionTemplate(transactions).execute(status -> {
                Map<String,Object> staged = service.stage(9L, key);
                assertEquals("HOLD", staged.get("reviewState"));
                assertFalse(staged.containsKey("url"));
                verifyNoInteractions(security);
                return null;
            });
        }
        verify(security).submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_MEDIA_ASSET, 12L,
                "https://scanner.invalid/audio-fetch", key + "#v=0", "verified-audio-author");
        verify(mapper).bindAuditTask(12L, 0, 101L);
    }
}
