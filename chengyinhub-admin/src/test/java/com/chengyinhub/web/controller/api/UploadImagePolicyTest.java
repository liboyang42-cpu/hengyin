package com.chengyinhub.web.controller.api;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadImagePolicyTest
{
    @Test
    void rejectsFakeImageContentEvenWithoutBusinessRatio() throws Exception
    {
        MockMultipartFile fake = new MockMultipartFile("file", "fake.png", "image/png", "not-an-image".getBytes("UTF-8"));

        assertThrows(IllegalArgumentException.class, () -> UploadImagePolicy.validate(fake, null));
    }

    @Test
    void rejectsPngBytesDisguisedAsJpeg() throws Exception
    {
        MockMultipartFile disguised = png("cover.jpg", 1600, 900);

        assertThrows(IllegalArgumentException.class, () -> UploadImagePolicy.validate(disguised, null));
    }

    @Test
    void rejectsPngWithoutPixelData() throws Exception
    {
        MockMultipartFile truncated = new MockMultipartFile("file", "cover.png", "image/png", pngHeaderWithoutPixelData(1600, 900));

        assertThrows(IllegalArgumentException.class, () -> UploadImagePolicy.validate(truncated, null));
    }

    @Test
    void validatesRealGifAndRejectsTruncatedOrOversizedGif() throws Exception
    {
        MockMultipartFile valid = gif("banner.gif", 160, 90);
        MockMultipartFile truncated = new MockMultipartFile("file", "banner.gif", "image/gif",
                "GIF89a".getBytes("UTF-8"));
        MockMultipartFile oversized = new MockMultipartFile("file", "banner.gif", "image/gif", oversizedGif());

        assertDoesNotThrow(() -> UploadImagePolicy.validate(valid, "image_16_9"));
        assertThrows(IllegalArgumentException.class, () -> UploadImagePolicy.validate(truncated, null));
        assertThrows(IllegalArgumentException.class, () -> UploadImagePolicy.validate(oversized, null));
    }

    @Test
    void rejectsGifWithOversizedLogicalScreen() throws Exception
    {
        byte[] bytes = gif("banner.gif", 1, 1).getBytes();
        bytes[6] = (byte) 0xFF;
        bytes[7] = (byte) 0xFF;
        bytes[8] = (byte) 0xFF;
        bytes[9] = (byte) 0xFF;

        assertThrows(IllegalArgumentException.class,
                () -> UploadImagePolicy.validate(new MockMultipartFile("file", "banner.gif", "image/gif", bytes), null));
    }

    @Test
    void rejectsImagesMissingRequiredTrailer() throws Exception
    {
        byte[] jpeg = jpeg(16, 9);
        byte[] png = png("cover.png", 16, 9).getBytes();
        byte[] gif = gif("banner.gif", 16, 9).getBytes();

        assertThrows(IllegalArgumentException.class, () -> UploadImagePolicy.validate(
                new MockMultipartFile("file", "cover.jpg", "image/jpeg", java.util.Arrays.copyOf(jpeg, jpeg.length - 2)), null));
        assertThrows(IllegalArgumentException.class, () -> UploadImagePolicy.validate(
                new MockMultipartFile("file", "cover.png", "image/png", java.util.Arrays.copyOf(png, png.length - 12)), null));
        assertThrows(IllegalArgumentException.class, () -> UploadImagePolicy.validate(
                new MockMultipartFile("file", "banner.gif", "image/gif", java.util.Arrays.copyOf(gif, gif.length - 1)), null));
    }

    @Test
    void rejectsImagesWithTrailerReattachedAfterPayloadTruncation() throws Exception
    {
        byte[] jpeg = jpeg(64, 64);
        byte[] png = png("cover.png", 64, 64).getBytes();
        byte[] gif = gif("banner.gif", 64, 64).getBytes();

        assertThrows(IllegalArgumentException.class, () -> UploadImagePolicy.validate(
                new MockMultipartFile("file", "cover.jpg", "image/jpeg", reattachTrailer(jpeg, 2)), null));
        assertThrows(IllegalArgumentException.class, () -> UploadImagePolicy.validate(
                new MockMultipartFile("file", "cover.png", "image/png", reattachTrailer(png, 12)), null));
        assertThrows(IllegalArgumentException.class, () -> UploadImagePolicy.validate(
                new MockMultipartFile("file", "banner.gif", "image/gif", reattachTrailer(gif, 1)), null));
    }

    @Test
    void closesUploadStreamsOnSuccessfulAndRejectedImagePaths() throws Exception
    {
        CloseTrackingMultipartFile valid = tracked("cover.png", "image/png", png("cover.png", 16, 9).getBytes());
        CloseTrackingMultipartFile invalid = tracked("cover.png", "image/png", "not-an-image".getBytes("UTF-8"));
        CloseTrackingMultipartFile stamp = tracked("stamp.jpg", "image/jpeg", jpeg(16, 9));

        UploadImagePolicy.validate(valid, null);
        assertThrows(IllegalArgumentException.class, () -> UploadImagePolicy.validate(invalid, null));
        UploadImagePolicy.sanitizeMetadata(stamp, "stamp");

        assertTrue(valid.inputClosed);
        assertTrue(invalid.inputClosed);
        assertTrue(stamp.inputClosed);
    }

    @Test
    void validatesProtectedBusinessRatio() throws Exception
    {
        MockMultipartFile landscape = png("cover.png", 1600, 900);
        MockMultipartFile square = png("cover.png", 900, 900);

        assertDoesNotThrow(() -> UploadImagePolicy.validate(landscape, "image_16_9"));
        assertThrows(IllegalArgumentException.class, () -> UploadImagePolicy.validate(square, "image_16_9"));
    }

    /** G-3/M-10:商家店铺封面按 5:3 裁剪上传,不能再报「不支持的图片业务类型」。 */
    @Test
    void merchantCoverRatioFiveToThreeIsSupported() throws Exception
    {
        assertDoesNotThrow(() -> UploadImagePolicy.validate(png("cover.png", 1500, 900), "image_5_3"));
        assertThrows(IllegalArgumentException.class,
                () -> UploadImagePolicy.validate(png("cover.png", 1600, 900), "image_5_3"));
    }

    @Test
    void freeAndStampImagesStillRequireRealImageContent() throws Exception
    {
        MockMultipartFile portrait = png("stamp.png", 900, 1200);

        assertDoesNotThrow(() -> UploadImagePolicy.validate(portrait, "image_free"));
        assertDoesNotThrow(() -> UploadImagePolicy.validate(portrait, "COMMUNITY_POST"));
        assertDoesNotThrow(() -> UploadImagePolicy.validate(portrait, "stamp"));
        assertDoesNotThrow(() -> UploadImagePolicy.validate(portrait, "merchant_aftercare_evidence"));
    }

    @Test
    void rejectsUnknownBusinessType() throws Exception
    {
        assertThrows(IllegalArgumentException.class,
                () -> UploadImagePolicy.validate(png("cover.png", 1600, 900), "cover_whatever"));
    }

    @Test
    void stampUploadIsReencodedWithoutExifOrGpsMetadata() throws Exception
    {
        byte[] jpegWithExif = insertExif(jpeg(900, 1200), "Exif\0\0GPSLatitude=31.2");
        MockMultipartFile source = new MockMultipartFile("file", "stamp.jpg", "image/jpeg", jpegWithExif);

        byte[] sanitized = UploadImagePolicy.sanitizeMetadata(source, "stamp").getBytes();

        assertFalse(containsAscii(sanitized, "Exif"));
        assertFalse(containsAscii(sanitized, "GPSLatitude"));
        assertDoesNotThrow(() -> UploadImagePolicy.validate(
                new MockMultipartFile("file", "stamp.jpg", "image/jpeg", sanitized), "stamp"));
    }

    @Test
    void metadataSanitizationRewritesNonStampImagesToo() throws Exception
    {
        byte[] sourceBytes = insertExif(jpeg(1600, 900), "Exif\0\0GPSLatitude=31.2");
        MockMultipartFile source = new MockMultipartFile("file", "cover.jpg", "image/jpeg", sourceBytes);
        org.springframework.web.multipart.MultipartFile sanitized = UploadImagePolicy.sanitizeMetadata(source, "image_free");

        assertNotSame(source, sanitized);
        assertFalse(java.util.Arrays.equals(sourceBytes, sanitized.getBytes()));
        assertFalse(containsAscii(sanitized.getBytes(), "Exif"));
        assertFalse(containsAscii(sanitized.getBytes(), "GPSLatitude"));
    }

    @Test
    void animatedGifSanitizationKeepsFramesDelayAndLoopWhileRemovingComments() throws Exception
    {
        byte[] sourceBytes = animatedGif(190, 7);
        MockMultipartFile source = new MockMultipartFile("file", "animated.gif", "image/gif", sourceBytes);

        UploadImagePolicy.validate(source, null);
        byte[] sanitized = UploadImagePolicy.sanitizeMetadata(source, null).getBytes();

        assertFalse(containsAscii(sanitized, "metadata-comment"));
        assertEquals(190, gifFrameCount(sanitized));
        assertEquals(7, gifDelay(sanitized, 0));
        assertTrue(hasNetscapeLoop(sanitized));
    }

    @Test
    void animatedGifSanitizationCanonicalizesNetscapeLoopPayloadWithoutSuffix() throws Exception
    {
        byte[] suffix = "globalThis.PWN=1".getBytes("ISO-8859-1");
        byte[] maliciousLoopPayload = new byte[3 + suffix.length];
        maliciousLoopPayload[0] = 1;
        maliciousLoopPayload[1] = 42;
        System.arraycopy(suffix, 0, maliciousLoopPayload, 3, suffix.length);
        byte[] sourceBytes = animatedGif(190, 7, maliciousLoopPayload);
        MockMultipartFile source = new MockMultipartFile("file", "animated.gif", "image/gif", sourceBytes);

        UploadImagePolicy.validate(source, null);
        byte[] sanitized = UploadImagePolicy.sanitizeMetadata(source, null).getBytes();

        assertTrue(containsAscii(sourceBytes, "globalThis.PWN=1"));
        assertFalse(containsAscii(sanitized, "globalThis.PWN=1"));
        assertEquals(190, gifFrameCount(sanitized));
        assertEquals(7, gifDelay(sanitized, 0));
        assertEquals(7, gifDelay(sanitized, 189));
        assertEquals(42, netscapeLoopCount(sanitized));
        assertEquals(3, netscapeLoopPayload(sanitized).length);
    }

    private MockMultipartFile png(String name, int width, int height) throws Exception
    {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", name, "image/png", out.toByteArray());
    }

    private byte[] jpeg(int width, int height) throws Exception
    {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private MockMultipartFile gif(String name, int width, int height) throws Exception
    {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "gif", out);
        return new MockMultipartFile("file", name, "image/gif", out.toByteArray());
    }

    private byte[] animatedGif(int frames, int delay) throws Exception
    {
        return animatedGif(frames, delay, new byte[] { 1, 0, 0 });
    }

    private byte[] animatedGif(int frames, int delay, byte[] loopPayload) throws Exception
    {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(out))
        {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            for (int index = 0; index < frames; index++)
            {
                BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
                image.setRGB(0, 0, index);
                IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),
                        writer.getDefaultWriteParam());
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(metadata.getNativeMetadataFormatName());
                IIOMetadataNode control = child(root, "GraphicControlExtension");
                control.setAttribute("delayTime", String.valueOf(delay));
                if (index == 0)
                {
                    IIOMetadataNode comments = new IIOMetadataNode("CommentExtensions");
                    IIOMetadataNode comment = new IIOMetadataNode("CommentExtension");
                    comment.setAttribute("value", "metadata-comment");
                    comments.appendChild(comment);
                    root.appendChild(comments);
                    IIOMetadataNode applications = new IIOMetadataNode("ApplicationExtensions");
                    IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
                    loop.setAttribute("applicationID", "NETSCAPE");
                    loop.setAttribute("authenticationCode", "2.0");
                    loop.setUserObject(loopPayload);
                    applications.appendChild(loop);
                    root.appendChild(applications);
                }
                metadata.setFromTree(metadata.getNativeMetadataFormatName(), root);
                writer.writeToSequence(new IIOImage(image, null, metadata), null);
            }
            writer.endWriteSequence();
        }
        finally
        {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private IIOMetadataNode child(IIOMetadataNode root, String name)
    {
        for (org.w3c.dom.Node node = root.getFirstChild(); node != null; node = node.getNextSibling())
        {
            if (name.equals(node.getNodeName()))
            {
                return (IIOMetadataNode) node;
            }
        }
        throw new IllegalStateException("GIF metadata node missing: " + name);
    }

    private int gifFrameCount(byte[] bytes) throws Exception
    {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)))
        {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            try
            {
                reader.setInput(input, false, false);
                return reader.getNumImages(true);
            }
            finally
            {
                reader.dispose();
            }
        }
    }

    private int gifDelay(byte[] bytes, int frame) throws Exception
    {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)))
        {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            try
            {
                reader.setInput(input, false, false);
                IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(frame)
                        .getAsTree(reader.getImageMetadata(frame).getNativeMetadataFormatName());
                return Integer.parseInt(child(root, "GraphicControlExtension").getAttribute("delayTime"));
            }
            finally
            {
                reader.dispose();
            }
        }
    }

    private boolean hasNetscapeLoop(byte[] bytes) throws Exception
    {
        return netscapeLoopPayload(bytes) != null;
    }

    private int netscapeLoopCount(byte[] bytes) throws Exception
    {
        byte[] payload = netscapeLoopPayload(bytes);
        if (payload == null || payload.length != 3 || payload[0] != 1)
        {
            throw new IllegalStateException("NETSCAPE loop payload invalid");
        }
        return (payload[1] & 0xFF) | ((payload[2] & 0xFF) << 8);
    }

    private byte[] netscapeLoopPayload(byte[] bytes) throws Exception
    {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)))
        {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            try
            {
                reader.setInput(input, false, false);
                IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(0)
                        .getAsTree(reader.getImageMetadata(0).getNativeMetadataFormatName());
                IIOMetadataNode applications = child(root, "ApplicationExtensions");
                for (org.w3c.dom.Node node = applications.getFirstChild(); node != null; node = node.getNextSibling())
                {
                    org.w3c.dom.Node id = node.getAttributes().getNamedItem("applicationID");
                    if (id != null && "NETSCAPE".equals(id.getNodeValue()))
                    {
                        return ((IIOMetadataNode) node).getUserObject() instanceof byte[]
                                ? (byte[]) ((IIOMetadataNode) node).getUserObject() : null;
                    }
                }
                return null;
            }
            finally
            {
                reader.dispose();
            }
        }
    }

    private byte[] oversizedGif()
    {
        return new byte[] { 'G', 'I', 'F', '8', '9', 'a', (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                0, 0, 0, ',', 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 2, 2,
                0x4C, 0x01, 0, ';' };
    }

    private byte[] reattachTrailer(byte[] bytes, int trailerLength)
    {
        byte[] shortened = java.util.Arrays.copyOf(bytes, bytes.length - trailerLength - 16);
        byte[] trailer = java.util.Arrays.copyOfRange(bytes, bytes.length - trailerLength, bytes.length);
        byte[] result = java.util.Arrays.copyOf(shortened, shortened.length + trailer.length);
        System.arraycopy(trailer, 0, result, shortened.length, trailer.length);
        return result;
    }

    private byte[] insertExif(byte[] jpeg, String metadata) throws Exception
    {
        byte[] payload = metadata.getBytes("ISO-8859-1");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        int segmentLength = payload.length + 2;
        out.write(segmentLength >> 8);
        out.write(segmentLength);
        out.write(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private boolean containsAscii(byte[] bytes, String value) throws Exception
    {
        return new String(bytes, "ISO-8859-1").contains(value);
    }

    private byte[] pngHeaderWithoutPixelData(int width, int height) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' });
        out.write(new byte[] { 0, 0, 0, 13, 'I', 'H', 'D', 'R' });
        out.write(new byte[] { (byte) (width >> 24), (byte) (width >> 16), (byte) (width >> 8), (byte) width,
                (byte) (height >> 24), (byte) (height >> 16), (byte) (height >> 8), (byte) height,
                8, 2, 0, 0, 0 });
        CRC32 crc = new CRC32();
        byte[] ihdr = out.toByteArray();
        crc.update(ihdr, 12, ihdr.length - 12);
        out.write(new byte[] { (byte) (crc.getValue() >> 24), (byte) (crc.getValue() >> 16),
                (byte) (crc.getValue() >> 8), (byte) crc.getValue() });
        return out.toByteArray();
    }

    private CloseTrackingMultipartFile tracked(String filename, String contentType, byte[] bytes)
    {
        return new CloseTrackingMultipartFile(filename, contentType, bytes);
    }

    private static final class CloseTrackingMultipartFile extends MockMultipartFile
    {
        private final byte[] sourceBytes;
        private boolean inputClosed;

        private CloseTrackingMultipartFile(String filename, String contentType, byte[] bytes)
        {
            super("file", filename, contentType, bytes);
            sourceBytes = bytes;
        }

        @Override
        public InputStream getInputStream()
        {
            return new ByteArrayInputStream(sourceBytes)
            {
                @Override
                public void close() throws java.io.IOException
                {
                    inputClosed = true;
                    super.close();
                }
            };
        }
    }

}
