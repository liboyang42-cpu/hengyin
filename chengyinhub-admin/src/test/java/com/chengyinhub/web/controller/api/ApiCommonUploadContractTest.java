package com.chengyinhub.web.controller.api;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.core.domain.AjaxResult;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiCommonUploadContractTest
{
    @Test
    void ossUploadRejectsActiveContentBeforeStorageAndAcceptsValidatedGif() throws Exception
    {
        RecordingOssController controller = new RecordingOssController();

        ResponseEntity<AjaxResult> activeContent = controller.uploadOSS(
                new MockMultipartFile("file", "payload.html", "text/html", "<script>alert(1)</script>".getBytes()), null, null);

        assertEquals(HttpStatus.BAD_REQUEST, activeContent.getStatusCode());
        assertEquals(400, activeContent.getBody().get(AjaxResult.CODE_TAG));
        assertFalse(controller.uploaded);

        ResponseEntity<AjaxResult> gif = controller.uploadOSS(gif("banner.gif"), "image_16_9", null);

        assertEquals(HttpStatus.OK, gif.getStatusCode());
        assertTrue(controller.uploaded);
    }

    private MockMultipartFile gif(String name) throws Exception
    {
        BufferedImage image = new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "gif", out);
        return new MockMultipartFile("file", name, "image/gif", out.toByteArray());
    }

    private static final class RecordingOssController extends ApiCommonController
    {
        private boolean uploaded;

        private RecordingOssController()
        {
            org.springframework.test.util.ReflectionTestUtils.setField(this, "appUploadService",
                    new AppUploadService(this::store, file -> { }));
        }

        private JSONObject store(MultipartFile file)
        {
            uploaded = true;
            JSONObject result = new JSONObject();
            result.put("fileName", "upload/test.gif");
            result.put("url", "https://example.test/upload/test.gif");
            return result;
        }
    }
}
