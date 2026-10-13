package com.chengyinhub.business.domain.dto;

/** 商家客户名册分页查询参数。 */
public class MerchantCustomerQuery {

    private Integer pageNum;
    private Integer pageSize;
    /** 按姓名模糊搜。空串与纯空白都当作没搜。 */
    private String keyword;
    /** 分段:pending / repeat / dormant / abnormal;空或 all 表示全部 */
    private String segment;

    public String getSegment() {
        return segment;
    }

    public void setSegment(String segment) {
        this.segment = segment;
    }

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public int safePageNum() {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    public int safePageSize() {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 50);
    }

    /**
     * 分段筛。null / "all" / 不认识的值一律当作不筛 —— 白名单而不是黑名单:
     * 前端将来加一个新段但后端没跟上时,退化成「全部」比返回空列表好,
     * 空列表会被当成「这个商家没客户」。
     */
    public String safeSegment() {
        if (segment == null) return null;
        String s = segment.trim();
        for (String allowed : new String[] { "pending", "repeat", "dormant", "abnormal" }) {
            if (allowed.equals(s)) return s;
        }
        return null;
    }

    /**
     * 关键词归一。返回 null 表示不筛 —— 全空白若原样透传就成了「找名字里有三个空格的人」,
     * 结果一个都搜不到,用起来像是坏了。
     *
     * 不做 LIKE 通配符转义:名册是在内存里按 String.contains 匹配的(见
     * ProjectPlayerReadService#listMerchantCustomers),这里转义反而会把名字里
     * 真带 % 的人搜不出来。哪天匹配下沉成 SQL LIKE,转义要跟着一起加回来。
     */
    public String safeKeyword() {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
