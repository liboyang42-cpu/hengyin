package com.chengyinhub.common.utils.file;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 媒体类型工具类
 * 
 * @author ruoyi
 */
public class MimeTypeUtils
{
    private static final Map<String, String[]> ALLOWED_CONTENT_TYPES = new HashMap<String, String[]>();

    static
    {
        ALLOWED_CONTENT_TYPES.put("gif", new String[] { "image/gif" });
        ALLOWED_CONTENT_TYPES.put("jpg", new String[] { "image/jpeg", "image/jpg", "image/pjpeg" });
        ALLOWED_CONTENT_TYPES.put("jpeg", new String[] { "image/jpeg", "image/jpg", "image/pjpeg" });
        ALLOWED_CONTENT_TYPES.put("png", new String[] { "image/png" });
        ALLOWED_CONTENT_TYPES.put("mp3", new String[] { "audio/mpeg", "audio/mp3" });
        ALLOWED_CONTENT_TYPES.put("wav", new String[] { "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" });
        ALLOWED_CONTENT_TYPES.put("m4a", new String[] { "audio/mp4", "audio/x-m4a" });
        ALLOWED_CONTENT_TYPES.put("aac", new String[] { "audio/aac", "audio/x-aac" });
        // 显形档 AR 的 3D 模型:微信 wx.uploadFile 传不认识的类型时报 application/octet-stream,真身靠下面的文件头判
        ALLOWED_CONTENT_TYPES.put("glb", new String[] { "model/gltf-binary", "application/octet-stream" });
    }

    public static final String IMAGE_PNG = "image/png";

    public static final String IMAGE_JPG = "image/jpg";

    public static final String IMAGE_JPEG = "image/jpeg";

    public static final String IMAGE_BMP = "image/bmp";

    public static final String IMAGE_GIF = "image/gif";
    
    public static final String[] IMAGE_EXTENSION = { "bmp", "gif", "jpg", "jpeg", "png" };

    public static final String[] FLASH_EXTENSION = { "swf", "flv" };

    public static final String[] MEDIA_EXTENSION = { "swf", "flv", "mp3", "wav", "wma", "wmv", "mid", "avi", "mpg",
            "asf", "rm", "rmvb" };

    public static final String[] VIDEO_EXTENSION = { "mp4", "avi", "rmvb" };

    public static final String[] DEFAULT_ALLOWED_EXTENSION = {
            // 图片
            "bmp", "gif", "jpg", "jpeg", "png",
            // word excel powerpoint
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "html", "htm", "txt",
            // 压缩文件
            "rar", "zip", "gz", "bz2",
            // 视频格式
            "mp4", "avi", "rmvb",
            // pdf
            "pdf" };

    public static String getExtension(String prefix)
    {
        switch (prefix)
        {
            case IMAGE_PNG:
                return "png";
            case IMAGE_JPG:
                return "jpg";
            case IMAGE_JPEG:
                return "jpeg";
            case IMAGE_BMP:
                return "bmp";
            case IMAGE_GIF:
                return "gif";
            default:
                return "";
        }
    }

    /**
     * 校验 multipart 声明的 MIME 是否与文件后缀一致。浏览器可伪造该字段，图片内容仍必须由上传策略解码确认。
     */
    public static boolean isAllowedContentType(String extension, String contentType)
    {
        if (extension == null || contentType == null)
        {
            return false;
        }
        String[] allowedTypes = ALLOWED_CONTENT_TYPES.get(extension.toLowerCase(Locale.ROOT));
        if (allowedTypes == null)
        {
            return false;
        }
        int parameterIndex = contentType.indexOf(';');
        String normalized = (parameterIndex < 0 ? contentType : contentType.substring(0, parameterIndex))
                .trim().toLowerCase(Locale.ROOT);
        for (String allowedType : allowedTypes)
        {
            if (allowedType.equals(normalized))
            {
                return true;
            }
        }
        return false;
    }

