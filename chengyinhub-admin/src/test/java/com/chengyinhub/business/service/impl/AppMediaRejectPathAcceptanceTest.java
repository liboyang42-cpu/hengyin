package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验收 CL-44(F-L5C-16)音频与 3D 模型的驳回路径:上传只进 HOLD 审核单,审核驳回落 REJECTED(带原因),
 * 驳回或待审的资产永远拿不到公开地址。AI 文本的驳回路径由 AiGatewayServiceImplTest 覆盖。
 */
class AppMediaRejectPathAcceptanceTest {

    private AppMediaAssetMapper mapper;
    private AppMediaAssetService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(AppMediaAssetMapper.class);
        IAuditTaskService audits = mock(IAuditTaskService.class);
        ObjectProvider<IAuditTaskService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(audits);
        service = new AppMediaAssetService(mapper, provider);
        when(mapper.insert(anyMap())).thenAnswer(a -> {
            a.<Map<String, Object>>getArgument(0).put("id", 12L);
            return 1;
        });
        when(audits.submitSafetyHold(eq("MEDIA_ASSET"), eq(12L), eq(9L), eq("NORMAL"), anyString())).thenReturn(101L);
        when(mapper.bindAuditTask(12L, 0, 101L)).thenReturn(1);
    }

    private static Map<String, Object> row(String mediaType, String state) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 12L);
        row.put("memberId", 9L);
        row.put("ownerType", "APP");
        row.put("mediaType", mediaType);
        row.put("reviewState", state);
        row.put("objectKey", "upload/0123456789abcdef0123456789abcdef.mp3");
        return row;
    }

    @Test
    void audioAndModelAreHeldThenRejectedAndNeverGetAPublicUrl() {
        for (String[] c : List.of(new String[] {"mp3", "AUDIO"}, new String[] {"glb", "MODEL3D"})) {
            Map<String, Object> staged = service.stage(9L, "upload/0123456789abcdef0123456789abcdef." + c[0]);
            assertEquals("HOLD", staged.get("reviewState"));
            assertEquals(c[1], staged.get("mediaType"));

            when(mapper.select(12L)).thenReturn(row(c[1], "HOLD"));
            assertEquals("媒体尚未审核通过", assertThrows(ServiceException.class, () -> service.approvedUrl(12L)).getMessage());

            AuditTask task = new AuditTask();
            task.setId(101L);
            task.setBizId(12L);
            task.setSnapshotJson("{\"assetId\":12,\"assetVersion\":0}");
            when(mapper.decide(12L, 0, 101L, "REJECTED", "含违规内容")).thenReturn(1);
            service.applyAudit(task, false, "含违规内容");
            verify(mapper, never()).decide(anyLong(), anyInt(), anyLong(), eq("APPROVED"), any());

            when(mapper.select(12L)).thenReturn(row(c[1], "REJECTED"));
            assertEquals("媒体尚未审核通过", assertThrows(ServiceException.class, () -> service.approvedUrl(12L)).getMessage(),
                    c[1] + " 被驳回后不得签出公开地址");
        }
        verify(mapper, org.mockito.Mockito.times(2)).decide(12L, 0, 101L, "REJECTED", "含违规内容");
    }
}
