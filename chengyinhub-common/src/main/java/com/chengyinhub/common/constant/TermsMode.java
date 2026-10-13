package com.chengyinhub.common.constant;

/**
 * P0-50 供给槽条款档(D21,2026-07-16 第五轮拍板)—— ③ 的商家参与方式从「权益一种」扩为三档。
 *
 * <p>BD 手里因此是一个**阶梯**而不是一道门槛:引流档开口、计酬档成交、权益档留给主力店。
 *
 * <p><b>档位挂在章节上,不是只挂 offer</b>(2026-07-16 拍板):商业文档三处口径全按章节走 ——
 * D5 承诺(详情页**按章节**展示一句)、感知价值公式(只加总**权益档章节**)、
 * quota/容量/安全垫(仅**权益档章节**)。若只挂 offer,混档章节里这三句全都答不出来。
 * ⇒ {@code cms_topic_chapter.terms_mode} 定口径,{@code merchant_chapter_offer.terms_mode}
 * 是入驻校验的对象(必须与章节一致),{@code offer.per_head_fee} 是该商家谈定的价。
 *
 * <p>⚠️ <b>{@link #REVSHARE} 只对 ③-自营 开放</b>({@code product_role.CONTENT.entity_type=PLATFORM}):
 * 自营的票款是平台自营收入 ⇒ 计酬是平台的**采购/推广费支出**(应付台账 → 对公打款 → 商家开票),
 * 零收付通依赖;而 ③-第三方 的计酬 = **第三方之间分钱 = 二清**,必须等收付通(P0-43)。
 */
public final class TermsMode {
    private TermsMode() {}

    /** 引流档:零承诺零结算,接待打卡即可(可自愿给折扣)。适合街边小店、冷启动填坑。 */
    public static final String TRAFFIC = "TRAFFIC";

    /**
     * 计酬档:零现金承诺,**按核销**计固定人头费 ¥X/人(MVP 形态)。
     * <p>⚠️ 计酬**必须绑核销,绝不按售票分** —— 钱不绑接待动作,体验就烂在店里。
     */
    public static final String REVSHARE = "REVSHARE";

    /** 权益档:承诺权益(retail_value ≥ 章节门槛),最强获客。**三档里唯一走 quota 容量链的**。 */
    public static final String PERK = "PERK";

    public static boolean isValid(String m) {
        return TRAFFIC.equals(m) || REVSHARE.equals(m) || PERK.equals(m);
    }

    /**
     * 该档是否走 quota 容量链。
     * <p>只有权益档走:它承诺的是**有限的实物权益**,卖超了商家赔不起(§4.5 的爆款敞口)。
     * 计酬/引流档 MVP 不设接待上限(D21),缺口判定退化为「该章有效商家数 ≥1」。
     */
    public static boolean usesQuota(String m) {
        return PERK.equals(m);
    }

    /** 该档是否要平台掏钱(决定要不要走应付台账 + 必亏硬闸)。 */
    public static boolean platformPays(String m) {
        return REVSHARE.equals(m);
    }
}
