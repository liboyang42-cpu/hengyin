package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.framework.interceptor.AppMediaVisibilityInterceptor;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppMediaVisibilityInterceptorTest {
    @Test void directPublicBytesBecomeUnavailableAfterLaterViolation() {
        AppMediaAssetMapper mapper=mock(AppMediaAssetMapper.class);
        String key="/profile/upload/media.jpg";
        when(mapper.selectByObjectKey(key)).thenReturn(Map.of("reviewState","APPROVED"))
                .thenReturn(Map.of("reviewState","REJECTED"));
        AppMediaVisibilityInterceptor interceptor=new AppMediaVisibilityInterceptor(mapper);
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/prod-api"+key);
        request.setContextPath("/prod-api");
        MockHttpServletResponse first=new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request,first,new Object()));
        assertEquals("no-store",first.getHeader("Cache-Control"));
        MockHttpServletResponse later=new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request,later,new Object()));
        assertEquals(404,later.getStatus());
    }

    @Test void downloadAliasAndEncodedStaticPathCannotBypassHold() {
        AppMediaAssetMapper mapper=mock(AppMediaAssetMapper.class);
        when(mapper.selectByObjectKey("/profile/upload/media.jpg")).thenReturn(Map.of("reviewState","HOLD"));
        AppMediaVisibilityInterceptor interceptor=new AppMediaVisibilityInterceptor(mapper);
        MockHttpServletRequest download=new MockHttpServletRequest("GET","/common/download/resource");
        download.setParameter("resource","/profile/upload/media.jpg");
        assertFalse(interceptor.preHandle(download,new MockHttpServletResponse(),new Object()));
        download.setParameter("resource","https://ignored.invalid/profile/upload/media.jpg");
        assertFalse(interceptor.preHandle(download,new MockHttpServletResponse(),new Object()));
        MockHttpServletRequest encoded=new MockHttpServletRequest("GET","/profile/upload/%6dedia.jpg");
        assertFalse(interceptor.preHandle(encoded,new MockHttpServletResponse(),new Object()));
    }

    @Test void unregisteredLegacySystemFilesAreNotReclassifiedAsUserUploads() {
        AppMediaAssetMapper mapper=mock(AppMediaAssetMapper.class);
        assertTrue(new AppMediaVisibilityInterceptor(mapper).preHandle(
                new MockHttpServletRequest("GET","/profile/static/logo.png"),new MockHttpServletResponse(),new Object()));
    }
}