    public static boolean hasActiveContentExtensionSegment(String fileName)
    {
        if (fileName == null)
        {
            return true;
        }
        String normalized = fileName.replace('\\', '/').toLowerCase(Locale.ROOT);
        int pathEnd = normalized.lastIndexOf('/');
        String baseName = pathEnd < 0 ? normalized : normalized.substring(pathEnd + 1);
        String[] segments = baseName.split("\\.");
        for (int i = 1; i < segments.length; i++)
        {
            if (isActiveContentExtension(segments[i]))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isActiveContentExtension(String extension)
    {
        return "html".equals(extension) || "htm".equals(extension) || "svg".equals(extension) || "js".equals(extension);
    }

    /**
     * App 上传入口的二进制文件头校验，防止把主动内容伪装成业务允许的文件。
     */
    public static boolean hasExpectedContent(String extension, InputStream input)
    {
        if (extension == null || input == null)
        {
            return false;
        }
        byte[] header = new byte[16];
        int length = 0;
        try
        {
            while (length < header.length)
            {
                int read = input.read(header, length, header.length - length);
                if (read < 0)
                {
                    break;
                }
                length += read;
            }
        }
        catch (IOException e)
        {
            return false;
        }
        if (startsWithActiveMarkup(header, length))
        {
            return false;
        }
        String normalized = extension.toLowerCase(Locale.ROOT);
        if ("jpg".equals(normalized) || "jpeg".equals(normalized))
        {
            return startsWith(header, length, 0xFF, 0xD8, 0xFF);
        }
        if ("png".equals(normalized))
        {
            return startsWith(header, length, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
        }
        if ("gif".equals(normalized))
        {
            return startsAscii(header, length, "GIF87a") || startsAscii(header, length, "GIF89a");
        }
        if ("wav".equals(normalized))
        {
            return startsAscii(header, length, "RIFF") && asciiAt(header, length, 8, "WAVE");
        }
        if ("m4a".equals(normalized))
        {
            return asciiAt(header, length, 4, "ftyp");
        }
        if ("aac".equals(normalized))
        {
            return startsWith(header, length, 0xFF) && length > 1 && (header[1] & 0xF6) == 0xF0;
        }
        if ("glb".equals(normalized))
        {
            // glTF 2.0 二进制:magic "glTF" + 小端 uint32 版本号 2(glTF 1.0 的 glb 结构不同,xr-frame 不认)
            return startsAscii(header, length, "glTF") && length >= 8
                    && header[4] == 0x02 && header[5] == 0x00 && header[6] == 0x00 && header[7] == 0x00;
        }
        if ("mp3".equals(normalized))
        {
            return startsAscii(header, length, "ID3") || (startsWith(header, length, 0xFF) && length > 1 && (header[1] & 0xE0) == 0xE0);
        }
        return false;
    }

    public static boolean containsGifJavaScriptPolyglot(InputStream input) throws IOException
    {
        if (input == null)
        {
            return false;
        }
        byte[] header = new byte[8];
        int headerLength = 0;
        boolean gifJavaScriptComment = false;
        int previous = -1;
        int value;
        while ((value = input.read()) >= 0)
        {
            if (headerLength < header.length)
            {
                header[headerLength++] = (byte) value;
                if (headerLength == header.length && (startsAscii(header, headerLength, "GIF87a")
                        || startsAscii(header, headerLength, "GIF89a")) && header[6] == '/' && header[7] == '*')
                {
                    gifJavaScriptComment = true;
                }
            }
            if (gifJavaScriptComment && previous == '*' && value == '/')
            {
                return true;
            }
            previous = value;
        }
        return false;
    }

    private static boolean startsWithActiveMarkup(byte[] bytes, int length)
    {
        int offset = 0;
        while (offset < length && (bytes[offset] == ' ' || bytes[offset] == '\t' || bytes[offset] == '\r' || bytes[offset] == '\n'))
        {
            offset++;
        }
        return asciiAtIgnoreCase(bytes, length, offset, "<html") || asciiAtIgnoreCase(bytes, length, offset, "<!doctype html")
                || asciiAtIgnoreCase(bytes, length, offset, "<svg") || asciiAtIgnoreCase(bytes, length, offset, "<script");
    }

    private static boolean startsWith(byte[] bytes, int length, int... expected)
    {
        if (length < expected.length)
        {
            return false;
        }
        for (int i = 0; i < expected.length; i++)
        {
            if ((bytes[i] & 0xFF) != expected[i])
            {
                return false;
            }
        }
        return true;
    }

    private static boolean startsAscii(byte[] bytes, int length, String expected)
    {
        return asciiAt(bytes, length, 0, expected);
    }

    private static boolean asciiAt(byte[] bytes, int length, int offset, String expected)
    {
        if (length < offset + expected.length())
        {
            return false;
        }
        for (int i = 0; i < expected.length(); i++)
        {
            if (bytes[offset + i] != (byte) expected.charAt(i))
            {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiAtIgnoreCase(byte[] bytes, int length, int offset, String expected)
    {
        if (length < offset + expected.length())
        {
            return false;
        }
        for (int i = 0; i < expected.length(); i++)
        {
            int actual = bytes[offset + i] & 0xFF;
            int expectedChar = expected.charAt(i);
            if (actual >= 'A' && actual <= 'Z')
            {
                actual += 'a' - 'A';
            }
            if (actual != expectedChar)
            {
                return false;
            }
        }
        return true;
    }
}
