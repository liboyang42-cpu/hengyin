package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.common.config.RuoYiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拍照视觉审核服务的判定契约:结论解析与降级口径。
 *
 * <p>降级(拿不到结论)永远不能变成 pass —— 放不放行由玩法段 fallback 决定,不在这一层。
 *
 * <p>图片用真本机文件(临时 profile 目录),服务必须把它编成 base64 data URL 再发模型;
 * 单测不联网:站内路径直接命中本地文件,走不到下载回落。
 */
class PhotoCheckVisionServiceTest {

    private static final long MEMBER_ID = 7L;
    private static final String IMAGE_URL = "/profile/upload/window.jpg";
    private static final String REQUIREMENT = "画面里要能看见窗外的街景,窗框入镜";

    private PhotoCheckVisionService service;
    private AiGatewayService gateway;
    private AlarmService alarm;
    private AtomicReference<String> sentImage;
    private Path profileDir;
    private String originalProfile;

    @BeforeEach
    void setUp() throws Exception {
        profileDir = Files.createTempDirectory("photo-check-vision");
        originalProfile = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(profileDir.toString());
        File uploadDir = profileDir.resolve("upload").toFile();
        assertTrue(uploadDir.mkdirs());
        Files.write(uploadDir.toPath().resolve("window.jpg"), jpeg(2, 2));

        service = new PhotoCheckVisionService();
        com.chengyinhub.business.service.support.AppMediaReferenceGuard media = mock(com.chengyinhub.business.service.support.AppMediaReferenceGuard.class);
        when(media.processingUrl(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq("IMAGE"))).thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(service, "mediaReferences", media);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "consentGuard", org.mockito.Mockito.mock(com.chengyinhub.business.service.ConsentGuard.class));
        gateway = mock(AiGatewayService.class);
        org.mockito.Mockito.when(gateway.consumeQuota(MEMBER_ID, "photo_check")).thenReturn("reservation");
        alarm = mock(AlarmService.class);
        sentImage = new AtomicReference<>();
        ReflectionTestUtils.setField(service, "aiGatewayService", gateway);
        ReflectionTestUtils.setField(service, "alarmService", alarm);
    }

    @AfterEach
    void tearDown() throws Exception {
        new RuoYiConfig().setProfile(originalProfile);
        deleteRecursively(profileDir.toFile());
    }

    // ==================== 结论解析 ====================

    @Test
    void separateConsentRefusalPreventsAnyProviderCallOrMediaWrite() {
        com.chengyinhub.common.config.ComplianceProperties versions = new com.chengyinhub.common.config.ComplianceProperties();
        versions.setDocVersions(java.util.Map.of("photo_model_processing", "v1.0", "voice_clone_processing", "v1.0"));
        com.chengyinhub.business.service.ConsentGuard guard = new com.chengyinhub.business.service.ConsentGuard(
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IConsentRecordService.class), versions);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "consentGuard", guard);
        org.junit.jupiter.api.Assertions.assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT));
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    @Test
    void 模型回标准JSON按结论处理() {
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"能看到窗外街景和窗框\"}");

        PhotoCheckVisionResult result = service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT);

        assertFalse(result.isDegraded());
        assertTrue(result.isPass());
        assertEquals(88, result.getConfidence());
        assertTrue(result.getReason().contains("窗框"));
        assertTrue(sentImage.get().startsWith("data:image/jpeg;base64,"),
                "photoCheck 必须发 base64 data URL,不能把 URL 交给模型:" + sentImage.get());
    }

    @Test
    void 模型回markdown围栏里的JSON也能解析() {
        modelSays("这是结论:\n```json\n{\"pass\":false,\"confidence\":70,\"reason\":\"没有窗框\"}\n```\n");

        PhotoCheckVisionResult result = service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT);

        assertFalse(result.isDegraded());
        assertFalse(result.isPass());
        assertEquals(70, result.getConfidence());
    }

    @Test
    void 置信度越界收进0到100() {
        modelSays("{\"pass\":true,\"confidence\":150,\"reason\":\"非常确定\"}");
        assertEquals(100, service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT).getConfidence());

        modelSays("{\"pass\":true,\"confidence\":-8,\"reason\":\"随手写的\"}");
        assertEquals(0, service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT).getConfidence());
    }

    // ==================== 降级:拿不到结论 ====================

    @Test
    void 负控_未配置key时降级且绝不能通过() {
        ReflectionTestUtils.setField(service, "visionClient",
                new OpenAiCompatibleLlmClient("vision", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "", "qwen-vl-max", true, 3000));

        PhotoCheckVisionResult result = service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT);

        assertTrue(result.isDegraded());
        assertFalse(result.isPass(), "拿不到结论不能变成通过");
        assertFalse(result.getReason().isEmpty());
        verify(gateway).refundQuota(MEMBER_ID, "photo_check", "reservation");
        verify(gateway, never()).stageQuota(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void 负控_模型回非JSON时降级() {
        modelSays("抱歉,我无法判断这张照片。");

        PhotoCheckVisionResult result = service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT);

        assertTrue(result.isDegraded());
        assertFalse(result.isPass());
    }

    @Test
    void 负控_缺pass或confidence字段时降级() {
        modelSays("{\"confidence\":88,\"reason\":\"看起来还行\"}");
        assertTrue(service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT).isDegraded());

        modelSays("{\"pass\":true,\"reason\":\"看起来还行\"}");
        assertTrue(service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT).isDegraded());
    }

    @Test
    void 负控_模型调用结果未知时降级且保留原预占() {
        modelThrows(new RuntimeException("AI 模型调用失败(429)"));
        AtomicBoolean refunded = new AtomicBoolean(false);
        org.mockito.Mockito.doAnswer(invocation -> {
            refunded.set(true);
            return null;
        }).when(gateway).refundQuota(MEMBER_ID, "photo_check", "reservation");

        PhotoCheckVisionResult result = service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT);

        assertTrue(result.isDegraded());
        assertFalse(result.isPass());
        assertFalse(refunded.get(), "供应商可能已计费，不能因异常退款");
        verify(gateway).stageQuota(MEMBER_ID, "photo_check", "reservation", "MODEL_STARTED");
        verify(gateway, never()).retainQuota(anyLong(), anyString(), anyString(), anyString());
        verify(alarm).alarm(any(), any());
    }

    @Test
    void 负控_每日额度超限时降级且不调模型() {
        doThrow(new AiServiceException(AiServiceException.Reason.RATE_LIMITED, "今日AI次数已用完(20/天),明天再来吧"))
                .when(gateway).consumeQuota(MEMBER_ID, "photo_check");
        AtomicBoolean called = new AtomicBoolean(false);
        ReflectionTestUtils.setField(service, "visionClient", new LlmClient() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public String chat(String systemPrompt, String userPrompt) {
                called.set(true);
                return "{}";
            }

            @Override
            public String chatVision(String systemPrompt, String imageUrl, String userPrompt) {
                called.set(true);
                return "{\"pass\":true,\"confidence\":99,\"reason\":\"x\"}";
            }
        });

        PhotoCheckVisionResult result = service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT);

        assertTrue(result.isDegraded(), "额度耗尽也要降级而不是把玩家拦住");
        assertFalse(result.isPass(), "额度耗尽绝不能假装通过");
        assertFalse(called.get(), "超限后不该再发模型调用");
        verify(gateway, never()).refundQuota(anyLong(), anyString(), anyString());
    }

    @Test
    void 负控_额度层故障不产生无凭证模型调用() {
        doThrow(new RuntimeException("Redis connection refused"))
                .when(gateway).consumeQuota(MEMBER_ID, "photo_check");
        modelSays("{\"pass\":true,\"confidence\":90,\"reason\":\"拍到了\"}");

        PhotoCheckVisionResult result = service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT);

        assertTrue(result.isDegraded());
        assertFalse(result.isPass());
        assertEquals(null, sentImage.get(), "额度故障时模型调用数必须为零");
        verify(gateway, never()).stageQuota(anyLong(), anyString(), anyString(), anyString());
        verify(gateway, never()).refundQuota(anyLong(), anyString(), anyString());
    }

    @Test
    void missingReservationOrGatewayNeverCallsProvider() {
        modelSays("{\"pass\":true,\"confidence\":90,\"reason\":\"ok\"}");
        for (String token : new String[]{null, "", "   "}) {
            when(gateway.consumeQuota(MEMBER_ID, "photo_check")).thenReturn(token);
            assertTrue(service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT).isDegraded());
            assertEquals(null, sentImage.get());
        }
        ReflectionTestUtils.setField(service, "aiGatewayService", null);
        assertTrue(service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT).isDegraded());
        assertEquals(null, sentImage.get());
        verify(gateway, never()).stageQuota(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void stageFailureCompensatesOriginalReservationWithoutCallingProvider() {
        modelSays("{\"pass\":true,\"confidence\":90,\"reason\":\"ok\"}");
        doThrow(new IllegalStateException("stage reply lost"))
                .when(gateway).stageQuota(MEMBER_ID, "photo_check", "reservation", "MODEL_STARTED");
        assertTrue(service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT).isDegraded());
        assertEquals(null, sentImage.get());
        verify(gateway).refundQuota(MEMBER_ID, "photo_check", "reservation");
    }

    @Test
    void resultPersistenceFailureKeepsOriginalReservationAndDoesNotRefund() {
        modelSays("{\"pass\":true,\"confidence\":90,\"reason\":\"ok\"}");
        doThrow(new IllegalStateException("receipt write unavailable"))
                .when(gateway).retainQuota(MEMBER_ID, "photo_check", "reservation", "PHOTO_RESULT");
        PhotoCheckVisionResult result = service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT);
        assertTrue(result.isDegraded());
        assertFalse(result.isPass());
        assertTrue(sentImage.get() != null);
        verify(gateway, never()).refundQuota(anyLong(), anyString(), anyString());
    }

    @Test
    void preparedReviewUsesOriginalReservationAndPersistsSnapshotBeforeProvider() {
        modelSays("{\"pass\":true,\"confidence\":90,\"reason\":\"门牌\"}");
        when(gateway.consumePlannedQuota(MEMBER_ID, "photo_check", "original-reservation"))
                .thenReturn("original-reservation");
        AtomicReference<PhotoCheckVisionService.InputSnapshot> snapshot = new AtomicReference<>();
        PhotoCheckVisionService.PreparedReview review = service.reviewPrepared(
                MEMBER_ID, IMAGE_URL, REQUIREMENT, "original-reservation", input -> {
                    assertEquals(null, sentImage.get());
                    snapshot.set(input);
                });
        assertTrue(review.result().isPass());
        assertEquals("RECEIVED", review.providerState());
        assertTrue(snapshot.get().encodedInputSha256().matches("[a-f0-9]{64}"));
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never())
                .consumeQuota(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(gateway).retainQuota(MEMBER_ID, "photo_check",
                "original-reservation", "PHOTO_RESULT");
    }

    @Test
    void preparedSnapshotFailureCannotCallModelAndOnlyCompensatesOriginalReservation() {
        modelSays("{\"pass\":true,\"confidence\":90,\"reason\":\"门牌\"}");
        when(gateway.consumePlannedQuota(MEMBER_ID, "photo_check", "original-reservation"))
                .thenReturn("original-reservation");
        PhotoCheckVisionService.PreparedReview review = service.reviewPrepared(
                MEMBER_ID, IMAGE_URL, REQUIREMENT, "original-reservation",
                input -> { throw new IllegalStateException("snapshot database unavailable"); });
        assertEquals("NOT_SENT", review.providerState());
        assertTrue(review.result().isDegraded());
        assertEquals(null, sentImage.get());
        org.mockito.Mockito.verify(gateway).refundQuota(MEMBER_ID, "photo_check", "original-reservation");
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never())
                .stageQuota(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void preparedProviderFailurePreservesUnknownOriginalQuota() {
        modelThrows(new IllegalStateException("provider timeout"));
        when(gateway.consumePlannedQuota(MEMBER_ID, "photo_check", "original-reservation"))
                .thenReturn("original-reservation");
        PhotoCheckVisionService.PreparedReview review = service.reviewPrepared(
                MEMBER_ID, IMAGE_URL, REQUIREMENT, "original-reservation", input -> {});
        assertEquals("UNKNOWN", review.providerState());
        assertTrue(review.result().isDegraded());
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never())
                .refundQuota(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void preparedReviewRejectsAnActiveBusinessTransactionBeforeAnyEffect() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> service.reviewPrepared(MEMBER_ID, IMAGE_URL, REQUIREMENT, "original", input -> {}));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        org.mockito.Mockito.verifyNoInteractions(gateway);
        assertEquals(null, sentImage.get());
    }

    @Test
    void 负控_图片超限在发请求前就拒掉且不占额度() {
        ReflectionTestUtils.setField(service, "visionClient", new LlmClient() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public String chat(String systemPrompt, String userPrompt) {
                throw new AssertionError("超限图不该发到模型");
            }

            @Override
            public String chatVision(String systemPrompt, String imageUrl, String userPrompt) {
                throw new AssertionError("超限图不该发到模型");
            }
        });

        PhotoCheckVisionResult result = service.review(MEMBER_ID,
                "data:image/bmp;base64,Qk0AAAAA", REQUIREMENT);

        assertTrue(result.isDegraded(), "不支持的格式要降级而不是硬拦:" + result.getReason());
        assertFalse(result.isPass());
        verify(gateway, never()).consumeQuota(anyLong(), anyString());
        verify(gateway, never()).refundQuota(anyLong(), anyString(), anyString());
    }

    @Test
    void 负控_扩展名jpg但内容不是图片_判不支持且不发请求() throws Exception {
        Files.write(profileDir.resolve("upload").resolve("fake.jpg"), "这不是图片".getBytes("UTF-8"));
        ReflectionTestUtils.setField(service, "visionClient", new LlmClient() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public String chat(String systemPrompt, String userPrompt) {
                throw new AssertionError("假 JPEG 不该发到模型");
            }

            @Override
            public String chatVision(String systemPrompt, String imageUrl, String userPrompt) {
                throw new AssertionError("假 JPEG 不该发到模型");
            }
        });

        PhotoCheckVisionResult result = service.review(MEMBER_ID, "/profile/upload/fake.jpg", REQUIREMENT);

        assertTrue(result.isDegraded(), "按魔数判不出格式必须降级:" + result.getReason());
        assertTrue(result.getReason().contains("格式不支持"), "降级理由要说清格式不支持:" + result.getReason());
        verify(gateway, never()).consumeQuota(anyLong(), anyString());
        verify(gateway, never()).refundQuota(anyLong(), anyString(), anyString());
    }

    @Test
    void 负控_超过32MB的本地图必须在发请求前拒掉() throws Exception {
        File big = profileDir.resolve("upload").resolve("big.jpg").toFile();
        Files.write(big.toPath(), jpeg(2, 2));
        try (RandomAccessFile raf = new RandomAccessFile(big, "rw")) {
            raf.setLength(VisionImageGuard.MAX_BYTES + 1);
        }
        ReflectionTestUtils.setField(service, "visionClient", new LlmClient() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public String chat(String systemPrompt, String userPrompt) {
                throw new AssertionError("超 32MB 的图不该发到模型");
            }

            @Override
            public String chatVision(String systemPrompt, String imageUrl, String userPrompt) {
                throw new AssertionError("超 32MB 的图不该发到模型");
            }
        });

        PhotoCheckVisionResult result = service.review(MEMBER_ID, "/profile/upload/big.jpg", REQUIREMENT);

        assertTrue(result.isDegraded(), "超限必须降级:" + result.getReason());
        assertTrue(result.getReason().contains("32MB"), "降级理由要说清超限:" + result.getReason());
        verify(gateway, never()).consumeQuota(anyLong(), anyString());
        verify(gateway, never()).refundQuota(anyLong(), anyString(), anyString());
    }

    @Test
    void 结论正常时不动额度退款() {
        modelSays("{\"pass\":true,\"confidence\":90,\"reason\":\"拍到了\"}");

        service.review(MEMBER_ID, IMAGE_URL, REQUIREMENT);

        verify(gateway).consumeQuota(MEMBER_ID, "photo_check");
        verify(gateway, never()).refundQuota(anyLong(), anyString(), anyString());
    }

    // ==================== 脚手架 ====================

    private void modelSays(String answer) {
        ReflectionTestUtils.setField(service, "visionClient", new LlmClient() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public String chat(String systemPrompt, String userPrompt) {
                throw new UnsupportedOperationException("photoCheck 只能走视觉通道");
            }

            @Override
            public String chatVision(String systemPrompt, String imageUrl, String userPrompt) {
                sentImage.set(imageUrl);
                return answer;
            }
        });
    }

    private void modelThrows(RuntimeException failure) {
        ReflectionTestUtils.setField(service, "visionClient", new LlmClient() {
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
