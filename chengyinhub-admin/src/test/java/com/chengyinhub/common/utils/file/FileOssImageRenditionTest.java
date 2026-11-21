package com.chengyinhub.common.utils.file;

import com.aliyun.oss.ClientConfiguration;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-21 图片按槽位出图:真 aliyun-sdk-oss 本地签名(不触网)。
 * 只有本存储签出、未过期的原图链接换得到缩略版;缩略版带 x-oss-process 且被签进签名,沿用原 Expires。
 */
class FileOssImageRenditionTest
{
    private static final String BUCKET = "chengyin-oss";
    private static final String ENDPOINT = "https://oss-cn-shanghai.aliyuncs.com";
    private static final String KEY = "upload/0123456789abcdef0123456789abcdef.jpg";
    private static final long EXPIRES_2100 = 4102444800L;
    private static final long NOW = 1_795_000_000_000L;

    private static OSSClient client(String secret)
    {
        return new OSSClient(ENDPOINT, new DefaultCredentialProvider("AK-ID", secret), new ClientConfiguration());
    }

    private static Map<String, String> signedQuery(OSSClient client, String key, long expires)
    {
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(BUCKET, key);
        request.setExpiration(new Date(expires * 1000L));
        return query(client.generatePresignedUrl(request).toString());
    }

    private static Map<String, String> query(String url)
    {
        Map<String, String> out = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int split = pair.indexOf('=');
            out.put(pair.substring(0, split), URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String rendition(OSSClient client, String key, Map<String, String> q, int width, boolean webp)
    {
        return FileOssUploadUtils.imageRenditionUrl(client, BUCKET, key, q.get("Expires"), q.get("OSSAccessKeyId"),
                q.get("Signature"), width, webp, NOW);
    }

    @Test
    void genuineSignedOriginalGetsSignedSizedRendition()
    {
        OSSClient client = client("SK");
        Map<String, String> original = signedQuery(client, KEY, EXPIRES_2100);
        String url = rendition(client, KEY, original, 750, true);
        URI uri = URI.create(url);
        assertEquals("https", uri.getScheme());
        assertEquals("chengyin-oss.oss-cn-shanghai.aliyuncs.com", uri.getHost());
        assertEquals("/" + KEY, uri.getPath());
        Map<String, String> q = query(url);
        assertEquals("image/resize,w_750,m_lfit/quality,q_80/format,webp", q.get("x-oss-process"));
        assertEquals(original.get("Expires"), q.get("Expires"));
        assertNotEquals(original.get("Signature"), q.get("Signature"), "处理参数必须进签名,否则 OSS 403");
        // 与 SDK 对同一 key+process+Expires 的签名完全一致:真 GET 能过 OSS 校验
        GeneratePresignedUrlRequest expected = new GeneratePresignedUrlRequest(BUCKET, KEY);
        expected.setExpiration(new Date(EXPIRES_2100 * 1000L));
        expected.setProcess("image/resize,w_750,m_lfit/quality,q_80/format,webp");
        assertEquals(client.generatePresignedUrl(expected).toString(), url);
        assertEquals(url, rendition(client, KEY, original, 750, true), "同一输入同一链接,缓存可命中");
    }

    @Test
    void origFormatKeepsSourceFormatForPngAlpha()
    {
        OSSClient client = client("SK");
        String png = "upload/0123456789abcdef0123456789abcdef.png";
        String url = rendition(client, png, signedQuery(client, png, EXPIRES_2100), 320, false);
        assertEquals("image/resize,w_320,m_lfit/quality,q_80", query(url).get("x-oss-process"));
    }

    @Test
    void everyRegisteredWidthIsAcceptedAndOthersRejected()
    {
        OSSClient client = client("SK");
        Map<String, String> original = signedQuery(client, KEY, EXPIRES_2100);
        for (int width : FileOssUploadUtils.IMAGE_RENDITION_WIDTHS) {
            assertTrue(query(rendition(client, KEY, original, width, false)).get("x-oss-process").contains("w_" + width + ","));
        }
        for (int width : new int[] {0, 100, 751, 2000, 4096}) {
            assertThrows(IllegalArgumentException.class, () -> rendition(client, KEY, original, width, false));
        }
    }

    @Test
    void forgedTamperedExpiredOrForeignSignaturesAreRejected()
    {
        OSSClient client = client("SK");
        Map<String, String> original = signedQuery(client, KEY, EXPIRES_2100);
        // 换 key:签名只对原对象有效
        assertThrows(IllegalArgumentException.class,
                () -> rendition(client, "upload/ffffffffffffffffffffffffffffffff.jpg", original, 750, false));
        // 改期
        Map<String, String> extended = new HashMap<>(original);
        extended.put("Expires", String.valueOf(EXPIRES_2100 + 1));
        assertThrows(IllegalArgumentException.class, () -> rendition(client, KEY, extended, 750, false));
        // 别的密钥签的(轮换前/外人)
        Map<String, String> foreign = signedQuery(client("OTHER-SK"), KEY, EXPIRES_2100);
        assertThrows(IllegalArgumentException.class, () -> rendition(client, KEY, foreign, 750, false));
        // AccessKeyId 不符
        Map<String, String> otherAk = new HashMap<>(original);
        otherAk.put("OSSAccessKeyId", "OTHER-AK");
        assertThrows(IllegalArgumentException.class, () -> rendition(client, KEY, otherAk, 750, false));
        // 已过期
        long past = NOW / 1000L - 1;
        Map<String, String> expired = signedQuery(client, KEY, past);
        assertThrows(IllegalArgumentException.class, () -> rendition(client, KEY, expired, 750, false));
        // 缺参
        Map<String, String> missing = new HashMap<>(original);
        missing.remove("Signature");
        assertThrows(IllegalArgumentException.class, () -> rendition(client, KEY, missing, 750, false));
        missing.put("Signature", original.get("Signature"));
        missing.put("Expires", "abc");
        assertThrows(IllegalArgumentException.class, () -> rendition(client, KEY, missing, 750, false));
    }

    @Test
    void onlyStillImageUploadKeysCanBeResized()
    {
        OSSClient client = client("SK");
        for (String key : new String[] {"upload/0123456789abcdef0123456789abcdef.gif",
                "upload/0123456789abcdef0123456789abcdef.mp4", "other/0123456789abcdef0123456789abcdef.jpg",
                "upload/../0123456789abcdef0123456789abcd.jpg", null}) {
            Map<String, String> q = key == null ? signedQuery(client, KEY, EXPIRES_2100) : signedQuery(client, key, EXPIRES_2100);
            assertThrows(IllegalArgumentException.class, () -> rendition(client, key, q, 750, false));
        }
    }
}
