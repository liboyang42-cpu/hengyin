package com.chengyinhub.business.service.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.service.impl.AlarmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 拍照审核:把照片地址 + 作者那句 requirement 交给真视觉模型,拿 {pass, confidence, reason} 结论。
 *
 * <p>判定全在服务端(契约 §2.2)。客户端报的分数不是输入,本服务也不读。
 *
 * <p>图片固定编成 base64 data URL 再发(站内路径直接读本机磁盘,OSS/外站回落下载),
 * <b>不把 URL 交给模型</b> —— 2026-09-18 真 key 实测 URL 路必然 400,详见 {@link VisionImageEncoder} 类注释。
 *
 * <p>降级口径(重要):key 未注入 / 超时 / 限流 / 模型回话解析失败时,**不判死也不假装通过** ——
 * 返回 degraded=true,由玩法段按 fallback 处理(state 标 flagged + degraded)。硬拦会让节点
 * 永久不可完成,把一条路线变成死路。
 *
 * <p>调用次数计入 {@link AiGatewayService} 的 **photo 独立池**(bizType=photo_check,
 * 上限 ai.model.photoDailyLimit):拍照与创作频次量级不同,共用 gen 池会让真审核静默停掉。
 * 键名/上限/分池全在网关,这里不新造计数。仅确定未调用模型时补偿；结果未知保留原预占。
 *
 * <p>⚠️ 照片会离开本站发给模型供应商(默认 DeepSeek,见 ai.vision.*,可配成百炼/DashScope)。
 * 上传链路里的微信 img_sec_check 合规机审是另一回事,保留不动
 * (一个查违规、一个查有没有拍到要求的东西)。
 *
 * @author chengyinhub
 */
@Service
public class PhotoCheckVisionService
{
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.ConsentGuard consentGuard;
    @Autowired private com.chengyinhub.business.service.support.AppMediaReferenceGuard mediaReferences;

    private static final Logger log = LoggerFactory.getLogger(PhotoCheckVisionService.class);

    /** 给玩家看的理由要短:落 state、进投影、还要下发小程序 */
    private static final int MAX_REASON_LENGTH = 120;

    /** 频控场景名:网关据此把调用计进 photo 独立池(不复用创作类 gen 池) */
    private static final String BIZ_TYPE = "photo_check";

    private static final String SYSTEM_PROMPT =
            "你是照片审核员。玩家按作者给出的拍摄要求拍了一张照片,请判断照片是否满足要求。\n"
            + "只输出一个 JSON 对象,不要输出任何其他文字,格式:\n"
            + "{\"pass\": true 或 false, \"confidence\": 0 到 100 的整数, \"reason\": \"一句话说明判断依据\"}\n"
            + "判断标准:画面内容明确满足要求才 pass=true;看不清、内容无关或明显不满足都 pass=false。";

    /** 视觉模型;测试可排空或替换。排空 = 拿不到结论,走降级。 */
    @Autowired(required = false)
    @Qualifier("visionLlmClient")
    private LlmClient visionClient;

    /** 未取得额度原预占时不调用模型。 */
    @Autowired(required = false)
    private AiGatewayService aiGatewayService;

    /** 告警通道;测试可排空。 */
    @Autowired(required = false)
    private AlarmService alarmService;

    /**
     * 图片编码器:站内路径优先读本地磁盘,远程回落下载,统一转 base64 data URL 再发模型。
     * 2026-09-18 真 key 实测:把 URL 直接交给模型不可靠(站内 URL 400 unsupported image、
     * 外站 URL 400 failed to download),只有 base64 一次就过;详见 {@link VisionImageEncoder} 类注释。
     */
    private VisionImageEncoder imageEncoder = new VisionImageEncoder();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PhotoCheckVisionResult review(Long memberId, String imageUrl, String requirement)
    {
        return reviewOnce(memberId, imageUrl, requirement, null, null).result();
    }

    public record InputSnapshot(String encodedInputSha256, String modelName,
            String promptVersion, String encoderVersion) {}

    public record PreparedReview(PhotoCheckVisionResult result, String providerState, String reasonCode) {}

    public PreparedReview reviewPrepared(Long memberId, String imageReference, String requirement,
            String reservation, java.util.function.Consumer<InputSnapshot> beforeModel)
    {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Photo provider execution cannot hold a business transaction");
        if (reservation == null || reservation.isBlank() || beforeModel == null)
            throw new IllegalArgumentException("Original photo reservation and snapshot writer required");
        return reviewOnce(memberId, imageReference, requirement, reservation, beforeModel);
    }

