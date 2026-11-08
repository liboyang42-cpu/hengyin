package com.chengyinhub.business.service.support;

import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * CT-07 / 0.5:指认结案的**服务端判定**。纯函数、无 IO、不解析答案。
 *
 * <p>原文依据(私有施工文档 v1.3.2,只读):
 * <ul>
 *   <li>CT-07:「可选结局块 {@code accusation{suspects[],introScreens[],winScreen,loseScreen,
 *       corkboardImage,earlyAccuse:{allowed,recordAs}}};最终一题复用 {@code ImageSingleChoice}(照片指认);
 *       允许随时指认的策略下,结束态记"提前结案"而非失败。」</li>
 *   <li>CT-07 字段/API:「提交走既有答案校验,返回 {@code {verdict,correct,early}};结论由服务端判定。」</li>
 *   <li>CT-07 旧数据:「无该块即普通结局。」</li>
 *   <li>0.5:「查看档案/改选不耗机会;明确确认指认才计一次」「错误指认有独立结局文案」
 *       「仅选对人但理由不成立,不标成有依据结案」。</li>
 * </ul>
 *
 * <p>边界:
 * <ul>
 *   <li><b>正确答案只有一个真源</b>:{@code correct} 由调用方从既有 QA 派生后传入
 *       ({@link AccusationTemplateSupport#correctSuspectId(JsonNode) 入参是原 qa 段},{@code qa.options[].correct})。
 *       本类不读 {@code accusation} 段里的任何答案字段,也不存在第二份答案。</li>
 *   <li>{@code recordAs} 是作者在 {@code accusation.earlyAccuse} 里写明的文字,本类**原样透传**:
 *       不设默认值、不限定取值集合。配置合法性由 {@link AccusationTemplateSupport#validate} 负责。</li>
 *   <li>本类只输出判定结果与文案引用,不产生答对/到达/扫码/支付证明,不发奖励。</li>
 *   <li>证据门槛由既有条件机制求值:调用方把 {@code clueGateSatisfied} 事实传进来(未配门槛传 {@code true})。
 *       本类不建推理器,不强制找齐线索。</li>
 * </ul>
 *
 * <p>关于 {@code verdict}:CT-07 只规定回执字段名,未给枚举取值。这里只用三个由原文语义直接推出的值
 * (正确结案 / 错误但可继续 / 机会用尽失败),不自造第四套状态机,也不新造结局枚举。
 */
public final class AccusationOutcomePolicy {

    /** 正确指认(含提前指认成立)。 */
    public static final String VERDICT_CORRECT = "CORRECT";
    /** 错误指认,且仍有作答机会 —— 0.5「按剩余机会继续调查」。 */
    public static final String VERDICT_WRONG = "WRONG";
    /** 错误指认且机会用尽 —— 走 {@code loseScreen}。 */
    public static final String VERDICT_FAILED = "FAILED";
    /** 本次提交不被接受(参数/配置问题),不计机会、不判对错。 */
    public static final String VERDICT_REFUSED = "REFUSED";

    public static final String REFUSED_MISSING_SUSPECT = "ACCUSATION_MISSING_SUSPECT";
    public static final String REFUSED_UNKNOWN_SUSPECT = "ACCUSATION_UNKNOWN_SUSPECT";
    public static final String REFUSED_EARLY_NOT_ALLOWED = "ACCUSATION_EARLY_NOT_ALLOWED";
    public static final String REFUSED_CORRECT_UNKNOWN = "ACCUSATION_CORRECT_UNRESOLVED";

    /**
     * {@code accusation.earlyAccuse} 的只读视图。
     *
     * @param allowed  是否允许提前指认(配置布尔;缺省 false)
     * @param recordAs 作者写明的结束态记录;未写则为 {@code null},本类不会补默认值
     */
    public record EarlyAccuse(boolean allowed, String recordAs) {
    }

    /**
     * 一次正式指认的结论。
     *
     * @param verdict      {@link #VERDICT_CORRECT} / {@link #VERDICT_WRONG} / {@link #VERDICT_FAILED} /
     *                     {@link #VERDICT_REFUSED}
     * @param correct      服务端判定的对错(REFUSED 恒 false)
     * @param early        门禁未满足就指认(与 correct 正交);提前且正确时结束态记「提前结案」而非失败
     * @param recordCode   仅「提前且正确」时为作者写的 {@code recordAs},其余为 {@code null}
     * @param canRetry     还能继续调查(消耗既有题级机会)
     * @param terminal     本局结案
     * @param endingScreen 提交后才给的结局文案键({@code winScreen}/{@code loseScreen});可继续时为 {@code null}
     * @param refuseCode   仅 REFUSED 时有值
     */
    public record Verdict(String verdict, boolean correct, boolean early, String recordCode,
                          boolean canRetry, boolean terminal, String endingScreen, String refuseCode) {
    }

    private AccusationOutcomePolicy() {
    }

    /**
     * 只读 {@code accusation.earlyAccuse};不校验、不补默认值。合法性由
     * {@link AccusationTemplateSupport#validate} 在保存/发布期把关。
     */
    public static EarlyAccuse earlyAccuse(JsonNode config) {
        JsonNode accusation = config == null ? null : config.path(AccusationTemplateSupport.SECTION);
        if (accusation == null || !accusation.isObject()) {
            return new EarlyAccuse(false, null);
        }
        JsonNode early = accusation.path("earlyAccuse");
        if (!early.isObject()) {
            return new EarlyAccuse(false, null);
        }
        String recordAs = early.path("recordAs").asText("");
        return new EarlyAccuse(early.path("allowed").asBoolean(false),
                StringUtils.isBlank(recordAs) ? null : recordAs.trim());
    }

    /**
     * 判定一次正式指认。
     *
     * @param submittedSuspectId 玩家确认提交的嫌疑人 ID(= 复用图片单选的 {@code optionId})
     * @param correctOptionId   由既有 QA 派生的唯一正确项;{@code null}/空表示答案来源不可用,直接拒绝
     * @param early              {@link #earlyAccuse} 读到的配置
     * @param clueGateSatisfied  既有条件机制给出的证据门槛是否已满足;未配门槛传 {@code true}
     * @param remainingChances   本次提交之后**还剩**的题级作答机会(既有机遇配置,不另计一份)
     */
    public static Verdict judge(String submittedSuspectId, String correctOptionId, EarlyAccuse early,
            boolean clueGateSatisfied, int remainingChances) {
        String submitted = submittedSuspectId == null ? "" : submittedSuspectId.trim();
        String correct = correctOptionId == null ? "" : correctOptionId.trim();
        if (correct.isEmpty()) {
            return refuse(REFUSED_CORRECT_UNKNOWN);
        }
        if (submitted.isEmpty()) {
            return refuse(REFUSED_MISSING_SUSPECT);
        }
        EarlyAccuse config = early == null ? new EarlyAccuse(false, null) : early;
        boolean isEarly = !clueGateSatisfied;
        if (isEarly && !config.allowed()) {
            return refuse(REFUSED_EARLY_NOT_ALLOWED);
        }
        if (correct.equals(submitted)) {
            // 提前指认成立时结束态记作者写的 recordAs,不是失败。
            return new Verdict(VERDICT_CORRECT, true, isEarly,
                    isEarly ? config.recordAs() : null, false, true, "winScreen", null);
        }
        boolean canRetry = remainingChances > 0;
        return new Verdict(canRetry ? VERDICT_WRONG : VERDICT_FAILED, false, isEarly, null,
                canRetry, !canRetry, canRetry ? null : "loseScreen", null);
    }

    /** 提交之后该发的结局段;可继续调查时没有终局段。 */
    public static JsonNode endingScreen(JsonNode config, Verdict verdict) {
        if (verdict == null || verdict.endingScreen() == null) {
            return null;
        }
        return AccusationTemplateSupport.resultScreen(config, verdict.correct());
    }

    private static Verdict refuse(String code) {
        return new Verdict(VERDICT_REFUSED, false, false, null, true, false, null, code);
    }
}
