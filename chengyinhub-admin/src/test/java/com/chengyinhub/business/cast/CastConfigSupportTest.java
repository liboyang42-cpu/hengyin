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
}
