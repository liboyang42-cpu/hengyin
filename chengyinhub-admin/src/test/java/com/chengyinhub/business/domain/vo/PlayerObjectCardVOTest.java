package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.PlayerObjectCard;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 藏品卡回包的帧清单(转台藏品卡施工文档 §2.2 / §4.7)。
 *
 * <p>客户端只认 {@code frames}:1 张 = 照片卡,24 张 = 转台。所以这里钉的是
 * 「什么时候给 24 张」——只有 READY 且帧数据齐全才给;其余一律只给原照片,
 * 绝不给出一个空数组让客户端画一张什么都没有的卡。
 *
 * <p>另钉一条:{@code cutoutUrl} / {@code category} 原样透出(第四版的抠图贴纸与藏品册分类),
 * 没值就是 null,不兜成空串。
 */
class PlayerObjectCardVOTest {

    private static final String PHOTO = "/profile/upload/window.jpg";
    private static final String PREFIX = "https://cdn.chengyinhub.com/profile/upload/card-frames/42/";

    @Test
    void READY时给出24个帧地址_按00到23编号() {
        List<String> frames = PlayerObjectCardVO.from(card("READY", PREFIX, 24)).getFrames();

        assertEquals(24, frames.size());
        assertEquals(PREFIX + "00.webp", frames.get(0));
        assertEquals(PREFIX + "23.webp", frames.get(23));
    }

    @Test
    void 未就绪时只给原照片() {
        for (String status : new String[] { "NONE", "QUEUED", "GENERATING", "FAILED" }) {
            assertEquals(Collections.singletonList(PHOTO),
                    PlayerObjectCardVO.from(card(status, null, 0)).getFrames(), status);
        }
    }

    /** 负控:状态说 READY、帧数据却不齐 —— 退回照片卡,不能出一张空卡。 */
    @Test
    void 负控_READY但帧数据缺失时退回原照片() {
        assertEquals(Collections.singletonList(PHOTO), PlayerObjectCardVO.from(card("READY", null, 24)).getFrames());
        assertEquals(Collections.singletonList(PHOTO), PlayerObjectCardVO.from(card("READY", PREFIX, 0)).getFrames());
    }

    @Test
    void 生成状态原样透出_空值按NONE() {
        assertEquals("GENERATING", PlayerObjectCardVO.from(card("GENERATING", null, 0)).getGenStatus());
        assertEquals("NONE", PlayerObjectCardVO.from(card(null, null, null)).getGenStatus());
    }

    /**
     * 抠图贴纸与分类(施工文档 §2.1 / §2.4):卡面用不用抠图、藏品册按哪类筛选,都由客户端看着
     * 这两个字段决定,回包必须原样带出。没抠出来就是 null —— 兜成空串会让前端判「有贴纸」。
     */
    @Test
    void 透出抠图地址与分类() {
        PlayerObjectCard source = card("NONE", null, 0);
        source.setCutoutUrl("https://cdn.chengyinhub.com/profile/upload/card-cutout/42.png");
        source.setCategory("电子产品");

        PlayerObjectCardVO vo = PlayerObjectCardVO.from(source);

        assertEquals("https://cdn.chengyinhub.com/profile/upload/card-cutout/42.png", vo.getCutoutUrl());
        assertEquals("电子产品", vo.getCategory());
    }

    @Test
    void 没抠出来时抠图与分类都是空() {
        PlayerObjectCardVO vo = PlayerObjectCardVO.from(card("READY", PREFIX, 24));

        assertNull(vo.getCutoutUrl());
        assertNull(vo.getCategory());
    }

    /**
     * 物品在原照片里的位置:库里是「x,y,w,h」四个比例,回包给数组。当场那一屏用它让背景散掉时
     * 物品留在原位;格式不对(少一个数、越出 0 到 1)就当没有 —— 位置错了比没有更难看。
     */
    @Test
    void 物品位置按四个比例给出_格式不对就不给() {
        PlayerObjectCard source = card("NONE", null, 0);
        source.setCutoutBox("0.1000,0.2500,0.5000,0.5000");
        assertEquals(java.util.Arrays.asList(0.1, 0.25, 0.5, 0.5), PlayerObjectCardVO.from(source).getCutoutBox());

        for (String bad : new String[] { null, "", "0.1,0.2,0.3", "a,b,c,d", "0.2,0.2,0.9,0.5", "-0.1,0.2,0.3,0.4",
                "0.1,0.2,0,0.4" }) {
            source.setCutoutBox(bad);
            assertNull(PlayerObjectCardVO.from(source).getCutoutBox(), String.valueOf(bad));
        }
    }

    @Test
    void 地点原样带出() {
        PlayerObjectCard source = card("NONE", null, 0);
        source.setPlace("预制人生 · 第一百货");
        assertEquals("预制人生 · 第一百货", PlayerObjectCardVO.from(source).getPlace());
    }

    private static PlayerObjectCard card(String status, String prefix, Integer frameCount) {
        PlayerObjectCard card = new PlayerObjectCard();
        card.setId(42L);
        card.setSourceUrl(PHOTO);
        card.setGenStatus(status);
        card.setFramesPrefix(prefix);
        card.setFrameCount(frameCount);
        return card;
    }
}
