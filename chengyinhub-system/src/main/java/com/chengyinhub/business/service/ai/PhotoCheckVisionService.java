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
 * 键名/上限/分池全在网关,这里不新造计数。只在「调用失败、用户没拿到结论」时退还那一次,口径同网关。
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

    /** 额度池;测试可排空(排空则不计次,不影响判定)。 */
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
        String url = imageUrl == null ? "" : imageUrl.trim();
        if (url.isEmpty())
        {
            return PhotoCheckVisionResult.degraded("照片地址为空");
        }
        // 供应商的图片限制在发请求前就拒掉,不要发出去等它报错;超限也算降级。
        // 这类拒绝是玩家输入问题,不占额度、不打告警(告警留给我们自己的链路故障)。
        // photoCheck 固定发 base64 data URL(不能发 URL,原因见 VisionImageEncoder 类注释)。
        VisionImageEncoder.Encoded encoded = imageEncoder.encode(url);
        if (encoded.isRejected())
        {
            log.info("[PhotoCheckVision] image-rejected memberId={} reason={}", memberId, encoded.getRejectReason());
            return PhotoCheckVisionResult.degraded(encoded.getRejectReason());
        }
        if (!chargeQuota(memberId))
        {
            return PhotoCheckVisionResult.degraded("今天的照片审核次数用完了");
        }

        String answer;
        try
        {
            if (visionClient == null)
            {
                throw new IllegalStateException("视觉审核通道未装配");
            }
            answer = visionClient.chatVision(SYSTEM_PROMPT, encoded.getDataUrl(), buildUserPrompt(requirement));
        }
        catch (Exception e)
        {
            // 没走到模型(未配置 key / 未启用)或调用失败(超时 / 限流 / HTTP 错):
            // 用户没拿到结论,按网关口径退还这一次,再按降级处理。
            refundQuota(memberId);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            alarm("拍照视觉审核调用失败", "memberId=" + memberId + " reason=" + truncate(reason, 200));
            return PhotoCheckVisionResult.degraded("照片暂时无法审核(" + truncate(reason, MAX_REASON_LENGTH) + ")");
        }

        PhotoCheckVisionResult parsed = parse(answer);
        if (parsed == null)
        {
            // 模型回话了但拿不出结论(非 JSON / 关键字段缺失):调用真实发生了,不退额度。
            alarm("拍照视觉审核返回无法解析", "memberId=" + memberId);
            return PhotoCheckVisionResult.degraded("照片暂时无法审核(模型没有给出结论)");
        }
        return parsed;
    }

    private String buildUserPrompt(String requirement)
    {
        String text = requirement == null ? "" : requirement.trim();
        return "拍摄要求:" + text + "\n请判断这张照片是否满足要求。";
    }

    /**
     * 抢一次每日额度。超限不是错误,是降级原因之一 —— 玩家侧绝不因为额度耗尽被硬拦。
     *
     * @return true=可以继续调模型;false=超限(已按降级口径返回)
     */
    private boolean chargeQuota(Long memberId)
    {
        if (aiGatewayService == null || memberId == null)
        {
            return true;
        }
        try
        {
            aiGatewayService.consumeQuota(memberId, BIZ_TYPE);
            return true;
        }
        catch (AiServiceException overLimit)
        {
            log.info("[PhotoCheckVision] quota-exhausted memberId={}", memberId);
            return false;
        }
        catch (RuntimeException quotaBroken)
        {
            // 额度层自身故障(Redis 抖动等)不该把玩家拦死:放行去调模型,
            // 与 remainingQuota 读不到时报满额的 fail-open 口径一致。
            log.warn("[PhotoCheckVision] quota-charge-failed memberId={}", memberId, quotaBroken);
            return true;
        }
    }

    private void refundQuota(Long memberId)
    {
        if (aiGatewayService == null || memberId == null)
        {
            return;
        }
        try
        {
            aiGatewayService.refundQuota(memberId, BIZ_TYPE);
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
