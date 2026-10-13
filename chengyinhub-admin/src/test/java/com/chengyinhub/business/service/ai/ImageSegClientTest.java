package com.chengyinhub.business.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阿里云视觉智能「商品分割」客户端(施工文档 §2.3)。
 *
 * <p>钉两件事:
 * ① 签名算法与阿里云官方示例逐字节一致 —— 这一段是手写 HMAC-SHA1,错一个转义就是线上
 *    400 SignatureDoesNotMatch,而本机没有 key 永远验不到,所以只能靠官方示例当基准;
 * ② fail-open:任何失败(没开通、没配 key、地址不合法、HTTP 错、返回不是 JSON)都是
 *    「没有结果」而不是异常 —— 抠不出来卡照样铸,不能因为第三方抖一下就玩家铸卡失败。
 *
 * <p>不打网络:HTTP 走 {@link ImageSegClient#get(String)} 这一缝,单测覆写它。
 */
class ImageSegClientTest {

    /**
     * 阿里云 RPC 签名 V1 官方示例。
     * 出处:《签名机制的方法及代码示例》 https://help.aliyun.com/document_detail/203278.html
     * (IoT Studio 调 Pub 接口的例子 —— 各产品的 RPC 签名算法同一套,阿里云文档里给全
     * 「给定 secret + 待签字符串 + 期望签名」三样的示例只有这类)。
     * 该示例的签名已用独立实现(python hmac)复核过,文档串与文档值确实自洽。
     */
    private static final String DOC_SECRET = "testsecret";
    private static final String DOC_STRING_TO_SIGN = "GET&%2F&AccessKeyId%3Dtestid%26Action%3DPub%26Format%3DXML"
            + "%26MessageContent%3DaGVsbG8gd29ybGQ%26ProductKey%3D12345abcde%26Qos%3D0"
            + "%26RegionId%3Dcn-shanghai%26SignatureMethod%3DHMAC-SHA1"
            + "%26SignatureNonce%3D3ee8c1b8-83d3-44af-a94f-4e0ad82fd6cf%26SignatureVersion%3D1.0"
            + "%26Timestamp%3D2018-07-31T07%253A43%253A57Z"
            + "%26TopicFullName%3D%252F12345abcde%252Ftestdevice%252Fuser%252Fget%26Version%3D2018-01-20";
    private static final String DOC_SIGNATURE = "NUh3otvAoXOZmG/a2gDShh6Ze9w=";

    private static final String PHOTO = "https://cdn.chengyinhub.com/profile/upload/window.jpg";

    // ==================== 签名 ====================

    @Test
    void 待签字符串与签名和官方示例逐字节一致() {
        Map<String, String> params = docExampleParams();

        assertEquals(DOC_STRING_TO_SIGN, ImageSegClient.stringToSign("GET", params));
        assertEquals(DOC_SIGNATURE, ImageSegClient.sign(DOC_SECRET, ImageSegClient.stringToSign("GET", params)));
    }

    /**
     * 负控:待签参数里随便改一个值,签名必须变。
     * 不变就说明这一步压根没把参数算进去(比如拼串漏了某个 key),那签名字段就是个装饰 ——
     * 线上表现为永远 SignatureDoesNotMatch,而单测全绿。
     */
    @Test
    void 负控_改任一待签参数值签名就变() {
        String baseline = ImageSegClient.sign(DOC_SECRET, ImageSegClient.stringToSign("GET", docExampleParams()));

        for (String key : docExampleParams().keySet()) {
            Map<String, String> tampered = docExampleParams();
            tampered.put(key, "tampered");

            String signature = ImageSegClient.sign(DOC_SECRET, ImageSegClient.stringToSign("GET", tampered));
            assertNotEquals(baseline, signature, "改了 " + key + " 签名却没变,说明它没参与签名");
        }
    }

    /** 官方示例里的 Timestamp 冒号要双层转义、Topic 的斜杠要转义成 %252F,这些都在待签串里已钉住。 */
    @Test
    void 转义规则() {
        assertEquals("a%20b", ImageSegClient.percentEncode("a b"));
        assertEquals("%2A", ImageSegClient.percentEncode("*"));
        assertEquals("~", ImageSegClient.percentEncode("~"));
        assertEquals("%2F", ImageSegClient.percentEncode("/"));
        assertEquals("100%25", ImageSegClient.percentEncode("100%"));
    }

    // ==================== fail-open:不去请求 ====================

    @Test
    void 开关关着_不发请求也没有结果() {
        FakeHttp client = new FakeHttp();
        client.config(false, "id", "secret");

        assertNull(client.segment(PHOTO));
        assertTrue(client.urls.isEmpty(), "开关关着却去打了请求:" + client.urls);
    }

    /** 开关开着但 key 没注入是最常见的「配了一半」,这种请求注定 404 式失败,不如不发。 */
    @Test
    void 没配key_不发请求() {
        FakeHttp client = new FakeHttp();
        client.config(true, "id", "");

        assertNull(client.segment(PHOTO));
        assertTrue(client.urls.isEmpty());

        FakeHttp noId = new FakeHttp();
        noId.config(true, "", "secret");
        assertNull(noId.segment(PHOTO));
        assertTrue(noId.urls.isEmpty());
    }

    /** 接口要求地址里不能是中文,含中文必被拒,提前收口而不是白跑一次超时。 */
    @Test
    void 地址为空或含中文_不发请求() {
        FakeHttp client = new FakeHttp();
        client.config(true, "id", "secret");

        assertNull(client.segment(""));
        assertNull(client.segment("   "));
        assertNull(client.segment(null));
        assertNull(client.segment("https://cdn.chengyinhub.com/窗外.jpg"));
        assertTrue(client.urls.isEmpty(), "非法地址不该发请求:" + client.urls);
    }

    // ==================== 请求形状 ====================

    @Test
    void 请求带上商品分割必需的参数并签名() {
        FakeHttp client = new FakeHttp();
        client.config(true, "testid", "testsecret");
        client.body = "{\"RequestId\":\"r1\",\"Data\":{\"ImageURL\":\"https://viapi-result.tmp/cut.png\"}}";

        assertEquals("https://viapi-result.tmp/cut.png", client.segment(PHOTO));

        assertEquals(1, client.urls.size());
        String url = client.urls.get(0);
        assertTrue(url.startsWith("https://imageseg.cn-shanghai.aliyuncs.com/?"), url);
        assertTrue(url.contains("Action=SegmentCommodity"), url);
        assertTrue(url.contains("Version=2019-12-30"), url);
        // 不带 ReturnForm = 按原图大小回透明 PNG:物品在原图的位置要靠它算,crop 形式把位置丢了
        assertTrue(!url.contains("ReturnForm"), url);
        assertTrue(url.contains("Format=JSON"), url);
        assertTrue(url.contains("SignatureMethod=HMAC-SHA1"), url);
        assertTrue(url.contains("SignatureVersion=1.0"), url);
        assertTrue(url.contains("AccessKeyId=testid"), url);
        // 图片地址本身要转义后带过去,斜杠不能裸着,否则参数会被劈开
        assertTrue(url.contains("ImageURL=https%3A%2F%2Fcdn.chengyinhub.com%2Fprofile%2Fupload%2Fwindow.jpg"), url);
        assertTrue(url.contains("SignatureNonce="), url);
        assertTrue(url.contains("Timestamp=20"), url);
        assertTrue(url.contains("Signature="), url);
    }

    /** 每次请求的 nonce 必须不同:重放保护,阿里云会拒掉重复 nonce。 */
    @Test
    void 两次请求的nonce与签名不该一样() {
        FakeHttp client = new FakeHttp();
        client.config(true, "testid", "testsecret");
        client.body = "{\"Data\":{\"ImageURL\":\"https://x/y.png\"}}";

        client.segment(PHOTO);
        client.segment(PHOTO);

        assertEquals(2, client.urls.size());
        assertNotEquals(nonceOf(client.urls.get(0)), nonceOf(client.urls.get(1)));
        assertNotEquals(client.urls.get(0), client.urls.get(1));
    }

    // ==================== fail-open:请求了但没结果 ====================

    @Test
    void HTTP错_没有结果() {
        FakeHttp client = usable();
        client.httpError = true;

        assertNull(client.segment(PHOTO));
    }

    @Test
    void 返回不是JSON_没有结果() {
        FakeHttp client = usable();
        client.body = "<html>502 Bad Gateway</html>";

        assertNull(client.segment(PHOTO));
    }

    /** 业务失败也是 200 返回(Code/Message),没有 Data.ImageURL 就是没抠出来。 */
    @Test
    void 返回里没有结果地址_没有结果() {
        FakeHttp noData = usable();
        noData.body = "{\"RequestId\":\"r1\"}";
        assertNull(noData.segment(PHOTO));

        FakeHttp emptyData = usable();
        emptyData.body = "{\"Code\":\"InvalidImageFormat\",\"Message\":\"bad image\",\"Data\":{}}";
        assertNull(emptyData.segment(PHOTO));
    }

    /** 缝炸了(超时、连接被重置)也不能把异常抛给铸卡那一步。 */
    @Test
    void 请求抛异常_吞掉并返回没有结果() {
        FakeHttp client = usable();
        client.boom = new IllegalStateException("connect timed out");

        assertNull(client.segment(PHOTO));
    }

    /** 日志与返回值里都不许出现 secret 明文:回包一旦带出去就是泄露。 */
    @Test
    void 结果里不含密钥明文() {
        FakeHttp client = usable();
        client.body = "{\"Data\":{\"ImageURL\":\"https://vapi-result.tmp/ok.png\"}}";

        String result = client.segment(PHOTO);

        assertEquals("https://vapi-result.tmp/ok.png", result);
        assertFalse(client.urls.get(0).contains("testsecret"), "请求地址里带出了 secret:" + client.urls.get(0));
    }

    // ==================== 脚手架 ====================

    private static FakeHttp usable() {
        FakeHttp client = new FakeHttp();
        client.config(true, "testid", "testsecret");
        return client;
    }

    private static Map<String, String> docExampleParams() {
        Map<String, String> params = new TreeMap<String, String>();
        params.put("AccessKeyId", "testid");
        params.put("Action", "Pub");
        params.put("Format", "XML");
        params.put("MessageContent", "aGVsbG8gd29ybGQ");
        params.put("ProductKey", "12345abcde");
        params.put("Qos", "0");
        params.put("RegionId", "cn-shanghai");
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", "3ee8c1b8-83d3-44af-a94f-4e0ad82fd6cf");
        params.put("SignatureVersion", "1.0");
        params.put("Timestamp", "2018-07-31T07:43:57Z");
        params.put("TopicFullName", "/12345abcde/testdevice/user/get");
        params.put("Version", "2018-01-20");
        return params;
    }

    private static String nonceOf(String url) {
        int at = url.indexOf("SignatureNonce=");
        int end = url.indexOf('&', at);
        return end < 0 ? url.substring(at) : url.substring(at, end);
    }

    /** HTTP 缝:记下请求地址、返回预设响应,单测因此一克网络都不碰。 */
    private static class FakeHttp extends ImageSegClient {
        private final List<String> urls = new ArrayList<String>();
        private String body;
        private boolean httpError;
        private RuntimeException boom;

        @Override
        String get(String url) {
            urls.add(url);
            if (boom != null) {
                throw boom;
            }
            return httpError ? null : body;
        }

        private void config(boolean enabledValue, String id, String secret) {
            ReflectionTestUtils.setField(this, "enabled", enabledValue);
            ReflectionTestUtils.setField(this, "accessKeyId", id);
            ReflectionTestUtils.setField(this, "accessKeySecret", secret);
        }
    }
}
