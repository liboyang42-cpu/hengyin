package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.exception.ChapterFlowValidationException;

import java.util.List;

/**
 * 主题规模上限的唯一出处(owner 2026-10-23 D8 / U-170):每个主题最多 30 章、300 个节点。
 * 保存(整包章节写入)与发布(按库里现有章节/节点计数)两处都按这里判;单章块数、文本、字节上限仍由
 * ChapterFlowCompiler 各自负责。
 */
public final class TopicSizeLimits
{
    public static final int MAX_CHAPTERS = 30;
    public static final int MAX_NODES = 300;

    private TopicSizeLimits()
    {
    }

    /** 超限返回给作者看的说明;未超限返回 null。 */
    public static String violation(int chapterCount, int nodeCount)
    {
        if (chapterCount > MAX_CHAPTERS) {
            return "主题最多 " + MAX_CHAPTERS + " 章,当前 " + chapterCount + " 章,请合并或删减章节";
        }
        if (nodeCount > MAX_NODES) {
            return "主题最多 " + MAX_NODES + " 个节点,当前 " + nodeCount + " 个,请删减节点";
        }
        return null;
    }

    /** 保存整包章节前校验;超限抛 {@link ChapterFlowValidationException}(400 语义,消息直达作者)。 */
    public static void requireWithinLimits(List<ChapterDTO> chapters)
    {
        if (chapters == null) return;
        int nodes = 0;
        for (ChapterDTO chapter : chapters) {
            if (chapter != null && chapter.getNodes() != null) nodes += chapter.getNodes().size();
        }
        String message = violation(chapters.size(), nodes);
        if (message != null) throw new ChapterFlowValidationException(message);
    }
}
