package com.chengyinhub.business.service.ai;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.common.config.RuoYiConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 认物(转台藏品卡施工文档 §2.4):一件拍过的物品 → 中文卡名 + 固定清单里的分类。
 *
 * <p>与拍照审核**共用视觉通道,但完全是另一次调用**:审核那条链有测试与负控钉着判定,
 * 认物不能蹭它的提示词(要认「这是什么」,不是「拍没拍到」),也不蹭它的额度。
 *
 * <p>这一层的契约是「拿不到就用兜底」:关着、通道没装配、超时、回话不是 JSON、字段缺失,
 * 一律返回 null(= 没有结果),**不抛出去**。铸卡在并行分支上等它 4 秒,抛出等于把一张
 * 本来能出的卡带走。
 *
 * <p>分类清单是真源:清单外一律「其他」,而空值是「没认出来」不是「其他」—— 卡面上这两件事不同。
 * 图片走 base64 data URL 不发 URL,这条与 {@link VisionImageEncoder} 的 2026-09-18 实测一致。
 */
class ObjectNamerTest {

    private static final String IMAGE_URL = "/profile/upload/window.jpg";

    private ObjectNamer namer;
    private Path profileDir;
    private String originalProfile;
    private AtomicReference<String> sentImage;
    private AtomicReference<String> sentSystemPrompt;

    @BeforeEach
    void setUp() throws Exception {
        profileDir = Files.createTempDirectory("object-namer");
        originalProfile = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(profileDir.toString());
        File uploadDir = profileDir.resolve("upload").toFile();
        assertTrue(uploadDir.mkdirs());
        Files.write(uploadDir.toPath().resolve("window.jpg"), jpeg(2, 2));

        namer = new ObjectNamer();
        ReflectionTestUtils.setField(namer, "enabled", true);
        sentImage = new AtomicReference<>();
        sentSystemPrompt = new AtomicReference<>();
    }

    @AfterEach
    void tearDown() {
        new RuoYiConfig().setProfile(originalProfile);
        deleteRecursively(profileDir.toFile());
    }

    // ==================== 认出来了 ====================

    @Test
    void 认出名字与分类_发的是base64不是URL() {
        modelSays("{\"name\":\"搪瓷茶缸\",\"category\":\"日用杂物\"}");

        ObjectNamer.Naming naming = namer.name(IMAGE_URL);

        assertEquals("搪瓷茶缸", naming.getName());
        assertEquals("日用杂物", naming.getCategory());
        assertTrue(sentImage.get().startsWith("data:image/jpeg;base64,"),
                "把 URL 交给模型必然 400(实测),只能发 data URL:" + sentImage.get());
    }

    /** 提示词里没有清单,模型就只能猜格式:「清单外归其他」会变成常态而不是例外。 */
    @Test
    void 系统提示里带全分类清单与字数上限() {
        modelSays("{\"name\":\"弹壳\",\"category\":\"玩具摆件\"}");

        namer.name(IMAGE_URL);

        String prompt = sentSystemPrompt.get();
        for (String category : ObjectNamer.CATEGORIES) {
            assertTrue(prompt.contains(category), "提示词漏了分类:" + category);
        }
        assertTrue(prompt.contains(String.valueOf(ObjectNamer.NAME_MAX)), "提示词没交代名字长度上限:" + prompt);
    }

    @Test
    void 模型把JSON包在围栏里也认() {
        modelSays("结论:\n```json\n{\"name\":\"老式收音机\",\"category\":\"电子产品\"}\n```\n");

        ObjectNamer.Naming naming = namer.name(IMAGE_URL);

        assertEquals("老式收音机", naming.getName());
        assertEquals("电子产品", naming.getCategory());
    }

    @Test
    void 分类两侧的空格不影响判定() {
        modelSays("{\"name\":\"书包\",\"category\":\"  书籍文具  \"}");

        assertEquals("书籍文具", namer.name(IMAGE_URL).getCategory());
    }

    // ==================== 分类清单 ====================

    /** 负控靶子:去掉「清单外归其他」的映射,这条就会绿着把「数码配件」写进分类列。 */
    @Test
    void 负控_清单外的分类归其他() {
        modelSays("{\"name\":\"数据线\",\"category\":\"数码配件\"}");

        assertEquals("其他", namer.name(IMAGE_URL).getCategory());
    }

    /** 空分类是「没认出来」,不是「其他」:库里 varchar 留空,卡面才不会显示一个凭空冒出来的分类。 */
    @Test
    void 分类为空就是没认出来_不是其他() {
        modelSays("{\"name\":\"一枚弹壳\",\"category\":\"\"}");

        assertNull(namer.name(IMAGE_URL).getCategory());
    }

    @Test
    void 只认出名或只认出分类都算一半结果() {
        modelSays("{\"name\":\"搪瓷茶缸\"}");
        assertEquals("搪瓷茶缸", namer.name(IMAGE_URL).getName());

        modelSays("{\"category\":\"服饰\"}");
        ObjectNamer.Naming onlyCategory = namer.name(IMAGE_URL);
        assertNull(onlyCategory.getName());
        assertEquals("服饰", onlyCategory.getCategory());
    }

