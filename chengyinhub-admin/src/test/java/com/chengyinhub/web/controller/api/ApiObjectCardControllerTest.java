package com.chengyinhub.web.controller.api;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.PlayerObjectCard;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.business.service.ObjectCardService;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 藏品卡读接口(立体藏品卡契约 §4.4):只有 list 与 rename 两个,memberId 一律从登录态取。
 *
 * <p>承重的是越权面:list 只能拿到自己的、rename 只能改自己的,
 * 而「自己的」这个事实只能来自登录态 —— 所以这里既测行为,也测**签名**:
 * 入口方法一旦多出 memberId/cardOwnerId 之类的参数,就是有人把归属交给了客户端。
 */
class ApiObjectCardControllerTest {

    private static final long MEMBER_ID = 9L;

    private ObjectCardService objectCardService;
    private ApiObjectCardController controller;
    private AppMediaReferenceGuard mediaReferences;

    @BeforeEach
    void setUp() {
        objectCardService = mock(ObjectCardService.class);
        controller = new ApiObjectCardController() {
            @Override
            Long uid() {
                return MEMBER_ID;
            }
        };
        ReflectionTestUtils.setField(controller, "objectCardService", objectCardService);
        mediaReferences = mock(AppMediaReferenceGuard.class);
        ReflectionTestUtils.setField(controller, "mediaReferences", mediaReferences);
        when(mediaReferences.visibleUrl(any(), eq(MEMBER_ID))).thenAnswer(call -> call.getArgument(0));
    }

    // ==================== list ====================