    private PreparedReview reviewOnce(Long memberId, String imageUrl, String requirement,
            String plannedReservation, java.util.function.Consumer<InputSnapshot> beforeModel)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        consentGuard.requireCurrentAgreement(memberId, "photo_model_processing", "object_photo");
        String url = imageUrl == null ? "" : imageUrl.trim();
        if (url.isEmpty())
            return unavailable("照片地址为空", "NOT_SENT", "IMAGE_EMPTY");
        url = mediaReferences.processingUrl(url, memberId, "IMAGE");
        VisionImageEncoder.Encoded encoded = imageEncoder.encode(url);
        if (encoded.isRejected())
        {
            log.info("[PhotoCheckVision] image-rejected memberId={} reason={}", memberId, encoded.getRejectReason());
            return unavailable(encoded.getRejectReason(), "NOT_SENT", "IMAGE_REJECTED");
        }
        String reservation;
        try
        {
            reservation = chargeQuota(memberId, plannedReservation);
        }
        catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) { throw paused; }
        catch (RuntimeException unavailable)
        {
            alarm("拍照视觉审核额度不可用", "memberId=" + memberId);
            return unavailable("照片审核额度暂时不可用", "NOT_SENT", "QUOTA_UNAVAILABLE");
        }
        if (reservation == null)
            return unavailable("今天的照片审核次数用完了", "NOT_SENT", "QUOTA_EXHAUSTED");

        try
        {
            if (visionClient == null)
                throw new IllegalStateException("视觉审核通道未装配");
            if (visionClient instanceof OpenAiCompatibleLlmClient client)
                client.requireReady();
            if (beforeModel != null)
            {
                String model = visionClient instanceof OpenAiCompatibleLlmClient client
                        ? client.configuredModel() : "client:" + visionClient.name();
                beforeModel.accept(new InputSnapshot(encodedInputHash(encoded.getDataUrl()), model,
                        "photo-review-v1", "vision-image-encoder-v1"));
            }
            aiGatewayService.stageQuota(memberId, BIZ_TYPE, reservation, "MODEL_STARTED");
        }
        catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) { throw paused; }
        catch (RuntimeException unsent)
        {
            // chatVision 尚未调用，只有此路径可以按原预占补偿。
            refundQuota(memberId, reservation);
            alarm("拍照视觉审核发送前失败", "memberId=" + memberId);
            return unavailable("照片暂时无法审核", "NOT_SENT", "PRE_SEND_FAILED");
        }

        String answer;
        try
        {
            answer = visionClient.chatVision(SYSTEM_PROMPT, encoded.getDataUrl(), buildUserPrompt(requirement));
        }
        catch (AiProviderNotSentException notSent)
        {
            // This proof belongs only to this chatVision attempt, not to any older UNKNOWN.
            refundQuota(memberId, reservation);
            alarm("拍照视觉审核请求未发送", "memberId=" + memberId);
            return unavailable("照片暂时无法审核", "NOT_SENT", "PROVIDER_NOT_SENT");
        }
        catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) { throw paused; }
        catch (RuntimeException unknown)
        {
            // 供应商可能已计费；保留原预占，首个本地降级结果仍由原意图持久化。
            alarm("拍照视觉审核结果待确认", "memberId=" + memberId);
            return unavailable("照片审核结果待确认", "UNKNOWN", "PROVIDER_RESULT_UNKNOWN");
        }
        try
        {
            // A failure here follows an actual answer and cannot prove the attempt was unsent.
            aiGatewayService.retainQuota(memberId, BIZ_TYPE, reservation, "PHOTO_RESULT");
        }
        catch (RuntimeException unknown)
        {
            // 供应商可能已计费；保留原预占，首个本地降级结果仍由原意图持久化。
            alarm("拍照视觉审核结果待确认", "memberId=" + memberId);
            return unavailable("照片审核结果待确认", "UNKNOWN", "PROVIDER_RESULT_UNKNOWN");
        }
        PhotoCheckVisionResult parsed = parse(answer);
        if (parsed == null)
        {
            alarm("拍照视觉审核返回无法解析", "memberId=" + memberId);
            return unavailable("照片暂时无法审核(模型没有给出结论)", "RECEIVED", "RESULT_UNPARSEABLE");
        }
        return new PreparedReview(parsed, "RECEIVED", "RESULT_RECEIVED");
    }

    private static PreparedReview unavailable(String reason, String providerState, String reasonCode)
    {
        return new PreparedReview(PhotoCheckVisionResult.degraded(reason), providerState, reasonCode);
    }

    private static String encodedInputHash(String encoded)
    {
        try
        {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            // Preserve String.getBytes(UTF_8) replacement/continuation semantics with bounded buffers.
            // The writer carries a surrogate across chunk boundaries and flushes it on close.
            try (var writer = new java.io.OutputStreamWriter(new java.security.DigestOutputStream(
                    java.io.OutputStream.nullOutputStream(), digest), java.nio.charset.StandardCharsets.UTF_8))
            {
                char[] chunk = new char[4096];
                for (int offset = 0; offset < encoded.length(); offset += chunk.length)
                {
                    int count = Math.min(chunk.length, encoded.length() - offset);
                    encoded.getChars(offset, offset + count, chunk, 0);
                    writer.write(chunk, 0, count);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
        catch (java.security.NoSuchAlgorithmException impossible)
        {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        catch (java.io.IOException impossible)
        {
            throw new IllegalStateException("Photo input hash unavailable", impossible);
        }
    }

    private String buildUserPrompt(String requirement)
    {
        String text = requirement == null ? "" : requirement.trim();
        return "拍摄要求:" + text + "\n请判断这张照片是否满足要求。";
    }

    /**
     * @return 原预占凭证；null 仅表示额度已耗尽；基础设施故障停止供应商调用。
     */
    private String chargeQuota(Long memberId, String plannedReservation)
    {
        if (aiGatewayService == null || memberId == null)
        {
            throw new IllegalStateException("Photo quota service or member unavailable");
        }
        try
        {
            String reservation = plannedReservation == null
                    ? aiGatewayService.consumeQuota(memberId, BIZ_TYPE)
                    : aiGatewayService.consumePlannedQuota(memberId, BIZ_TYPE, plannedReservation);
            if (plannedReservation != null && !plannedReservation.equals(reservation))
                throw new IllegalStateException("Photo quota reservation identity changed");
            if (reservation == null || reservation.trim().isEmpty())
            {
                throw new IllegalStateException("Photo quota reservation unavailable");
            }
            return reservation;
        }
        catch (AiServiceException overLimit)
        {
            if (overLimit.getReason() != AiServiceException.Reason.RATE_LIMITED)
            {
                throw overLimit;
            }
            log.info("[PhotoCheckVision] quota-exhausted memberId={}", memberId);
            return null;
        }
    }

    private void refundQuota(Long memberId, String reservation)
    {
        if (aiGatewayService == null || memberId == null || reservation == null || reservation.isEmpty())
        {
            return;
        }
        try
        {
            aiGatewayService.refundQuota(memberId, BIZ_TYPE, reservation);
        }
        catch (Exception e)
        {
            // 退款失败只留痕:不能把上面的降级原因盖掉
            log.warn("[PhotoCheckVision] refund-failed memberId={}", memberId, e);
        }
    }

    /**
     * 解析模型回话为结论。解析失败一律返回 null(拿不到结论),绝不猜成通过。
     * 模型常把 JSON 包在 ```json 围栏或前后话术里:取第一个 { 到最后一个 } 再解析。
     */
    private PhotoCheckVisionResult parse(String answer)
    {
        if (answer == null || answer.trim().isEmpty())
        {
            return null;
        }
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start)
        {
            return null;
        }
        try
        {
            JsonNode root = objectMapper.readTree(answer.substring(start, end + 1));
            if (!root.path("pass").isBoolean() || !root.path("confidence").isNumber())
            {
                return null;
            }
            int confidence = (int) Math.round(root.path("confidence").asDouble());
            confidence = Math.max(0, Math.min(100, confidence));
            String reason = root.path("reason").asText("");
            return PhotoCheckVisionResult.concluded(root.path("pass").asBoolean(), confidence,
                    truncate(reason, MAX_REASON_LENGTH));
        }
        catch (Exception notJson)
        {
            return null;
        }
    }

    private void alarm(String title, String detail)
    {
        if (alarmService == null)
        {
            return;
        }
        try
        {
            alarmService.alarm(title, detail);
        }
        catch (Exception e)
        {
            log.warn("[PhotoCheckVision] alarm-failed title={}", title, e);
        }
    }

    private static String truncate(String s, int max)
    {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
