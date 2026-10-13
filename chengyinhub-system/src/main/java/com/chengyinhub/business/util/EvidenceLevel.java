package com.chengyinhub.business.util;

/**
 * 模板证据等级四级(07-23 指南 P0 字段字典)。
 *
 * <p>验收口径原文:<b>「无实测时明确标记试点目标／估算」</b> —— 也就是说
 * {@link #NONE} / {@link #ESTIMATED} 必须显式表达出来,不能靠留空蒙混。
 * 指南第十四节把「展示确定收益,但没有样本量、基线和成本」列为让模板失去商业可信度的做法。
 *
 * <p>放在 business/util 的原因同 {@link TemplateStatus}。
 */
public final class EvidenceLevel {

    private EvidenceLevel() {}

    /** 无实测:尚无任何运行数据。 */
    public static final String NONE = "NONE";

    /** 估算:数字来自测算而非实跑,必须标明。 */
    public static final String ESTIMATED = "ESTIMATED";

    /** 已试点:有试点实例数据。 */
    public static final String PILOTED = "PILOTED";

    /** 已验证:有可复现的实测结论。 */
    public static final String VERIFIED = "VERIFIED";

    public static boolean isValid(String s) {
        return NONE.equals(s) || ESTIMATED.equals(s) || PILOTED.equals(s) || VERIFIED.equals(s);
    }
}
