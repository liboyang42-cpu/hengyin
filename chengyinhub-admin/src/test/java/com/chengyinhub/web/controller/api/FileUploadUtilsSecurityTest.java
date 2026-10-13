package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.exception.file.InvalidExtensionException;
import com.chengyinhub.common.exception.file.FileSizeLimitExceededException;
import com.chengyinhub.common.utils.file.FileUploadUtils;
import com.chengyinhub.common.utils.file.MimeTypeUtils;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileUploadUtilsSecurityTest
{
    @Test
    void wavRequiresAudioMimeAndRiffWaveHeader() throws Exception
    {
        byte[] wav = new byte[] {'R','I','F','F',36,0,0,0,'W','A','V','E','f','m','t',' '};
        assertDoesNotThrow(() -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "voice.wav", "audio/wav", wav), AppUploadService.OSS_EXTENSIONS));
        assertThrows(InvalidExtensionException.class, () -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "voice.wav", "text/html", wav), AppUploadService.OSS_EXTENSIONS));
        assertThrows(InvalidExtensionException.class, () -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "voice.wav", "audio/wav", "RIFFxxxxWEBPxxxx".getBytes()), AppUploadService.OSS_EXTENSIONS));
        assertThrows(InvalidExtensionException.class, () -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "voice.wav", "audio/wav", "<script>alert(1)</script>".getBytes()), AppUploadService.OSS_EXTENSIONS));
    }

    /** 显形档 AR 的 3D 模型(2026-09-23):只收 glTF 2.0 二进制 —— 头 4 字节 "glTF" + 小端版本号 2。 */
    @Test
    void glbRequiresGltfBinaryHeaderVersion2()
    {
        byte[] glb = new byte[] {'g','l','T','F', 2,0,0,0, 20,0,0,0, 0,0,0,0};
        assertDoesNotThrow(() -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "cat.glb", "model/gltf-binary", glb), AppUploadService.OSS_EXTENSIONS));
        // 微信 wx.uploadFile 传不认识的类型时报 application/octet-stream
        assertDoesNotThrow(() -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "cat.glb", "application/octet-stream", glb), AppUploadService.OSS_EXTENSIONS));
        assertThrows(InvalidExtensionException.class, () -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "cat.glb", "text/html", glb), AppUploadService.OSS_EXTENSIONS));
        byte[] v1 = new byte[] {'g','l','T','F', 1,0,0,0, 20,0,0,0, 0,0,0,0};
        assertThrows(InvalidExtensionException.class, () -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "cat.glb", "model/gltf-binary", v1), AppUploadService.OSS_EXTENSIONS));
        assertThrows(InvalidExtensionException.class, () -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "cat.glb", "model/gltf-binary", "glTX\2\0\0\0xxxxxxxx".getBytes()), AppUploadService.OSS_EXTENSIONS));
        assertThrows(InvalidExtensionException.class, () -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "cat.glb", "application/octet-stream", "<script>alert(1)</script>".getBytes()), AppUploadService.OSS_EXTENSIONS));
        assertThrows(InvalidExtensionException.class, () -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "cat.html.glb", "model/gltf-binary", glb), AppUploadService.OSS_EXTENSIONS));
    }

    @Test
    void strictValidationRejectsActiveHtmlFromLegacyWhitelist()
    {
        MockMultipartFile html = new MockMultipartFile("file", "attack.html", "text/html",
                "<script>alert(1)</script>".getBytes());

        assertThrows(InvalidExtensionException.class,
                () -> FileUploadUtils.assertAllowedStrict(html, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION));
    }

    @Test
    void rejectsSvgJavaScriptAndDoubleExtensionPayloadNames()
    {
        assertThrows(InvalidExtensionException.class, () -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "icon.svg", "image/svg+xml", "<svg/>".getBytes()),
                MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION));
        assertThrows(InvalidExtensionException.class, () -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "app.js", "application/javascript", "alert(1)".getBytes()),
                MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION));
        assertThrows(InvalidExtensionException.class, () -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "cover.png.html", "text/html", "<html/>".getBytes()),
                MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION));
        assertThrows(InvalidExtensionException.class, () -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "cover.html.jpg", "image/jpeg", jpegHeader()),
                MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION));
    }

    @Test
    void rejectsImageExtensionWhenDeclaredMimeTypeIsActiveContent()
    {
        MockMultipartFile disguised = new MockMultipartFile("file", "cover.png", "text/html",
                new byte[] { (byte) 0x89, 'P', 'N', 'G' });

        assertThrows(InvalidExtensionException.class,
                () -> FileUploadUtils.assertAllowedStrict(disguised, MimeTypeUtils.IMAGE_EXTENSION));
    }

    @Test
    void rejectsHtmlBytesDisguisedAsPdf()
    {
        MockMultipartFile disguised = new MockMultipartFile("file", "notice.pdf", "application/pdf",
                "<html><script>alert(1)</script></html>".getBytes());

        assertThrows(InvalidExtensionException.class,
                () -> FileUploadUtils.assertAllowedStrict(disguised, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION));
    }

    @Test
    void preservesPngJpegAndAudioMimeCompatibility() throws Exception
    {
        assertDoesNotThrow(() -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "cover.png", "image/png", image("png")), MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION));
        assertDoesNotThrow(() -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "cover.jpg", "image/jpeg", image("jpg")), MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION));
        assertDoesNotThrow(() -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "narration.mp3", "audio/mpeg", "ID3audio".getBytes()), new String[] { "mp3" }));
        assertDoesNotThrow(() -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "narration.m4a", "audio/mp4", m4aHeader()), new String[] { "m4a" }));
        assertDoesNotThrow(() -> FileUploadUtils.assertAllowedStrict(
                new MockMultipartFile("file", "narration.aac", "audio/aac", aacHeader()), new String[] { "aac" }));
    }

    @Test
    void rejectsOversizedUploadBeforeReadingItsBody()
    {
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.getSize()).thenReturn(FileUploadUtils.DEFAULT_MAX_SIZE + 1);

        assertThrows(FileSizeLimitExceededException.class,
                () -> FileUploadUtils.assertAllowed(oversized, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION));
    }

    @Test
    void defaultUploadValidationKeepsLegacyDocumentCompatibility()
    {
        MockMultipartFile document = new MockMultipartFile("file", "legacy.docx", "application/octet-stream",
                "legacy document body".getBytes());

        assertDoesNotThrow(() -> FileUploadUtils.assertAllowed(document, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION));
        assertThrows(InvalidExtensionException.class,
                () -> FileUploadUtils.assertAllowedStrict(document, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION));
    }

    @Test
    void defaultUploadValidationKeepsLegacyHtmlCompatibility()
    {
        MockMultipartFile html = new MockMultipartFile("file", "legacy.html", "text/html",
                "<html><body>legacy content</body></html>".getBytes());

        assertDoesNotThrow(() -> FileUploadUtils.assertAllowed(html, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION));
        assertThrows(InvalidExtensionException.class,
                () -> FileUploadUtils.assertAllowedStrict(html, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION));
    }

    private byte[] image(String format) throws Exception
    {
        BufferedImage image = new BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private byte[] jpegHeader()
    {
        return new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
    }

    private byte[] m4aHeader()
    {
        return new byte[] { 0, 0, 0, 16, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ' };
    }

    private byte[] aacHeader()
    {
        return new byte[] { (byte) 0xFF, (byte) 0xF1 };
    }

}