    @Test
    void 列表按登录身份取_带分页字段() {
        when(objectCardService.list(eq(MEMBER_ID), isNull(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
        when(objectCardService.count(MEMBER_ID, null)).thenReturn(0);

        AjaxResult result = controller.list(1, 20, null);

        Map<?, ?> data = dataOf(result);
        assertEquals(0, data.get("total"));
        assertEquals(1, data.get("pageNum"));
        assertEquals(20, data.get("pageSize"));
        verify(objectCardService).list(MEMBER_ID, null, 0, 20);
    }

    /** 藏品册可以很长,一页最多 50 张:客户端传 9999 也不能一次拉光。 */
    @Test
    void 负控_分页夹取_页大小不超过50且页码不会算出负偏移() {
        when(objectCardService.list(any(), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
        when(objectCardService.count(anyLong(), any())).thenReturn(0);

        controller.list(1, 9999, null);
        controller.list(0, null, null);
        controller.list(-5, 20, null);
        controller.list(Integer.MAX_VALUE, 20, null);

        verify(objectCardService, times(1)).list(MEMBER_ID, null, 0, 50);
        // 页码 0 / 负数 / 大到溢出偏移,都退回第一页,且绝不把负数交给 MySQL LIMIT
        verify(objectCardService, times(3)).list(MEMBER_ID, null, 0, 20);
        verify(objectCardService, times(4)).list(any(), any(), anyInt(), anyInt());
    }

    /**
     * 藏品册按分类筛(施工文档 §3.3):筛选在库里做,分页与总数跟着筛过的结果走 ——
     * 在客户端筛,第一页之外的同类卡永远翻不到,计数也对不上。空白当「全部」。
     */
    @Test
    void 按分类筛_分页与总数跟着筛过的走_空白当全部() {
        when(objectCardService.list(eq(MEMBER_ID), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());

        controller.list(1, 20, " 电子产品 ");
        controller.list(1, 20, "   ");

        verify(objectCardService).list(MEMBER_ID, "电子产品", 0, 20);
        verify(objectCardService).count(MEMBER_ID, "电子产品");
        verify(objectCardService).list(MEMBER_ID, null, 0, 20);
        verify(objectCardService).count(MEMBER_ID, null);
    }

    @Test
    void 未登录不读任何卡() {
        ApiObjectCardController anonymous = new ApiObjectCardController() {
            @Override
            Long uid() {
                return 0L;
            }
        };
        ReflectionTestUtils.setField(anonymous, "objectCardService", objectCardService);

        AjaxResult result = anonymous.list(1, 20, null);

        assertEquals(500, result.get("code"));
        verify(objectCardService, never()).list(any(), any(), anyInt(), anyInt());
    }

    /** 回包只给小程序要渲染的字段:归属 id 与库内事实不外泄。 */
    @Test
    void 负控_列表回包不序列化memberId() {
        PlayerObjectCard card = card(11L, "一枚弹壳");
        card.setMemberId(4242L);
        when(objectCardService.list(eq(MEMBER_ID), isNull(), anyInt(), anyInt())).thenReturn(Arrays.asList(card));
        when(objectCardService.count(MEMBER_ID, null)).thenReturn(1);

        String json = JSON.toJSONString(controller.list(1, 20, null));

        assertTrue(json.contains("一枚弹壳"), json);
        assertTrue(json.contains("window.jpg"), json);
        assertFalse(json.contains("4242"), "memberId 不能出现在回包里:" + json);
        assertFalse(json.contains("memberId"), "同上:" + json);
    }

    @Test
    void heldAiOutputIsResolvedForItsOwnerAndNeverReturnedAsAnAssetUrn() {
        PlayerObjectCard card = card(11L, "已识别物品");
        card.setCutoutUrl("media-asset:41");
        when(objectCardService.list(eq(MEMBER_ID), isNull(), anyInt(), anyInt())).thenReturn(Collections.singletonList(card));
        when(mediaReferences.visibleUrl("media-asset:41", MEMBER_ID)).thenReturn("https://private/cutout?expires=300");
        String json = JSON.toJSONString(controller.list(1, 20, null));
        assertTrue(json.contains("expires=300"));
        assertFalse(json.contains("media-asset:"));
        verify(mediaReferences).visibleUrl("media-asset:41", MEMBER_ID);
    }

    @Test
    void unknownMediaDoesNotLeakThroughCardListOrRename() {
        PlayerObjectCard card = card(11L, "旧图片");
        card.setSourceUrl("https://unknown/image.jpg");
        card.setCutoutUrl("https://unknown/cutout.png");
        when(mediaReferences.visibleUrl("https://unknown/image.jpg", MEMBER_ID)).thenReturn("");
        when(mediaReferences.visibleUrl("https://unknown/cutout.png", MEMBER_ID)).thenReturn("");
        when(objectCardService.list(eq(MEMBER_ID), isNull(), anyInt(), anyInt())).thenReturn(Collections.singletonList(card));
        when(objectCardService.rename(MEMBER_ID, 11L, "新名称")).thenReturn(card);
        assertFalse(JSON.toJSONString(controller.list(1, 20, null)).contains("https://unknown"));
        assertFalse(JSON.toJSONString(controller.rename(11L, "新名称")).contains("https://unknown"));
    }

    // ==================== rename ====================

    @Test
    void 改名带的是登录身份不是卡的主人() {
        when(objectCardService.rename(MEMBER_ID, 11L, "新名字")).thenReturn(card(11L, "新名字"));

        AjaxResult result = controller.rename(11L, "新名字");

        assertEquals(200, result.get("code"));
        verify(objectCardService).rename(MEMBER_ID, 11L, "新名字");
        assertTrue(JSON.toJSONString(result).contains("新名字"));
    }

    /** 负控:改别人的卡 = 服务返回 null(归属不匹配),入口必须报错而不是回一份别人的数据。 */
    @Test
    void 负控_改不到别人的卡() {
        when(objectCardService.rename(MEMBER_ID, 11L, "新名字")).thenReturn(null);

        AjaxResult result = controller.rename(11L, "新名字");

        assertEquals(500, result.get("code"));
        assertTrue(String.valueOf(result.get("msg")).contains("不是你的"), String.valueOf(result.get("msg")));
    }

    @Test
    void 空名字与超长名字都不入库() {
        assertEquals(500, controller.rename(11L, "   ").get("code"));

        StringBuilder title = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            title.append('壳');
        }
        assertEquals(500, controller.rename(11L, title.toString()).get("code"));
        verify(objectCardService, never()).rename(anyLong(), anyLong(), any());
    }

    /** 承重:归属只来自登录态 —— 两个入口的签名里一旦出现 memberId 就是把它交给了客户端。 */
    @Test
    void 负控_两个入口的入参里都没有归属字段() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/com/chengyinhub/web/controller/api/ApiObjectCardController.java"
        )), java.nio.charset.StandardCharsets.UTF_8);

        for (String signature : entryPointSignatures(source)) {
            assertFalse(signature.toLowerCase().contains("memberid"),
                    "入口不该再接收归属参数:" + signature);
        }
    }

    private static List<String> entryPointSignatures(String source) {
        List<String> signatures = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("public AjaxResult (?:list|rename)\\(([^)]*)\\)").matcher(source);
        while (matcher.find()) {
            signatures.add(matcher.group(1));
        }
        assertEquals(2, signatures.size(), "两个入口都在契约里,少一个说明入口被改名或删了:" + signatures);
        return signatures;
    }

    private static PlayerObjectCard card(long id, String title) {
        PlayerObjectCard card = new PlayerObjectCard();
        card.setId(id);
        card.setSourceUrl("https://cdn.chengyinhub.com/profile/upload/window.jpg");
        card.setTitle(title);
        card.setCaption("把窗框也拍进去");
        card.setCardStyle("plain");
        card.setOriginType("PLAY");
        card.setOriginId("29");
        return card;
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> dataOf(AjaxResult result) {
        return (Map<?, ?>) result.get("data");
    }
}
