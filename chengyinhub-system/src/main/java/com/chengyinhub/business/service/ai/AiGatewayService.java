package com.chengyinhub.business.service.ai;

/**
 * AI 调用网关:频控 + 入/出内容安全检测 + 落审计日志。
 * 四大能力(merchant-plan/theme-draft/node-generate/safety-precheck)共用此网关,再经 AiCreatorService 编排。
 *
 * @author chengyinhub
 */
public interface AiGatewayService
{
    /**
     * 调用 AI 模型,封装频控/内容安全/落库。
     *
     * @param memberId     当前会员
     * @param role         当前角色 player/club/merchant
     * @param bizType      场景 merchant_plan/theme_draft/node_generate/safety_precheck
     * @param systemPrompt 系统提示词(含 [BIZ:xxx] 标记)
     * @param userPrompt   用户上下文(结构化 JSON, AiCreatorService 拼装)
     * @return 网关结果(含模型文本、traceId)
     * @throws AiServiceException 频控/内容安全拦截时抛出,Controller 层 catch 转 AjaxResult.error
     */
    AiGatewayResult generate(Long memberId, String role, String bizType,
                              String systemPrompt, String userPrompt);

    /** NPC shop ASR has already reserved this conversation; verify its persisted handoff, never consume again. */
    AiGatewayResult generateNpcAsrHandoff(NpcAsrQuotaHandoff original,String systemPrompt,String userPrompt);

    /** Compensate only the persisted original NOT_SENT proof, through the existing quota recovery owner. */
    void refundNpcAsrNotSent(Long memberId,String requestId);

    /** Local configuration fingerprint only; excludes credentials and credential digests. */
    String themeDraftConfigurationHash();

    /** Consumes the original plan, never a newly planned quota identity. */
    AiGatewayResult generateThemeDraftPrepared(Long memberId, AiThemeDraftPlan plan,
            String systemPrompt, String userPrompt);

    /**
     * 查询某会员在指定场景所属额度池当日的剩余次数。
     *
     * <p>存在的理由:额度只由本网关执行,键名与分池规则也只有本网关知道。
     * 对外展示额度的接口若自己另算一套,迟早和真实执行错开
     * ——{@code /api/ai/theme/draft/quota} 就曾对俱乐部/商家答「不限量」,
     * 而网关实际上照样在 gen 池按天切断。
     *
     * @param memberId 会员
     * @param bizType  场景,决定落在哪个额度池
     * @return 当日剩余次数(已扣减),不会为负
     */
    int remainingQuota(Long memberId, String bizType);

    /**
     * 查询某场景所属额度池的每日上限。
     *
     * @param bizType 场景
     * @return 每日上限
     */
    int dailyQuota(String bizType);

    /**
     * 为一次**不经文本编排**的 AI 调用(如拍照视觉审核)抢占一次当日额度。
     * 池子由 bizType 决定(photo_check → photo 独立池;其余创作类 → gen 池),
     * 键名/上限/分池规则与 {@link #generate} 同源 —— 额度只在本网关执行,调用方不要自己造计数。
     *
     * @param memberId 会员
     * @param bizType  场景,决定落在哪个额度池(如 photo_check)
     * @return 绑定本会员、本额度池和原日的预占凭证，退款必须传回同一凭证
     * @throws AiServiceException 超限时抛出(调用方据此降级,而不是阻断玩家)
     */
    String consumeQuota(Long memberId, String bizType);

    /** Pure identity planning; caller persists this original reservation before any Redis/provider work. */
    String planQuotaReservation(Long memberId, String bizType, String nonce);

    /** Consume only the previously persisted member/pool/day/nonce; never substitutes a fresh identity. */
    String consumePlannedQuota(Long memberId, String bizType, String reservation);

    /**
     * 退还原预占；仅限能证明未调用/未受理的路径。供应商超时、网络失败或结果未知
     * 不能证明未发生费用，必须保留原身份核验；超限不退。
     * 退失败只 warn,不抛异常。
     *
     * @param memberId 会员
     * @param bizType  与 consumeQuota 逐字相同的场景(退错池子 = 没退)
     * @param reservation 本次 consumeQuota 返回的原预占凭证
     */
    void refundQuota(Long memberId, String bizType, String reservation);
    /** 外部视觉消费者调用前持久阶段；失败不得继续启动提供方。 */
    void stageQuota(Long memberId,String bizType,String reservation,String stage);
    /** 保持该消费者已有不退款结论，仅保存固定内部原因码。 */
    void retainQuota(Long memberId,String bizType,String reservation,String reason);
}
