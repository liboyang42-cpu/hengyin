package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsTemplateLibrary;

/** 公共模板列表的最小投影；详情与编辑字段不得从列表接口透出。 */
public class PublicTemplateListItemVO {
    private Long id;
    private String title;
    private String description;
    private String imgUrl;
    private String players;
    private Long duration;
    private String activityCategoryids;
    /** 模板形态:0 单节点玩法 / 1 剧情包 / 2 店铺 IP 包。 */
    private Integer packType;
    /**
     * 被采用次数。★ 这是**真的原子自增**(引用本模板发布时 +1),不是人工填的 ——
     * 列表默认就按它排序,客户端显示它是为了让排序有解释,不是装饰。
     */
    private Integer useNum;

    public static PublicTemplateListItemVO from(CmsTemplateLibrary source) {
        PublicTemplateListItemVO item = new PublicTemplateListItemVO();
        item.id = source.getId();
        item.title = source.getTitle();
        item.description = source.getDescription();
        item.imgUrl = source.getImgUrl();
        item.players = source.getPlayers();
        item.duration = source.getDuration();
        item.activityCategoryids = source.getActivityCategoryids();
        // 缺省归一:老数据 pack_type 为 null 时按 0(单节点玩法),
        // 与迁移的 DEFAULT 0 同口径 —— 客户端不必自己再兜一次。
        item.packType = source.getPackType() == null ? 0 : source.getPackType();
        item.useNum = source.getUseNum() == null ? 0 : source.getUseNum();
        return item;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getImgUrl() { return imgUrl; }
    public String getPlayers() { return players; }
    public Long getDuration() { return duration; }
    public String getActivityCategoryids() { return activityCategoryids; }
    public Integer getPackType() { return packType; }
    public Integer getUseNum() { return useNum; }
}
