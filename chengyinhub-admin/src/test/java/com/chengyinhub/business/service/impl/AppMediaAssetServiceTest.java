package com.chengyinhub.business.service.impl;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class AppMediaAssetServiceTest {
    AppMediaAssetMapper mapper;
    IAuditTaskService audits;
    AppMediaAssetService service;
    @BeforeEach void setup() {
        mapper=mock(AppMediaAssetMapper.class);audits=mock(IAuditTaskService.class);
        ObjectProvider<IAuditTaskService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(audits);
        service=new AppMediaAssetService(mapper,provider);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"publicBaseUrl","https://server.invalid/prod-api");
        when(mapper.insert(anyMap())).thenAnswer(a->{a.<Map<String,Object>>getArgument(0).put("id",12L);return 1;});
        when(mapper.insertApprovedImage(anyMap())).thenAnswer(a->{a.<Map<String,Object>>getArgument(0).put("id",13L);return 1;});
        when(audits.submitSafetyHold(eq("MEDIA_ASSET"),eq(12L),eq(9L),eq("NORMAL"),anyString())).thenReturn(101L);
        when(mapper.bindAuditTask(12L,0,101L)).thenReturn(1);
    }
    @Test void audioOrModelHoldCreatesRealAuditBindingAndNeverReturnsPublicationUrl() {
        for(String ext:java.util.List.of("mp3","glb")) {
            Map<String,Object> status=service.stage(9L,"upload/0123456789abcdef0123456789abcdef."+ext);
            assertEquals("HOLD",status.get("reviewState"));assertEquals(12L,status.get("assetId"));
            assertFalse(status.containsKey("url"));assertFalse(status.containsKey("objectKey"));
        }
        verify(audits,times(2)).submitSafetyHold(eq("MEDIA_ASSET"),eq(12L),eq(9L),eq("NORMAL"),argThat(s->s.contains("\"assetVersion\":0")&&!s.contains("url")));
    }
    @Test void failedBindAndForeignOwnershipNeverBecomeReadableOrPublic() {
        when(mapper.bindAuditTask(12L,0,101L)).thenReturn(0);
        assertThrows(ServiceException.class,()->service.stage(9L,"upload/0123456789abcdef0123456789abcdef.mp3"));
        Map<String,Object> row=new LinkedHashMap<>();row.put("memberId",9L);row.put("ownerType","APP");row.put("reviewState","HOLD");
        when(mapper.select(12L)).thenReturn(row);when(mapper.selectForUpdate(12L)).thenReturn(row);
        assertThrows(ServiceException.class,()->service.ownerPreview(12L,10L));
        assertThrows(ServiceException.class,()->service.status(12L,10L));
        assertThrows(ServiceException.class,()->service.retry(12L,10L));
        assertThrows(ServiceException.class,()->service.approvedUrl(12L));
    }
    @Test void systemAndAppNumericIdentityNeverShareAuthorPreview() {
        Map<String,Object> row=new LinkedHashMap<>();row.put("memberId",9L);row.put("ownerType","SYSTEM");row.put("reviewState","HOLD");
        when(mapper.select(12L)).thenReturn(row);
        assertThrows(ServiceException.class,()->service.ownerPreview(12L,9L));
        assertThrows(ServiceException.class,()->service.status(12L,9L));
    }
    @Test void onlyCurrentAuditTaskAndAssetVersionCanPublish() {
        AuditTask task=new AuditTask();task.setId(101L);task.setBizId(12L);task.setSnapshotJson("{\"assetId\":12,\"assetVersion\":0}");
        when(mapper.decide(12L,0,101L,"APPROVED",null)).thenReturn(1);
        assertDoesNotThrow(()->service.applyAudit(task,true,null));
        task.setSnapshotJson("{\"assetVersion\":1}");
        assertThrows(ServiceException.class,()->service.applyAudit(task,true,null));
        task.setSnapshotJson("{}");
        assertThrows(ServiceException.class,()->service.applyAudit(task,true,null));
        verify(mapper,never()).decide(eq(12L),eq(0),eq(101L),eq("REJECTED"),any());
    }
    @Test void systemReceiptRequiresRandomActualImageKeyAndMatchingServerUrl() {
        when(mapper.insertApprovedSystemImage(anyMap())).thenReturn(1);
        String key="/profile/upload/2026/10/12/0123456789abcdef0123456789abcdef.png";
        assertEquals("https://server.invalid/prod-api"+key,service.registerApprovedSystemImage(99L,key,"https://spoofed.invalid/prod-api"+key));
        assertThrows(ServiceException.class,()->service.registerApprovedSystemImage(99L,key,"httpx://server.invalid"+key));
        assertThrows(ServiceException.class,()->service.registerApprovedSystemImage(99L,key,"https://user@server.invalid"+key));
        assertThrows(ServiceException.class,()->service.registerApprovedSystemImage(99L,key,"https://server.invalid/other.png"));
        assertThrows(ServiceException.class,()->service.registerApprovedSystemImage(99L,"/profile/upload/2026/10/12/../other.png","https://server.invalid/other.png"));
        verify(mapper,times(1)).insertApprovedSystemImage(anyMap());
    }
    @Test void localSigningUsesOnlyExplicitConfiguredOriginAndFailsClosedWhenMissing() {
        Map<String,Object> row=new LinkedHashMap<>();String key="/profile/upload/2026/10/12/0123456789abcdef0123456789abcdef.png";
        row.put("reviewState","APPROVED");row.put("storageType","LOCAL");row.put("objectKey",key);row.put("publicUrl","https://spoofed.invalid"+key);
        when(mapper.select(14L)).thenReturn(row);
        assertEquals("https://server.invalid/prod-api"+key,service.approvedUrl(14L));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"publicBaseUrl","");
        assertThrows(ServiceException.class,()->service.approvedUrl(14L));
        assertThrows(ServiceException.class,()->service.registerApprovedSystemImage(99L,key,"https://server.invalid"+key));
        verify(mapper,never()).insertApprovedSystemImage(anyMap());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"publicBaseUrl","https://user@server.invalid/prod-api");
        assertThrows(ServiceException.class,()->service.requireLocalPublicationOrigin());
    }
    @Test void approvedImageReceiptAcceptsOnlyOwnedRandomImageKeyAndSkipsManualQueue() {
        Map<String,Object> status=service.stageApprovedImage(9L,"upload/0123456789abcdef0123456789abcdef.jpg");
        assertEquals("APPROVED",status.get("reviewState"));assertEquals(13L,status.get("assetId"));
        assertThrows(ServiceException.class,()->service.stageApprovedImage(9L,"https://external.invalid/image.jpg"));
        assertThrows(ServiceException.class,()->service.stageApprovedImage(9L,"upload/0123456789abcdef0123456789abcdef.mp3"));
        verifyNoInteractions(audits);
    }
    @Test void ownedCursorPageKeepsExactOwnerAndLongIdentityAndRejectsInvalidCursor() {
        Map<String,Object> row=new LinkedHashMap<>();row.put("id",9007199254740993L);row.put("reviewState","REJECTED");
        row.put("mediaType","AUDIO");row.put("objectKey","private-key");row.put("publicUrl","unsafe");
        when(mapper.selectOwnedBefore(9L,9007199254740994L)).thenReturn(java.util.List.of(row));
        Map<String,Object> result=service.ownedRecent(9L,9007199254740994L).get(0);
        assertEquals("9007199254740993",result.get("assetId"));assertFalse(result.containsKey("objectKey"));assertFalse(result.containsKey("publicUrl"));
        assertThrows(ServiceException.class,()->service.ownedRecent(9L,0L));
        assertThrows(ServiceException.class,()->service.ownedRecent(0L,9007199254740994L));
        verify(mapper).selectOwnedBefore(9L,9007199254740994L);
        verify(mapper,never()).selectOwnedBefore(eq(0L),anyLong());
    }
}
