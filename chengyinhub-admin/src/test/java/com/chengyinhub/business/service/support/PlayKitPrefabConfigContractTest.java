package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预制人生四个新玩法段(profile / photoCheck / note / typeIn)的配置校验契约。
 *
 * check 段不在本测试范围:R14 已有 check(checkId/tier/mods)是旅程检定,照旧由
 * 现成的 validateCheck 管,不做形状判别、不新增玩法段。
 */
class PlayKitPrefabConfigContractTest {

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();

    private static String wrap(String section) {
        return "{\"schemaVersion\":1," + section + "}";
    }

    private static final String PROFILE = "\"profile\":{\"enabled\":true,\"title\":\"出生登记\","
            + "\"lead\":\"填完这张表,你就有了身份\",\"xp\":0,"
            + "\"avatar\":{\"enabled\":true,\"required\":false},"
            + "\"questions\":[{\"key\":\"name\",\"label\":\"你叫什么\",\"kind\":\"text\","
            + "\"maxLength\":12,\"required\":true},"
            + "{\"key\":\"job\",\"label\":\"你做什么工作\",\"kind\":\"pick\",\"options\":["
            + "{\"key\":\"clerk\",\"label\":\"店员\",\"effects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":1}]},"
            + "{\"key\":\"painter\",\"label\":\"画师\",\"effects\":[{\"op\":\"INC\",\"var\":\"counter.energy\",\"value\":1}]}]}]}";

    private static final String PHOTO_CHECK = "\"photoCheck\":{\"enabled\":true,\"title\":\"拍一张窗外\","
            + "\"shotNote\":\"把窗框也拍进去\",\"requirement\":\"画面里要能看见窗外的街景,窗框入镜\","
            + "\"minConfidence\":60,\"maxTries\":3,\"fallback\":\"retake\",\"xp\":0}";

    private static final String NOTE = "\"note\":{\"enabled\":true,\"title\":\"写一句留给下一个人\","
            + "\"prompt\":\"你看到窗外什么\",\"maxLength\":40,"
            + "\"presets\":[\"天是灰的\",\"有人在等红灯\"],\"showPrevious\":3,\"xp\":0}";

    private static final String TYPE_IN = "\"typeIn\":{\"enabled\":true,\"title\":\"打出这行字\","
            + "\"target\":\"hello world\",\"seconds\":10,\"caseSensitive\":false,\"tries\":0,\"xp\":0}";

    // ==================== 在册 ====================

    @Test
    void 四个新段任一开启都算有高级玩法() {
        // 漏认一个 = 配了玩法但 hasAdvanced 仍 false,玩家走到点直接打卡、玩法静默不存在
        for (String section : new String[] { PROFILE, PHOTO_CHECK, NOTE, TYPE_IN }) {
            assertTrue(validator.hasEnabledMechanic(wrap(section)),
                    "该段开启后必须被认作高级玩法:" + section);
        }
    }

    @Test
    void 未开启的段不校验内部字段() {
        assertFalse(validator.hasEnabledMechanic(wrap("\"typeIn\":{\"enabled\":false,\"seconds\":1}")));
        assertFalse(validator.hasEnabledMechanic(wrap("\"note\":{\"enabled\":false,\"maxLength\":999}")));
    }

    @Test
    void 负控_建档加成绝不下发到公开投影() {
        String safe = com.chengyinhub.business.util.AdvancedGamePublicProjection.safeConfigJson(wrap(PROFILE));

        // 加成露出来就成了攻略表:effects / sys.luck / counter.energy 一个字都不该出现
        assertFalse(safe.contains("effects"), safe);
        assertFalse(safe.contains("sys.luck"), safe);
        assertFalse(safe.contains("counter.energy"), safe);
        // 但题面与选项要在
        assertTrue(safe.contains("店员"), safe);
        assertTrue(safe.contains("你叫什么"), safe);
    }

    @Test
    void 四个新段在公开投影里都要有形状() {
        String safe = com.chengyinhub.business.util.AdvancedGamePublicProjection.safeConfigJson(
                wrap(PROFILE + "," + PHOTO_CHECK + "," + NOTE + "," + TYPE_IN));

        assertTrue(safe.contains("\"photoCheck\""), safe);
        assertTrue(safe.contains("\"note\""), safe);
        assertTrue(safe.contains("\"typeIn\""), safe);
        assertTrue(safe.contains("hello world"), "target 就是打字题的题面:" + safe);
    }

    // ==================== profile ====================

    @Test
    void 负控_建档问题key必须是合法标识() {
        String bad = PROFILE.replace("\"name\"", "\"1name\"");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(bad)));
    }

    @Test
    void 负控_建档选择题少于两个选项判红() {
        String bad = "\"profile\":{\"enabled\":true,\"questions\":["
                + "{\"key\":\"job\",\"label\":\"你做什么工作\",\"kind\":\"pick\","
                + "\"options\":[{\"key\":\"clerk\",\"label\":\"店员\"}]}]}";
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(bad)));
    }

    @Test
    void 负控_建档加成按现成effects校验_非法变量名判红() {
        // 加成改成 effects 形状后,变量名必须落在具名键/系统变量白名单里 ——
        // 写错变量名不会报错,只会让加成永远不生效
        String bad = PROFILE.replace("\"var\":\"counter.energy\"", "\"var\":\"energy\"");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(bad)));
    }

    @Test
    void 负控_建档加成非法运算符判红() {
        String bad = PROFILE.replace("\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":1",
                "\"op\":\"SET\",\"var\":\"sys.luck\",\"value\":1");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(bad)));
    }

    private static final String KACCHA = PROFILE.replace("\"kind\":\"pick\",\"options\"",
            "\"kind\":\"pick\",\"override\":{\"requires\":[{\"op\":\"GTE\",\"var\":\"counter.instab\",\"value\":4}],"
                    + "\"option\":\"clerk\",\"voice\":\"这一栏已经填好了。\"},\"options\"");

    @Test
    void 咔哒配置保存后原样保留_公开投影不露() {
        String normalized = validator.validateAndNormalize(wrap(KACCHA));
        assertTrue(normalized.contains("counter.instab"), normalized);
        String safe = com.chengyinhub.business.util.AdvancedGamePublicProjection.safeConfigJson(wrap(KACCHA));
        assertFalse(safe.contains("override"), safe);
        assertFalse(safe.contains("这一栏已经填好了"), safe);
    }

    @Test
    void 负控_咔哒没写条件或改写成不存在的选项判红() {
        String noWhen = KACCHA.replace("[{\"op\":\"GTE\",\"var\":\"counter.instab\",\"value\":4}]", "[]");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(noWhen)));
        String badOption = KACCHA.replace("\"option\":\"clerk\"", "\"option\":\"astronaut\"");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(badOption)));
        String badVar = KACCHA.replace("counter.instab", "instab");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(badVar)));
    }

    // ==================== photoCheck ====================

    @Test
    void 负控_拍照要求缺失为空或超长判红() {
        // requirement 是给视觉模型的唯一题面:缺了模型无从判断,必须拦在保存这一步
        String missing = "\"photoCheck\":{\"enabled\":true,\"title\":\"拍一张窗外\","
                + "\"maxTries\":3,\"fallback\":\"retake\"}";
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(missing)));

        String empty = PHOTO_CHECK.replace("\"画面里要能看见窗外的街景,窗框入镜\"", "\"\"");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(empty)));

        StringBuilder long61 = new StringBuilder();
        for (int i = 0; i < 61; i++) long61.append('拍');
        String tooLong = PHOTO_CHECK.replace("画面里要能看见窗外的街景,窗框入镜", long61.toString());
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(tooLong)));
    }

    @Test
    void 负控_拍照置信度阈值越界判红() {
        String high = PHOTO_CHECK.replace("\"minConfidence\":60", "\"minConfidence\":101");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(high)));
        String low = PHOTO_CHECK.replace("\"minConfidence\":60", "\"minConfidence\":-1");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(low)));
    }

    @Test
    void 负控_旧版客户端报分rule段不再被接受() {
        // rule.mode / threshold 那套(客户端启发式报分)已整组作废:
        // 只带 rule 不带 requirement 的旧配置必须判红,防止旧形状悄悄混进新语义
        String legacy = "\"photoCheck\":{\"enabled\":true,\"title\":\"拍一张窗外\","
                + "\"shotNote\":\"把窗框也拍进去\",\"rule\":{\"mode\":\"brightArea\",\"threshold\":60},"
                + "\"maxTries\":3,\"fallback\":\"retake\"}";
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(legacy)));
    }

    @Test
    void 负控_旧版rule与threshold绝不下发公开投影() {
        String safe = com.chengyinhub.business.util.AdvancedGamePublicProjection.safeConfigJson(wrap(PHOTO_CHECK));
        assertTrue(safe.contains("\"requirement\""), "玩家要知道要拍到什么:" + safe);
        assertFalse(safe.contains("\"rule\""), "旧版 rule 不存在了:" + safe);
        assertFalse(safe.contains("threshold"), "旧版 threshold 不存在了:" + safe);
    }

    @Test
    void 负控_拍照兜底枚举必须收口() {
        String fallback = PHOTO_CHECK.replace("\"fallback\":\"retake\"", "\"fallback\":\"manual\"");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(fallback)));
    }

    @Test
    void 负控_拍照次数越界判红() {
        String zero = PHOTO_CHECK.replace("\"maxTries\":3", "\"maxTries\":0");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(zero)));
        String tooMany = PHOTO_CHECK.replace("\"maxTries\":3", "\"maxTries\":11");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(tooMany)));
    }

    // ---------- photoCheck · 取景轮廓(2026-09-22 现场感三件套 S1) ----------

    /** 在 PHOTO_CHECK 段尾追加字段：既有真值一个不动，只加要测的那个。 */
    private static String photoCheckWith(String extraField) {
        return PHOTO_CHECK.replace("\"xp\":0}", "\"xp\":0," + extraField + "}");
    }

    @Test
    void 取景轮廓是选填的_不配也照常通过且不凭空补值() {
        // 没配 frameUrl 的存量配置必须与加字段之前同判 —— 老主题不能因为一个新字段被判红
        String plain = validator.validateAndNormalize(wrap(PHOTO_CHECK));
        assertFalse(plain.contains("frameUrl"), "没配就不该冒出这个键:" + plain);
        assertFalse(plain.contains("frameOpacity"), "不透明度是缺省不补:" + plain);
        validator.validateAndNormalize(wrap(photoCheckWith("\"frameUrl\":\"/profile/outline.png\"")));
    }

    @Test
    void 取景轮廓不透明度越界夹回0至100并写回配置() {
        // 与 normalizeSkillLevels 同一立场：越界值夹合法后落库，商家下次编辑看到的就是真会生效的那个
        String high = validator.validateAndNormalize(
                wrap(photoCheckWith("\"frameUrl\":\"https://www.chengyinhub.com/a.png\",\"frameOpacity\":999")));
        assertTrue(high.contains("\"frameOpacity\":100"), high);
        String low = validator.validateAndNormalize(
                wrap(photoCheckWith("\"frameUrl\":\"/profile/a.png\",\"frameOpacity\":-5")));
        assertTrue(low.contains("\"frameOpacity\":0"), low);
    }

    @Test
    void 负控_取景轮廓只能是https或站内路径() {
        // 与全仓素材字段同一条规则：只认 https 或站内路径，防 javascript: 与协议相对地址塞进 <image src>
        for (String bad : new String[] {"\"http://a.com/x.png\"", "\"//a.com/x.png\"",
                "\"javascript:alert(1)\"", "\"x.png\"", "\"\""}) {
            String json = wrap(photoCheckWith("\"frameUrl\":" + bad));
            String label = bad + " 不该被接受";
            assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(json), label);
        }
    }

    @Test
    void 负控_取景轮廓超长判红() {
        StringBuilder longUrl = new StringBuilder("https://www.chengyinhub.com/");
        while (longUrl.length() <= 512) longUrl.append('a');
        String json = wrap(photoCheckWith("\"frameUrl\":\"" + longUrl + "\""));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(json));
    }

    @Test
    void 取景轮廓随题面进公开投影() {
        // 轮廓是给玩家对位用的，属于公开题面那一份；判定仍然只在服务端按 requirement 判
        String safe = com.chengyinhub.business.util.AdvancedGamePublicProjection.safeConfigJson(
                wrap(photoCheckWith("\"frameUrl\":\"/profile/outline.png\",\"frameOpacity\":60")));
        assertTrue(safe.contains("\"frameUrl\":\"/profile/outline.png\""), "题面要下发:" + safe);
        assertTrue(safe.contains("\"frameOpacity\":60"), safe);
    }

    @Test
    void 负控_未配取景轮廓时公开投影不多这两个键() {
        String safe = com.chengyinhub.business.util.AdvancedGamePublicProjection.safeConfigJson(wrap(PHOTO_CHECK));
        assertFalse(safe.contains("frameUrl"), "没配的字段不该凭空出现:" + safe);
        assertFalse(safe.contains("frameOpacity"), safe);
    }

    // ==================== 拍物成卡(立体藏品卡契约 §2.1)====================

    /** 卡片那三个字段的合法形状:mode=CARD + 卡名 + 样式。这一段与拍照审核共用,不新建段。 */
    private static String withCard(String extra) {
        return PHOTO_CHECK.replace("\"fallback\":\"retake\"", "\"fallback\":\"retake\"," + extra);
    }

    @Test
    void 老模板不带卡片三个键照样放行() {
        // 上线前存下来的配置里没有这三个键。要求它们存在 = 把存量模板全判死。
        assertDoesNotThrow(() -> validator.validateAndNormalize(wrap(PHOTO_CHECK)));
    }

    @Test
    void 拍物成卡的合法配置放行() {
        assertDoesNotThrow(() -> validator.validateAndNormalize(wrap(
                withCard("\"mode\":\"CARD\",\"cardTitle\":\"一枚弹壳\",\"cardStyle\":\"plain\""))));
        // cardStyle 可空 = 用默认高光;只有填了值又不在名单里才算脏
        assertDoesNotThrow(() -> validator.validateAndNormalize(wrap(withCard("\"mode\":\"CARD\""))));
    }

    @Test
    void 负控_卡片的mode与样式枚举和卡名长度收口() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(
                withCard("\"mode\":\"COIN\""))), "没登记的 mode 不能放行:它会静默铸出一张没人配过的卡");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(
                withCard("\"cardStyle\":\"gold\""))), "没登记的样式不能放行");
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 65; i++) longTitle.append('壳');
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(
                withCard("\"mode\":\"CARD\",\"cardTitle\":\"" + longTitle + "\""))),
                "卡名落 player_object_card.title(varchar 64),超长要在保存时拦住");
    }

    @Test
    void 负控_卡片字段不得被公开投影剥掉() {
        // 采用别人的模板时走 safeConfigJson:mode 被剥掉 = 复刻出来的节点悄悄退回普通拍照审核
        String safe = com.chengyinhub.business.util.AdvancedGamePublicProjection.safeConfigJson(
                wrap(withCard("\"mode\":\"CARD\",\"cardTitle\":\"一枚弹壳\",\"cardStyle\":\"plain\"")));
        assertTrue(safe.contains("\"mode\":\"CARD\""), "mode 是玩法判别键,被剥掉就换玩法了:" + safe);
        assertTrue(safe.contains("一枚弹壳"), "卡名是题面的一部分:" + safe);
    }

    // ==================== note ====================

    @Test
    void 负控_留言预设超过六条判红() {
        String bad = NOTE.replace("[\"天是灰的\",\"有人在等红灯\"]",
                "[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\"]");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(bad)));
    }

    @Test
    void 负控_留言展示条数越界判红() {
        String bad = NOTE.replace("\"showPrevious\":3", "\"showPrevious\":6");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(bad)));
    }

    @Test
    void 负控_留言长度上限越界判红() {
        String bad = NOTE.replace("\"maxLength\":40", "\"maxLength\":41");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(bad)));
    }

    // ==================== typeIn ====================

    @Test
    void 负控_打字时限越界判红() {
        String shortLimit = TYPE_IN.replace("\"seconds\":10", "\"seconds\":2");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(shortLimit)));
        String longLimit = TYPE_IN.replace("\"seconds\":10", "\"seconds\":121");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(longLimit)));
    }

    @Test
    void 负控_打字目标为空或超长判红() {
        String empty = TYPE_IN.replace("\"target\":\"hello world\"", "\"target\":\"\"");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(empty)));
        StringBuilder long45 = new StringBuilder();
        for (int i = 0; i < 45; i++) long45.append('a');
        String tooLong = TYPE_IN.replace("hello world", long45.toString());
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(tooLong)));
    }

    @Test
    void 负控_打字次数越界判红() {
        String bad = TYPE_IN.replace("\"tries\":0", "\"tries\":11");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(bad)));
    }
}
