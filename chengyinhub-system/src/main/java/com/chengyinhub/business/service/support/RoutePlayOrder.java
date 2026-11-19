package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 线性路线的游玩顺序:章节 sort_id → 节点 sort_id → 节点 id(与小程序展示同一口径)。
 * 作者在编辑器里调整站点顺序只改 sort_id、不改 id,所以下一站 / 当前站 / 解锁判断都不能按 id 或单看节点 sort_id。
 * 不属于任何已知章节的节点排在所有章节之前(没有章节的老主题即退化为按节点 sort_id)。
 */
public final class RoutePlayOrder {
    private RoutePlayOrder() { }

    public static List<CmsTopicNode> sort(List<CmsTopicNode> nodes, List<CmsTopicChapter> chapters) {
        List<CmsTopicNode> ordered = nodes == null ? new ArrayList<CmsTopicNode>() : new ArrayList<CmsTopicNode>(nodes);
        List<CmsTopicChapter> sortedChapters = chapters == null ? new ArrayList<CmsTopicChapter>() : new ArrayList<CmsTopicChapter>(chapters);
        sortedChapters.sort(Comparator.comparing(CmsTopicChapter::getSortId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(CmsTopicChapter::getId, Comparator.nullsLast(Long::compareTo)));
        Map<Long, Integer> chapterRank = new HashMap<Long, Integer>();
        for (CmsTopicChapter chapter : sortedChapters) {
            if (chapter != null && chapter.getId() != null && !chapterRank.containsKey(chapter.getId())) {
                chapterRank.put(chapter.getId(), chapterRank.size());
            }
        }
        ordered.sort(Comparator.<CmsTopicNode>comparingInt(node -> rank(chapterRank, node))
                .thenComparing(CmsTopicNode::getSortId, Comparator.nullsFirst(Long::compareTo))
                .thenComparing(CmsTopicNode::getId, Comparator.nullsLast(Long::compareTo)));
        return ordered;
    }

    private static int rank(Map<Long, Integer> chapterRank, CmsTopicNode node) {
        Integer rank = node == null || node.getChapterId() == null ? null : chapterRank.get(node.getChapterId());
        return rank == null ? -1 : rank;
    }
}
