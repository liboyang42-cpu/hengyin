package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ISensitiveContentLibrary;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Round5D:已知违规图在落库前就被拒,不依赖微信;存储与微信检查都不会被调用。 */
class AppUploadKnownBadImageTest {

    @Test
    void knownBadImageIsRefusedBeforeStorage() {
        AtomicBoolean stored = new AtomicBoolean();
        AtomicBoolean wechat = new AtomicBoolean();
        AppUploadService service = new AppUploadService(file -> {
            stored.set(true);
            return null;
        }, file -> wechat.set(true));
        ISensitiveContentLibrary lib = mock(ISensitiveContentLibrary.class);
        when(lib.matchImage(any())).thenReturn("PORN");
        ReflectionTestUtils.setField(service, "sensitiveLibrary", lib);

        UploadRejectedException e = assertThrows(UploadRejectedException.class,
                () -> service.uploadOss(new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3}), "image"));

        assertEquals(ISensitiveContentLibrary.IMAGE_VIOLATION_MESSAGE, e.getMessage());
        assertFalse(stored.get());
        assertFalse(wechat.get());
        verify(lib).recordHit("IMAGE", "UPLOAD", null, "PORN", "BLOCK");
    }
}
