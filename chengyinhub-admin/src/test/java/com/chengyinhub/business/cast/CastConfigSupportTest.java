package com.chengyinhub.business.cast;

import com.chengyinhub.business.service.support.CastConfigSupport;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 角色(Cast)配置 v1:规范化 + 上限 / 颜色 / 名字唯一 / 头像 / 声音校验。 */
class CastConfigSupportTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    static ObjectNode cast(String id, String name, String color)
    {
        ObjectNode c = JSON.createObjectNode();
        c.put("id", id);
        c.put("name", name);
        c.put("identity", "邮局局长");
        c.put("avatarUrl", "");
        c.put("color", color);
        c.putObject("voice").put("mode", "preset").put("preset", "warm_female");
        return c;
    }

    static ObjectNode config()
    {
        ObjectNode cfg = JSON.createObjectNode();
        cfg.put("enabled", true);
        cfg.put("playerName", true);
        cfg.put("playerIdentity", true);
        cfg.putArray("identities").addObject().put("key", "detective").put("title", "侦探").put("sub", "擅长推理");
        cfg.putArray("cast").add(cast("c_abc123", "林姐", "#E8604C"));
        return cfg;
    }

    private static ServiceException rejected(JsonNode cfg)
    {
        ServiceException e = assertThrows(ServiceException.class, () -> CastConfigSupport.normalize(cfg));
        assertEquals(400, e.getCode());
        return e;
    }

    @Test
    void 合法配置规范化_未知字段丢弃_null为关闭() throws Exception
    {
        ObjectNode cfg = config();
        cfg.put("secret", "x");
        ((ObjectNode) cfg.get("cast").get(0)).put("hidden", 1);
        String stored = CastConfigSupport.normalize(cfg);
        JsonNode back = JSON.readTree(stored);
        assertFalse(back.has("secret"));
        assertFalse(back.get("cast").get(0).has("hidden"));
        assertEquals("warm_female", back.get("cast").get(0).get("voice").get("preset").asText());
        assertNull(CastConfigSupport.normalize(null));
        assertNull(CastConfigSupport.normalize(JSON.nullNode()));
        assertNull(CastConfigSupport.parse(null));
        // 也接受 JSON 字符串
        assertEquals(stored, CastConfigSupport.normalize(JSON.getNodeFactory().textNode(cfg.toString())));
    }

    @Test
    void 上限_身份最多4个_角色最多12个()
    {
        ObjectNode cfg = config();
        ArrayNode ids = cfg.putArray("identities");
        for (int i = 0; i < 5; i++) ids.addObject().put("key", "k" + i).put("title", "身份" + i).put("sub", "");
        assertTrue(rejected(cfg).getMessage().contains("最多 4"));

        ObjectNode many = config();
        ArrayNode cast = many.putArray("cast");
        for (int i = 0; i < 13; i++) cast.add(cast("c_abcde" + (char) ('a' + i), "角色" + i, "#3D8BFD"));
        assertTrue(rejected(many).getMessage().contains("最多 12"));

        ObjectNode twelve = config();
        ArrayNode ok = twelve.putArray("cast");
        for (int i = 0; i < 12; i++) ok.add(cast("c_abcde" + (char) ('a' + i), "角色" + i, "#3D8BFD"));
        CastConfigSupport.normalize(twelve);
    }

    @Test
    void 颜色只能是六个预设()
    {
        for (String color : new String[] {"#E8604C", "#3D8BFD", "#2FB57A", "#B98AF0", "#F2B33D", "#8E8E93"})
        {
            ObjectNode cfg = config();
            ((ObjectNode) cfg.get("cast").get(0)).put("color", color);
            CastConfigSupport.normalize(cfg);
        }
        ObjectNode bad = config();
        ((ObjectNode) bad.get("cast").get(0)).put("color", "#000000");
        rejected(bad);
        ObjectNode lower = config();
        ((ObjectNode) lower.get("cast").get(0)).put("color", "#e8604c");
        rejected(lower);
    }

    @Test
    void 角色名唯一_1到8字_id格式与唯一()
    {
        ObjectNode dupName = config();
        ((ArrayNode) dupName.get("cast")).add(cast("c_xyz789", "林姐", "#3D8BFD"));
        assertTrue(rejected(dupName).getMessage().contains("角色名重复"));

        ObjectNode dupId = config();
        ((ArrayNode) dupId.get("cast")).add(cast("c_abc123", "老周", "#3D8BFD"));
        assertTrue(rejected(dupId).getMessage().contains("角色标识重复"));

        ObjectNode longName = config();
        ((ObjectNode) longName.get("cast").get(0)).put("name", "一二三四五六七八九");
        rejected(longName);
        ObjectNode emptyName = config();
        ((ObjectNode) emptyName.get("cast").get(0)).put("name", "  ");
        rejected(emptyName);

        for (String id : new String[] {"c_abc12", "c_ABC123", "x_abc123", "c_abcdefghijklm"})
        {
            ObjectNode cfg = config();
            ((ObjectNode) cfg.get("cast").get(0)).put("id", id);
            rejected(cfg);
        }
    }

    @Test
    void 身份标题长度_说明长度_开启身份必须有选项()
    {
        ObjectNode longTitle = config();
        ((ObjectNode) longTitle.get("identities").get(0)).put("title", "一二三四五六七八九");
        rejected(longTitle);
        ObjectNode longSub = config();
        ((ObjectNode) longSub.get("identities").get(0)).put("sub", "一二三四五六七八九十一二三四五六七八九十一二三四五");
        rejected(longSub);
        ObjectNode none = config();
        none.putArray("identities");
        rejected(none);
        ObjectNode off = config();
        off.put("playerIdentity", false);
        off.putArray("identities");
        CastConfigSupport.normalize(off);
    }

    @Test
    void 头像只认空_https_素材引用_声音预设校验()
    {
        for (String ok : new String[] {"", "https://img.example.cn/a.png", "media-asset:42"})
        {
            ObjectNode cfg = config();
            ((ObjectNode) cfg.get("cast").get(0)).put("avatarUrl", ok);
            CastConfigSupport.normalize(cfg);
        }
        for (String bad : new String[] {"http://img.example.cn/a.png", "media-asset:0", "/local/a.png"})
        {
            ObjectNode cfg = config();
            ((ObjectNode) cfg.get("cast").get(0)).put("avatarUrl", bad);
            rejected(cfg);
        }
        ObjectNode badPreset = config();
        ((ObjectNode) badPreset.get("cast").get(0).get("voice")).put("preset", "robot");
        rejected(badPreset);
        ObjectNode noPreset = config();
        ((ObjectNode) noPreset.get("cast").get(0).get("voice")).remove("preset");
        rejected(noPreset);
        ObjectNode badMode = config();
        ((ObjectNode) badMode.get("cast").get(0).get("voice")).put("mode", "clone");
        rejected(badMode);
        ObjectNode record = config();
        ((ObjectNode) record.get("cast").get(0).get("voice")).put("mode", "record").remove("preset");
        CastConfigSupport.normalize(record);
    }

    @Test
    void 布尔字段类型错误被拒()
    {
        ObjectNode cfg = config();
        cfg.put("enabled", "yes");
        rejected(cfg);
    }

    @Test
    void 线索与结局台词_规范化往返_上限与长度() throws Exception
    {
        ObjectNode cfg = config();
        ObjectNode member = (ObjectNode) cfg.get("cast").get(0);
        ArrayNode clues = member.putArray("clues");
        clues.addObject().put("text", " 她左手有道疤 ").putNull("chapterId");
        clues.addObject().put("text", "她三年前离开过乐队").put("chapterId", 55);
        clues.addObject().put("text", "字符串章节号也认").put("chapterId", "56");
        member.putObject("endingLines").put("low", "你走吧。").put("mid", "").put("high", "下次还一起来。").put("extra", "丢");
        JsonNode back = JSON.readTree(CastConfigSupport.normalize(cfg)).get("cast").get(0);
        assertEquals(3, back.get("clues").size());
        assertEquals("她左手有道疤", back.get("clues").get(0).get("text").asText());
        assertTrue(back.get("clues").get(0).get("chapterId").isNull());
        assertEquals(55L, back.get("clues").get(1).get("chapterId").asLong());
        assertEquals(56L, back.get("clues").get(2).get("chapterId").asLong());
        assertEquals("你走吧。", back.get("endingLines").get("low").asText());
        assertFalse(back.get("endingLines").has("mid"));
        assertFalse(back.get("endingLines").has("extra"));

        ObjectNode tooMany = config();
        ArrayNode seven = ((ObjectNode) tooMany.get("cast").get(0)).putArray("clues");
        for (int i = 0; i < 7; i++) seven.addObject().put("text", "线索" + i).putNull("chapterId");
        assertTrue(rejected(tooMany).getMessage().contains("最多 6"));

        ObjectNode emptyText = config();
        ((ObjectNode) emptyText.get("cast").get(0)).putArray("clues").addObject().put("text", "  ").putNull("chapterId");
        rejected(emptyText);

        ObjectNode longText = config();
        ((ObjectNode) longText.get("cast").get(0)).putArray("clues").addObject().put("text", "字".repeat(41)).putNull("chapterId");
        rejected(longText);

        ObjectNode badChapter = config();
        ((ObjectNode) badChapter.get("cast").get(0)).putArray("clues").addObject().put("text", "x").put("chapterId", "第一章");
        rejected(badChapter);

        ObjectNode longLine = config();
        ((ObjectNode) longLine.get("cast").get(0)).putObject("endingLines").put("high", "字".repeat(61));
        rejected(longLine);
        ObjectNode sixty = config();
        ((ObjectNode) sixty.get("cast").get(0)).putObject("endingLines").put("high", "字".repeat(60));
        CastConfigSupport.normalize(sixty);
    }

    @Test
    void 录音声音_只在record时保留url_且必须是素材或https() throws Exception
    {
        ObjectNode cfg = config();
        ((ObjectNode) cfg.get("cast").get(0)).putObject("voice").put("mode", "record").put("url", "media-asset:12");
        assertEquals("media-asset:12", JSON.readTree(CastConfigSupport.normalize(cfg)).get("cast").get(0).get("voice").get("url").asText());

        ObjectNode preset = config();
        ((ObjectNode) preset.get("cast").get(0)).putObject("voice").put("mode", "preset").put("preset", "youth").put("url", "media-asset:12");
        assertFalse(JSON.readTree(CastConfigSupport.normalize(preset)).get("cast").get(0).get("voice").has("url"));

        ObjectNode bad = config();
        ((ObjectNode) bad.get("cast").get(0)).putObject("voice").put("mode", "record").put("url", "http://insecure.example.cn/a.mp3");
        rejected(bad);
    }

    @Test
    void 玩家视图_未解锁线索不下发正文_只给lockedCount() throws Exception
    {
        ObjectNode cfg = config();
        ArrayNode clues = ((ObjectNode) cfg.get("cast").get(0)).putArray("clues");
        clues.addObject().put("text", "开局就知道").putNull("chapterId");
        clues.addObject().put("text", "第一章才知道").put("chapterId", 55);
        clues.addObject().put("text", "第二章才知道").put("chapterId", 56);
        JsonNode stored = CastConfigSupport.parse(CastConfigSupport.normalize(cfg));

        JsonNode guest = CastConfigSupport.playerView(stored, java.util.Collections.<Long>emptySet()).get("cast").get(0);
        assertEquals(1, guest.get("clues").size());
        assertEquals("开局就知道", guest.get("clues").get(0).get("text").asText());
        assertEquals(2, guest.get("lockedCount").asInt());
        assertFalse(guest.toString().contains("第一章才知道"));
        assertFalse(guest.toString().contains("第二章才知道"));

        JsonNode mid = CastConfigSupport.playerView(stored, new java.util.HashSet<>(java.util.Arrays.asList(55L))).get("cast").get(0);
        assertEquals(2, mid.get("clues").size());
        assertEquals(1, mid.get("lockedCount").asInt());
        assertFalse(mid.toString().contains("第二章才知道"));

        // 不改入参(作者读口仍是全文)
        assertEquals(3, stored.get("cast").get(0).get("clues").size());
        assertNull(CastConfigSupport.playerView(null, java.util.Collections.<Long>emptySet()));
        // 没有线索的角色也给 lockedCount=0,不多出 clues 键
        ObjectNode plain = config();
        JsonNode none = CastConfigSupport.playerView(CastConfigSupport.parse(CastConfigSupport.normalize(plain)), null).get("cast").get(0);
        assertFalse(none.has("clues"));
        assertEquals(0, none.get("lockedCount").asInt());
    }
}
