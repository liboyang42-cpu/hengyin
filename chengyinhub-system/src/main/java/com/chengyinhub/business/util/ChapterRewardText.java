package com.chengyinhub.business.util;

/**
 * D-82(2026-11-11 业主):章节级奖励 —— 章节结束卡上的一行奖励文案。
 *
 * <p>只是作者写给玩家看的展示文字,<b>不发放任何东西</b>(积分/券/徽章各有自己的发放链与围栏,
 * 这里不复制一条)。所以形状取最小:一行纯文本,章节结束卡单行放得下。
 *
 * <p>与章节其它可选文本同口径:null = 调用方没送(动态 update 跳过);空白 = 没有奖励。
 */
public final class ChapterRewardText
{
    /** 结束卡单行可放下的上限(按字符计,不按字节;emoji 算一个)。列宽 64 留有余量。 */
    public static final int MAX_CODE_POINTS = 24;

    private ChapterRewardText()
    {
    }

    /**
     * @return null(未送)、""(清空)或去首尾空白后的文案
     * @throws IllegalStateException 超长或含换行/控制字符
     */
    public static String requireValid(String value)
    {
        if (value == null)
        {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty())
        {
            return "";
        }
        if (text.codePointCount(0, text.length()) > MAX_CODE_POINTS)
        {
            throw new IllegalStateException("章节奖励不能超过 " + MAX_CODE_POINTS + " 个字");
        }
        if (text.codePoints().anyMatch(Character::isISOControl))
        {
            throw new IllegalStateException("章节奖励只能是一行文字");
        }
        return text;
    }

    /** 整章重建的写路径:空白不落库,存 NULL。 */
    public static String requireValidOrNull(String value)
    {
        String text = requireValid(value);
        return text == null || text.isEmpty() ? null : text;
    }
}
