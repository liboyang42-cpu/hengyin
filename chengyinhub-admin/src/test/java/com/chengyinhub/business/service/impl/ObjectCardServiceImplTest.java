package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.PlayerObjectCard;
import com.chengyinhub.business.mapper.PlayerObjectCardMapper;
import com.chengyinhub.business.service.ai.CardCutout;
import com.chengyinhub.business.service.ai.CardCutoutStorage;
import com.chengyinhub.business.service.ai.ImageSegClient;
import com.chengyinhub.business.service.ai.ObjectNamer;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 铸卡本体(转台藏品卡施工文档 §4.2):来源闸 → 抠图 + 认物并行 → 一行 upsert。
 *
 * <p>这里钉的是三件「出事就静默」的东西:
 * ① 来源闸 —— 这张图之后要交给第三方(抠图、3D 生成),别人传的任意 URL 不能接;
 * ② 抠图与认物是加分项:各自 4 秒预算、总等待也是 4 秒量级,任何一件没结果都只让卡面朴素一点,
 *    绝不能把一张本来能出的卡带走;
 * ③ 铸卡仍然不排队做 3D:那是后台任务的事,这里落的卡 gen_status 一律 NONE。
 *
 * <p>upsert 走的是同一条 mapper 调用:覆盖语义交给 uk_member_card_origin,
 * 服务层不查「在不在」再决定插还是改 —— 那是并发下会多写一行的写法。
 */
class ObjectCardServiceImplTest {

    private static final long MEMBER_ID = 7L;
    private static final long NODE_ID = 29L;
    private static final Date NOW = new Date(1_775_000_000_000L);
    private static final String OSS_URL = "https://cdn.chengyinhub.com/profile/upload/window.jpg";
    /** 抠图链路的两个地址:临时结果 / 转存之后的自家地址(只有后者能进 cutout_url)。 */
    private static final String TEMP_CUTOUT_URL =
            "https://imageseg-result-cn-shanghai.oss-cn-shanghai.aliyuncs.com/tmp.png";
    private static final String OUR_CUTOUT_URL = "https://cdn.chengyinhub.com/upload/object_card_cutout/seg.png";
    /** 物品在原照片里的位置(x,y,w,h 比例),跟着抠图地址一起落库。 */
    private static final String BOX = "0.1000,0.2500,0.5000,0.5000";
    /** 整形之后交给商品分割的地址(合规原图就是原地址,超限/站内路径是转存出来的新地址)。 */
    private static final String SEG_INPUT_URL = "https://cdn.chengyinhub.com/upload/seg-input.jpg";
    /**
     * 预算与上限的取法:上限必须**小于两倍预算**,才分得开「两件共用一个截止点」(约 1 个预算)
     * 与「两件各等各的」(约 2 个预算,线上就是 4 + 4 = 8 秒)。600 / 1000 / 1200 之间各留几百毫秒,
     * CI 负载高时也不至于误判。卡住时长 2 秒远大于两者,串行版本同样会红。
     */
    private static final long TASK_BUDGET_MS = 600L;
    private static final long STUCK_MS = 2000L;
    private static final long WAIT_LIMIT_MS = 1000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private PlayerObjectCardMapper cardMapper;
    /** 桩记下商品分割实际收到的地址;segInput 是整形那一步交出的地址(null = 整形没结果)。 */
    private final java.util.List<String> segmentedUrls = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile String segInput = SEG_INPUT_URL;
    private ObjectCardServiceImpl service;

