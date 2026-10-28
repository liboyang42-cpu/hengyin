package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.file.FileUploadUtils;
import com.chengyinhub.common.utils.file.MimeTypeUtils;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOReadWarningListener;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.w3c.dom.Node;
import org.springframework.web.multipart.MultipartFile;

/**
 * OSS 图片上传的服务端内容与比例策略。
 */
final class UploadImagePolicy
{
    static final String MERCHANT_AFTERCARE_EVIDENCE_BIZ_TYPE = "merchant_aftercare_evidence";
    static final String COMMUNITY_POST_BIZ_TYPE = "COMMUNITY_POST";
    private static final long MAX_PIXELS = 40_000_000L;
    private static final long MAX_STAMP_PIXELS = 16_000_000L;
    private static final int MAX_GIF_FRAMES = 200;
    private static final double RATIO_TOLERANCE = 0.02D;
    private static final Map<String, Double> RATIOS = new HashMap<String, Double>();

    static
    {
        RATIOS.put("image_1_1", 1D);
        RATIOS.put("image_16_9", 16D / 9D);
        RATIOS.put("image_9_16", 9D / 16D);
        RATIOS.put("image_4_3", 4D / 3D);
        RATIOS.put("image_3_4", 3D / 4D);
        RATIOS.put("image_5_4", 5D / 4D);
        RATIOS.put("image_4_5", 4D / 5D);
        // G-3/M-10:商家店铺封面(主页背景)按 5:3 裁剪。
        RATIOS.put("image_5_3", 5D / 3D);
    }

    private UploadImagePolicy()
    {
    }

    static void validate(MultipartFile file, String bizType)
    {
        String extension = FileUploadUtils.getExtension(file).toLowerCase(Locale.ROOT);
        boolean imageExtension = isImageExtension(extension);
        Double expectedRatio = expectedRatio(bizType);

        if (!imageExtension)
        {
            if (StringUtils.isNotEmpty(bizType))
            {
                throw new IllegalArgumentException("该业务类型只允许上传图片");
            }
            return;
        }

        if ("gif".equals(extension) && containsGifJavaScriptPolyglot(file))
        {
            throw new IllegalArgumentException("图片内容包含主动脚本");
        }

        int[] size = readImageSize(file, extension);
        if (!hasRequiredTrailer(file, extension))
        {
            throw new IllegalArgumentException("图片内容无法识别");
        }
        long pixels = (long) size[0] * size[1];
        if (size[0] <= 0 || size[1] <= 0 || pixels > MAX_PIXELS
                || ("stamp".equals(bizType) && pixels > MAX_STAMP_PIXELS))
        {
            throw new IllegalArgumentException("图片尺寸无效或过大");
        }
        if (expectedRatio != null)
        {
            double actualRatio = (double) size[0] / size[1];
            if (Math.abs(actualRatio - expectedRatio) / expectedRatio > RATIO_TOLERANCE)
            {
                throw new IllegalArgumentException("图片比例不符合上传位置要求");
            }
        }
    }

