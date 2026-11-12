package com.chengyinhub.business.domain.vo;

import java.util.List;

/**
 * M7 公开落地详情：完整卡 + 有限售前预览 + 章节名清单(不含 blocks)。
 *
 * <p>章节只给「章节名」，让落地页能说出游戏结构；正文 blocks、节点奖励、
 * 答案与核销码一律不下发 —— 那些只在有权威路线局时由既有
 * {@code /api/play/nodes} 路线投影给出。
 */
public class PublicContentCatalogDetailVO extends PublicContentCatalogItemVO {

    /** 章节名，按 sort_id 顺序；不含任何正文。 */
    private List<String> chapterNames;

    private PublicContentCatalogPreviewVO preview;

    public List<String> getChapterNames() { return chapterNames; }

    public void setChapterNames(List<String> chapterNames) { this.chapterNames = chapterNames; }

    public PublicContentCatalogPreviewVO getPreview() { return preview; }

    public void setPreview(PublicContentCatalogPreviewVO preview) { this.preview = preview; }
}