    @BeforeEach
    void setUp() {
        cardMapper = mock(PlayerObjectCardMapper.class);
        service = new ObjectCardServiceImpl() {
            /** 配置缝:生产从 OSS 配置算允许域,单测不碰环境变量。 */
            @Override
            boolean isAllowedOrigin(String url) {
                return url != null && (url.startsWith("/profile/upload/") || url.startsWith("https://cdn.chengyinhub.com/"));
            }
        };
        ReflectionTestUtils.setField(service, "cardMapper", cardMapper);
        com.chengyinhub.business.service.support.AppMediaReferenceGuard media = mock(com.chengyinhub.business.service.support.AppMediaReferenceGuard.class);
        when(media.requireAttachment(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(true)))
                .thenAnswer(call -> call.getArgument(1));
        when(media.processingUrl(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq("IMAGE")))
                .thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(service, "mediaReferences", media);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "consentGuard", org.mockito.Mockito.mock(com.chengyinhub.business.service.ConsentGuard.class));
        ReflectionTestUtils.setField(service, "wxContentSecurityService", mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        ReflectionTestUtils.setField(service, "umsMemberService", mock(com.chengyinhub.business.service.IUmsMemberService.class));
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> NOW);
    }

    // ==================== 卡面内容 ====================

    @Test
    void separateConsentRefusalPreventsAnyProviderCallOrMediaWrite() {
        com.chengyinhub.common.config.ComplianceProperties versions = new com.chengyinhub.common.config.ComplianceProperties();
        versions.setDocVersions(java.util.Map.of("photo_model_processing", "v1.0", "voice_clone_processing", "v1.0"));
        com.chengyinhub.business.service.ConsentGuard guard = new com.chengyinhub.business.service.ConsentGuard(
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IConsentRecordService.class), versions);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "consentGuard", guard);
        org.junit.jupiter.api.Assertions.assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.mint(7L, "/profile/upload/photo.jpg", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(), "PLAY", "1"));
        org.mockito.Mockito.verifyNoInteractions(cardMapper);
        org.junit.jupiter.api.Assertions.assertTrue(segmentedUrls.isEmpty());
    }

    @Test
    void 卡名卡样式一句话都按模板落库() {
        PlayerObjectCard card = mint("{\"mode\":\"CARD\",\"cardTitle\":\"一枚弹壳\",\"cardStyle\":\"plain\","
                + "\"title\":\"拍一张窗外\",\"shotNote\":\"把窗框也拍进去\"}");

        assertEquals("一枚弹壳", card.getTitle());
        assertEquals("plain", card.getCardStyle());
        assertEquals("把窗框也拍进去", card.getCaption());
        assertEquals(OSS_URL, card.getSourceUrl());
        assertEquals(MEMBER_ID, card.getMemberId().longValue());
        assertEquals("PLAY", card.getOriginType());
        assertEquals(String.valueOf(NODE_ID), card.getOriginId());
        assertEquals(NOW, card.getCreateTime());
        assertEquals(NOW, card.getUpdateTime());
    }

    /** 模板没起名时用玩法段标题顶着:卡上总得有字,空白卡读起来像坏了。 */
    @Test
    void 没起卡名时用玩法标题_再没有就用兜底名() {
        assertEquals("拍一张窗外", mint("{\"mode\":\"CARD\",\"title\":\"拍一张窗外\"}").getTitle());
        assertEquals("藏品卡", mint("{\"mode\":\"CARD\"}").getTitle());
    }

    /** 发布侧已经拦过 64 字,铸卡这里再收一次:库里 varchar(64) 截断在 MySQL 是警告不是失败。 */
    @Test
    void 负控_超长卡名与超长一句话在落库前截到列宽() {
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            longTitle.append('壳');
        }
        StringBuilder longShotNote = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            longShotNote.append('窗');
        }
        PlayerObjectCard card = mint("{\"mode\":\"CARD\",\"cardTitle\":\"" + longTitle + "\",\"shotNote\":\""
                + longShotNote + "\"}");

        assertEquals(64, card.getTitle().length());
        assertEquals(128, card.getCaption().length());
    }

    /** 样式只认 foil / plain:脏值一律按 foil,不能把未知串写进列里让前端猜。 */
    @Test
    void 负控_未知样式退回foil() {
        assertEquals("foil", mint("{\"mode\":\"CARD\",\"cardStyle\":\"hologram\"}").getCardStyle());
        assertEquals("foil", mint("{\"mode\":\"CARD\"}").getCardStyle());
    }

    // ==================== 来源闸 ====================

    @Test
    void 站内相对路径与自家域名放行() {
        assertEquals("/profile/upload/window.jpg", mintCardWithUrl("/profile/upload/window.jpg").getSourceUrl());
        assertEquals(OSS_URL, mintCardWithUrl(OSS_URL).getSourceUrl());
    }

    /** 负控:外域地址不铸卡 —— 这张图之后要交给 3D 生成服务,这里是它唯一的来源闸。 */
    @Test
    void 负控_外域图片地址拒绝且不落库() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.mint(MEMBER_ID, "https://attacker.example.com/x.jpg", config("{}"), "PLAY",
                        String.valueOf(NODE_ID)));

        assertTrue(error.getMessage().contains("照片"), error.getMessage());
        verify(cardMapper, never()).upsert(any());
    }

    @Test
    void 负控_空地址与超长地址一律拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.mint(MEMBER_ID, "", config("{}"), "PLAY", String.valueOf(NODE_ID)));
        StringBuilder url = new StringBuilder("https://cdn.chengyinhub.com/profile/upload/");
        for (int i = 0; i < 600; i++) {
            url.append('a');
        }
        assertThrows(IllegalArgumentException.class,
                () -> service.mint(MEMBER_ID, url.toString(), config("{}"), "PLAY", String.valueOf(NODE_ID)));
    }

    /** 来源只能是玩法:商家侧 L1(契约 §7)没做,不能提前写进表里。 */
    @Test
    void 负控_未知来源类型拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.mint(MEMBER_ID, OSS_URL, config("{}"), "MERCHANT", "x"));
    }

    // ==================== 生成状态 ====================

    /** 铸出来的一律是照片卡:3D 由后台任务补,这一步不排队(Phase 5 再接),抠图与认物不在此列。 */
    @Test
    void 铸出的是照片卡_生成状态NONE_没有帧() {
        PlayerObjectCard card = mint("{\"mode\":\"CARD\",\"cardTitle\":\"弹壳\"}");

        assertEquals("NONE", card.getGenStatus());
        assertEquals(0, card.getFrameCount().intValue());
        assertNull(card.getFramesPrefix());
    }

    // ==================== 抠图 + 认物(并行加分项) ====================

    /** 两件都回来:抠图地址、AI 名、分类一起进那一行,卡面才有贴纸与分类。 */
    @Test
    void 两件都成功_抠图地址与AI名与分类一起落库() {
        stubCutoutAndNaming("一枚弹壳", "玩具摆件", OUR_CUTOUT_URL, 0L, 0L);

        PlayerObjectCard card = mint("{\"mode\":\"CARD\",\"cardTitle\":\"玩法名\",\"cardStyle\":\"plain\"}");

        assertEquals("一枚弹壳", card.getTitle());
        assertEquals("玩具摆件", card.getCategory());
        assertEquals(OUR_CUTOUT_URL, card.getCutoutUrl());
        assertEquals(BOX, card.getCutoutBox(), "位置不落库,当场那一屏的物品就没法留在原位");
        assertEquals("NONE", card.getGenStatus());
    }

    @Test
    void 抠图没结果时认物照用_卡面退成没贴纸的AI名卡() {
        stubCutoutAndNaming("一枚弹壳", "玩具摆件", null, 0L, 0L);

        PlayerObjectCard card = mint("{\"mode\":\"CARD\",\"cardTitle\":\"玩法名\"}");

        assertNull(card.getCutoutUrl());
        assertNull(card.getCutoutBox());
        assertEquals("一枚弹壳", card.getTitle());
        assertEquals("玩具摆件", card.getCategory());
    }

    @Test
    void 认物没结果时抠图照用_卡名退回模板名_分类留空() {
        stubCutoutAndNaming(null, null, OUR_CUTOUT_URL, 0L, 0L);

        PlayerObjectCard card = mint("{\"mode\":\"CARD\",\"cardTitle\":\"玩法名\"}");

        assertEquals(OUR_CUTOUT_URL, card.getCutoutUrl());
        assertEquals("玩法名", card.getTitle());
        assertNull(card.getCategory());
    }

    /** 两件都没有结果 = 第四版之前的那张卡:照片卡照样铸出来,一个字段都不欠。 */
    @Test
    void 两件都没结果_卡照样铸_三个字段都空() {
        stubCutoutAndNaming(null, null, null, 0L, 0L);

        PlayerObjectCard card = mint("{\"mode\":\"CARD\",\"cardTitle\":\"玩法名\"}");

        assertNull(card.getCutoutUrl());
        assertNull(card.getCategory());
        assertEquals("玩法名", card.getTitle());
        verify(cardMapper).upsert(any());
    }

    /** 送给商品分割的必须是整形过的地址:原地址可能超 3MB / 2000px,或是阿里云取不到的站内相对路径。 */
    @Test
    void 送给抠图接口的是整形之后的地址() {
        stubCutoutAndNaming("一枚弹壳", "玩具摆件", OUR_CUTOUT_URL, 0L, 0L);

        mint("{\"mode\":\"CARD\"}");

        assertEquals(java.util.Collections.singletonList(SEG_INPUT_URL), segmentedUrls);
    }

    @Test
    void 照片整形没结果时不调抠图接口_卡照样铸() {
        stubCutoutAndNaming("一枚弹壳", "玩具摆件", OUR_CUTOUT_URL, 0L, 0L);
        segInput = null;

        PlayerObjectCard card = mint("{\"mode\":\"CARD\"}");

        assertTrue(segmentedUrls.isEmpty(), "照片都没整形好就别花一次接口调用:" + segmentedUrls);
        assertNull(card.getCutoutUrl());
        assertEquals("一枚弹壳", card.getTitle());
    }

    /** 卡名顺序(施工文档 §2.4):AI 名 → 模板 cardTitle → 玩法段 title → 兜底名。 */
    @Test
    void 卡名按AI名_模板名_玩法标题_兜底名依次回落() {
        stubCutoutAndNaming("一枚弹壳", "日用杂物", OUR_CUTOUT_URL, 0L, 0L);
        assertEquals("一枚弹壳", mint("{\"mode\":\"CARD\",\"cardTitle\":\"玩法名\",\"title\":\"拍一张窗外\"}").getTitle());

        stubCutoutAndNaming(null, null, OUR_CUTOUT_URL, 0L, 0L);
        assertEquals("玩法名", mint("{\"mode\":\"CARD\",\"cardTitle\":\"玩法名\",\"title\":\"拍一张窗外\"}").getTitle());
        assertEquals("拍一张窗外", mint("{\"mode\":\"CARD\",\"title\":\"拍一张窗外\"}").getTitle());
        assertEquals("藏品卡", mint("{\"mode\":\"CARD\"}").getTitle());
    }

    /**
     * 预算与「并行」这一条一起钉:抠图桩卡 2 秒、认物桩立刻返回、预算 600 毫秒。
     *
     * <p>并行时 mint 到点就走(约 0.6 秒);写成先后串行就要等满 2 秒,时长断言立刻红。
     * 下限也钉住:预算没到就返回,说明根本没等这两件。
     */
    @Test
    void 抠图卡住超过预算时认物结果照用且不等双倍时间() {
        stubCutoutAndNaming("一枚弹壳", "玩具摆件", OUR_CUTOUT_URL, STUCK_MS, 0L);

        long startedAt = System.nanoTime();
        PlayerObjectCard card = mint("{\"mode\":\"CARD\",\"cardTitle\":\"玩法名\"}");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        // 时长先断:串行版本要红在「mint 到点就走」这一条上,而不是红在后面那个字段上
        assertTrue(elapsedMs < WAIT_LIMIT_MS, "两件是并行的,不该等到串行那么久:" + elapsedMs + "ms");
        assertTrue(elapsedMs >= TASK_BUDGET_MS / 2, "等这么短说明根本没等这两件:" + elapsedMs + "ms");
        assertNull(card.getCutoutUrl(), "抠图卡住就只能没有贴纸");
        assertEquals("一枚弹壳", card.getTitle(), "另一件的结果照用");
        assertEquals("玩具摆件", card.getCategory());
    }

    @Test
    void 认物卡住超过预算时抠图结果照用() {
        stubCutoutAndNaming("一枚弹壳", "玩具摆件", OUR_CUTOUT_URL, 0L, STUCK_MS);

        long startedAt = System.nanoTime();
        PlayerObjectCard card = mint("{\"mode\":\"CARD\",\"cardTitle\":\"玩法名\"}");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(elapsedMs < WAIT_LIMIT_MS, "总等待必须是 4 秒量级(这里是预算 " + TASK_BUDGET_MS + "ms):" + elapsedMs + "ms");
        assertEquals(OUR_CUTOUT_URL, card.getCutoutUrl());
        assertEquals("玩法名", card.getTitle(), "认物没赶上,卡名只能按兜底顺序来");
        assertNull(card.getCategory());
    }

    /**
     * 两件都卡住:到点就走,铸出来的还是那张照片卡。
     *
     * <p>这条是「共用一个截止点」的负控靶子:两件各算各的截止点时,总等待约 2 个预算(1200ms),
     * 超过上限 1000ms 会红。桩打断不了(见 {@link #blockIgnoringInterrupts}),和真实 socket 读一样。
     */
    @Test
    void 两件都卡住时到点就走_卡照样铸() {
        stubCutoutAndNaming("一枚弹壳", "玩具摆件", OUR_CUTOUT_URL, STUCK_MS, STUCK_MS);

        long startedAt = System.nanoTime();
        PlayerObjectCard card = mint("{\"mode\":\"CARD\",\"cardTitle\":\"玩法名\"}");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(elapsedMs < WAIT_LIMIT_MS, "总等待必须是一个预算而不是两个:" + elapsedMs + "ms");
        assertNull(card.getCutoutUrl());
        assertNull(card.getCategory());
        assertEquals("玩法名", card.getTitle());
        verify(cardMapper).upsert(any());
    }

    /**
     * 到点不等的那件必须收到「被放弃」的信号:socket 读不理中断,但读完之后转存那一步要靠这个标记
     * 决定还传不传(见 OssCardCutoutStorage)。没有 cancel(true),卡住的抠图读完照样上传成孤儿对象。
     */
    @Test
    void 超时被放弃的抠图_读完回来时带着中断标记() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean abandoned = new java.util.concurrent.atomic.AtomicBoolean();
        stubCutoutAndNaming(null, null, OUR_CUTOUT_URL, 0L, 0L);
        ReflectionTestUtils.setField(service, "cutoutStorage", new CardCutoutStorage() {
            @Override
            public String segmentInputOf(String photoUrl, long ownerId) {
                return SEG_INPUT_URL;
            }

            @Override
            public CardCutout transfer(String tempPngUrl, long ownerId) {
                blockIgnoringInterrupts(TASK_BUDGET_MS * 2);
                abandoned.set(Thread.currentThread().isInterrupted());
                finished.countDown();
                return new CardCutout(OUR_CUTOUT_URL, BOX);
            }
        });

        PlayerObjectCard card = mint("{\"mode\":\"CARD\"}");

        assertNull(card.getCutoutUrl());
        assertTrue(finished.await(STUCK_MS, TimeUnit.MILLISECONDS), "桩没跑完");
        assertTrue(abandoned.get(), "到点放弃的任务没被打上中断标记,读完会照样上传");
    }

    /**
     * 排不进就不做:把铸卡自己的池真塞满(4 个线程 + 20 个排队位全被卡住),两件都被拒,
     * 卡照样铸,而且**不等**—— 队列若是无界的,两件会排进去干等满一个预算,这里的时长断言会红。
     */
    @Test
    void 负控_两件排不进线程池时不做它们_卡照样铸() throws Exception {
        stubCutoutAndNaming("一枚弹壳", "玩具摆件", OUR_CUTOUT_URL, 0L, 0L);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "mintTasks");
        CountDownLatch release = new CountDownLatch(1);
        int filled = 0;
        try {
            while (true) {
                try {
                    pool.execute(() -> awaitQuietly(release));
                    filled++;
                } catch (RejectedExecutionException full) {
                    break;
                }
                assertTrue(filled <= 100, "塞了 100 件还没满:线程池不是有界的");
            }

            long started = System.nanoTime();
            PlayerObjectCard card = mint("{\"mode\":\"CARD\",\"cardTitle\":\"玩法名\"}");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertNull(card.getCutoutUrl());
            assertNull(card.getCategory());
            assertEquals("玩法名", card.getTitle());
            verify(cardMapper).upsert(any());
            assertTrue(elapsedMs < TASK_BUDGET_MS / 2, "排不进的任务不该再等预算:" + elapsedMs + "ms");
        } finally {
            release.countDown();
        }
    }

    /**
     * 两个玩家同时判过:每次铸卡要两个线程(抠图 + 认物),两次就是四个,必须同时跑起来。
     * 2026-09-24 审查实证:池子写成核心 2、最大 4、队列 20 时,JDK 线程池只在队列满了才加线程 ——
     * 第二个人的两件在队列里等第一个人的做完,等到截止点就超时,贴纸和 AI 名都没了。
     */
    @Test
    void 两个玩家同时判过_两张卡都在预算内拿到抠图与AI名() throws Exception {
        stubCutoutAndNaming("一枚弹壳", "玩具摆件", OUR_CUTOUT_URL, TASK_BUDGET_MS * 3 / 4, TASK_BUDGET_MS * 3 / 4);
        java.util.concurrent.ExecutorService players = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<PlayerObjectCard> first = players.submit(() -> mint("{\"mode\":\"CARD\"}"));
            java.util.concurrent.Future<PlayerObjectCard> second = players.submit(() -> mint("{\"mode\":\"CARD\"}"));
            for (PlayerObjectCard card : new PlayerObjectCard[] { first.get(), second.get() }) {
                assertEquals(OUR_CUTOUT_URL, card.getCutoutUrl(), "同时判过的另一位没拿到贴纸:排在队列里等超时了");
                assertEquals("一枚弹壳", card.getTitle());
            }
        } finally {
            players.shutdownNow();
        }
    }

    @Test
    void uncheckedTemplateAndRenameAreRejectedBeforePersistingCardText() {
        com.chengyinhub.business.service.IWxContentSecurityService safety =
                mock(com.chengyinhub.business.service.IWxContentSecurityService.class);
        org.mockito.Mockito.when(safety.checkText(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.chengyinhub.business.service.IWxContentSecurityService.MSG_UNAVAILABLE);
        ReflectionTestUtils.setField(service, "wxContentSecurityService", safety);
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> mint("{\"mode\":\"CARD\",\"cardTitle\":\"未经检查的旧模板\",\"shotNote\":\"旧文字\"}"));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.rename(MEMBER_ID, 10L, "新名字"));
        verify(cardMapper, never()).upsert(any());
        verify(cardMapper, never()).renameOwned(anyLong(), anyLong(), any());
    }

    @Test
    void unsafeOrUnavailableAiNamingFallsBackToReviewedTemplateWithoutLeakingModelText() {
        stubCutoutAndNaming("不应展示的模型名", "其他", null, 0, 0);
        com.chengyinhub.business.service.IWxContentSecurityService safety =
                mock(com.chengyinhub.business.service.IWxContentSecurityService.class);
        org.mockito.Mockito.when(safety.checkText(org.mockito.ArgumentMatchers.eq("不应展示的模型名"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.chengyinhub.business.service.IWxContentSecurityService.MSG_UNAVAILABLE);
        ReflectionTestUtils.setField(service, "wxContentSecurityService", safety);
        mint("{\"mode\":\"CARD\",\"cardTitle\":\"模板名\"}");
        ArgumentCaptor<PlayerObjectCard> captor = ArgumentCaptor.forClass(PlayerObjectCard.class);
        verify(cardMapper).upsert(captor.capture());
        assertEquals("模板名", captor.getValue().getTitle());
        assertNull(captor.getValue().getCategory());
    }

    // ==================== 写库形状 ====================

    @Test
    void 一次铸卡只发一条upsert() {
        mint("{\"mode\":\"CARD\",\"cardTitle\":\"弹壳\"}");

        ArgumentCaptor<PlayerObjectCard> captor = ArgumentCaptor.forClass(PlayerObjectCard.class);
        verify(cardMapper).upsert(captor.capture());
        assertEquals("弹壳", captor.getValue().getTitle());
    }

    // ==================== 脚手架 ====================

    /**
     * 抠图(裁 + 转存)与认物换成可控桩:传 null 表示那一件没有结果,
     * 延迟用来验「卡住的那一件不拖累另一件,也不拖累出卡」。
     */
    private void stubCutoutAndNaming(String aiName, String category, String cutoutUrl,
            long cutoutDelayMs, long namingDelayMs) {
        ReflectionTestUtils.setField(service, "imageSegClient", new ImageSegClient() {
            @Override
            public String segment(String imageUrl) {
                segmentedUrls.add(imageUrl);
                return TEMP_CUTOUT_URL;
            }
        });
        ReflectionTestUtils.setField(service, "cutoutStorage", new CardCutoutStorage() {
            @Override
            public String segmentInputOf(String photoUrl, long ownerId) {
                return segInput;
            }

            @Override
            public CardCutout transfer(String tempPngUrl, long ownerId) {
                blockIgnoringInterrupts(cutoutDelayMs);
                return cutoutUrl == null ? null : new CardCutout(cutoutUrl, BOX);
            }
        });
        ReflectionTestUtils.setField(service, "objectNamer", new ObjectNamer() {
            @Override
            public Naming name(String imageUrl) {
                blockIgnoringInterrupts(namingDelayMs);
                return aiName == null && category == null ? null : new Naming(aiName, category);
            }
        });
        ReflectionTestUtils.setField(service, "taskBudgetMs", TASK_BUDGET_MS);
    }

    /**
     * 打断不了的阻塞:模拟真实的 socket 读 —— hutool 读连接时 {@code cancel(true)} 的中断不管用,
     * 线程会一直占到底层超时为止。用可以被打断的 sleep 做桩,会把「外层到点就走」测成理所当然。
     */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void blockIgnoringInterrupts(long millis) {
        if (millis <= 0) {
            return;
        }
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        boolean interrupted = false;
        while (true) {
            long left = until - System.nanoTime();
            if (left <= 0) {
                break;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(left);
            } catch (InterruptedException ignored) {
                interrupted = true;   // 照样等满,和 socket 读一样不理中断
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private PlayerObjectCard mint(String photoCheckJson) {
        return mintCardWithUrl(OSS_URL, photoCheckJson);
    }

    private PlayerObjectCard mintCardWithUrl(String url) {
        return mintCardWithUrl(url, "{\"mode\":\"CARD\",\"cardTitle\":\"弹壳\"}");
    }

    private PlayerObjectCard mintCardWithUrl(String url, String photoCheckJson) {
        PlayerObjectCard card = service.mint(MEMBER_ID, url, config(photoCheckJson), "PLAY",
                String.valueOf(NODE_ID));
        ArgumentCaptor<PlayerObjectCard> captor = ArgumentCaptor.forClass(PlayerObjectCard.class);
        verify(cardMapper, atLeastOnce()).upsert(captor.capture());
        return captor.getValue();
    }

    private static JsonNode config(String photoCheckJson) {
        try {
            return JSON.readTree("{\"photoCheck\":" + photoCheckJson + "}").path("photoCheck");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    @Test void registeredMediaAdmissionFailurePreventsMintAndAllSupplierRequests() {
        com.chengyinhub.business.service.support.AppMediaReferenceGuard media =
                (com.chengyinhub.business.service.support.AppMediaReferenceGuard) ReflectionTestUtils.getField(service, "mediaReferences");
        when(media.requireAttachment(MEMBER_ID, OSS_URL, true)).thenThrow(new com.chengyinhub.common.exception.ServiceException("媒体尚未审核通过",422));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> mint("{\"mode\":\"CARD\"}"));
        verify(media, never()).processingUrl(org.mockito.ArgumentMatchers.anyString(), anyLong(), org.mockito.ArgumentMatchers.anyString());
        verify(cardMapper, never()).upsert(any());
    }
}