    static MultipartFile sanitizeMetadata(MultipartFile file, String bizType)
    {
        String extension = FileUploadUtils.getExtension(file).toLowerCase(Locale.ROOT);
        if (!isImageExtension(extension))
        {
            return file;
        }
        if ("gif".equals(extension))
        {
            return new ByteArrayMultipartFile(file, reencodeGif(file));
        }
        String format;
        if ("jpg".equals(extension) || "jpeg".equals(extension))
        {
            format = "jpg";
        }
        else if ("png".equals(extension))
        {
            format = "png";
        }
        else
        {
            throw new IllegalArgumentException("图片格式不支持规范化");
        }

        try (InputStream input = file.getInputStream())
        {
            BufferedImage image = ImageIO.read(input);
            if (image == null)
            {
                throw new IllegalArgumentException("图片内容无法识别");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, out))
            {
                throw new IllegalArgumentException("图片处理失败");
            }
            return new ByteArrayMultipartFile(file, out.toByteArray());
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("图片处理失败", e);
        }
    }

    private static byte[] reencodeGif(MultipartFile file)
    {
        try (InputStream source = file.getInputStream(); ImageInputStream input = ImageIO.createImageInputStream(source))
        {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext() || input == null)
            {
                throw new IllegalArgumentException("图片处理失败");
            }
            ImageReader reader = readers.next();
            try
            {
                reader.setInput(input, false, false);
                int frames = reader.getNumImages(true);
                Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
                if (frames <= 0 || !writers.hasNext())
                {
                    throw new IllegalArgumentException("图片处理失败");
                }
                ImageWriter writer = writers.next();
                try
                {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (ImageOutputStream output = ImageIO.createImageOutputStream(out))
                    {
                        writer.setOutput(output);
                        writer.prepareWriteSequence(null);
                        for (int index = 0; index < frames; index++)
                        {
                            BufferedImage frame = reader.read(index);
                            writer.writeToSequence(new IIOImage(frame, null,
                                    sanitizedGifMetadata(writer, reader.getImageMetadata(index), frame)), null);
                        }
                        writer.endWriteSequence();
                    }
                    return out.toByteArray();
                }
                finally
                {
                    writer.dispose();
                }
            }
            finally
            {
                reader.dispose();
            }
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("图片处理失败", e);
        }
    }

    private static IIOMetadata sanitizedGifMetadata(ImageWriter writer, IIOMetadata sourceMetadata, BufferedImage frame)
            throws Exception
    {
        IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame),
                writer.getDefaultWriteParam());
        if (sourceMetadata == null || metadata == null || metadata.isReadOnly()
                || metadata.getNativeMetadataFormatName() == null)
        {
            return metadata;
        }
        Node root = metadata.getAsTree(metadata.getNativeMetadataFormatName());
        Node sourceRoot = sourceMetadata.getAsTree(sourceMetadata.getNativeMetadataFormatName());
        copyGifControlExtension(sourceRoot, root);
        copyNetscapeLoopExtension(sourceRoot, root);
        metadata.setFromTree(metadata.getNativeMetadataFormatName(), root);
        return metadata;
    }

    private static void copyGifControlExtension(Node source, Node destination)
    {
        Node sourceControl = findNode(source, "GraphicControlExtension");
        Node destinationControl = findNode(destination, "GraphicControlExtension");
        if (sourceControl == null || destinationControl == null || sourceControl.getAttributes() == null)
        {
            return;
        }
        for (int index = 0; index < sourceControl.getAttributes().getLength(); index++)
        {
            Node attribute = sourceControl.getAttributes().item(index);
            if (destinationControl.getAttributes().getNamedItem(attribute.getNodeName()) != null)
            {
                destinationControl.getAttributes().getNamedItem(attribute.getNodeName()).setNodeValue(attribute.getNodeValue());
            }
        }
    }

    private static void copyNetscapeLoopExtension(Node source, Node destination)
    {
        Node sourceExtensions = findNode(source, "ApplicationExtensions");
        if (sourceExtensions == null)
        {
            return;
        }
        for (Node extension = sourceExtensions.getFirstChild(); extension != null; extension = extension.getNextSibling())
        {
            Node id = extension.getAttributes() == null ? null : extension.getAttributes().getNamedItem("applicationID");
            if (id != null && "NETSCAPE".equals(id.getNodeValue()))
            {
                IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
                loop.setAttribute("applicationID", "NETSCAPE");
                loop.setAttribute("authenticationCode", "2.0");
                if (!(extension instanceof IIOMetadataNode)
                        || !isValidNetscapeLoopPayload(((IIOMetadataNode) extension).getUserObject()))
                {
                    return;
                }
                Node destinationExtensions = findNode(destination, "ApplicationExtensions");
                if (destinationExtensions == null)
                {
                    destinationExtensions = new IIOMetadataNode("ApplicationExtensions");
                    destination.appendChild(destinationExtensions);
                }
                byte[] sourcePayload = (byte[]) ((IIOMetadataNode) extension).getUserObject();
                loop.setUserObject(new byte[] { 1, sourcePayload[1], sourcePayload[2] });
                destinationExtensions.appendChild(loop);
                return;
            }
        }
    }

    private static boolean isValidNetscapeLoopPayload(Object payload)
    {
        return payload instanceof byte[] && ((byte[]) payload).length >= 3 && ((byte[]) payload)[0] == 1;
    }

    private static Double expectedRatio(String bizType)
    {
        if (StringUtils.isEmpty(bizType))
        {
            return null;
        }
        if ("image_free".equals(bizType) || "stamp".equals(bizType)
                || COMMUNITY_POST_BIZ_TYPE.equals(bizType)
                || MERCHANT_AFTERCARE_EVIDENCE_BIZ_TYPE.equals(bizType))
        {
            return null;
        }
        Double ratio = RATIOS.get(bizType);
        if (ratio == null)
        {
            throw new IllegalArgumentException("不支持的图片业务类型");
        }
        return ratio;
    }

    private static boolean containsGifJavaScriptPolyglot(MultipartFile file)
    {
        try (InputStream input = file.getInputStream())
        {
            return MimeTypeUtils.containsGifJavaScriptPolyglot(input);
        }
        catch (IOException e)
        {
            throw new IllegalArgumentException("图片内容无法识别", e);
        }
    }

    private static int[] readImageSize(MultipartFile file, String extension)
    {
        try (InputStream source = file.getInputStream(); ImageInputStream input = ImageIO.createImageInputStream(source))
        {
            if (input == null)
            {
                throw new IllegalArgumentException("图片内容无法识别");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext())
            {
                throw new IllegalArgumentException("图片内容无法识别");
            }
            ImageReader reader = readers.next();
            try
            {
                final boolean[] readWarning = new boolean[1];
                reader.addIIOReadWarningListener(new IIOReadWarningListener()
                {
                    @Override
                    public void warningOccurred(ImageReader source, String warning)
                    {
                        readWarning[0] = true;
                    }
                });
                if (!matchesExtension(reader.getFormatName(), extension))
                {
                    throw new IllegalArgumentException("图片内容无法识别");
                }
                reader.setInput(input, false, true);
                if ("gif".equals(extension))
                {
                    assertGifLogicalScreenWithinLimit(reader);
                }
                int frameCount = "gif".equals(extension) ? reader.getNumImages(true) : 1;
                if (frameCount <= 0 || ("gif".equals(extension) && frameCount > MAX_GIF_FRAMES))
                {
                    throw new IllegalArgumentException("图片帧数无效或过多");
                }
                long totalPixels = 0L;
                int width = 0;
                int height = 0;
                for (int index = 0; index < frameCount; index++)
                {
                    int frameWidth = reader.getWidth(index);
                    int frameHeight = reader.getHeight(index);
                    long framePixels = (long) frameWidth * frameHeight;
                    if (frameWidth <= 0 || frameHeight <= 0 || framePixels > MAX_PIXELS - totalPixels
                            || reader.read(index) == null)
                    {
                        throw new IllegalArgumentException("图片尺寸无效或过大");
                    }
                    if (index == 0)
                    {
                        width = frameWidth;
                        height = frameHeight;
                    }
                    totalPixels += framePixels;
                }
                if (readWarning[0])
                {
                    throw new IllegalArgumentException("图片内容无法识别");
                }
                return new int[] { width, height };
            }
            finally
            {
                reader.dispose();
            }
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("图片内容无法识别", e);
        }
    }

    private static void assertGifLogicalScreenWithinLimit(ImageReader reader) throws Exception
    {
        IIOMetadata metadata = reader.getStreamMetadata();
        if (metadata == null || metadata.getNativeMetadataFormatName() == null)
        {
            throw new IllegalArgumentException("图片尺寸无效或过大");
        }
        Node descriptor = findNode(metadata.getAsTree(metadata.getNativeMetadataFormatName()), "LogicalScreenDescriptor");
        if (descriptor == null || descriptor.getAttributes().getNamedItem("logicalScreenWidth") == null
                || descriptor.getAttributes().getNamedItem("logicalScreenHeight") == null)
        {
            throw new IllegalArgumentException("图片尺寸无效或过大");
        }
        int width = Integer.parseInt(descriptor.getAttributes().getNamedItem("logicalScreenWidth").getNodeValue());
        int height = Integer.parseInt(descriptor.getAttributes().getNamedItem("logicalScreenHeight").getNodeValue());
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS)
        {
            throw new IllegalArgumentException("图片尺寸无效或过大");
        }
    }

    private static Node findNode(Node node, String name)
    {
        if (name.equals(node.getNodeName()))
        {
            return node;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
        {
            Node match = findNode(child, name);
            if (match != null)
            {
                return match;
            }
        }
        return null;
    }

    private static boolean hasRequiredTrailer(MultipartFile file, String extension)
    {
        try
        {
            byte[] bytes = file.getBytes();
            if ("jpg".equals(extension) || "jpeg".equals(extension))
            {
                return endsWith(bytes, 0xFF, 0xD9);
            }
            if ("png".equals(extension))
            {
                return endsWith(bytes, 0, 0, 0, 0, 'I', 'E', 'N', 'D', 0xAE, 0x42, 0x60, 0x82);
            }
            return !"gif".equals(extension) || endsWith(bytes, 0x3B);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static boolean endsWith(byte[] bytes, int... suffix)
    {
        if (bytes.length < suffix.length)
        {
            return false;
        }
        int offset = bytes.length - suffix.length;
        for (int index = 0; index < suffix.length; index++)
        {
            if ((bytes[offset + index] & 0xFF) != suffix[index])
            {
                return false;
            }
        }
        return true;
    }

    private static boolean isImageExtension(String extension)
    {
        return "bmp".equals(extension) || "gif".equals(extension) || "jpg".equals(extension)
                || "jpeg".equals(extension) || "png".equals(extension);
    }

    private static boolean matchesExtension(String formatName, String extension)
    {
        String normalizedFormat = formatName.toLowerCase(Locale.ROOT);
        if ("jpg".equals(extension) || "jpeg".equals(extension))
        {
            return "jpeg".equals(normalizedFormat);
        }
        return extension.equals(normalizedFormat);
    }

    private static final class ByteArrayMultipartFile implements MultipartFile
    {
        private final MultipartFile source;
        private final byte[] bytes;

        private ByteArrayMultipartFile(MultipartFile source, byte[] bytes)
        {
            this.source = source;
            this.bytes = bytes;
        }

        @Override
        public String getName()
        {
            return source.getName();
        }

        @Override
        public String getOriginalFilename()
        {
            return source.getOriginalFilename();
        }

        @Override
        public String getContentType()
        {
            return source.getContentType();
        }

        @Override
        public boolean isEmpty()
        {
            return bytes.length == 0;
        }

        @Override
        public long getSize()
        {
            return bytes.length;
        }

        @Override
        public byte[] getBytes()
        {
            return bytes.clone();
        }

        @Override
        public InputStream getInputStream()
        {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(File dest) throws java.io.IOException
        {
            Files.write(dest.toPath(), bytes);
        }
    }
}
