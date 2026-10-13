package com.chengyinhub.web.controller.common;

import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.framework.config.ServerConfig;
import com.chengyinhub.web.controller.api.AdminFileUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommonControllerLocalMediaOriginTest {
    final String key="/profile/upload/2026/10/12/0123456789abcdef0123456789abcdef.png";
    @Test void uploadReceiptUsesActualRegisteredOriginRatherThanRequestHost() {
        AdminFileUploadService uploads=mock(AdminFileUploadService.class);AppMediaAssetService media=mock(AppMediaAssetService.class);
        ServerConfig host=mock(ServerConfig.class);when(host.getUrl()).thenReturn("https://spoofed.invalid");
        when(uploads.uploadLocal(anyString(),any())).thenReturn(key);
        when(media.registerApprovedSystemImage(99L,key,"https://spoofed.invalid"+key)).thenReturn("https://configured.invalid/prod-api"+key);
        CommonController controller=controller(uploads,media,host);
        assertEquals("https://configured.invalid/prod-api"+key,controller.uploadFile(new MockMultipartFile("file","image.png","image/png",new byte[]{1})).getBody().get("url"));
        verify(media).requireLocalPublicationOrigin();verify(media).registerApprovedSystemImage(99L,key,"https://spoofed.invalid"+key);
    }
    @Test void missingCanonicalOriginRejectsBeforeStorageOrImageProviderCall() {
        AdminFileUploadService uploads=mock(AdminFileUploadService.class);AppMediaAssetService media=mock(AppMediaAssetService.class);
        doThrow(new ServiceException("图片发布地址未配置")).when(media).requireLocalPublicationOrigin();
        CommonController controller=controller(uploads,media,mock(ServerConfig.class));
        org.springframework.http.ResponseEntity<com.chengyinhub.common.core.domain.AjaxResult> response=controller.uploadFile(new MockMultipartFile("file","image.png","image/png",new byte[]{1}));
        assertEquals(503,response.getStatusCode().value());verifyNoInteractions(uploads);
        verify(media,never()).registerApprovedSystemImage(anyLong(),anyString(),anyString());
    }
    CommonController controller(AdminFileUploadService uploads,AppMediaAssetService media,ServerConfig server) {
        CommonController controller=new CommonController(){@Override protected Long currentSystemUserId(){return 99L;}};
        ReflectionTestUtils.setField(controller,"adminFileUploadService",uploads);ReflectionTestUtils.setField(controller,"appMediaAssetService",media);
        ReflectionTestUtils.setField(controller,"serverConfig",server);return controller;
    }
}