    // ==================== 卡名长度 ====================

    @Test
    void 名字超长截到十二字() {
        modelSays("{\"name\":\"这是一只上世纪六十年代的搪瓷茶缸\",\"category\":\"日用杂物\"}");

        String name = namer.name(IMAGE_URL).getName();

        assertEquals(12, name.length(), name);
        assertEquals("这是一只上世纪六十年代的", name);
    }

    /** 截断落在代理对中间会留下半个字符(小程序侧渲染成 ?),宁可不放那一个字。 */
    @Test
    void 截断不切开代理对() {
        // 11 个汉字 + 一个 emoji(占 2 个 char)= 13 char:截到 12 正好落在代理对中间
        modelSays("{\"name\":\"上世纪六十年代的搪瓷茶" + "\uD83D\uDE6A" + "\",\"category\":\"日用杂物\"}");

        String name = namer.name(IMAGE_URL).getName();

        assertEquals(11, name.length(), name);
        assertFalse(Character.isHighSurrogate(name.charAt(name.length() - 1)), "末尾留了半个代理对:" + name);
    }

    // ==================== 没有结果:一律 null,不抛出 ====================

    @Test
    void 开关关着时不调模型也没有结果() {
        ReflectionTestUtils.setField(namer, "enabled", false);
        AtomicBoolean called = new AtomicBoolean(false);
        model(called, () -> "{\"name\":\"弹壳\",\"category\":\"玩具摆件\"}");

        assertNull(namer.name(IMAGE_URL));
        assertFalse(called.get(), "关着就不该发这次调用");
    }

    @Test
    void 通道没装配也没有结果且不抛出() {
        assertNull(namer.name(IMAGE_URL), "visionClient 没装配时不能把异常抛给铸卡");
    }

    @Test
    void 模型调用炸了也没有结果() {
        modelThrows(new RuntimeException("AI 模型调用失败(429)"));

        assertNull(namer.name(IMAGE_URL));
    }

    @Test
    void 回话不是JSON或缺字段就没有结果() {
        modelSays("抱歉,我无法判断这张照片。");
        assertNull(namer.name(IMAGE_URL));

        modelSays("{}");
        assertNull(namer.name(IMAGE_URL));

        modelSays("{\"pass\":true}");
        assertNull(namer.name(IMAGE_URL));
    }

    @Test
    void 空地址与超限图都挡在发模型之前() {
        AtomicBoolean called = new AtomicBoolean(false);
        model(called, () -> "{}");

        assertNull(namer.name(null));
        assertNull(namer.name("   "));
        assertNull(namer.name("data:image/bmp;base64,Qk0AAAAA"));
        assertFalse(called.get(), "认不出/超限的图片不该发出去等模型报错");
    }

    // ==================== 额度 ====================

    /**
     * 认物**不计入**拍照那条链的额度池(施工文档 §2.4 与任务书):每张卡只认一次、跟着铸卡走。
     * 蹭那个池会让玩家的照片审核次数被铸卡悄悄吃掉 —— 少掉的那几次没人报错,只会静默降级。
     */
    @Test
    void 认物没有次数上限_连认三十次都照常调模型() {
        AtomicInteger calls = new AtomicInteger();
        model(new AtomicBoolean(), () -> {
            calls.incrementAndGet();
            return "{\"name\":\"搪瓷茶缸\",\"category\":\"日用杂物\"}";
        });

        for (int i = 0; i < 30; i++) {
            assertNotNull(namer.name(IMAGE_URL), "第 " + (i + 1) + " 次没认出来:被什么额度挡住了");
        }
        assertEquals(30, calls.get());
    }

    // ==================== 脚手架 ====================

    private void modelSays(String answer) {
        ReflectionTestUtils.setField(namer, "visionClient", new LlmClient() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public String chat(String systemPrompt, String userPrompt) {
                throw new UnsupportedOperationException("认物只能走视觉通道");
            }

            @Override
            public String chatVision(String systemPrompt, String imageUrl, String userPrompt) {
                sentSystemPrompt.set(systemPrompt);
                sentImage.set(imageUrl);
                return answer;
            }
        });
    }

    private void modelThrows(RuntimeException failure) {
        ReflectionTestUtils.setField(namer, "visionClient", new LlmClient() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public String chat(String systemPrompt, String userPrompt) {
                throw failure;
            }

            @Override
            public String chatVision(String systemPrompt, String imageUrl, String userPrompt) {
                throw failure;
            }
        });
    }

    private void model(AtomicBoolean called, Answer answer) {
        ReflectionTestUtils.setField(namer, "visionClient", new LlmClient() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public String chat(String systemPrompt, String userPrompt) {
                called.set(true);
                return answer.get();
            }

            @Override
            public String chatVision(String systemPrompt, String imageUrl, String userPrompt) {
                called.set(true);
                sentImage.set(imageUrl);
                return answer.get();
            }
        });
    }

    private interface Answer {
        String get();
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg", out);
        return out.toByteArray();
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
