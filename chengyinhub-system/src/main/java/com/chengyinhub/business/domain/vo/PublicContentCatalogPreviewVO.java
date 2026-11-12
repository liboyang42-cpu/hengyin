package com.chengyinhub.business.domain.vo;

import java.util.List;

/**
 * M7 公开落地详情的「有限售前预览」。
 *
 * <p>只开放：首章名、站数、以及首章前 {@code maxStops} 个公开站点的<b>钩子文案</b>
 * ({@code hook_text}，空则取该站 {@code description} 前 40 字)。
 *
 * <p><b>不开放</b>：任何答案/正确答案/提示、blocks 正文、剧情碎片({@code fragment_text})、
 * 角色私密线索、未解锁章节、核销码、商家联系方式。预览是售前摘要，
 * 不能替代可售修订，也不能当作实地核验证明。
 */
public class PublicContentCatalogPreviewVO {

    /** 首章名；无章节时为 null。 */
    private String firstChapterName;

    /** 公开站点总数(与卡片 locationCount 同口径：status=1)。 */
    private Long stopCount;

    /** 本次实际给出的预览站数上限(默认 3，服务端硬上限 5)。 */
    private Integer maxStops;

    private List<PreviewStopVO> stops;

    public String getFirstChapterName() { return firstChapterName; }

    public void setFirstChapterName(String firstChapterName) { this.firstChapterName = firstChapterName; }

    public Long getStopCount() { return stopCount; }

    public void setStopCount(Long stopCount) { this.stopCount = stopCount; }

    public Integer getMaxStops() { return maxStops; }

    public void setMaxStops(Integer maxStops) { this.maxStops = maxStops; }

    public List<PreviewStopVO> getStops() { return stops; }

    public void setStops(List<PreviewStopVO> stops) { this.stops = stops; }

    /** 单站售前摘要：只有展示顺序、站名与钩子文案。 */
    public static class PreviewStopVO {
        private Integer order;
        private String name;
        private String hookTeaser;

        public Integer getOrder() { return order; }

        public void setOrder(Integer order) { this.order = order; }

        public String getName() { return name; }

        public void setName(String name) { this.name = name; }

        public String getHookTeaser() { return hookTeaser; }

        public void setHookTeaser(String hookTeaser) { this.hookTeaser = hookTeaser; }
    }
}
