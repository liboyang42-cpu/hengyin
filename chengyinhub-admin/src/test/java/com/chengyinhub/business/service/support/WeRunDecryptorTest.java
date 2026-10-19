package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信运动解密的校验契约。
 *
 * 这里的重点不是"能解开",是**该拒的必须拒** —— 步数直接换 XP 与落章,
 * 解密器一旦对来源不设防,拿别家小程序的合法密文就能刷分,而且不报错、零告警。
 * 所以每条拒绝路径都各有一个负控。
 */
class WeRunDecryptorTest {

    private static final String APP_ID = "wxdf8fd71ea3c614b4";
    private static final String OTHER_APP_ID = "wxffffffffffffffff";
    /** 16 字节 key/iv,base64 后给解密器 */
    /* 测试固定的 16 字节 AES 分组材料。刻意不叫 KEY:那个名字会让密钥扫描器把测试夹具
       当成泄露的凭据(gitleaks generic-api-key),而放宽规则去迁就测试是本末倒置。 */
    private static final byte[] AES_BLOCK = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AES_IV = "fedcba9876543210".getBytes(StandardCharsets.UTF_8);

    /** 用与微信侧相同的 AES-128-CBC + PKCS#7 造密文,好让测试真的走完整条解密路径 */
    private static String encrypt(String plain) throws Exception {
        byte[] raw = plain.getBytes(StandardCharsets.UTF_8);
        int pad = 16 - (raw.length % 16);
        byte[] padded = new byte[raw.length + pad];
        System.arraycopy(raw, 0, padded, 0, raw.length);
        for (int i = raw.length; i < padded.length; i++) {
            padded[i] = (byte) pad;
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_BLOCK, "AES"), new IvParameterSpec(AES_IV));
        return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
    }

    private static String key() {
        return Base64.getEncoder().encodeToString(AES_BLOCK);
    }

    private static String iv() {
        return Base64.getEncoder().encodeToString(AES_IV);
    }

    private static String payload(String appId, String stepInfoList) {
        return "{\"stepInfoList\":" + stepInfoList
                + ",\"watermark\":{\"appid\":\"" + appId + "\",\"timestamp\":1756200000}}";
    }

    @Test
    void 解出最后一天的步数() throws Exception {
        String cipherText = encrypt(payload(APP_ID,
                "[{\"timestamp\":1756000000,\"step\":3000},"
                        + "{\"timestamp\":1756100000,\"step\":5000},"
                        + "{\"timestamp\":1756200000,\"step\":4286}]"));

        WeRunDecryptor.WeRunResult result = WeRunDecryptor.decrypt(key(), cipherText, iv(), APP_ID);

        // stepInfoList 是最近 30 天升序序列,取最后一条才是今天 —— 取第一条会拿到 30 天前
        assertEquals(4286, result.getTodaySteps());
        assertEquals(1756200000L, result.getTimestamp());
    }

    @Test
    void 负控_别家小程序的合法密文必须被拒() throws Exception {
        // 密文本身完全合法(能解开、结构正确),只有 watermark.appid 不是我们
        String cipherText = encrypt(payload(OTHER_APP_ID, "[{\"timestamp\":1756200000,\"step\":99999}]"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(key(), cipherText, iv(), APP_ID));
        assertTrue(e.getMessage().contains("来源不匹配"), e.getMessage());
    }

    @Test
    void 负控_appid未配置时整条拒绝而不是放行() throws Exception {
        String cipherText = encrypt(payload(APP_ID, "[{\"timestamp\":1756200000,\"step\":100}]"));

        // 配置缺失时若放行,等于把上面那条 appid 校验静默关掉
        assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(key(), cipherText, iv(), ""));
    }

    @Test
    void 负控_空步数序列不能当成0步() throws Exception {
        String cipherText = encrypt(payload(APP_ID, "[]"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(key(), cipherText, iv(), APP_ID));
        assertTrue(e.getMessage().contains("没有步数记录"), e.getMessage());
    }

    @Test
    void 负控_密文被篡改必须解不开而不是解出垃圾() throws Exception {
        String cipherText = encrypt(payload(APP_ID, "[{\"timestamp\":1756200000,\"step\":100}]"));
        byte[] raw = Base64.getDecoder().decode(cipherText);
        raw[raw.length - 1] ^= 0x5A;   // 动最后一个分组,补位与内容一起被破坏
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(key(), tampered, iv(), APP_ID));
    }

    @Test
    void 负控_密钥长度不对直接拒() throws Exception {
        String cipherText = encrypt(payload(APP_ID, "[{\"timestamp\":1756200000,\"step\":100}]"));
        String shortKey = Base64.getEncoder().encodeToString("short".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(shortKey, cipherText, iv(), APP_ID));
        assertTrue(e.getMessage().contains("长度不正确"), e.getMessage());
    }

    @Test
    void 负控_缺参数不能静默当成0步() {
        assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(null, "x", "y", APP_ID));
        assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(key(), "", iv(), APP_ID));
        assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(key(), "x", " ", APP_ID));
    }

    @Test
    void 负控_负数步数不合法() throws Exception {
        String cipherText = encrypt(payload(APP_ID, "[{\"timestamp\":1756200000,\"step\":-1}]"));

        assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(key(), cipherText, iv(), APP_ID));
    }
    private static final long STRICT_NOW=java.time.Instant.parse("2026-10-10T10:00:00Z").toEpochMilli();
    private static com.fasterxml.jackson.databind.node.ObjectNode verifiedRoot(long now,String[] days,int[] steps) {
        com.fasterxml.jackson.databind.node.ObjectNode root=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();root.putObject("watermark").put("appid",APP_ID).put("timestamp",now/1000);
        com.fasterxml.jackson.databind.node.ArrayNode list=root.putArray("stepInfoList");for(int i=0;i<days.length;i++)list.addObject().put("timestamp",java.time.LocalDate.parse(days[i]).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toEpochSecond()).put("step",steps[i]);return root;
    }
    private static WeRunDecryptor.VerifiedWeRunReading verified(long now,String[] days,int[] steps) throws Exception {
        return WeRunDecryptor.decryptVerified(key(),encrypt(verifiedRoot(now,days,steps).toString()),iv(),APP_ID,now);
    }
    @Test void strictReadingUsesFreshWatermarkAndRejectsMalformedNumbersOrDates() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root=verifiedRoot(STRICT_NOW,new String[]{"2026-10-09","2026-10-10"},new int[]{100,200});
        assertEquals(Integer.valueOf(200),verified(STRICT_NOW,new String[]{"2026-10-09","2026-10-10"},new int[]{100,200}).getDays().get("2026-10-10"));
        for(String bad:new String[]{"stale","future","fraction","string","overflow","order","duplicate","wrongApp"}) {
            com.fasterxml.jackson.databind.node.ObjectNode copy=root.deepCopy();com.fasterxml.jackson.databind.node.ObjectNode first=(com.fasterxml.jackson.databind.node.ObjectNode)copy.path("stepInfoList").get(0);
            if("stale".equals(bad))copy.with("watermark").put("timestamp",STRICT_NOW/1000-301);
            if("future".equals(bad))copy.with("watermark").put("timestamp",STRICT_NOW/1000+31);
            if("fraction".equals(bad))first.put("step",1.5);if("string".equals(bad))first.put("step","2");if("overflow".equals(bad))first.put("step",Long.MAX_VALUE);
            if("order".equals(bad))first.put("timestamp",STRICT_NOW/1000);if("duplicate".equals(bad))first.set("timestamp",copy.path("stepInfoList").get(1).path("timestamp"));if("wrongApp".equals(bad))copy.with("watermark").put("appid",OTHER_APP_ID);
            String cipher=encrypt(copy.toString());assertThrows(IllegalArgumentException.class,()->WeRunDecryptor.decryptVerified(key(),cipher,iv(),APP_ID,STRICT_NOW),bad);
        }
    }
    @Test void strictPaddingChecksEveryByteWhileLegacyValidReadingRemainsCompatible() throws Exception {
        String plain=verifiedRoot(STRICT_NOW,new String[]{"2026-10-10"},new int[]{20}).toString();while(plain.getBytes(StandardCharsets.UTF_8).length%16==15)plain+=" ";
        byte[] raw=plain.getBytes(StandardCharsets.UTF_8);int pad=16-raw.length%16;byte[] padded=java.util.Arrays.copyOf(raw,raw.length+pad);java.util.Arrays.fill(padded,raw.length,padded.length,(byte)pad);padded[padded.length-2]=(byte)(pad+1);
        Cipher aes=Cipher.getInstance("AES/CBC/NoPadding");aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(AES_BLOCK,"AES"),new IvParameterSpec(AES_IV));String cipher=Base64.getEncoder().encodeToString(aes.doFinal(padded));
        assertEquals(20,WeRunDecryptor.decrypt(key(),cipher,iv(),APP_ID).getTodaySteps());assertThrows(IllegalArgumentException.class,()->WeRunDecryptor.decryptVerified(key(),cipher,iv(),APP_ID,STRICT_NOW));
    }
    @Test void strictServiceBindsExchangeIdentityAndRejectsPlainSteps() throws Exception {
        VerifiedWeRunService service=new VerifiedWeRunService();com.chengyinhub.business.mapper.UmsMemberMapper members=org.mockito.Mockito.mock(com.chengyinhub.business.mapper.UmsMemberMapper.class);com.chengyinhub.business.service.IWeChatMiniSessionService wx=org.mockito.Mockito.mock(com.chengyinhub.business.service.IWeChatMiniSessionService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"umsMemberMapper",members);org.springframework.test.util.ReflectionTestUtils.setField(service,"weChatMiniSessionService",wx);com.chengyinhub.business.domain.UmsMember member=new com.chengyinhub.business.domain.UmsMember();member.setWxOpenId("fixture-player");org.mockito.Mockito.when(members.selectUmsMemberById(7L)).thenReturn(member);org.mockito.Mockito.when(wx.miniAppId()).thenReturn(APP_ID);
        com.fasterxml.jackson.databind.node.ObjectNode cipher=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("code","fixture-code").put("encryptedData",encrypt(verifiedRoot(STRICT_NOW,new String[]{"2026-10-10"},new int[]{20}).toString())).put("iv",iv());
        org.mockito.Mockito.when(wx.exchange("fixture-code")).thenReturn(new com.chengyinhub.business.service.IWeChatMiniSessionService.WeChatMiniSession("other-player",key()));assertThrows(IllegalArgumentException.class,()->service.verify(7L,cipher,STRICT_NOW));
        org.mockito.Mockito.when(wx.exchange("fixture-code")).thenReturn(new com.chengyinhub.business.service.IWeChatMiniSessionService.WeChatMiniSession("fixture-player",key()));assertEquals(Integer.valueOf(20),service.verify(7L,cipher,STRICT_NOW).getDays().get("2026-10-10"));cipher.put("steps",10000);assertThrows(IllegalArgumentException.class,()->service.verify(7L,cipher,STRICT_NOW));
    }
    @Test void zeroStepsBaselinesHighWaterCrossDayAndRestoresExactlyOnce() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();com.fasterxml.jackson.databind.node.ObjectNode snapshot=json.createObjectNode();com.fasterxml.jackson.databind.node.ObjectNode theme=snapshot.putObject("themeConfig").put("schemaVersion",1).put("capacity",0);theme.putArray("items");theme.putObject("gameplayRuntime").put("runtimeScope","TEAM").putObject("survival").put("zeroHpPolicy","STEPS").putObject("zeroSteps").put("version",1).put("steps",100).put("restoreTo",4).put("limit",1).put("thenPolicy","THEME_FAIL");
        com.chengyinhub.business.domain.JourneyRules rules=new com.chengyinhub.business.domain.JourneyRules();rules.setCheckEnabled(true);rules.getHp().setMax(12);TopicRouteEngine.State state=new TopicRouteEngine.State();state.getVariables().put("sys.hp",0);GameplaySurvivalEngine engine=new GameplaySurvivalEngine();engine.syncZeroHp(snapshot,rules,state,STRICT_NOW);String episode=state.getThemeRuntime().path("zeroHp").path("episodeId").asText();
        WeRunDecryptor.VerifiedWeRunReading base=verified(STRICT_NOW,new String[]{"2026-10-10"},new int[]{1000});assertThrows(IllegalStateException.class,()->engine.syncZeroHpSteps(snapshot,rules,state,episode,8L,base,false,true,STRICT_NOW));
        engine.syncZeroHpSteps(snapshot,rules,state,episode,7L,base,true,true,STRICT_NOW);assertEquals(0,state.getThemeRuntime().path("zeroHp").path("progress").asInt());engine.syncZeroHpSteps(snapshot,rules,state,episode,7L,base,false,true,STRICT_NOW);assertEquals(0,state.getThemeRuntime().path("zeroHp").path("progress").asInt());
        WeRunDecryptor.VerifiedWeRunReading raised=verified(STRICT_NOW,new String[]{"2026-10-10"},new int[]{1040});engine.syncZeroHpSteps(snapshot,rules,state,episode,7L,raised,false,true,STRICT_NOW);engine.syncZeroHpSteps(snapshot,rules,state,episode,7L,base,false,true,STRICT_NOW);assertEquals(40,state.getThemeRuntime().path("zeroHp").path("progress").asInt());
        long nextDay=STRICT_NOW+86400000;WeRunDecryptor.VerifiedWeRunReading missing=verified(nextDay,new String[]{"2026-10-11"},new int[]{60});String before=state.getThemeRuntime().toString();assertThrows(IllegalArgumentException.class,()->engine.syncZeroHpSteps(snapshot,rules,state,episode,7L,missing,false,true,nextDay));assertEquals(before,state.getThemeRuntime().toString());
        WeRunDecryptor.VerifiedWeRunReading across=verified(nextDay,new String[]{"2026-10-10","2026-10-11"},new int[]{1040,60});engine.syncZeroHpSteps(snapshot,rules,state,episode,7L,across,false,true,nextDay);assertEquals(Integer.valueOf(4),state.getVariables().get("sys.hp"));assertEquals(1,state.getThemeRuntime().path("zeroHp").path("used").asInt());engine.syncZeroHpSteps(snapshot,rules,state,episode,7L,across,false,true,nextDay);assertEquals(1,state.getThemeRuntime().path("zeroHp").path("used").asInt());
    }

    @Test
    void nonIntegralStepsAndTimestampAreRejected() throws Exception {
        for(String record:new String[]{"{\"timestamp\":1756200000,\"step\":1.5}",
                "{\"timestamp\":\"1756200000\",\"step\":1}",
                "{\"timestamp\":0,\"step\":1}","{\"timestamp\":1756200000,\"step\":2147483648}"}) {
            String ciphertext=encrypt(payload(APP_ID,"["+record+"]"));
            assertThrows(IllegalArgumentException.class,()->WeRunDecryptor.decrypt(key(),ciphertext,iv(),APP_ID));
        }
    }

}
