package com.chengyinhub.business.domain.vo;

import java.util.Collections;
import java.util.List;

/** 服务端记录提示事实后返回给玩家的已揭示提示状态。 */
public class PuzzleHintResult
{
    private final int level;
    private final List<String> hints;
    private final int hintCount;
    private final int scoreCap;

    public PuzzleHintResult(int level, List<String> hints, int hintCount, int scoreCap)
    {
        this.level = level;
        this.hints = hints == null ? Collections.emptyList() : Collections.unmodifiableList(hints);
        this.hintCount = hintCount;
        this.scoreCap = scoreCap;
    }

    public int getLevel() { return level; }
    public String getHint() { return hints.isEmpty() ? null : hints.get(hints.size() - 1); }
    public List<String> getHints() { return hints; }
    public int getHintCount() { return hintCount; }
    public int getScoreCap() { return scoreCap; }
}
